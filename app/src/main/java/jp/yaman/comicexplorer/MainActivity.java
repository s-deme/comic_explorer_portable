package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Product-facing local library: folder browsing, filtering, favorite and recent views. */
public final class MainActivity extends Activity {
    private static final int REQUEST_TREE = 41;
    private static final int MODE_LIBRARY = 0;
    private static final int MODE_FAVORITES = 1;
    private static final int MODE_RECENTS = 2;
    private static final int SORT_NAME = 0;
    private static final int SORT_MODIFIED = 1;
    private static final int SORT_SIZE = 2;

    private final ExecutorService folderWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWorker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
    };
    private final Collator collator = Collator.getInstance(Locale.getDefault());
    private final ArrayList<Entry> allRows = new ArrayList<>();
    private final ArrayList<Entry> visibleRows = new ArrayList<>();

    private Uri treeUri;
    private Uri directoryUri;
    private int mode = MODE_LIBRARY;
    private int sortMode = SORT_NAME;
    private boolean descending;
    private String query = "";
    private TextView pathText;
    private TextView stateText;
    private Button modeButton;
    private Button sortButton;
    private Button upButton;
    private EditText search;
    private LibraryAdapter adapter;

    static final class Entry {
        final Uri uri;
        final String name;
        final String mime;
        final String kind;
        final boolean directory;
        final long size;
        final long modified;

        Entry(Uri uri, String name, String mime, String kind, boolean directory, long size, long modified) {
            this.uri = uri;
            this.name = name == null || name.trim().isEmpty() ? "名称なし" : name;
            this.mime = mime;
            this.kind = kind;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        treeUri = AppState.getTree(this);
        directoryUri = treeUri;
        buildUi();
        if (treeUri == null) showEmptyLibrary(); else loadDirectory();
    }

    @Override protected void onResume() {
        super.onResume();
        Uri saved = AppState.getTree(this);
        if (saved == null && treeUri != null) {
            treeUri = null;
            directoryUri = null;
            mode = MODE_LIBRARY;
            showEmptyLibrary();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.setBackgroundColor(Ui.LIGHT_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Comic Explorer", 25, Ui.TEXT_PRIMARY);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = compactButton("更新", "ライブラリを更新");
        refresh.setOnClickListener(view -> refresh());
        header.addView(refresh);
        Button settings = compactButton("設定", "設定を開く");
        settings.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);
        root.addView(header);

        TextView subtitle = text("ローカル専用。作品データは端末から送信されません", 14, Ui.TEXT_SECONDARY);
        subtitle.setPadding(0, 0, 0, dp(10));
        root.addView(subtitle);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout firstControlRow = new LinearLayout(this);
        firstControlRow.setGravity(Gravity.CENTER_VERTICAL);
        Button folder = button("フォルダ");
        Ui.styleButton(folder, Ui.ButtonStyle.PRIMARY);
        folder.setContentDescription("ライブラリフォルダを選択");
        folder.setOnClickListener(view -> chooseFolder());
        firstControlRow.addView(folder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeButton = button("ライブラリ");
        modeButton.setContentDescription("表示する作品を切り替え");
        modeButton.setOnClickListener(view -> chooseMode());
        firstControlRow.addView(modeButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(firstControlRow);
        LinearLayout secondControlRow = new LinearLayout(this);
        secondControlRow.setGravity(Gravity.CENTER_VERTICAL);
        sortButton = button("名前 ↑");
        sortButton.setContentDescription("並び順を変更");
        sortButton.setOnClickListener(view -> chooseSort());
        secondControlRow.addView(sortButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        upButton = compactButton("上へ", "親フォルダへ移動");
        upButton.setOnClickListener(view -> goUp());
        secondControlRow.addView(upButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .65f));
        controls.addView(secondControlRow);
        root.addView(controls);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("作品名・ファイル名を検索");
        search.setContentDescription("ライブラリを検索");
        Ui.styleSearch(search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                query = value.toString().trim().toLowerCase(Locale.ROOT);
                applyFilters();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        pathText = text("", 14, Ui.BRAND_DARK);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pathText.setPadding(dp(3), dp(8), dp(3), dp(2));
        root.addView(pathText);
        stateText = text("", 14, Ui.TEXT_SECONDARY);
        stateText.setPadding(dp(3), 0, dp(3), dp(5));
        root.addView(stateText);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setContentDescription("作品一覧");
        adapter = new LibraryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(visibleRows.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visibleRows.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = text("PDF・CBZ/ZIP・画像に対応。作品を長押しすると操作を選べます", 14, Ui.TEXT_SECONDARY);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(8), 0, 0);
        root.addView(footer);
        setContentView(root);
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        treeUri = data.getData();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(treeUri, flags); } catch (SecurityException ignored) { }
        AppState.setTree(this, treeUri);
        directoryUri = treeUri;
        mode = MODE_LIBRARY;
        search.setText("");
        loadDirectory();
    }

    private void refresh() {
        if (mode == MODE_LIBRARY) loadDirectory(); else loadSavedItems();
    }

    private void chooseMode() {
        String[] labels = {"ライブラリ", "お気に入り", "最近開いた作品"};
        Ui.show(new AlertDialog.Builder(this).setTitle("表示").setSingleChoiceItems(labels, mode, (dialog, selected) -> {
            dialog.dismiss();
            mode = selected;
            search.setText("");
            updateButtons();
            if (mode == MODE_LIBRARY) {
                if (treeUri == null) showEmptyLibrary(); else loadDirectory();
            } else {
                loadSavedItems();
            }
        }));
    }

    private void chooseSort() {
        String[] labels = {"名前順", "更新日時順", "サイズ順"};
        Ui.show(new AlertDialog.Builder(this)
                .setTitle("並び順")
                .setSingleChoiceItems(labels, sortMode, (dialog, selected) -> {
                    sortMode = selected;
                    dialog.dismiss();
                    applyFilters();
                })
                .setNegativeButton(descending ? "昇順にする" : "降順にする", (dialog, selected) -> {
                    descending = !descending;
                    applyFilters();
                }));
    }

    private void updateButtons() {
        modeButton.setText(mode == MODE_FAVORITES ? "お気に入り" : mode == MODE_RECENTS ? "最近開いた作品" : "ライブラリ");
        Ui.setVisibleAsDisabled(upButton, mode == MODE_LIBRARY && directoryUri != null && !directoryUri.equals(treeUri));
    }

    private void showEmptyLibrary() {
        allRows.clear();
        visibleRows.clear();
        pathText.setText("ライブラリフォルダが選択されていません");
        stateText.setText("「フォルダ」から漫画を保存した場所を選択してください。");
        updateButtons();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadDirectory() {
        if (treeUri == null || directoryUri == null) { showEmptyLibrary(); return; }
        mode = MODE_LIBRARY;
        updateButtons();
        pathText.setText("フォルダを読み込み中…");
        stateText.setText("");
        folderWorker.execute(() -> {
            ArrayList<Entry> loaded = new ArrayList<>();
            String error = null;
            try {
                String documentId = DocumentsContract.getDocumentId(directoryUri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
                String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
                try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
                    if (cursor == null) throw new IllegalStateException("フォルダを読み取れません。");
                    while (cursor.moveToNext()) {
                        String childId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                        long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                        boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                        if (directory || isSupported(name, mime)) loaded.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId), name, mime,
                                directory ? "フォルダ" : kindFor(name, mime), directory, size, modified));
                    }
                }
            } catch (Exception exception) { error = readableError(exception); }
            String finalError = error;
            runOnUiThread(() -> {
                allRows.clear();
                if (finalError == null) allRows.addAll(loaded);
                pathText.setText(finalError == null ? displayName(directoryUri) : "フォルダを開けません");
                stateText.setText(finalError == null ? "対応ファイル " + loaded.size() + " 件" : finalError + "  フォルダを選び直してください。");
                applyFilters();
            });
        });
    }

    private void loadSavedItems() {
        updateButtons();
        pathText.setText(mode == MODE_FAVORITES ? "お気に入り" : "最近開いた作品");
        allRows.clear();
        List<AppState.SavedItem> items = mode == MODE_FAVORITES ? AppState.favorites(this) : AppState.recents(this);
        for (AppState.SavedItem item : items) allRows.add(new Entry(item.uri, item.title, null, item.kind, false, 0, item.timestamp));
        stateText.setText(items.isEmpty() ? (mode == MODE_FAVORITES ? "お気に入りはまだありません。作品の「保存」を押して追加できます。" : "最近開いた作品はまだありません。") : items.size() + " 件");
        applyFilters();
    }

    private void applyFilters() {
        visibleRows.clear();
        for (Entry item : allRows) if (query.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(query) || item.kind.toLowerCase(Locale.ROOT).contains(query)) visibleRows.add(item);
        Collections.sort(visibleRows, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                int result = sortMode == SORT_MODIFIED ? Long.compare(left.modified, right.modified) : sortMode == SORT_SIZE ? Long.compare(left.size, right.size) : collator.compare(left.name, right.name);
                if (result == 0) result = collator.compare(left.name, right.name);
                return descending ? -result : result;
            }
        });
        sortButton.setText((sortMode == SORT_MODIFIED ? "日時" : sortMode == SORT_SIZE ? "サイズ" : "名前") + (descending ? " ↓" : " ↑"));
        if (!allRows.isEmpty() && visibleRows.isEmpty()) stateText.setText("「" + query + "」に一致する作品はありません。");
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void goUp() {
        if (treeUri == null || directoryUri == null || directoryUri.equals(treeUri)) return;
        String currentId = DocumentsContract.getDocumentId(directoryUri);
        int cut = currentId.lastIndexOf('/');
        directoryUri = cut <= 0 ? treeUri : DocumentsContract.buildDocumentUriUsingTree(treeUri, currentId.substring(0, cut));
        loadDirectory();
    }

    private void open(Entry item) {
        if (item.directory) { directoryUri = item.uri; loadDirectory(); return; }
        AppState.addRecent(this, item.uri, item.name, item.kind);
        Intent viewer = new Intent(this, ViewerActivity.class);
        viewer.setData(item.uri);
        viewer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewer.putExtra(ViewerActivity.EXTRA_TITLE, item.name);
        if (isImage(item.name, item.mime) && mode == MODE_LIBRARY) {
            ArrayList<Uri> pages = new ArrayList<>();
            for (Entry candidate : allRows) if (!candidate.directory && isImage(candidate.name, candidate.mime)) pages.add(candidate.uri);
            viewer.putParcelableArrayListExtra(ViewerActivity.EXTRA_IMAGE_URIS, pages);
            viewer.putExtra(ViewerActivity.EXTRA_START_INDEX, pages.indexOf(item.uri));
        }
        startActivity(viewer);
    }

    private void showActions(Entry item) {
        if (item.directory) { Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setItems(new String[]{"開く"}, (dialog, which) -> open(item))); return; }
        boolean favorite = AppState.isFavorite(this, item.uri);
        String[] actions = {favorite ? "お気に入りから外す" : "お気に入りに追加", "復帰位置を消去", "詳細を表示"};
        Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setItems(actions, (dialog, which) -> {
            if (which == 0) {
                AppState.setFavorite(this, item.uri, item.name, item.kind, !favorite);
                Toast.makeText(this, !favorite ? "お気に入りに追加しました" : "お気に入りから外しました", Toast.LENGTH_SHORT).show();
                if (mode == MODE_FAVORITES) loadSavedItems(); else adapter.notifyDataSetChanged();
            } else if (which == 1) {
                AppState.clearPosition(this, item.uri);
                Toast.makeText(this, "復帰位置を消去しました", Toast.LENGTH_SHORT).show();
                adapter.notifyDataSetChanged();
            } else showDetails(item);
        }));
    }

    private void showDetails(Entry item) {
        String message = "形式: " + item.kind + "\n" + (item.size > 0 ? "サイズ: " + formatSize(item.size) + "\n" : "")
                + (item.modified > 0 ? "更新: " + DateFormat.getMediumDateFormat(this).format(new Date(item.modified)) + "\n" : "")
                + "復帰位置: " + (AppState.getPosition(this, item.uri) + 1) + " ページ";
        Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setMessage(message).setPositiveButton("閉じる", null));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "ライブラリ";
    }

    private String readableError(Exception exception) {
        if (exception instanceof SecurityException) return "フォルダへのアクセス許可が失われました。";
        return exception.getMessage() == null ? "フォルダを読み取れません。" : exception.getMessage();
    }

    static boolean isSupported(String name, String mime) { return isImage(name, mime) || extension(name).equals("pdf") || extension(name).equals("zip") || extension(name).equals("cbz"); }
    static boolean isImage(String name, String mime) {
        if (mime != null && mime.startsWith("image/")) return true;
        String ext = extension(name);
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("gif") || ext.equals("bmp") || ext.equals("webp") || ext.equals("avif");
    }
    static String extension(String name) { int dot = name == null ? -1 : name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    static String kindFor(String name, String mime) { if (isImage(name, mime)) return "画像"; String ext = extension(name); return ext.equals("pdf") ? "PDF" : ext.equals("cbz") ? "CBZ" : "ZIP"; }
    private String formatSize(long bytes) { return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB" : String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f)); }

    private Button button(String label) { return Ui.button(this, label, Ui.ButtonStyle.SECONDARY); }
    private Button compactButton(String label, String description) { Button view = button(label); view.setContentDescription(description); return view; }
    private TextView text(String value, int size, int color) { return Ui.text(this, value, size, color); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (mode != MODE_LIBRARY) { mode = MODE_LIBRARY; if (treeUri == null) showEmptyLibrary(); else loadDirectory(); }
        else if (directoryUri != null && !directoryUri.equals(treeUri)) goUp(); else super.onBackPressed();
    }

    @Override protected void onDestroy() { folderWorker.shutdownNow(); thumbnailWorker.shutdownNow(); super.onDestroy(); }

    private final class LibraryAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleRows.size(); }
        @Override public Entry getItem(int position) { return visibleRows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(5), dp(7), dp(4), dp(7));
                ImageView thumbnail = new ImageView(MainActivity.this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackgroundColor(Ui.LIGHT_SURFACE_TINT);
                row.addView(thumbnail, new LinearLayout.LayoutParams(dp(56), dp(72)));
                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(dp(10), 0, dp(4), 0);
                TextView name = text("", 17, Ui.TEXT_PRIMARY); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); info.addView(name);
                TextView detail = text("", 14, Ui.TEXT_SECONDARY); detail.setPadding(0, dp(3), 0, 0); info.addView(detail);
                TextView progress = text("", 14, Ui.BRAND_DARK); progress.setPadding(0, dp(3), 0, 0); info.addView(progress);
                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button favorite = button("保存");
                row.addView(favorite, new LinearLayout.LayoutParams(dp(76), dp(56)));
                holder = new Holder(thumbnail, name, detail, progress, favorite);
                row.setTag(holder);
                convertView = row;
            } else holder = (Holder) convertView.getTag();
            Entry item = getItem(position);
            holder.name.setText(item.name);
            holder.detail.setText(item.directory ? "フォルダ" : item.kind + (item.size > 0 ? "  •  " + formatSize(item.size) : ""));
            int saved = AppState.getPosition(MainActivity.this, item.uri);
            holder.progress.setText(item.directory || saved <= 0 ? "" : "前回: " + (saved + 1) + " ページ");
            boolean favorite = !item.directory && AppState.isFavorite(MainActivity.this, item.uri);
            holder.favorite.setVisibility(item.directory ? View.GONE : View.VISIBLE);
            holder.favorite.setText(favorite ? "保存済" : "保存");
            holder.favorite.setContentDescription(favorite ? "お気に入りから外す" : "お気に入りに追加");
            Ui.styleButton(holder.favorite, favorite ? Ui.ButtonStyle.PRIMARY : Ui.ButtonStyle.SECONDARY);
            holder.favorite.setOnClickListener(view -> {
                boolean next = !AppState.isFavorite(MainActivity.this, item.uri);
                AppState.setFavorite(MainActivity.this, item.uri, item.name, item.kind, next);
                if (mode == MODE_FAVORITES && !next) loadSavedItems(); else notifyDataSetChanged();
            });
            bindThumbnail(holder.thumbnail, item);
            convertView.setContentDescription(item.name + "、" + holder.detail.getText() + (holder.progress.getText().length() > 0 ? "、" + holder.progress.getText() : ""));
            return convertView;
        }

        private void bindThumbnail(ImageView view, Entry item) {
            view.setTag(item.uri.toString());
            view.setImageDrawable(null);
            if (item.directory) { view.setImageResource(android.R.drawable.ic_menu_agenda); return; }
            if (!isImage(item.name, item.mime)) { view.setImageResource(item.kind.equals("PDF") ? android.R.drawable.ic_menu_view : android.R.drawable.ic_menu_save); return; }
            String key = item.uri.toString();
            Bitmap cached = thumbnails.get(key);
            if (cached != null) { view.setImageBitmap(cached); return; }
            view.setImageResource(android.R.drawable.ic_menu_gallery);
            thumbnailWorker.execute(() -> {
                try {
                    Bitmap bitmap = getContentResolver().loadThumbnail(item.uri, new Size(dp(112), dp(144)), null);
                    if (bitmap == null) return;
                    thumbnails.put(key, bitmap);
                    runOnUiThread(() -> { if (key.equals(view.getTag())) view.setImageBitmap(bitmap); });
                } catch (Exception ignored) { }
            });
        }
    }

    private static final class Holder {
        final ImageView thumbnail; final TextView name; final TextView detail; final TextView progress; final Button favorite;
        Holder(ImageView thumbnail, TextView name, TextView detail, TextView progress, Button favorite) { this.thumbnail = thumbnail; this.name = name; this.detail = detail; this.progress = progress; this.favorite = favorite; }
    }
}
