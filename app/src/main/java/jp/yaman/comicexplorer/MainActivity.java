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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private Button libraryTab;
    private Button favoritesTab;
    private Button recentsTab;
    private Button sortButton;
    private Button upButton;
    private EditText search;
    private View emptyPanel;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private Button emptyAction;
    private ProgressBar emptyProgress;
    private ImageView emptyIcon;
    private boolean compactHeight;
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
        compactHeight = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density < 600f;
        boolean narrowWidth = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density < 360f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(8));
        root.setBackgroundColor(Ui.LIGHT_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("PRIVATE COMIC LIBRARY", 14, Ui.BRAND_DARK);
        Ui.label(eyebrow);
        eyebrow.setLetterSpacing(.08f);
        eyebrow.setVisibility(narrowWidth ? View.GONE : View.VISIBLE);
        identity.addView(eyebrow);
        TextView title = text("Comic Explorer", narrowWidth ? 24 : 28, Ui.TEXT_PRIMARY);
        Ui.title(title);
        identity.addView(title);
        header.addView(identity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = compactButton("更新", "ライブラリを更新");
        Ui.styleButton(refresh, Ui.ButtonStyle.GHOST);
        refresh.setOnClickListener(view -> refresh());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(64), dp(48)));
        Button settings = compactButton("設定", "設定を開く");
        Ui.styleButton(settings, Ui.ButtonStyle.TONAL);
        settings.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings, new LinearLayout.LayoutParams(dp(68), dp(48)));
        root.addView(header);

        TextView subtitle = text("端末の中だけで、好きな作品を静かに楽しむ本棚", 14, Ui.TEXT_SECONDARY);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        subtitle.setVisibility(compactHeight ? View.GONE : View.VISIBLE);
        root.addView(subtitle);

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
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        Ui.styleInsetPanel(tabs);
        libraryTab = tabButton("本棚", MODE_LIBRARY, "ライブラリを表示");
        favoritesTab = tabButton("お気に入り", MODE_FAVORITES, "お気に入りを表示");
        recentsTab = tabButton("最近", MODE_RECENTS, "最近開いた作品を表示");
        tabs.addView(libraryTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(favoritesTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(recentsTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        tabsParams.setMargins(0, dp(10), 0, dp(10));
        if (compactHeight) {
            LinearLayout discovery = new LinearLayout(this);
            discovery.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(52), 1.25f);
            searchParams.setMargins(0, 0, dp(8), 0);
            discovery.addView(search, searchParams);
            discovery.addView(tabs, new LinearLayout.LayoutParams(0, dp(56), 1f));
            LinearLayout.LayoutParams discoveryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            discoveryParams.setMargins(0, dp(4), 0, dp(6));
            root.addView(discovery, discoveryParams);
        } else {
            root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            root.addView(tabs, tabsParams);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button folder = button("フォルダ");
        Ui.styleButton(folder, Ui.ButtonStyle.PRIMARY);
        folder.setContentDescription("ライブラリフォルダを選択");
        folder.setOnClickListener(view -> chooseFolder());
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, dp(48), 1.2f);
        folderParams.setMargins(0, 0, dp(6), 0);
        actions.addView(folder, folderParams);
        upButton = compactButton("上へ", "親フォルダへ移動");
        upButton.setOnClickListener(view -> goUp());
        LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0, dp(48), .9f);
        upParams.setMargins(0, 0, dp(6), 0);
        actions.addView(upButton, upParams);
        sortButton = button("名前 ↑");
        sortButton.setContentDescription("並び順を変更");
        sortButton.setOnClickListener(view -> chooseSort());
        actions.addView(sortButton, new LinearLayout.LayoutParams(0, dp(48), .85f));

        LinearLayout location = new LinearLayout(this);
        location.setOrientation(LinearLayout.VERTICAL);
        location.setPadding(dp(14), dp(10), dp(14), dp(10));
        Ui.styleInsetPanel(location);
        pathText = text("", 15, Ui.TEXT_PRIMARY);
        Ui.label(pathText);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        location.addView(pathText);
        stateText = text("", 14, Ui.TEXT_SECONDARY);
        stateText.setPadding(0, dp(2), 0, 0);
        location.addView(stateText);
        LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        locationParams.setMargins(0, dp(10), 0, dp(4));
        if (compactHeight) {
            LinearLayout contextRow = new LinearLayout(this);
            contextRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(48), 1.1f);
            actionParams.setMargins(0, 0, dp(8), 0);
            contextRow.addView(actions, actionParams);
            contextRow.addView(location, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams contextParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            contextParams.setMargins(0, 0, 0, dp(4));
            root.addView(contextRow, contextParams);
        } else {
            root.addView(actions);
            root.addView(location, locationParams);
        }

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(8));
        list.setPadding(dp(1), dp(4), dp(1), dp(4));
        list.setClipToPadding(false);
        list.setContentDescription("作品一覧");
        adapter = new LibraryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(visibleRows.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visibleRows.get(position));
            return true;
        });
        FrameLayout content = new FrameLayout(this);
        content.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyPanel = createEmptyPanel();
        content.addView(emptyPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
    }

    private Button tabButton(String label, int targetMode, String description) {
        Button button = Ui.button(this, label, Ui.ButtonStyle.GHOST);
        button.setContentDescription(description);
        button.setOnClickListener(view -> selectMode(targetMode));
        return button;
    }

    private View createEmptyPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(compactHeight ? 16 : 20), dp(compactHeight ? 14 : 20), dp(compactHeight ? 16 : 20), dp(compactHeight ? 14 : 20));
        Ui.styleCard(panel, false);
        emptyIcon = new ImageView(this);
        try { emptyIcon.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo())); } catch (Exception ignored) { }
        emptyIcon.setContentDescription(null);
        panel.addView(emptyIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        emptyProgress = new ProgressBar(this);
        emptyProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND));
        emptyProgress.setVisibility(View.GONE);
        panel.addView(emptyProgress, new LinearLayout.LayoutParams(dp(48), dp(48)));
        emptyTitle = text("", compactHeight ? 18 : 21, Ui.TEXT_PRIMARY);
        Ui.title(emptyTitle);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(6));
        panel.addView(emptyTitle);
        emptyMessage = text("", 15, Ui.TEXT_SECONDARY);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setLineSpacing(0, 1.08f);
        panel.addView(emptyMessage);
        emptyAction = Ui.button(this, "フォルダを選ぶ", Ui.ButtonStyle.PRIMARY);
        emptyAction.setOnClickListener(view -> chooseFolder());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        actionParams.setMargins(0, dp(compactHeight ? 8 : 16), 0, 0);
        panel.addView(emptyAction, actionParams);
        scroll.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
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

    private void selectMode(int selected) {
        if (mode == selected) return;
        mode = selected;
        search.setText("");
        updateButtons();
        if (mode == MODE_LIBRARY) {
            if (treeUri == null) showEmptyLibrary(); else loadDirectory();
        } else {
            loadSavedItems();
        }
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
        Ui.styleSegment(libraryTab, mode == MODE_LIBRARY);
        Ui.styleSegment(favoritesTab, mode == MODE_FAVORITES);
        Ui.styleSegment(recentsTab, mode == MODE_RECENTS);
        Ui.setVisibleAsDisabled(upButton, mode == MODE_LIBRARY && directoryUri != null && !directoryUri.equals(treeUri));
    }

    private void showEmptyLibrary() {
        allRows.clear();
        visibleRows.clear();
        pathText.setText("本棚の準備");
        stateText.setText("選択したフォルダだけを安全に読み取ります");
        showEmptyState("本棚をつくりましょう", "漫画を保存したフォルダを選ぶと、PDF・CBZ・ZIP・画像をここに並べます。", "フォルダを選ぶ", false);
        updateButtons();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadDirectory() {
        if (treeUri == null || directoryUri == null) { showEmptyLibrary(); return; }
        mode = MODE_LIBRARY;
        updateButtons();
        allRows.clear();
        visibleRows.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        pathText.setText("フォルダを読み込み中…");
        stateText.setText("対応作品を探しています");
        showEmptyState("本棚を読み込み中", "フォルダ内の作品を確認しています。", null, true);
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
                stateText.setText(finalError == null ? "対応作品 " + loaded.size() + " 件  •  長押しで詳細メニュー" : finalError);
                if (finalError != null) showEmptyState("フォルダを開けません", finalError + " フォルダを選び直してください。", "選び直す", false);
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
        stateText.setText(items.isEmpty() ? (mode == MODE_FAVORITES ? "気になる作品を保存して、すぐ戻れるようにしましょう" : "開いた作品がここに新しい順で並びます") : items.size() + " 件");
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
        if (!allRows.isEmpty() && visibleRows.isEmpty()) {
            stateText.setText("「" + query + "」に一致する作品はありません");
            showEmptyState("見つかりませんでした", "検索語を短くするか、別の名前で試してください。", null, false);
        } else if (visibleRows.isEmpty()) {
            if (mode == MODE_FAVORITES) showEmptyState("お気に入りはまだありません", "作品の「保存」を押すと、読みたい本だけをここに集められます。", null, false);
            else if (mode == MODE_RECENTS) showEmptyState("読書履歴はまだありません", "本棚から作品を開くと、続きへ戻りやすいようここに並びます。", "本棚を見る", false);
            else if (treeUri != null && emptyProgress.getVisibility() != View.VISIBLE && !pathText.getText().toString().equals("フォルダを開けません"))
                showEmptyState("このフォルダは空です", "対応するPDF・CBZ・ZIP・画像、またはサブフォルダが見つかりませんでした。", "別のフォルダを選ぶ", false);
        } else {
            hideEmptyState();
            stateText.setText((query.isEmpty() ? "" : "検索結果  ") + visibleRows.size() + " 件  •  長押しで詳細メニュー");
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void showEmptyState(String title, String message, String action, boolean loading) {
        if (emptyPanel == null) return;
        emptyPanel.setVisibility(View.VISIBLE);
        emptyTitle.setText(title);
        emptyMessage.setText(message);
        emptyProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        emptyIcon.setVisibility(loading || compactHeight ? View.GONE : View.VISIBLE);
        emptyAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) {
            emptyAction.setText(action);
            emptyAction.setOnClickListener(view -> {
                if (mode == MODE_RECENTS && treeUri != null) selectMode(MODE_LIBRARY); else chooseFolder();
            });
        }
    }

    private void hideEmptyState() {
        if (emptyPanel != null) emptyPanel.setVisibility(View.GONE);
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
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.setMinimumHeight(dp(106));
                Ui.styleCard(row, true);
                FrameLayout cover = new FrameLayout(MainActivity.this);
                ImageView thumbnail = new ImageView(MainActivity.this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                Ui.styleThumbnail(thumbnail);
                cover.addView(thumbnail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView formatMark = Ui.badge(MainActivity.this, "", Ui.ON_BRAND_CONTAINER, Ui.BRAND_CONTAINER);
                formatMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                cover.addView(formatMark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                row.addView(cover, new LinearLayout.LayoutParams(dp(62), dp(84)));
                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(dp(12), 0, dp(8), 0);
                TextView name = text("", 17, Ui.TEXT_PRIMARY);
                Ui.label(name);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(name);
                TextView detail = text("", 14, Ui.TEXT_SECONDARY);
                detail.setPadding(0, dp(4), 0, 0);
                info.addView(detail);
                TextView progress = Ui.badge(MainActivity.this, "", Ui.ON_BRAND_CONTAINER, Ui.BRAND_CONTAINER);
                LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                progressParams.setMargins(0, dp(6), 0, 0);
                info.addView(progress, progressParams);
                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button favorite = button("保存");
                row.addView(favorite, new LinearLayout.LayoutParams(dp(76), dp(48)));
                holder = new Holder(thumbnail, formatMark, name, detail, progress, favorite);
                row.setTag(holder);
                convertView = row;
            } else holder = (Holder) convertView.getTag();
            Entry item = getItem(position);
            holder.name.setText(item.name);
            holder.detail.setText(item.directory ? "フォルダ" : item.kind + (item.size > 0 ? "  •  " + formatSize(item.size) : ""));
            int saved = AppState.getPosition(MainActivity.this, item.uri);
            holder.progress.setVisibility(item.directory || saved <= 0 ? View.GONE : View.VISIBLE);
            holder.progress.setText(item.directory || saved <= 0 ? "" : "続き  " + (saved + 1) + "ページ");
            boolean favorite = !item.directory && AppState.isFavorite(MainActivity.this, item.uri);
            holder.favorite.setVisibility(item.directory ? View.GONE : View.VISIBLE);
            holder.favorite.setText(favorite ? "保存済" : "保存");
            holder.favorite.setContentDescription(favorite ? "お気に入りから外す" : "お気に入りに追加");
            Ui.styleButton(holder.favorite, favorite ? Ui.ButtonStyle.TONAL : Ui.ButtonStyle.SECONDARY);
            holder.favorite.setOnClickListener(view -> {
                boolean next = !AppState.isFavorite(MainActivity.this, item.uri);
                AppState.setFavorite(MainActivity.this, item.uri, item.name, item.kind, next);
                if (mode == MODE_FAVORITES && !next) loadSavedItems(); else notifyDataSetChanged();
            });
            bindThumbnail(holder.thumbnail, holder.formatMark, item);
            convertView.setContentDescription(item.name + "、" + holder.detail.getText() + (holder.progress.getText().length() > 0 ? "、" + holder.progress.getText() : ""));
            return convertView;
        }

        private void bindThumbnail(ImageView view, TextView formatMark, Entry item) {
            view.setTag(item.uri.toString());
            view.setImageDrawable(null);
            formatMark.setText(item.directory ? "DIR" : item.kind);
            formatMark.setVisibility(View.VISIBLE);
            if (item.directory || !isImage(item.name, item.mime)) return;
            formatMark.setText("IMG");
            String key = item.uri.toString();
            Bitmap cached = thumbnails.get(key);
            if (cached != null) { view.setImageBitmap(cached); formatMark.setVisibility(View.GONE); return; }
            thumbnailWorker.execute(() -> {
                try {
                    Bitmap bitmap = getContentResolver().loadThumbnail(item.uri, new Size(dp(112), dp(144)), null);
                    if (bitmap == null) return;
                    thumbnails.put(key, bitmap);
                    runOnUiThread(() -> {
                        if (key.equals(view.getTag())) {
                            view.setImageBitmap(bitmap);
                            formatMark.setVisibility(View.GONE);
                        }
                    });
                } catch (Exception ignored) { }
            });
        }
    }

    private static final class Holder {
        final ImageView thumbnail; final TextView formatMark; final TextView name; final TextView detail; final TextView progress; final Button favorite;
        Holder(ImageView thumbnail, TextView formatMark, TextView name, TextView detail, TextView progress, Button favorite) {
            this.thumbnail = thumbnail; this.formatMark = formatMark; this.name = name; this.detail = detail; this.progress = progress; this.favorite = favorite;
        }
    }
}
