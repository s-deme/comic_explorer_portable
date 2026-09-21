package jp.yaman.comicexplorer;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
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

/** Product-facing local library: folder browsing, filtering, history and bookmarks. */
public final class MainActivity extends BaseActivity {
    private static final int REQUEST_TREE = 41;
    private static final int REQUEST_FILE = 42;
    private static final int REQUEST_MOVE = 43;
    private static final int MODE_LIBRARY = 0;
    private static final int MODE_RECENTS = 2;
    private static final int MODE_BOOKMARKS = 3;
    private static final int SORT_NAME = 0;
    private static final int SORT_MODIFIED = 1;
    private static final int SORT_SIZE = 2;

    private final ExecutorService folderWorker = Executors.newSingleThreadExecutor();
    private final LibraryThumbnails thumbnails = new LibraryThumbnails(this);
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
    private Button recentsDestination;
    private Button bookmarksDestination;
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
    private ImageButton quickView;
    private ArrayList<LibraryEntry> transferring;
    private boolean movingFiles;
    private int transferConflict;
    private float swipeX, swipeY;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // A launcher re-entry must reveal the existing reader, not cover it with another library.
        if (!isTaskRoot() && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        treeUri = AppState.getTree(this);
        directoryUri = treeUri;
        if (state != null) {
            mode = state.getInt("mode", MODE_LIBRARY);
            directoryUri = state.getParcelable("directory");
            movingFiles = state.getBoolean("moving_files"); transferConflict = state.getInt("transfer_conflict");
            ArrayList<Bundle> savedItems = state.getParcelableArrayList("transfer_items");
            if (savedItems != null) {
                transferring = new ArrayList<>();
                for (Bundle item : savedItems) transferring.add(new LibraryEntry(item.getParcelable("uri"), item.getString("name"), item.getString("mime"), item.getString("kind"), item.getBoolean("directory"), item.getLong("size"), item.getLong("modified")));
            }
        }
        if (mode != MODE_RECENTS && mode != MODE_BOOKMARKS) mode = MODE_LIBRARY;
        gridMode = AppState.gridView(this);
        buildUi();
        Uri opened = getIntent().getData();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && opened != null) {
            openExternal(opened);
            getIntent().setAction(null);
        }
        if (mode != MODE_LIBRARY) refresh(); else if (treeUri == null) showEmptyLibrary(); else loadDirectory();
    }

    @Override protected void onResume() {
        super.onResume();
        ReadingSync.authorize(this, false);
        boolean savedGrid = AppState.gridView(this);
        if (savedGrid != gridMode) gridMode = savedGrid;
        updateCollectionView();
        getWindow().setFlags(AppState.enabled(this, "list_statusbar", true) ? 0 : android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN, android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (quickView != null) quickView.setVisibility(AppState.enabled(this, "quick_view", true) && !AppState.recents(this).isEmpty() ? View.VISIBLE : View.GONE);
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
        thumbnails.clear();
        if (mode == MODE_LIBRARY) adapter.notifyDataSetChanged(); else loadSavedItems();
    }

    private void buildUi() {
        compactHeight = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density < 600f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setBackgroundColor(Ui.TOOLBAR);
        upButton = toolbarButton(R.drawable.ic_arrow_back, I18n.t(R.string.ui_parent_folder));
        upButton.setOnClickListener(view -> goUp());
        toolbar.addView(upButton, new LinearLayout.LayoutParams(dp(48), dp(56)));
        screenTitle = text("Comic Explorer", 20, Ui.TEXT_PRIMARY);
        Ui.title(screenTitle);
        screenTitle.setGravity(Gravity.CENTER_VERTICAL);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        screenTitle.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(screenTitle, new LinearLayout.LayoutParams(0, dp(56), 1f));
        searchButton = toolbarButton(R.drawable.ic_toolbar_search, I18n.t(R.string.ui_show_search));
        searchButton.setOnClickListener(view -> toggleSearchPanel());
        toolbar.addView(searchButton, new LinearLayout.LayoutParams(dp(48), dp(56)));
        ImageButton menu = toolbarButton(R.drawable.ic_toolbar_more, I18n.t(R.string.ui_open_menu));
        menu.setOnClickListener(view -> showAppMenu());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setBackgroundColor(Ui.SURFACE);
        libraryDestination = tabButton(I18n.t(R.string.ui_storage), MODE_LIBRARY, I18n.t(R.string.ui_show_storage));
        recentsDestination = tabButton(I18n.t(R.string.ui_history), MODE_RECENTS, I18n.t(R.string.ui_show_reading_history));
        bookmarksDestination = tabButton(I18n.t(R.string.ui_add_bookmark), MODE_BOOKMARKS, I18n.t(R.string.ui_show_bookmarked_books));
        tabs.addView(libraryDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(recentsDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(bookmarksDestination, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(I18n.t(R.string.ui_search_file_names));
        search.setContentDescription(I18n.t(R.string.ui_search_library));
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
        searchRow.setBackgroundColor(Ui.SURFACE_RAISED);
        searchRow.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(searchRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        searchPanel = searchRow;
        searchPanel.setVisibility(View.GONE);

        LinearLayout location = new LinearLayout(this);
        location.setGravity(Gravity.CENTER_VERTICAL);
        location.setPadding(dp(6), 0, dp(6), 0);
        location.setBackgroundColor(Ui.BACKGROUND);
        pathText = text("", 11, Ui.BRAND);
        Ui.label(pathText);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        location.addView(pathText, new LinearLayout.LayoutParams(0, dp(24), 1f));
        stateText = text("", 11, Ui.TEXT_SECONDARY);
        stateText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        stateText.setSingleLine(true);
        stateText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        location.addView(stateText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
        locationRow = location;
        root.addView(locationRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(Ui.OUTLINE));
        listView.setDividerHeight(dp(1));
        listView.setBackgroundColor(Ui.BACKGROUND);
        listView.setContentDescription(I18n.t(R.string.ui_books));
        // Keep row-level tap and long-press handling available.
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
        gridView.setBackgroundColor(Ui.BACKGROUND);
        gridView.setContentDescription(I18n.t(R.string.ui_book_thumbnails));
        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> open(visibleRows.get(position)));
        gridView.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visibleRows.get(position));
            return true;
        });
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Ui.BACKGROUND);
        content.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(gridView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        quickView = Ui.iconButton(this, R.drawable.ic_reader_resume_book, I18n.t(R.string.ui_open_last_book));
        quickView.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.ON_BRAND));
        quickView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND));
        quickView.setOnClickListener(v -> {
            List<AppState.SavedItem> recents = AppState.recents(this);
            if (!recents.isEmpty()) { AppState.SavedItem last = recents.get(0); open(new LibraryEntry(last.uri, last.title, null, last.kind, false, 0, last.timestamp), false); }
        });
        FrameLayout.LayoutParams quickParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        quickParams.setMargins(dp(16), dp(16), dp(16), dp(16)); content.addView(quickView, quickParams);
        emptyPanel = createEmptyPanel();
        content.addView(emptyPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        emptyTitle = text("", 17, Ui.TEXT_PRIMARY);
        Ui.title(emptyTitle);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(6));
        panel.addView(emptyTitle);
        emptyMessage = text("", 14, Ui.TEXT_SECONDARY);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setLineSpacing(0, 1.08f);
        panel.addView(emptyMessage);
        emptyAction = Ui.button(this, I18n.t(R.string.ui_choose_folder), Ui.ButtonStyle.PRIMARY);
        emptyAction.setOnClickListener(view -> chooseFolder());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        actionParams.setMargins(0, dp(16), 0, 0);
        panel.addView(emptyAction, actionParams);
        scroll.addView(panel, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/zip", "application/x-cbz", "application/vnd.rar", "application/x-rar-compressed", "application/x-cbr", "application/vnd.comicbook-rar", "application/x-7z-compressed", "image/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) { transferring = null; return; }
        if (data.getData() == null) return;
        if (requestCode == REQUEST_MOVE && transferring != null) {
            Uri target = data.getData(); ArrayList<LibraryEntry> items = transferring; transferring = null;
            int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) grants |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try { getContentResolver().takePersistableUriPermission(target, grants); } catch (SecurityException ignored) { }
            Uri destination = DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target));
            final boolean move = movingFiles; final int conflict = transferConflict;
            fileOperation(() -> { for (LibraryEntry item : items) new DocumentTransfer(this).transfer(item, destination, move, conflict); }); return;
        }
        if (requestCode == REQUEST_FILE) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            openExternal(uri);
            return;
        }
        if (requestCode != REQUEST_TREE) return;
        treeUri = data.getData();
        int permission = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) permission |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(treeUri, permission); } catch (SecurityException ignored) { }
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
        String[] labels = {I18n.t(R.string.ui_name_2), I18n.t(R.string.ui_modified_date_2), I18n.t(R.string.ui_size_2)};
        Ui.show(new AlertDialog.Builder(this)
                .setTitle(I18n.t(R.string.ui_sort))
                .setSingleChoiceItems(labels, sortMode, (dialog, selected) -> {
                    sortMode = selected;
                    dialog.dismiss();
                    applyFilters();
                })
                .setNegativeButton(descending ? I18n.t(R.string.ui_ascending_2) : I18n.t(R.string.ui_descending), (dialog, selected) -> {
                    descending = !descending;
                    applyFilters();
                }));
    }

    private void updateNavigation() {
        Ui.styleTopTab(libraryDestination, mode == MODE_LIBRARY);
        Ui.styleTopTab(recentsDestination, mode == MODE_RECENTS);
        Ui.styleTopTab(bookmarksDestination, mode == MODE_BOOKMARKS);
        if (screenTitle != null) screenTitle.setText(mode == MODE_RECENTS ? I18n.t(R.string.ui_history) : mode == MODE_BOOKMARKS ? I18n.t(R.string.ui_add_bookmark) : "Comic Explorer");
        if (upButton != null) upButton.setVisibility(mode == MODE_LIBRARY && treeUri != null && directoryUri != null && !directoryUri.equals(treeUri) ? View.VISIBLE : View.GONE);
    }

    private void toggleSearchPanel() {
        boolean expanded = searchPanel.getVisibility() != View.VISIBLE;
        searchPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        searchButton.setContentDescription(expanded ? I18n.t(R.string.ui_close_search) : I18n.t(R.string.ui_show_search));
        if (expanded) search.requestFocus(); else search.clearFocus();
    }

    private void toggleCollectionView() {
        ReaderOptions.list(this, () -> { gridMode = AppState.gridView(this); listView.setAdapter(adapter); gridView.setAdapter(adapter); updateCollectionView(); });
    }

    private void updateCollectionView() {
        if (listView == null || gridView == null) return;
        listView.setVisibility(gridMode ? View.GONE : View.VISIBLE);
        gridView.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        gridView.setNumColumns(AppState.gridColumns(this));
        gridView.setBackgroundColor(AppState.number(this, "grid_color", Ui.BACKGROUND));
        if (locationRow != null) locationRow.setVisibility(AppState.showLibraryPath(this) ? View.VISIBLE : View.GONE);
        int scrollPosition = AppState.leftLibraryScrollbar(this)
                ? View.SCROLLBAR_POSITION_LEFT : View.SCROLLBAR_POSITION_RIGHT;
        listView.setVerticalScrollbarPosition(scrollPosition);
        gridView.setVerticalScrollbarPosition(scrollPosition);
        adapter.notifyDataSetChanged();
    }

    private void showAppMenu() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_refresh), this::refresh);
        menu.add(I18n.t(R.string.ui_open_file), this::chooseFile);
        menu.add(I18n.t(R.string.ui_list_type), this::toggleCollectionView);
        menu.add(I18n.t(R.string.ui_sort), this::chooseSort);
        if (mode == MODE_LIBRARY) {
            menu.add(I18n.t(R.string.ui_select_folder_again), this::chooseFolder);
            if (treeUri != null && directoryUri != null && !directoryUri.equals(treeUri))
                menu.add(I18n.t(R.string.ui_parent_folder), this::goUp);
        }
        menu.add(I18n.t(R.string.ui_settings), () -> startActivity(new Intent(this, SettingsActivity.class)));
        menu.show(this, I18n.t(R.string.ui_menu));
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
        if (name == null || name.trim().isEmpty()) name = I18n.t(R.string.ui_external_file);
        String mime = getContentResolver().getType(uri);
        if (!ComicFile.isSupported(name, mime)) {
            Toast.makeText(this, I18n.t(R.string.ui_select_a_pdf_cbz_zip_or_image), Toast.LENGTH_LONG).show();
            return;
        }
        open(new LibraryEntry(uri, name, mime, ComicFile.kindFor(name, mime), false, size, 0), false);
    }

    private void showEmptyLibrary() {
        directoryLoadToken++;
        allRows.clear();
        visibleRows.clear();
        pathText.setText(I18n.t(R.string.ui_no_folder_selected));
        stateText.setText("");
        showEmptyState(I18n.t(R.string.ui_no_folder_selected_2), I18n.t(R.string.ui_choose_the_folder_containing_your_comics), I18n.t(R.string.ui_choose_folder), false);
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
        pathText.setText(I18n.t(R.string.ui_loading));
        stateText.setText("");
        showEmptyState(I18n.t(R.string.ui_loading), "", null, true);
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
                pathText.setText(finalError == null ? LibraryDirectoryReader.displayName(getContentResolver(), requestedDirectory) : I18n.t(R.string.ui_cannot_open_folder));
                stateText.setText(finalError == null ? finalLoaded.size() + I18n.t(R.string.ui_items) : finalError);
                if (finalError != null) showEmptyState(I18n.t(R.string.ui_cannot_open_folder), finalError + I18n.t(R.string.ui_select_the_folder_again), I18n.t(R.string.ui_choose_again), false);
                applyFilters();
            });
        });
    }

    private void loadSavedItems() {
        directoryLoadToken++;
        updateNavigation();
        pathText.setText(mode == MODE_BOOKMARKS ? I18n.t(R.string.ui_bookmarked_books) : I18n.t(R.string.ui_recently_opened_books));
        allRows.clear();
        List<AppState.SavedItem> items = mode == MODE_BOOKMARKS ? AppState.bookmarkedItems(this) : AppState.recents(this);
        for (AppState.SavedItem item : items) allRows.add(new LibraryEntry(item.uri, item.title, null, item.kind, false, 0, item.timestamp));
        stateText.setText(items.isEmpty() ? I18n.t(R.string.ui_0_items) : items.size() + I18n.t(R.string.ui_items));
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
        if (!allRows.isEmpty() && visibleRows.isEmpty()) {
            stateText.setText(I18n.t(R.string.ui_0_items));
            showEmptyState(I18n.t(R.string.ui_no_results), I18n.t(R.string.ui_try_a_different_name), null, false);
        } else if (visibleRows.isEmpty()) {
            if (mode == MODE_RECENTS) showEmptyState(I18n.t(R.string.ui_no_history), I18n.t(R.string.ui_opened_books_appear_here), I18n.t(R.string.ui_browse_folders), false);
            else if (mode == MODE_BOOKMARKS) showEmptyState(I18n.t(R.string.ui_no_bookmarks), I18n.t(R.string.ui_bookmark_a_page_while_reading_to_add_its_book_here), I18n.t(R.string.ui_browse_folders), false);
            else if (treeUri != null && emptyProgress.getVisibility() != View.VISIBLE && !pathText.getText().toString().equals(I18n.t(R.string.ui_cannot_open_folder)))
                showEmptyState(I18n.t(R.string.ui_no_supported_files), I18n.t(R.string.ui_supports_pdf_cbz_zip_and_images), I18n.t(R.string.ui_choose_another_folder), false);
        } else {
            hideEmptyState();
            stateText.setText(visibleRows.size() + I18n.t(R.string.ui_items));
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
        if (mode == MODE_LIBRARY && includeSiblingImages) {
            ArrayList<Uri> books = new ArrayList<>(); ArrayList<String> titles = new ArrayList<>();
            for (LibraryEntry candidate : visibleRows) if (!candidate.directory && !ComicFile.isImage(candidate.name, candidate.mime)) { books.add(candidate.uri); titles.add(candidate.name); }
            viewer.putParcelableArrayListExtra(ViewerActivity.EXTRA_BOOK_URIS, books); viewer.putStringArrayListExtra(ViewerActivity.EXTRA_BOOK_TITLES, titles);
        }
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
        if (item.directory) { fileMenu(item); return; }
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_clear_reading_position), () -> {
            AppState.clearPosition(this, item.uri);
            Toast.makeText(this, I18n.t(R.string.ui_reading_position_cleared), Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
        });
        menu.add(I18n.t(R.string.ui_details), () -> showDetails(item));
        menu.add(I18n.t(R.string.ui_file_operations), () -> fileMenu(item));
        if (mode == MODE_RECENTS) menu.add(I18n.t(R.string.ui_remove_from_history), () -> { AppState.removeRecent(this, item.uri); loadSavedItems(); });
        if (!AppState.bookmarks(this, item.uri).isEmpty()) menu.add(I18n.t(R.string.ui_delete_all_bookmarks_2), () -> {
            AppState.clearBookmarks(this, item.uri);
            if (mode == MODE_BOOKMARKS) loadSavedItems(); else adapter.notifyDataSetChanged();
        });
        if (AppState.hasCover(this, item.uri)) menu.add(I18n.t(R.string.ui_restore_default_cover), () -> {
            AppState.removeCover(this, item.uri);
            thumbnails.clear();
            adapter.notifyDataSetChanged();
        });
        menu.show(this, item.name);
    }

    private void showDetails(LibraryEntry item) {
        int saved = AppState.getPosition(this, item.uri);
        int total = AppState.totalPages(this, item.uri);
        int bookmarkCount = AppState.bookmarks(this, item.uri).size();
        String message = I18n.t(R.string.ui_format) + item.kind + "\n" + (item.size > 0 ? I18n.t(R.string.ui_size_3) + ComicFile.formatSize(item.size) + "\n" : "")
                + (item.modified > 0 ? I18n.t(R.string.ui_updated) + DateFormat.getMediumDateFormat(this).format(new Date(item.modified)) + "\n" : "")
                + I18n.t(R.string.ui_reading_position) + (saved + 1) + (total > 0 ? " / " + total + I18n.t(R.string.ui_pages) + Math.round((saved + 1) * 100f / total) + "%）" : I18n.t(R.string.ui_pages_2))
                + I18n.t(R.string.ui_bookmarks) + bookmarkCount + I18n.t(R.string.ui_items);
        Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setMessage(message).setPositiveButton(I18n.t(R.string.ui_close), null));
    }

    private String readableError(Exception exception) {
        if (exception instanceof SecurityException) return I18n.t(R.string.ui_folder_access_was_revoked);
        return exception.getMessage() == null ? I18n.t(R.string.ui_cannot_read_folder) : exception.getMessage();
    }

    private TextView text(String value, int size, int color) { return Ui.text(this, value, size, color); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) { swipeX = event.getX(); swipeY = event.getY(); }
        if (event.getAction() == android.view.MotionEvent.ACTION_UP && Math.abs(event.getX()-swipeX) > dp(100) && Math.abs(event.getY()-swipeY) < dp(48)) {
            int[] tabs = {MODE_LIBRARY, MODE_RECENTS, MODE_BOOKMARKS}; int index = 0;
            for (int i=0; i<tabs.length; i++) if (tabs[i] == mode) index = i;
            selectMode(tabs[(index + (event.getX()<swipeX ? 1 : tabs.length - 1)) % tabs.length]);
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event); cancel.setAction(android.view.MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); return true;
        }
        return super.dispatchTouchEvent(event);
    }
    private interface FileAction { void run() throws Exception; }
    private void fileOperation(FileAction action) {
        AlertDialog progress = Ui.show(new AlertDialog.Builder(this).setMessage(I18n.t(R.string.ui_transfer_running)).setCancelable(false));
        folderWorker.execute(() -> {
            String failure = null;
            try { action.run(); } catch (Exception e) { failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            String error = failure;
            runOnUiThread(() -> { if (isFinishing() || isDestroyed()) return; progress.dismiss(); Toast.makeText(this, error == null ? I18n.t(R.string.ui_done) : I18n.t(R.string.ui_operation_failed) + error, Toast.LENGTH_LONG).show(); refresh(); });
        });
    }
    private void fileMenu(LibraryEntry item) {
        if (!DocumentsContract.isDocumentUri(this, item.uri)) { Toast.makeText(this, I18n.t(R.string.ui_select_folder_again), Toast.LENGTH_LONG).show(); return; }
        Ui.show(new AlertDialog.Builder(this).setTitle(item.name).setItems(new String[]{I18n.t(R.string.ui_rename), I18n.t(R.string.ui_move), I18n.t(R.string.ui_delete), I18n.t(R.string.ui_copy)}, (d, i) -> {
            if (i == 0) editFile(item, I18n.t(R.string.ui_rename));
            if (i == 1 || i == 3) beginTransfer(java.util.Collections.singletonList(item), i == 1);
            if (i == 2) Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_delete_item)).setMessage(item.name + I18n.t(R.string.ui_delete_permanently) + (item.directory ? I18n.t(R.string.ui_files_inside_this_folder_will_also_be_deleted) : ""))
                    .setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_delete), (a, b) -> fileOperation(() -> {
                        if (!DocumentsContract.deleteDocument(getContentResolver(), item.uri)) throw new java.io.IOException(I18n.t(R.string.ui_cannot_delete));
                        AppState.removeRecent(this, item.uri); AppState.clearPosition(this, item.uri); AppState.clearBookmarks(this, item.uri);
                    })));
        }));
    }
    private void editFile(LibraryEntry item, String title) {
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(item == null ? "" : item.name); name.setHint(I18n.t(R.string.ui_name_3));
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setTitle(title).setView(name).setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_save), null));
        dialog.getButton(-1).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\") || value.indexOf(0) >= 0) { name.setError(I18n.t(R.string.ui_enter_a_valid_name)); return; }
            dialog.dismiss(); fileOperation(() -> {
                if (item == null) {
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, directoryUri.equals(treeUri) ? DocumentsContract.getTreeDocumentId(treeUri) : DocumentsContract.getDocumentId(directoryUri));
                    if (DocumentsContract.createDocument(getContentResolver(), parent, DocumentsContract.Document.MIME_TYPE_DIR, value) == null) throw new java.io.IOException(I18n.t(R.string.ui_cannot_create_folder));
                } else {
                    DocumentTransfer.rename(this, item, value);
                }
            });
        });
    }
    private void beginTransfer(List<LibraryEntry> items, boolean move) {
        for (LibraryEntry item : items) if (!DocumentsContract.isDocumentUri(this, item.uri)) { Toast.makeText(this, I18n.t(R.string.ui_select_folder_again), Toast.LENGTH_LONG).show(); return; }
        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_conflict)).setItems(new String[]{I18n.t(R.string.ui_keep_both), I18n.t(R.string.ui_replace), I18n.t(R.string.ui_skip)}, (d, i) -> {
            transferring = new ArrayList<>(items); movingFiles = move; transferConflict = i;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQUEST_MOVE);
        }));
    }
    @Override public void onBackPressed() {
        if (mode != MODE_LIBRARY) { mode = MODE_LIBRARY; if (treeUri == null) showEmptyLibrary(); else loadDirectory(); }
        else if (directoryUri != null && !directoryUri.equals(treeUri)) goUp(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        directoryLoadToken++;
        folderWorker.shutdownNow();
        thumbnails.close();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("mode", mode); state.putParcelable("directory", directoryUri);
        state.putBoolean("moving_files",movingFiles); state.putInt("transfer_conflict",transferConflict);
        if (transferring != null) {
            ArrayList<Bundle> saved = new ArrayList<>();
            for (LibraryEntry item : transferring) { Bundle value = new Bundle(); value.putParcelable("uri",item.uri);value.putString("name",item.name);value.putString("mime",item.mime);value.putString("kind",item.kind);value.putBoolean("directory",item.directory);value.putLong("size",item.size);value.putLong("modified",item.modified);saved.add(value); }
            state.putParcelableArrayList("transfer_items",saved);
        }
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
                row.setMinimumHeight(dp(gridMode ? 48 : 88));
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
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(48), (getResources().getDisplayMetrics().widthPixels - dp(16)) / AppState.gridColumns(MainActivity.this) * (AppState.enabled(MainActivity.this, "grid_square", false) ? 1 : 3) / (AppState.enabled(MainActivity.this, "grid_square", false) ? 1 : 2)))
                        : new LinearLayout.LayoutParams(dp(54), dp(76)));
                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setGravity(Gravity.CENTER_VERTICAL);
                info.setPadding(dp(gridMode ? 2 : 8), dp(gridMode ? 5 : 0), dp(4), 0);
                TextView name = text("", gridMode ? 13 : 15, Ui.TEXT_PRIMARY);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(name);
                TextView detail = text("", gridMode ? 11 : 12, Ui.TEXT_SECONDARY);
                detail.setPadding(0, dp(2), 0, 0);
                detail.setSingleLine(true);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(detail);
                row.addView(info, gridMode
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView progress = text("", gridMode ? 11 : 12, Ui.BRAND);
                progress.setGravity(gridMode ? Gravity.START : Gravity.END | Gravity.CENTER_VERTICAL);
                row.addView(progress, gridMode
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22))
                        : new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT));
                holder = new Holder(thumbnail, formatMark, name, detail, progress, gridMode);
                row.setTag(holder);
                convertView = row;
            } else holder = (Holder) convertView.getTag();
            LibraryEntry item = getItem(position);
            if (gridMode) {
                int background=AppState.number(MainActivity.this,"grid_color",Ui.BACKGROUND);
                boolean customBackground=AppState.prefs(MainActivity.this).contains("setting.grid_color");
                int foreground=customBackground ? (androidx.core.graphics.ColorUtils.calculateLuminance(background)>.179 ? 0xff000000 : 0xffffffff) : Ui.TEXT_PRIMARY;
                convertView.setBackgroundColor(background);holder.name.setTextColor(foreground);holder.detail.setTextColor(customBackground ? foreground : Ui.TEXT_SECONDARY);
                holder.progress.setTextColor(androidx.core.graphics.ColorUtils.calculateContrast(Ui.BRAND,background)>=4.5 ? Ui.BRAND : foreground);
            }
            holder.name.setText(item.name);
            holder.name.setVisibility(!gridMode || AppState.enabled(MainActivity.this, "grid_name", true) ? View.VISIBLE : View.GONE);
            String detail = item.directory ? I18n.t(R.string.ui_directory) : item.kind + (item.size > 0 ? "  •  " + ComicFile.formatSize(item.size) : "");
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
            thumbnails.bind(holder.thumbnail, holder.formatMark, item);
            convertView.setContentDescription(item.name + "、" + holder.detail.getText() + (holder.progress.getText().length() > 0 ? "、" + holder.progress.getText() : ""));
            return convertView;
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
