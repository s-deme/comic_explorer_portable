package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ImageButton;
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
    private static final int REQUEST_FILE = 42;
    private static final int MODE_LIBRARY = 0;
    private static final int MODE_FAVORITES = 1;
    private static final int MODE_RECENTS = 2;
    private static final int MODE_BOOKMARKS = 3;
    private static final int MODE_DIRECTORIES = 4;
    private static final int SORT_NAME = 0;
    private static final int SORT_MODIFIED = 1;
    private static final int SORT_SIZE = 2;

    private final ExecutorService folderWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWorker = Executors.newFixedThreadPool(2);
    private final BitmapMemoryCache thumbnails = new BitmapMemoryCache(12 * 1024);
    private final Collator collator = Collator.getInstance(Locale.getDefault());
    private final ArrayList<LibraryEntry> allRows = new ArrayList<>();
    private final ArrayList<LibraryEntry> visibleRows = new ArrayList<>();

    private Uri treeUri;
    private Uri directoryUri;
    private int mode = MODE_LIBRARY;
    private int sortMode = SORT_NAME;
    private boolean descending;
    private String query = "";
    private int directoryLoadToken;
    private TextView pathText;
    private TextView stateText;
    private TextView screenTitle;
    private View locationRow;
    private ImageButton upButton;
    private ImageButton searchButton;
    private Button libraryDestination;
    private Button directoriesDestination;
    private Button recentsDestination;
    private Button bookmarksDestination;
    private Button sortButton;
    private Button viewButton;
    private EditText search;
    private View searchPanel;
    private View emptyPanel;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private Button emptyAction;
    private ProgressBar emptyProgress;
    private ImageView emptyIcon;
    private boolean compactHeight;
    private boolean gridMode;
    private ListView listView;
    private GridView gridView;
    private LibraryAdapter adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        treeUri = AppState.getTree(this);
        directoryUri = treeUri;
        gridMode = AppState.gridView(this);
        buildUi();
        Uri opened = getIntent().getData();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && opened != null) {
            openExternal(opened);
            getIntent().setAction(null);
        }
        if (treeUri == null) showEmptyLibrary(); else loadDirectory();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean savedGrid = AppState.gridView(this);
        if (savedGrid != gridMode) gridMode = savedGrid;
        updateCollectionView();
        Uri saved = AppState.getTree(this);
        if (saved == null && treeUri != null) {
            treeUri = null;
            directoryUri = null;
            mode = MODE_LIBRARY;
            showEmptyLibrary();
        }
    }

    @Override protected void onRestart() {
        super.onRestart();
        thumbnails.evictAll();
        if (mode == MODE_LIBRARY) adapter.notifyDataSetChanged(); else loadSavedItems();
    }

    private void buildUi() {
        compactHeight = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density < 600f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.DARK_BACKGROUND);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setBackgroundColor(Ui.TOOLBAR);
        upButton = toolbarButton(R.drawable.ic_arrow_back, "親フォルダへ");
        upButton.setOnClickListener(view -> goUp());
        toolbar.addView(upButton, new LinearLayout.LayoutParams(dp(48), dp(56)));
        screenTitle = text("Comic Explorer", 20, Ui.TOOLBAR_TEXT);
        Ui.title(screenTitle);
        screenTitle.setGravity(Gravity.CENTER_VERTICAL);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        screenTitle.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(screenTitle, new LinearLayout.LayoutParams(0, dp(56), 1f));
        searchButton = toolbarButton(R.drawable.ic_toolbar_search, "検索を表示");
        searchButton.setOnClickListener(view -> toggleSearchPanel());
        toolbar.addView(searchButton, new LinearLayout.LayoutParams(dp(48), dp(56)));
        ImageButton menu = toolbarButton(R.drawable.ic_toolbar_more, "メニューを開く");
        menu.setOnClickListener(view -> showAppMenu());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setBackgroundColor(Ui.DARK_SURFACE);
        libraryDestination = tabButton("ストレージ", MODE_LIBRARY, "ストレージを表示");
        directoriesDestination = tabButton("ディレクトリ", MODE_DIRECTORIES, "登録したディレクトリを表示");
        recentsDestination = tabButton("履歴", MODE_RECENTS, "読書履歴を表示");
        bookmarksDestination = tabButton("しおり", MODE_BOOKMARKS, "しおりのある作品を表示");
        tabs.addView(libraryDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(directoriesDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(recentsDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(bookmarksDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("ファイル名を検索");
        search.setContentDescription("ライブラリを検索");
        Ui.styleDarkSearch(search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                query = value.toString().trim().toLowerCase(Locale.ROOT);
                applyFilters();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        searchRow.setBackgroundColor(Ui.DARK_SURFACE_RAISED);
        searchRow.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(searchRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        searchPanel = searchRow;
        searchPanel.setVisibility(View.GONE);

        LinearLayout location = new LinearLayout(this);
        location.setGravity(Gravity.CENTER_VERTICAL);
        location.setPadding(dp(6), 0, dp(6), 0);
        location.setBackgroundColor(Ui.DARK_BACKGROUND);
        pathText = text("", 11, Ui.READER_ACCENT);
        Ui.label(pathText);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        location.addView(pathText, new LinearLayout.LayoutParams(0, dp(24), 1f));
        stateText = text("", 11, Ui.DARK_MUTED);
        stateText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        stateText.setSingleLine(true);
        stateText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        location.addView(stateText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
        locationRow = location;
        root.addView(locationRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Ui.DARK_OUTLINE));
        listView.setDividerHeight(dp(1));
        listView.setBackgroundColor(Ui.DARK_BACKGROUND);
        listView.setContentDescription("作品一覧");
        // Keep row-level tap and long-press handling available when a row has a star control.
        listView.setItemsCanFocus(false);
        adapter = new LibraryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> open(visibleRows.get(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visibleRows.get(position));
            return true;
        });
        gridView = new GridView(this);
        gridView.setNumColumns(AppState.gridColumns(this));
        gridView.setHorizontalSpacing(dp(4));
        gridView.setVerticalSpacing(dp(6));
        gridView.setPadding(dp(4), dp(6), dp(4), dp(6));
        gridView.setClipToPadding(false);
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setBackgroundColor(Ui.DARK_BACKGROUND);
        gridView.setContentDescription("作品のサムネイル一覧");
        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> open(visibleRows.get(position)));
        gridView.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visibleRows.get(position));
            return true;
        });
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Ui.DARK_BACKGROUND);
        content.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(gridView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyPanel = createEmptyPanel();
        content.addView(emptyPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setBackgroundColor(Ui.DARK_SURFACE_RAISED);
        Button actionsButton = navigationAction("操作", "現在の一覧の操作", R.drawable.ic_nav_folder);
        actionsButton.setOnClickListener(view -> showListActions());
        Button recentButton = navigationAction("履歴", "読書履歴を表示", R.drawable.ic_nav_history);
        recentButton.setOnClickListener(view -> selectMode(MODE_RECENTS));
        viewButton = navigationAction(gridMode ? "リスト" : "グリッド", "一覧の表示形式を切り替え", R.drawable.ic_image_file);
        viewButton.setOnClickListener(view -> toggleCollectionView());
        sortButton = navigationAction("並び順", "並び順を変更", R.drawable.ic_nav_sort);
        sortButton.setOnClickListener(view -> chooseSort());
        navigation.addView(actionsButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        navigation.addView(recentButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        navigation.addView(viewButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        navigation.addView(sortButton, new LinearLayout.LayoutParams(0, dp(58), 1f));
        root.addView(navigation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
        updateCollectionView();
    }

    private ImageButton toolbarButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        Ui.styleToolbarButton(button, Ui.TOOLBAR);
        return button;
    }

    private Button tabButton(String label, int targetMode, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(description);
        button.setOnClickListener(view -> selectMode(targetMode));
        Ui.styleTopTab(button, false);
        return button;
    }

    private Button navigationAction(String label, String description, int icon) {
        Button button = Ui.button(this, label, Ui.ButtonStyle.GHOST);
        button.setContentDescription(description);
        button.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        Ui.styleNavigationDestination(button, false);
        return button;
    }

    private View createEmptyPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        emptyIcon = new ImageView(this);
        emptyIcon.setImageResource(R.drawable.ic_folder);
        emptyIcon.setContentDescription(null);
        panel.addView(emptyIcon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        emptyProgress = new ProgressBar(this);
        emptyProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND));
        emptyProgress.setVisibility(View.GONE);
        panel.addView(emptyProgress, new LinearLayout.LayoutParams(dp(48), dp(48)));
        emptyTitle = text("", 17, Ui.DARK_TEXT);
        Ui.title(emptyTitle);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(6));
        panel.addView(emptyTitle);
        emptyMessage = text("", 14, Ui.DARK_MUTED);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setLineSpacing(0, 1.08f);
        panel.addView(emptyMessage);
        emptyAction = Ui.button(this, "フォルダを選ぶ", Ui.ButtonStyle.PRIMARY);
        emptyAction.setOnClickListener(view -> chooseFolder());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        actionParams.setMargins(0, dp(16), 0, 0);
        panel.addView(emptyAction, actionParams);
        scroll.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/zip", "application/x-cbz", "image/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_FILE) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            openExternal(uri);
            return;
        }
        if (requestCode != REQUEST_TREE) return;
        treeUri = data.getData();
        try { getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
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
        updateNavigation();
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

    private void updateNavigation() {
        Ui.styleTopTab(libraryDestination, mode == MODE_LIBRARY);
        Ui.styleTopTab(directoriesDestination, mode == MODE_DIRECTORIES);
        Ui.styleTopTab(recentsDestination, mode == MODE_RECENTS);
        Ui.styleTopTab(bookmarksDestination, mode == MODE_BOOKMARKS);
        if (screenTitle != null) screenTitle.setText(mode == MODE_DIRECTORIES ? "ディレクトリ" : mode == MODE_FAVORITES ? "お気に入り" : mode == MODE_RECENTS ? "履歴" : mode == MODE_BOOKMARKS ? "しおり" : "Comic Explorer");
        if (upButton != null) upButton.setVisibility(mode == MODE_LIBRARY && treeUri != null && directoryUri != null && !directoryUri.equals(treeUri) ? View.VISIBLE : View.GONE);
    }

    private void toggleSearchPanel() {
        boolean expanded = searchPanel.getVisibility() != View.VISIBLE;
        searchPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        searchButton.setContentDescription(expanded ? "検索を閉じる" : "検索を表示");
        if (expanded) search.requestFocus(); else search.clearFocus();
    }

    private void toggleCollectionView() {
        gridMode = !gridMode;
        AppState.setGridView(this, gridMode);
        updateCollectionView();
    }

    private void updateCollectionView() {
        if (listView == null || gridView == null) return;
        listView.setVisibility(gridMode ? View.GONE : View.VISIBLE);
        gridView.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        gridView.setNumColumns(AppState.gridColumns(this));
        if (locationRow != null) locationRow.setVisibility(AppState.showLibraryPath(this) ? View.VISIBLE : View.GONE);
        int scrollPosition = AppState.leftLibraryScrollbar(this)
                ? View.SCROLLBAR_POSITION_LEFT : View.SCROLLBAR_POSITION_RIGHT;
        listView.setVerticalScrollbarPosition(scrollPosition);
        gridView.setVerticalScrollbarPosition(scrollPosition);
        if (viewButton != null) {
            viewButton.setText(gridMode ? "リスト" : "グリッド");
            viewButton.setContentDescription(gridMode ? "リスト表示に切り替え" : "グリッド表示に切り替え");
        }
        adapter.notifyDataSetChanged();
    }

    private void showListActions() {
        ArrayList<String> labels = new ArrayList<>();
        labels.add("ファイルを開く");
        labels.add("フォルダを選び直す");
        if (mode == MODE_RECENTS && !allRows.isEmpty()) labels.add("履歴を期間指定で消去");
        if (mode == MODE_DIRECTORIES && !allRows.isEmpty()) labels.add("登録ディレクトリをすべて解除");
        if (mode == MODE_FAVORITES && !allRows.isEmpty()) labels.add("お気に入りをすべて解除");
        if (mode == MODE_BOOKMARKS && !allRows.isEmpty()) labels.add("しおりをすべて消去");
        Ui.show(new AlertDialog.Builder(this).setTitle("操作").setItems(labels.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) { chooseFile(); return; }
            if (which == 1) { chooseFolder(); return; }
            if (mode == MODE_RECENTS) { showHistoryCleanup(); return; }
            Ui.show(new AlertDialog.Builder(this).setMessage(labels.get(which) + "しますか？")
                    .setNegativeButton("キャンセル", null).setPositiveButton("実行", (ignored, button) -> {
                        if (mode == MODE_DIRECTORIES) {
                            AppState.clearDirectories(this);
                        } else if (mode == MODE_FAVORITES) {
                            for (AppState.SavedItem item : AppState.favorites(this)) AppState.setFavorite(this, item.uri, item.title, item.kind, false);
                        } else if (mode == MODE_BOOKMARKS) {
                            for (AppState.SavedItem item : AppState.bookmarkedItems(this)) AppState.clearBookmarks(this, item.uri);
                        }
                        loadSavedItems();
                    }));
        }));
    }

    private void showHistoryCleanup() {
        String[] choices = {"1時間以内", "24時間以内", "1週間以内", "すべて"};
        long[] ages = {60L * 60 * 1000, 24L * 60 * 60 * 1000, 7L * 24 * 60 * 60 * 1000, Long.MAX_VALUE};
        Ui.show(new AlertDialog.Builder(this).setTitle("履歴を消去").setItems(choices, (dialog, selected) -> {
            if (selected == choices.length - 1) AppState.clearRecents(this);
            else AppState.clearRecentsSince(this, System.currentTimeMillis() - ages[selected]);
            loadSavedItems();
        }));
    }

    private void showAppMenu() {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();
        labels.add("更新");
        actions.add(0);
        labels.add("ファイルを開く");
        actions.add(4);
        labels.add("お気に入り");
        actions.add(5);
        if (mode == MODE_LIBRARY) {
            labels.add("フォルダを選び直す");
            actions.add(1);
            if (treeUri != null && directoryUri != null && !directoryUri.equals(treeUri)) {
                labels.add("親フォルダへ");
                actions.add(2);
            }
        }
        labels.add("設定");
        actions.add(3);
        Ui.show(new AlertDialog.Builder(this)
                .setTitle("メニュー")
                .setItems(labels.toArray(new String[0]), (dialog, selected) -> {
                    switch (actions.get(selected)) {
                        case 0: refresh(); break;
                        case 1: chooseFolder(); break;
                        case 2: goUp(); break;
                        case 3: startActivity(new Intent(this, SettingsActivity.class)); break;
                        case 4: chooseFile(); break;
                        case 5: selectMode(MODE_FAVORITES); break;
                        default: break;
                    }
                }));
    }

    private void openExternal(Uri uri) {
        String name = uri.getLastPathSegment();
        long size = 0;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = "外部ファイル";
        String mime = getContentResolver().getType(uri);
        if (!ComicFile.isSupported(name, mime)) {
            Toast.makeText(this, "PDF、CBZ/ZIP、画像を選択してください", Toast.LENGTH_LONG).show();
            return;
        }
        open(new LibraryEntry(uri, name, mime, ComicFile.kindFor(name, mime), false, size, 0), false);
    }

    private void showEmptyLibrary() {
        directoryLoadToken++;
        allRows.clear();
        visibleRows.clear();
        pathText.setText("フォルダ未選択");
        stateText.setText("");
        showEmptyState("フォルダが選択されていません", "漫画の入ったフォルダを選択してください。", "フォルダを選ぶ", false);
        updateNavigation();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadDirectory() {
        if (treeUri == null || directoryUri == null) { showEmptyLibrary(); return; }
        mode = MODE_LIBRARY;
        final Uri requestedTree = treeUri;
        final Uri requestedDirectory = directoryUri;
        final int token = ++directoryLoadToken;
        updateNavigation();
        allRows.clear();
        visibleRows.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        pathText.setText("読み込み中…");
        stateText.setText("");
        showEmptyState("読み込み中…", "", null, true);
        folderWorker.execute(() -> {
            List<LibraryEntry> loaded = new ArrayList<>();
            String error = null;
            try { loaded = LibraryDirectoryReader.read(getContentResolver(), requestedTree, requestedDirectory); }
            catch (Exception exception) { error = readableError(exception); }
            List<LibraryEntry> finalLoaded = loaded;
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || token != directoryLoadToken || !requestedDirectory.equals(directoryUri)) return;
                allRows.clear();
                if (finalError == null) allRows.addAll(finalLoaded);
                pathText.setText(finalError == null ? LibraryDirectoryReader.displayName(getContentResolver(), requestedDirectory) : "フォルダを開けません");
                stateText.setText(finalError == null ? finalLoaded.size() + " 件" : finalError);
                if (finalError != null) showEmptyState("フォルダを開けません", finalError + " フォルダを選び直してください。", "選び直す", false);
                applyFilters();
            });
        });
    }

    private void loadSavedItems() {
        directoryLoadToken++;
        updateNavigation();
        pathText.setText(mode == MODE_DIRECTORIES ? "登録ディレクトリ" : mode == MODE_FAVORITES ? "お気に入り" : mode == MODE_BOOKMARKS ? "しおりのある作品" : "最近開いた作品");
        allRows.clear();
        List<AppState.SavedItem> items = mode == MODE_DIRECTORIES ? AppState.directories(this) : mode == MODE_FAVORITES ? AppState.favorites(this)
                : mode == MODE_BOOKMARKS ? AppState.bookmarkedItems(this) : AppState.recents(this);
        for (AppState.SavedItem item : items) allRows.add(new LibraryEntry(item.uri, item.title, null, item.kind, mode == MODE_DIRECTORIES, 0, item.timestamp));
        stateText.setText(items.isEmpty() ? "0 件" : items.size() + " 件");
        applyFilters();
    }

    private void applyFilters() {
        visibleRows.clear();
        for (LibraryEntry item : allRows) if (query.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(query) || item.kind.toLowerCase(Locale.ROOT).contains(query)) visibleRows.add(item);
        Collections.sort(visibleRows, new Comparator<LibraryEntry>() {
            @Override public int compare(LibraryEntry left, LibraryEntry right) {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                int result = sortMode == SORT_MODIFIED ? Long.compare(left.modified, right.modified) : sortMode == SORT_SIZE ? Long.compare(left.size, right.size) : collator.compare(left.name, right.name);
                if (result == 0) result = collator.compare(left.name, right.name);
                return descending ? -result : result;
            }
        });
        sortButton.setText("並び順");
        sortButton.setContentDescription((sortMode == SORT_MODIFIED ? "更新日時" : sortMode == SORT_SIZE ? "サイズ" : "名前") + (descending ? "の降順" : "の昇順") + "。タップして変更");
        if (!allRows.isEmpty() && visibleRows.isEmpty()) {
            stateText.setText("0 件");
            showEmptyState("見つかりません", "別の名前で検索してください。", null, false);
        } else if (visibleRows.isEmpty()) {
            if (mode == MODE_DIRECTORIES) showEmptyState("登録ディレクトリはありません", "フォルダを長押しして登録できます。", "フォルダを見る", false);
            else if (mode == MODE_FAVORITES) showEmptyState("お気に入りはありません", "作品を長押しして追加できます。", null, false);
            else if (mode == MODE_RECENTS) showEmptyState("履歴はありません", "作品を開くとここに表示されます。", "フォルダを見る", false);
            else if (mode == MODE_BOOKMARKS) showEmptyState("しおりはありません", "読書画面でページにしおりを付けると、作品がここに表示されます。", "フォルダを見る", false);
            else if (treeUri != null && emptyProgress.getVisibility() != View.VISIBLE && !pathText.getText().toString().equals("フォルダを開けません"))
                showEmptyState("表示できるファイルがありません", "PDF・CBZ・ZIP・画像に対応しています。", "別のフォルダを選ぶ", false);
        } else {
            hideEmptyState();
            stateText.setText(visibleRows.size() + " 件");
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
                if (mode != MODE_LIBRARY && treeUri != null) selectMode(MODE_LIBRARY); else chooseFolder();
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

    private void open(LibraryEntry item) {
        open(item, true);
    }

    private void open(LibraryEntry item, boolean includeSiblingImages) {
        if (item.directory) { directoryUri = item.uri; loadDirectory(); return; }
        AppState.addRecent(this, item.uri, item.name, item.kind);
        Intent viewer = new Intent(this, ViewerActivity.class);
        viewer.setData(item.uri);
        viewer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewer.putExtra(ViewerActivity.EXTRA_TITLE, item.name);
        if (ComicFile.isImage(item.name, item.mime)) {
            ArrayList<Uri> pages = new ArrayList<>();
            if (includeSiblingImages && mode == MODE_LIBRARY) {
                for (LibraryEntry candidate : visibleRows) if (!candidate.directory && ComicFile.isImage(candidate.name, candidate.mime)) pages.add(candidate.uri);
            } else {
                pages.add(item.uri);
            }
            viewer.putParcelableArrayListExtra(ViewerActivity.EXTRA_IMAGE_URIS, pages);
            viewer.putExtra(ViewerActivity.EXTRA_START_INDEX, pages.indexOf(item.uri));
        }
        startActivity(viewer);
    }

    private void showActions(LibraryEntry item) {
        if (item.directory) {
            boolean saved = AppState.isDirectory(this, item.uri);
            Ui.show(new AlertDialog.Builder(this).setTitle(item.name)
                    .setItems(new String[]{"開く", saved ? "登録ディレクトリから外す" : "ディレクトリに登録"}, (dialog, which) -> {
                        if (which == 0) open(item);
                        else {
                            AppState.setDirectory(this, item.uri, item.name, !saved);
                            if (mode == MODE_DIRECTORIES && saved) loadSavedItems();
                            Toast.makeText(this, saved ? "登録ディレクトリから外しました" : "ディレクトリに登録しました", Toast.LENGTH_SHORT).show();
                        }
                    }));
            return;
        }
        boolean favorite = AppState.isFavorite(this, item.uri);
        ArrayList<String> actions = new ArrayList<>();
        actions.add(favorite ? "お気に入りから外す" : "お気に入りに追加");
        actions.add("復帰位置を消去");
        actions.add("詳細を表示");
        if (mode == MODE_RECENTS) actions.add("履歴から削除");
        if (!AppState.bookmarks(this, item.uri).isEmpty()) actions.add("しおりをすべて消去");
        if (AppState.hasCover(this, item.uri)) actions.add("表紙を初期状態に戻す");
        Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) {
                setFavorite(item, !favorite);
                Toast.makeText(this, !favorite ? "お気に入りに追加しました" : "お気に入りから外しました", Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                AppState.clearPosition(this, item.uri);
                Toast.makeText(this, "復帰位置を消去しました", Toast.LENGTH_SHORT).show();
                adapter.notifyDataSetChanged();
            } else if (which == 2) showDetails(item);
            else if ("履歴から削除".equals(actions.get(which))) { AppState.removeRecent(this, item.uri); loadSavedItems(); }
            else if ("表紙を初期状態に戻す".equals(actions.get(which))) {
                AppState.removeCover(this, item.uri);
                thumbnails.evictAll();
                adapter.notifyDataSetChanged();
            }
            else { AppState.clearBookmarks(this, item.uri); if (mode == MODE_BOOKMARKS) loadSavedItems(); else adapter.notifyDataSetChanged(); }
        }));
    }

    private void setFavorite(LibraryEntry item, boolean favorite) {
        AppState.setFavorite(this, item.uri, item.name, item.kind, favorite);
        if (mode == MODE_FAVORITES && !favorite) loadSavedItems(); else adapter.notifyDataSetChanged();
    }

    private void showDetails(LibraryEntry item) {
        int saved = AppState.getPosition(this, item.uri);
        int total = AppState.totalPages(this, item.uri);
        int bookmarkCount = AppState.bookmarks(this, item.uri).size();
        String message = "形式: " + item.kind + "\n" + (item.size > 0 ? "サイズ: " + ComicFile.formatSize(item.size) + "\n" : "")
                + (item.modified > 0 ? "更新: " + DateFormat.getMediumDateFormat(this).format(new Date(item.modified)) + "\n" : "")
                + "復帰位置: " + (saved + 1) + (total > 0 ? " / " + total + " ページ（" + Math.round((saved + 1) * 100f / total) + "%）" : " ページ")
                + "\nしおり: " + bookmarkCount + " 件";
        Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setMessage(message).setPositiveButton("閉じる", null));
    }

    private String readableError(Exception exception) {
        if (exception instanceof SecurityException) return "フォルダへのアクセス許可が失われました。";
        return exception.getMessage() == null ? "フォルダを読み取れません。" : exception.getMessage();
    }

    private TextView text(String value, int size, int color) { return Ui.text(this, value, size, color); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (mode != MODE_LIBRARY) { mode = MODE_LIBRARY; if (treeUri == null) showEmptyLibrary(); else loadDirectory(); }
        else if (directoryUri != null && !directoryUri.equals(treeUri)) goUp(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        directoryLoadToken++;
        folderWorker.shutdownNow();
        thumbnailWorker.shutdownNow();
        thumbnails.evictAll();
        super.onDestroy();
    }

    private final class LibraryAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleRows.size(); }
        @Override public LibraryEntry getItem(int position) { return visibleRows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null || !(convertView.getTag() instanceof Holder) || ((Holder) convertView.getTag()).grid != gridMode) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(gridMode ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                row.setGravity(gridMode ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
                row.setPadding(dp(gridMode ? 4 : 6), dp(6), dp(gridMode ? 4 : 8), dp(6));
                row.setMinimumHeight(dp(gridMode ? 210 : 88));
                Ui.styleListRow(row);
                FrameLayout cover = new FrameLayout(MainActivity.this);
                ImageView thumbnail = new ImageView(MainActivity.this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                Ui.styleThumbnail(thumbnail);
                cover.addView(thumbnail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView formatMark = Ui.badge(MainActivity.this, "", 0xFFFFFFFF, 0xAA212121);
                formatMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                cover.addView(formatMark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
                row.addView(cover, gridMode
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
                        : new LinearLayout.LayoutParams(dp(54), dp(76)));
                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setGravity(Gravity.CENTER_VERTICAL);
                info.setPadding(dp(gridMode ? 2 : 8), dp(gridMode ? 5 : 0), dp(4), 0);
                TextView name = text("", gridMode ? 13 : 15, Ui.DARK_TEXT);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(name);
                TextView detail = text("", gridMode ? 11 : 12, Ui.DARK_MUTED);
                detail.setPadding(0, dp(2), 0, 0);
                detail.setSingleLine(true);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(detail);
                row.addView(info, gridMode
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView progress = text("", gridMode ? 11 : 12, Ui.READER_ACCENT);
                progress.setGravity(gridMode ? Gravity.START : Gravity.END | Gravity.CENTER_VERTICAL);
                row.addView(progress, gridMode
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22))
                        : new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));
                holder = new Holder(thumbnail, formatMark, name, detail, progress, gridMode);
                row.setTag(holder);
                convertView = row;
            } else holder = (Holder) convertView.getTag();
            LibraryEntry item = getItem(position);
            holder.name.setText(item.name);
            String detail = item.directory ? "フォルダ" : item.kind + (item.size > 0 ? "  •  " + ComicFile.formatSize(item.size) : "");
            if (!item.directory && mode != MODE_LIBRARY && item.modified > 0)
                detail += "  •  " + DateFormat.getDateFormat(MainActivity.this).format(new Date(item.modified));
            holder.detail.setText(detail);
            int saved = AppState.getPosition(MainActivity.this, item.uri);
            int total = AppState.totalPages(MainActivity.this, item.uri);
            boolean hasProgress = !item.directory && (saved > 0 || total > 0);
            holder.progress.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
            holder.progress.setText(!hasProgress ? "" : total > 0
                    ? (saved + 1) + " / " + total + "  " + Math.round((saved + 1) * 100f / total) + "%"
                    : (saved + 1) + " p");
            bindThumbnail(holder.thumbnail, holder.formatMark, item);
            convertView.setContentDescription(item.name + "、" + holder.detail.getText() + (holder.progress.getText().length() > 0 ? "、" + holder.progress.getText() : ""));
            return convertView;
        }

        private void bindThumbnail(ImageView view, TextView formatMark, LibraryEntry item) {
            view.setTag(item.uri.toString());
            view.setImageDrawable(null);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            formatMark.setVisibility(View.GONE);
            if (item.directory) { view.setImageResource(R.drawable.ic_folder); return; }
            formatMark.setText(item.kind);
            formatMark.setVisibility(View.VISIBLE);
            java.io.File customCover = AppState.coverFile(MainActivity.this, item.uri);
            if (customCover.isFile()) {
                view.setImageResource(R.drawable.ic_archive);
                String key = "cover:" + item.uri;
                Bitmap cached = thumbnails.get(key);
                if (cached != null) { view.setScaleType(ImageView.ScaleType.CENTER_CROP); view.setImageBitmap(cached); return; }
                thumbnailWorker.execute(() -> {
                    Bitmap bitmap = BitmapFactory.decodeFile(customCover.getAbsolutePath());
                    if (bitmap == null) return;
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        thumbnails.put(key, bitmap);
                        if (item.uri.toString().equals(view.getTag())) {
                            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            view.setImageBitmap(bitmap);
                        }
                    });
                });
                return;
            }
            if (!ComicFile.isImage(item.name, item.mime)) { view.setImageResource(R.drawable.ic_archive); return; }
            view.setImageResource(R.drawable.ic_image_file);
            String key = item.uri.toString();
            Bitmap cached = thumbnails.get(key);
            if (cached != null) { view.setScaleType(ImageView.ScaleType.CENTER_CROP); view.setImageBitmap(cached); return; }
            thumbnailWorker.execute(() -> {
                try {
                    Bitmap bitmap = getContentResolver().loadThumbnail(item.uri, new Size(dp(112), dp(144)), null);
                    if (bitmap == null) return;
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        thumbnails.put(key, bitmap);
                        if (key.equals(view.getTag())) {
                            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            view.setImageBitmap(bitmap);
                        }
                    });
                } catch (Exception ignored) { }
            });
        }
    }

    private static final class Holder {
        final ImageView thumbnail; final TextView formatMark; final TextView name; final TextView detail; final TextView progress;
        final boolean grid;
        Holder(ImageView thumbnail, TextView formatMark, TextView name, TextView detail, TextView progress, boolean grid) {
            this.thumbnail = thumbnail; this.formatMark = formatMark; this.name = name; this.detail = detail; this.progress = progress; this.grid = grid;
        }
    }
}
