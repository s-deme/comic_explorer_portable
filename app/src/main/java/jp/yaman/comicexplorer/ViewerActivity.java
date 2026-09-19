package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

import java.io.IOException;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Responsive page reader. Work that touches files or decodes images is off the UI thread. */
public final class ViewerActivity extends BaseActivity implements ZoomImageView.InteractionListener {
    public static final String EXTRA_IMAGE_URIS = "image_uris";
    public static final String EXTRA_START_INDEX = "start_index";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BOOK_URIS = "book_uris";
    public static final String EXTRA_BOOK_TITLES = "book_titles";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private BitmapMemoryCache pageCache;
    private final Runnable autoPage = new Runnable() {
        @Override public void run() {
            if (autoDelayMs <= 0 || destroyed || isFinishing()) return;
            if (loading != null && loading.getVisibility() == View.VISIBLE) {
                autoHandler.postDelayed(this, autoDelayMs);
                return;
            }
            int before = page;
            forward();
            if (page == before) { stopAutoPage(); return; }
            autoHandler.postDelayed(this, autoDelayMs);
        }
    };

    private PageSource pageSource;
    private Uri sourceUri;
    private String title;
    private ArrayList<Uri> imageUris;

    private int page;
    private int totalPages;
    private int loadToken;
    private int autoDelayMs;
    private int maxBitmapPixels;
    private int pageLayout;

    private int cropPercent;
    private boolean initialized;
    private boolean chromeVisible;
    private boolean fullScreen;

    private boolean dualPageDivider;
    private volatile boolean destroyed;

    private ZoomImageView imageView;
    private TextView pageText;
    private TextView errorText;
    private View chromeTop;
    private View chromeBottom;
    private LinearLayout readerMenuRow;
    private View errorPanel;
    private ProgressBar loading;
    private SeekBar pageSlider;
    private ImageButton quickBookmarkButton;
    private ImageButton leftPageButton;
    private ImageButton rightPageButton;
    private ImageButton autoPageButton;
    private ImageButton fullscreenButton;
    private ContinuousReader continuous;
    private FrameLayout pageCanvas;
    private androidx.drawerlayout.widget.DrawerLayout readerDrawer;
    private String bookPassword;
    private final java.util.Map<String,File> archiveVolumes=new java.util.HashMap<>();
    private boolean requestingBookAccess;
    private FrameLayout drawerPanel;
    private boolean drawerChapters;
    private Runnable disposeDrawer;

    private android.graphics.RectF customCrop;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.containsKey("current_page")) getIntent().putExtra(EXTRA_START_INDEX, state.getInt("current_page"));
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int maxKb = manager == null ? 20 * 1024 : Math.min(32 * 1024, manager.getMemoryClass() * 1024 / 6);
        pageCache = new BitmapMemoryCache(Math.max(8 * 1024, maxKb));
        maxBitmapPixels = Math.max(2 * 1024 * 1024, maxKb * 1024 / 4);
        getWindow().setStatusBarColor(Ui.BACKGROUND);
        getWindow().setNavigationBarColor(Ui.BACKGROUND);
        sourceUri = getIntent().getData();
        if (sourceUri == null) { finish(); return; }
        customCrop = AppState.crop(this, sourceUri);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty()) title = "Comic Explorer";
        imageUris = getIntent().getParcelableArrayListExtra(EXTRA_IMAGE_URIS);
        buildUi();
        applyReaderPreferences();
        if (AppState.startFullscreen(this)) {
            fullScreen = true;
            imageView.post(this::applyFullscreen);
        }

        refreshReaderActions();
        initializeSource();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);

        LinearLayout topChrome = new LinearLayout(this);
        topChrome.setOrientation(LinearLayout.VERTICAL);
        topChrome.setBackgroundColor(Ui.TOOLBAR);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        top.setBackgroundColor(Ui.TOOLBAR);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_arrow_back);
        close.setContentDescription(I18n.t(R.string.ui_close_book));
        Ui.styleToolbarButton(close, Ui.TOOLBAR);
        close.setOnClickListener(view -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(56)));
        TextView titleText = text(title, 16, Ui.TEXT_PRIMARY);
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleText.setPadding(dp(8), 0, dp(8), 0);
        top.addView(titleText, new LinearLayout.LayoutParams(0, dp(56), 1f));
        ImageButton more = new ImageButton(this);
        more.setImageResource(R.drawable.ic_toolbar_more);
        more.setContentDescription(I18n.t(R.string.ui_reader_menu));
        Ui.styleToolbarButton(more, Ui.TOOLBAR);
        more.setOnClickListener(view -> showReaderMenu());
        top.addView(more, new LinearLayout.LayoutParams(dp(48), dp(56)));
        topChrome.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        topChrome.addView(buildReaderMenu(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        chromeTop = topChrome;

        FrameLayout canvas = new FrameLayout(this);
        pageCanvas = canvas;
        imageView = new ZoomImageView(this);
        imageView.setInteractionListener(this);
        canvas.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        continuous = new ContinuousReader(this, worker, index -> readerDrawable(index,decodeLayout(index)), index -> {
            if (!initialized || !vertical() || page == index) return;
            page = index; updateControls(); AppState.updateReadingProgress(this, sourceUri, page, totalPages);
        }, this);
        continuous.setVisibility(View.GONE);
        canvas.addView(continuous, new FrameLayout.LayoutParams(-1, -1));
        leftPageButton = Ui.iconButton(this, R.drawable.ic_reader_page_left, I18n.t(R.string.ui_left_page_button));
        rightPageButton = Ui.iconButton(this, R.drawable.ic_reader_page_right, I18n.t(R.string.ui_right_page_button));
        Ui.styleReaderPageButton(leftPageButton);
        Ui.styleReaderPageButton(rightPageButton);
        leftPageButton.setOnClickListener(view -> pageButton(false));
        rightPageButton.setOnClickListener(view -> pageButton(true));
        FrameLayout.LayoutParams leftPageParams = new FrameLayout.LayoutParams(dp(48), dp(96), Gravity.START | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rightPageParams = new FrameLayout.LayoutParams(dp(48), dp(96), Gravity.END | Gravity.CENTER_VERTICAL);
        canvas.addView(leftPageButton, leftPageParams);
        canvas.addView(rightPageButton, rightPageParams);
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND));
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        canvas.addView(loading, loadingParams);
        LinearLayout error = new LinearLayout(this);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(24), dp(24), dp(24));
        Ui.styleDarkPanel(error);
        errorText = text("", 16, Ui.TEXT_PRIMARY);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(0, 1.08f);
        error.addView(errorText);
        Button retry = button(I18n.t(R.string.ui_retry), I18n.t(R.string.ui_reload_file));
        Ui.styleButton(retry, Ui.ButtonStyle.PRIMARY);
        retry.setOnClickListener(view -> { if (initialized) loadPage(page, false); else initializeSource(); });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        retryParams.setMargins(0, dp(16), 0, 0);
        error.addView(retry, retryParams);
        errorPanel = error;
        errorPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        errorParams.setMargins(dp(18), 0, dp(18), 0);
        canvas.addView(errorPanel, errorParams);
        root.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Ui.SURFACE);

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(dp(8), 0, dp(8), 0);
        pageText = text(I18n.t(R.string.ui_loading), 13, Ui.TEXT_PRIMARY);
        pageText.setGravity(Gravity.CENTER);
        Ui.styleChip(pageText, false);
        pageText.setContentDescription(I18n.t(R.string.ui_page_number_tap_to_jump));
        pageText.setOnClickListener(view -> showPageJump());
        sliderRow.addView(pageText, new LinearLayout.LayoutParams(dp(88), dp(36)));
        pageSlider = new SeekBar(this);
        pageSlider.setMax(0);
        pageSlider.setContentDescription(I18n.t(R.string.ui_page_slider));
        Ui.styleSeekBar(pageSlider);
        pageSlider.setMinimumHeight(dp(40));
        pageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && totalPages > 0) pageText.setText((progress + 1) + " / " + totalPages);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (initialized) goToPage(bar.getProgress()); }
        });
        sliderRow.addView(pageSlider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(sliderRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        chromeBottom = bottom;
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(topChrome, topParams);
        root.addView(bottom, bottomParams);
        chromeTop.setVisibility(View.GONE);
        chromeBottom.setVisibility(View.GONE);
        readerDrawer = new androidx.drawerlayout.widget.DrawerLayout(this);
        readerDrawer.addView(root, new androidx.drawerlayout.widget.DrawerLayout.LayoutParams(-1, -1));
        drawerPanel = new FrameLayout(this);
        drawerPanel.setBackgroundColor(Ui.SURFACE);
        readerDrawer.addView(drawerPanel, new androidx.drawerlayout.widget.DrawerLayout.LayoutParams(dp(96), -1, Gravity.LEFT));
        readerDrawer.setDrawerTitle(Gravity.LEFT, I18n.t(R.string.ui_page_thumbnails));
        readerDrawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        readerDrawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View view) {
                stopAutoPage();
                if(drawerPanel.getChildCount()>0 && drawerPanel.getChildAt(0) instanceof android.widget.ListView) {
                    android.widget.ListView list=(android.widget.ListView)drawerPanel.getChildAt(0);
                    android.widget.BaseAdapter adapter=(android.widget.BaseAdapter)list.getAdapter();adapter.notifyDataSetChanged();
                    int selected=0;for(int i=0;i<adapter.getCount();i++)if(adapter.getItemId(i)<=page)selected=i;
                    list.setItemChecked(selected,true);list.setSelection(selected);
                }
            }
            @Override public void onDrawerStateChanged(int state) {
                if (state == androidx.drawerlayout.widget.DrawerLayout.STATE_DRAGGING && initialized)
                    stopAutoPage();
            }
        });
        setContentView(readerDrawer);
        Ui.applySystemBarInsets(this, root);
    }

    private View buildReaderMenu() {
        readerMenuRow = new LinearLayout(this);
        readerMenuRow.setGravity(Gravity.CENTER_VERTICAL);
        readerMenuRow.setBackgroundColor(Ui.SURFACE_RAISED);
        addReaderMenuAction(R.drawable.ic_reader_pages, I18n.t(R.string.ui_page_thumbnails), view -> showPageList());
        quickBookmarkButton = addReaderMenuAction(R.drawable.ic_reader_bookmark, I18n.t(R.string.ui_bookmark_this_page), view -> toggleBookmark());
        addReaderMenuAction(R.drawable.ic_reader_brightness, I18n.t(R.string.ui_brightness), view -> showBrightnessDialog());
        fullscreenButton = addReaderMenuAction(R.drawable.ic_reader_fullscreen, I18n.t(R.string.ui_enter_fullscreen), view -> toggleFullscreen());
        autoPageButton = addReaderMenuAction(R.drawable.ic_reader_auto_page, I18n.t(R.string.ui_auto_page_turn), view -> {
            if (autoDelayMs > 0) stopAutoPage(); else showAutoPageDialog();
        });
        refreshReaderActions();
        return readerMenuRow;
    }

    private void refreshReaderActions() {
        refreshQuickBookmark();
        if (autoPageButton != null) Ui.updateIconButton(autoPageButton,
                autoDelayMs > 0 ? R.drawable.ic_reader_stop : R.drawable.ic_reader_auto_page,
                I18n.t(autoDelayMs > 0 ? R.string.ui_stop_auto_page_turn : R.string.ui_auto_page_turn),
                autoDelayMs > 0, Ui.TEXT_PRIMARY);
        if (fullscreenButton != null) Ui.updateIconButton(fullscreenButton,
                fullScreen ? R.drawable.ic_reader_fullscreen_exit : R.drawable.ic_reader_fullscreen,
                I18n.t(fullScreen ? R.string.ui_exit_fullscreen : R.string.ui_enter_fullscreen), fullScreen, Ui.TEXT_PRIMARY);
    }

    private ImageButton addReaderMenuAction(int symbol, String description, View.OnClickListener listener) {
        ImageButton button = Ui.iconButton(this, symbol, description);
        button.setOnClickListener(listener);
        readerMenuRow.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1f));
        return button;
    }

    private void applyReaderPreferences() {
        if (AppState.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pageLayout = AppState.pageLayout(this);
        dualPageDivider = AppState.dualPageDivider(this);

        cropPercent = AppState.cropPercent(this);
        imageView.setFitMode(AppState.fitMode(this));
        imageView.setDoubleTapScale(AppState.doubleTapScale(this) / 100f);
        imageView.setDoubleTapMode(AppState.doubleTapMode(this));
        imageView.setVerticalPaging(AppState.readingFlow(this) == AppState.FLOW_VERTICAL);
        imageView.setFilterMode(AppState.FILTER_NONE);
        updatePageButtons();
        applyBrightness(AppState.brightness(this));
    }

    private void updatePageButtons() {
        if (leftPageButton == null) return;
        boolean visible = AppState.pageButtons(this) && (!chromeVisible || AppState.enabled(this, "page_fixed", true));
        leftPageButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        rightPageButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        float alpha = AppState.pageButtonOpacity(this) / 100f;
        leftPageButton.setAlpha(alpha);
        rightPageButton.setAlpha(alpha);
        int size = dp(AppState.number(this, "page_button_height", 96));
        int position = AppState.number(this, "page_position", 0);
        int type = AppState.number(this, "page_type", 0);
        boolean horizontal = position < 2;
        int edge = position == 0 ? Gravity.BOTTOM : position == 1 ? Gravity.TOP : position == 2 ? Gravity.LEFT : Gravity.RIGHT;
        int first = edge | (horizontal ? Gravity.LEFT : Gravity.TOP);
        int second = edge | (horizontal ? Gravity.RIGHT : Gravity.BOTTOM);
        int width = horizontal ? (type == 1 ? getResources().getDisplayMetrics().widthPixels / 2 : size) : dp(48);
        int height = horizontal ? dp(48) : (type == 1 ? Math.max(size, pageCanvas.getHeight()/2) : size);
        if (type == 2) { first = Gravity.LEFT | Gravity.CENTER_VERTICAL; second = Gravity.RIGHT | Gravity.CENTER_VERTICAL; width = dp(48); height = size; }
        if (type == 3) { first = edge | Gravity.CENTER; second = edge | Gravity.CENTER; }
        FrameLayout.LayoutParams left = new FrameLayout.LayoutParams(width, height, first);
        FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(width, height, second);
        if (type == 3) { if (horizontal) { left.rightMargin = size; right.leftMargin = size; } else { left.bottomMargin = size; right.topMargin = size; } }
        leftPageButton.setLayoutParams(left); rightPageButton.setLayoutParams(right);
        leftPageButton.requestLayout();
        rightPageButton.requestLayout();
        leftPageButton.setContentDescription(pageButtonForward(false) ? I18n.t(R.string.ui_next_page) : I18n.t(R.string.ui_previous_page));
        rightPageButton.setContentDescription(pageButtonForward(true) ? I18n.t(R.string.ui_next_page) : I18n.t(R.string.ui_previous_page));
        leftPageButton.setTooltipText(leftPageButton.getContentDescription());
        rightPageButton.setTooltipText(rightPageButton.getContentDescription());
    }

    private void initializeSource() {
        if(imageView!=null)imageView.setImageDrawable(null);
        if(continuous!=null)continuous.reset(0,0);
        initialized = false;
        showLoading(true);
        showError(null);
        int token = ++loadToken;
        worker.execute(() -> {
            if (destroyed) return;
            try {
                if (pageSource != null) pageSource.close();
                pageSource = new PageSource(this, sourceUri, title, imageUris,
                        Charset.forName(ReaderOptions.ENCODINGS[Math.max(0, Math.min(ReaderOptions.ENCODINGS.length-1, AppState.archiveEncoding(this)))]), maxBitmapPixels,bookPassword,archiveVolumes);
                totalPages = pageSource.pageCount();
                int savedPage = getIntent().getIntExtra(EXTRA_START_INDEX, AppState.resumeMode(this, getIntent().getBooleanExtra("next_book", false)) == 2 ? 0 : AppState.getPosition(this, sourceUri));
                page = Math.max(0, Math.min(savedPage, totalPages - 1));
                if (usesDualPageLayout()) page -= page % 2;
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken || isFinishing()) return;
                    initialized = true;
                    readerDrawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED);
                    showPageBrowser(false, false, false);
                    continuous.setVisibility(vertical() ? View.VISIBLE : View.GONE);
                    imageView.setVisibility(vertical() ? View.GONE : View.VISIBLE);
                    if (vertical()) continuous.reset(totalPages, page);
                    pageSlider.setMax(Math.max(0, totalPages - 1));
                    updateControls();
                    loadPage(page, false);
                    if (!getIntent().hasExtra(EXTRA_START_INDEX) && page > 0 && AppState.resumeMode(this, getIntent().getBooleanExtra("next_book", false)) == 0) {
                        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_resume_page)).setMessage((page + 1) + I18n.t(R.string.ui_resume_from_this_page))
                                .setPositiveButton(I18n.t(R.string.ui_resume_page), (d, i) -> { })
                                .setNegativeButton(I18n.t(R.string.ui_first_page_2), (d, i) -> goToPage(0)));
                    }
                    if (!AppState.hasSeenReaderHint(this)) {
                        Toast.makeText(this, I18n.t(R.string.ui_tap_or_swipe_to_turn_pages_tap_the_center_for), Toast.LENGTH_LONG).show();
                        AppState.markReaderHintSeen(this);
                    }
                });
            } catch (Exception | OutOfMemoryError exception) {
                if (exception instanceof OutOfMemoryError) pageCache.evictAll();
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken || isFinishing()) return;
                    showLoading(false);
                    showError(readableError(exception));
                    requestBookAccess(exception);
                });
            }
        });
    }

    private void loadPage(int target, boolean prefetch) {
        if (!initialized || target < 0 || target >= totalPages) return;
        int token = prefetch ? loadToken : ++loadToken;
        String cacheKey = cacheKey(target);
        Bitmap cached = pageCache.get(cacheKey);
        if (!prefetch) {
            page = target;
            updateControls();
            showError(null);
        }
        if (cached != null) {
            if (!prefetch) {
                showLoading(false);
                showLoadedPage(target, cached);
            }
            return;
        }
        if (!prefetch) showLoading(true);
        worker.execute(() -> {
            if (destroyed) return;
            Bitmap bitmap = null;
            Throwable failure = null;
            try { bitmap = decodeLayout(target); if (bitmap == null) throw new IOException(I18n.t(R.string.ui_cannot_read_image)); }
            catch (Exception | OutOfMemoryError exception) {
                if (exception instanceof OutOfMemoryError) pageCache.evictAll();
                failure = exception;
            }
            Bitmap finalBitmap = bitmap;
            Throwable finalFailure = failure;
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || !cacheKey.equals(cacheKey(target))) return;
                if (finalBitmap != null) pageCache.put(cacheKey, finalBitmap);
                if (prefetch) return;
                if (token != loadToken) return;
                showLoading(false);
                if (finalFailure != null) {
                    stopAutoPage();
                    showError(readableError(finalFailure));
                    requestBookAccess(finalFailure);
                }
                else {
                    showLoadedPage(target, finalBitmap);
                }
            });
        });
    }

    private void prefetchAround(int current) {
        int next = nextIndex(current, true);
        int previous = nextIndex(current, false);
        if (next >= 0 && pageCache.get(cacheKey(next)) == null) loadPage(next, true);
        if (previous >= 0 && pageCache.get(cacheKey(previous)) == null) loadPage(previous, true);
    }

    private Bitmap processedSinglePage(int target,long milliseconds) throws IOException {
        Bitmap raw = cropMargins(applyCustomCrop(pageSource.decode(target,getResources().getDisplayMetrics().widthPixels,milliseconds)));
        int targetWidth = getResources().getDisplayMetrics().widthPixels / (usesDualPageLayout() ? 2 : 1);
        Bitmap processed = ImageProcessing.apply(this, raw, targetWidth, Math.min(maxBitmapPixels, 2_000_000));
        if (processed != raw) raw.recycle();
        return processed;
    }

    private Bitmap applyCustomCrop(Bitmap raw) {
        if (customCrop != null) {
            int x = Math.min(raw.getWidth()-1, Math.round(raw.getWidth()*customCrop.left));
            int y = Math.min(raw.getHeight()-1, Math.round(raw.getHeight()*customCrop.top));
            int width = Math.max(1, Math.min(raw.getWidth()-x, Math.round(raw.getWidth()*customCrop.width())));
            int height = Math.max(1, Math.min(raw.getHeight()-y, Math.round(raw.getHeight()*customCrop.height())));
            Bitmap crop = Bitmap.createBitmap(raw, x, y, width, height);
            if (crop != raw) raw.recycle(); raw = crop;
        }
        return raw;
    }

    private Bitmap decodeLayout(int target) throws IOException {
        return decodeLayout(target,-1);
    }
    private Bitmap decodeLayout(int target,long milliseconds) throws IOException {
        Bitmap first = processedSinglePage(target,milliseconds);
        if (!usesDualPageLayout() || target + 1 >= totalPages) return first;
        Bitmap second = null;
        try {
            second = processedSinglePage(target + 1,milliseconds);
            return combinePages(first, second, AppState.direction(this) == AppState.DIRECTION_RTL);
        } finally {
            first.recycle();
            if (second != null) second.recycle();
        }
    }

    private Bitmap decodeSinglePage(int target) throws IOException {
        return applyCustomCrop(pageSource.decode(target, getResources().getDisplayMetrics().widthPixels));
    }
    private Bitmap combinePages(Bitmap first, Bitmap second, boolean rightToLeft) {
        int sourceWidth = first.getWidth() + second.getWidth();
        int sourceHeight = Math.max(first.getHeight(), second.getHeight());
        double scale = PageSource.bitmapScale(sourceWidth, sourceHeight, maxBitmapPixels);
        int width = Math.max(1, (int) Math.floor(sourceWidth * scale));
        int height = Math.max(1, (int) Math.floor(sourceHeight * scale));
        int firstWidth = Math.max(1, (int) Math.floor(first.getWidth() * scale));
        int secondWidth = Math.max(1, width - firstWidth);
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(0xFFFFFFFF);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap left = rightToLeft ? second : first;
        Bitmap right = rightToLeft ? first : second;
        int leftWidth = rightToLeft ? secondWidth : firstWidth;
        int leftHeight = Math.max(1, (int) Math.floor(left.getHeight() * scale));
        int rightHeight = Math.max(1, (int) Math.floor(right.getHeight() * scale));
        canvas.drawBitmap(left, null, new Rect(0, (height - leftHeight) / 2, leftWidth, (height + leftHeight) / 2), paint);
        canvas.drawBitmap(right, null, new Rect(leftWidth, (height - rightHeight) / 2, width, (height + rightHeight) / 2), paint);
        if (dualPageDivider) {
            paint.setColor(0xFF424242);
            canvas.drawRect(Math.max(0, leftWidth - 1), 0, Math.min(width, leftWidth + 1), height, paint);
        }
        return result;
    }

    private Bitmap cropMargins(Bitmap source) {
        int[] bounds = cropBounds(source.getWidth(), source.getHeight(), cropPercent);
        if (bounds[0] == 0 && bounds[1] == 0) return source;
        Bitmap cropped = Bitmap.createBitmap(source, bounds[0], bounds[1], bounds[2], bounds[3]);
        source.recycle();
        return cropped;
    }

    static int[] cropBounds(int width, int height, int percent) {
        int safePercent = Math.max(0, Math.min(10, percent));
        int insetX = width * safePercent / 100;
        int insetY = height * safePercent / 100;
        return new int[]{insetX, insetY, Math.max(1, width - insetX * 2), Math.max(1, height - insetY * 2)};
    }

    private int renderGeneration;
    private void invalidatePages() { renderGeneration++; pageCache.evictAll(); }
    private String cacheKey(int target) { return renderGeneration + ":" + (usesDualPageLayout() ? AppState.PAGE_DUAL : AppState.PAGE_SINGLE) + ":" + target; }

    private void showLoadedPage(int target, Bitmap bitmap) {
        displayBitmap(bitmap);
        AppState.updateReadingProgress(this, sourceUri, target, totalPages);
        prefetchAround(target);
    }

    private void displayBitmap(Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        imageView.setFilterMode(AppState.FILTER_NONE);
        if(vertical())return;
        int target=page,token=loadToken,generation=renderGeneration;
        worker.execute(() -> {
            if(destroyed || pageSource==null)return;
            android.graphics.drawable.Drawable drawable=readerDrawable(target,bitmap);
            if(drawable instanceof AnimatedPageDrawable)runOnUiThread(() -> {
                if(!destroyed && token==loadToken && generation==renderGeneration){imageView.setImageDrawable(drawable);imageView.fitImage();}
            });
        });
    }
    private android.graphics.drawable.Drawable readerDrawable(int target,Bitmap first) {
        PageSource owner=pageSource;
        if(owner.isGif(target) || usesDualPageLayout() && target+1<totalPages && owner.isGif(target+1))
            return new AnimatedPageDrawable(first,worker,time -> destroyed || pageSource!=owner ? null : decodeLayout(target,time));
        return new android.graphics.drawable.BitmapDrawable(getResources(),first);
    }

    private void goToPage(int target) {
        if (vertical() && target >= 0 && target < totalPages) continuous.setSelection(target);
        if (usesDualPageLayout()) target -= target % 2;
        if (target < 0 || target >= totalPages || target == page && imageView.getDrawable() != null) return;
        loadPage(target, false);
    }

    private int nextIndex(int index, boolean forward) {
        int step = usesDualPageLayout() ? 2 : 1;
        int next = index + (forward ? step : -step);
        return next >= 0 && next < totalPages ? next : -1;
    }

    private void forward() { if (vertical() && continuous.canScrollVertically(1)) { continuous.move(true); return; } int next = nextIndex(page, true); if (next >= 0) goToPage(next); else nextBook(true); }
    private void back() { if (vertical() && continuous.canScrollVertically(-1)) { continuous.move(false); return; } int previous = nextIndex(page, false); if (previous >= 0) goToPage(previous); else nextBook(false); }

    private void updateControls() {
        boolean dualPage = usesDualPageLayout();
        int shownEnd = dualPage ? Math.min(totalPages, page + 2) : page + 1;
        pageText.setText(totalPages > 0 ? (dualPage ? (page + 1) + "-" + shownEnd : String.valueOf(page + 1))
                + " / " + totalPages + "  " + Math.round(shownEnd * 100f / totalPages) + "%" : I18n.t(R.string.ui_loading));
        pageSlider.setProgress(page);
        refreshQuickBookmark();
    }

    private void toggleBookmark() {
        if (!initialized) return;
        boolean next = !AppState.hasBookmark(this, sourceUri, page);
        AppState.setBookmark(this, sourceUri, page, next, title, ComicFile.kindFor(title, getContentResolver().getType(sourceUri)));
        refreshQuickBookmark();
        Toast.makeText(this, next ? I18n.t(R.string.ui_bookmark_added) : I18n.t(R.string.ui_bookmark_removed), Toast.LENGTH_SHORT).show();
    }

    private void refreshQuickBookmark() {
        if (quickBookmarkButton == null) return;
        boolean marked = initialized && AppState.hasBookmark(this, sourceUri, page);
        Ui.updateIconButton(quickBookmarkButton,
                marked ? R.drawable.ic_reader_bookmark_on : R.drawable.ic_reader_bookmark,
                I18n.t(marked ? R.string.ui_remove_this_bookmark : R.string.ui_bookmark_this_page),
                marked, marked ? Ui.BRAND : Ui.TEXT_PRIMARY);
    }

    private void showPageJump() {
        if (!initialized) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1〜" + totalPages);
        input.setText(String.valueOf(page + 1));
        Ui.styleSearch(input);
        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_go_to_page)).setView(input).setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_move), (dialog, which) -> {
            try {
                int target = Integer.parseInt(input.getText().toString()) - 1;
                if (target < 0 || target >= totalPages) throw new NumberFormatException();
                goToPage(target);
            } catch (NumberFormatException error) { Toast.makeText(this, "1〜" + totalPages + I18n.t(R.string.ui_enter_a_page_number), Toast.LENGTH_SHORT).show(); }
        }));
    }

    private void showReaderMenu() {
        Ui.Actions menu = new Ui.Actions();
        if (initialized) {
            menu.add(I18n.t(R.string.ui_find_page), this::showPageNavigation);
            menu.add(I18n.t(R.string.ui_bookmark_actions), this::showBookmarkActions);
        }
        menu.add(I18n.t(R.string.ui_display_reading), this::showDisplaySettings);
        if (initialized) menu.add(I18n.t(R.string.ui_book_actions), this::showBookActions);
        menu.add(I18n.t(R.string.ui_control_settings), this::showControlSettings);
        menu.show(this, I18n.t(R.string.ui_reader_menu));
    }

    private void showPageNavigation() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_page_thumbnails), this::showPageList);
        menu.add(I18n.t(R.string.ui_chapters), () -> showPageBrowser(true));
        menu.add(I18n.t(R.string.ui_go_to_page), this::showPageJump);
        menu.show(this, I18n.t(R.string.ui_find_page));
    }

    private void showPageList() {
        if (!initialized) return;
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_page_thumbnails), () -> showPageBrowser(false));
        menu.add(I18n.t(R.string.ui_page_strip), () -> showPageBrowser(false, true));
        menu.show(this, I18n.t(R.string.ui_page_list_style));
    }

    private void showBookmarkActions() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(AppState.hasBookmark(this, sourceUri, page) ? R.string.ui_remove_this_bookmark : R.string.ui_bookmark_this_page), this::toggleBookmark);
        menu.add(I18n.t(R.string.ui_bookmarks_2), this::showBookmarks);
        menu.add(I18n.t(R.string.ui_edit_bookmark_note), this::showBookmarkMemo);
        menu.show(this, I18n.t(R.string.ui_bookmark_actions));
    }

    private void showDisplaySettings() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_reading_mode), this::showReadingSettings);
        menu.add(I18n.t(R.string.ui_page_layout), this::showPageLayoutDialog);
        menu.add(I18n.t(R.string.ui_fit_screen), this::showFitDialog);
        menu.add(I18n.t(R.string.ui_screen_rotation), this::showOrientationDialog);
        menu.add(I18n.t(R.string.ui_brightness), this::showBrightnessDialog);
        menu.add(I18n.t(R.string.ui_image_filters), this::showFilterDialog);
        menu.add(I18n.t(R.string.ui_crop_settings), this::showCropSettings);
        menu.show(this, I18n.t(R.string.ui_display_reading));
    }

    private void showReadingSettings() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_reading_direction), this::showDirectionDialog);
        menu.add(I18n.t(R.string.ui_scroll_mode), this::showReadingFlowDialog);
        if (vertical()) menu.add(I18n.t(R.string.ui_page_spacing), () -> {
            PreferenceRows rows = new PreferenceRows(this);
            rows.slider(I18n.t(R.string.ui_page_spacing), "page_gap", 0, 0, 100, " dp", this::refreshReader);
            rows.show(I18n.t(R.string.ui_page_spacing));
        });
        menu.show(this, I18n.t(R.string.ui_reading_mode));
    }

    private void showCropSettings() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_crop_all_books), this::showCropDialog);
        if (initialized) menu.add(I18n.t(R.string.ui_crop_this_book), this::showCropEditor);
        menu.show(this, I18n.t(R.string.ui_crop_settings));
    }

    private void showBookActions() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_save_screen_as_image), this::capturePage);
        menu.add(I18n.t(R.string.ui_set_current_page_as_cover), this::saveCurrentPageCover);
        if (AppState.hasCover(this, sourceUri)) menu.add(I18n.t(R.string.ui_restore_default_cover), () -> {
            AppState.removeCover(this, sourceUri);
            Toast.makeText(this, I18n.t(R.string.ui_cover_restored), Toast.LENGTH_SHORT).show();
        });
        ArrayList<Uri> books = getIntent().getParcelableArrayListExtra(EXTRA_BOOK_URIS);
        ArrayList<String> titles = getIntent().getStringArrayListExtra(EXTRA_BOOK_TITLES);
        if (books != null && titles != null && books.size() == titles.size()
                && books.indexOf(sourceUri) >= 0 && books.indexOf(sourceUri) + 1 < books.size())
            menu.add(I18n.t(R.string.ui_open_the_next_file), () -> nextBook(true));
        menu.add(I18n.t(R.string.ui_reset_reading_position), () -> Ui.show(new AlertDialog.Builder(this)
                .setTitle(I18n.t(R.string.ui_reset_reading_position)).setMessage(I18n.t(R.string.ui_reset_position_confirm))
                .setNegativeButton(I18n.t(R.string.ui_cancel), null)
                .setPositiveButton(I18n.t(R.string.ui_reset_reading_position), (dialog, which) -> {
                    stopAutoPage();
                    AppState.clearPosition(this, sourceUri); goToPage(0);
                    Toast.makeText(this, I18n.t(R.string.ui_reading_position_reset), Toast.LENGTH_SHORT).show();
                })));
        menu.show(this, I18n.t(R.string.ui_book_actions));
    }

    private void showControlSettings() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_page_button_area), () -> ReaderOptions.pageButtons(this, this::refreshReader));
        menu.add(I18n.t(R.string.ui_hardware_key), () -> ReaderOptions.hardware(this, () -> { }));
        menu.add(I18n.t(R.string.ui_double_tap_zoom), this::showZoomDialog);
        menu.add(I18n.t(R.string.ui_settings), () -> startActivity(new Intent(this, SettingsActivity.class)));
        menu.show(this, I18n.t(R.string.ui_control_settings));
    }

    private void saveCurrentPageCover() {
        Bitmap current = pageCache.get(cacheKey(page));
        if (!initialized) {
            Toast.makeText(this, I18n.t(R.string.ui_wait_for_the_page_to_load), Toast.LENGTH_SHORT).show();
            return;
        }
        int selectedPage = page;
        worker.execute(() -> {
            Bitmap cover = null;
            File output = AppState.coverFile(this, sourceUri);
            File directory = output.getParentFile();
            File temporary = new File(directory, output.getName() + ".tmp");
            try {
                Bitmap decoded = current == null ? decodeLayout(selectedPage) : current;
                if (decoded == null) throw new IOException("Page unavailable");
                int[] size = coverSize(decoded.getWidth(), decoded.getHeight());
                cover = Bitmap.createScaledBitmap(decoded, size[0], size[1], true);
                if (cover == decoded) cover = decoded.copy(Bitmap.Config.ARGB_8888, false);
                if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException(I18n.t(R.string.ui_cannot_create_cover_directory));
                try (FileOutputStream stream = new FileOutputStream(temporary)) {
                    if (!cover.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new IOException(I18n.t(R.string.ui_cannot_write_cover));
                }
                if (output.isFile() && !output.delete()) throw new IOException(I18n.t(R.string.ui_cannot_replace_the_existing_cover));
                if (!temporary.renameTo(output)) throw new IOException(I18n.t(R.string.ui_cannot_save_cover));
                runOnUiThread(() -> Toast.makeText(this, I18n.t(R.string.ui_current_page_set_as_cover), Toast.LENGTH_SHORT).show());
            } catch (Exception | OutOfMemoryError error) {
                temporary.delete();
                runOnUiThread(() -> Toast.makeText(this, I18n.t(R.string.ui_could_not_save_cover), Toast.LENGTH_SHORT).show());
            } finally {
                if (cover != null) cover.recycle();
            }
        });
    }

    static int[] coverSize(int width, int height) {
        float scale = Math.min(1f, Math.min(320f / Math.max(1, width), 480f / Math.max(1, height)));
        return new int[]{Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    private void showCropDialog() {
        String[] labels = {I18n.t(R.string.ui_none), "2%", "5%", "10%"};
        int[] values = {0, 2, 5, 10};
        int selected = 0;
        for (int index = 0; index < values.length; index++) if (values[index] == cropPercent) selected = index;
        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_crop_page_margins))
                .setSingleChoiceItems(labels, selected, (dialog, chosen) -> {
                    cropPercent = values[chosen];
                    AppState.setCropPercent(this, cropPercent);
                    dialog.dismiss();
                    refreshReader();
                }));
    }

    private void showPageLayoutDialog() { ReaderOptions.pageLayout(this, this::refreshReader); }

    private void showReadingFlowDialog() { ReaderOptions.readingFlow(this, this::refreshReader); }

    private void showFilterDialog() { ReaderOptions.filters(this, this::refreshReader); }

    private void showZoomDialog() { ReaderOptions.zoom(this, this::refreshReader); }

    private void showBookmarkMemo() {
        if (!initialized) return;
        if (!AppState.hasBookmark(this, sourceUri, page))
            AppState.setBookmark(this, sourceUri, page, true, title, ComicFile.kindFor(title, getContentResolver().getType(sourceUri)));
        EditText input = new EditText(this);
        input.setHint(I18n.t(R.string.ui_note_optional));
        input.setText(AppState.bookmarkMemo(this, sourceUri, page));
        Ui.styleSearch(input);
        Ui.show(new AlertDialog.Builder(this).setTitle((page + 1) + I18n.t(R.string.ui_bookmark_note)).setView(input)
                .setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_save), (dialog, which) -> {
                    AppState.setBookmarkMemo(this, sourceUri, page, input.getText().toString());
                    refreshQuickBookmark();
                }));
    }

    private void showFitDialog() { ReaderOptions.fit(this, () -> imageView.setFitMode(AppState.fitMode(this))); }

    private void showBrightnessDialog() { ReaderOptions.brightness(this, this::applyBrightness); }

    private void showOrientationDialog() { ReaderOptions.orientation(this); }

    private void showDirectionDialog() { ReaderOptions.direction(this, this::refreshReader); }

    private void showBookmarks() {
        Set<Integer> pages = AppState.bookmarks(this, sourceUri);
        if (pages.isEmpty()) { Toast.makeText(this, I18n.t(R.string.ui_no_bookmarks_yet), Toast.LENGTH_SHORT).show(); return; }
        List<Integer> ordered = new ArrayList<>(pages);
        Collections.sort(ordered);
        String[] labels = new String[ordered.size()];
        for (int index = 0; index < labels.length; index++) {
            int bookmarkedPage = ordered.get(index);
            String memo = AppState.bookmarkMemo(this, sourceUri, bookmarkedPage);
            labels[index] = (bookmarkedPage + 1) + I18n.t(R.string.ui_pages_2) + (memo.isEmpty() ? "" : "  •  " + memo);
        }
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_bookmarks_2)).setItems(labels, (ignored, selected) -> goToPage(ordered.get(selected)))
                .setNegativeButton(I18n.t(R.string.ui_delete_all), (ignored, which) -> { AppState.clearBookmarks(this, sourceUri); refreshQuickBookmark(); }));
        Ui.styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), Ui.ButtonStyle.DANGER);
    }

    private void showAutoPageDialog() {
        String[] labels = {I18n.t(R.string.ui_every_5_seconds), I18n.t(R.string.ui_every_10_seconds), I18n.t(R.string.ui_every_15_seconds)};
        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_auto_page_turn)).setItems(labels, (dialog, selected) -> {
            autoDelayMs = new int[]{5000, 10000, 15000}[selected];
            autoHandler.removeCallbacks(autoPage);
            autoHandler.postDelayed(autoPage, autoDelayMs);
            refreshReaderActions();
        }));
    }

    private void stopAutoPage() {
        autoDelayMs = 0;
        autoHandler.removeCallbacks(autoPage);
        refreshReaderActions();
    }

    private void capturePage() {
        View visiblePage = vertical() ? continuous : imageView;
        if (!initialized || visiblePage.getWidth() == 0 || visiblePage.getHeight() == 0 || (!vertical() && imageView.getDrawable() == null)) {
            Toast.makeText(this, I18n.t(R.string.ui_wait_for_the_page_to_load_before_saving), Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap capture = Bitmap.createBitmap(visiblePage.getWidth(), visiblePage.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(capture);
        canvas.drawColor(Ui.BACKGROUND);
        visiblePage.draw(canvas);
        worker.execute(() -> {
            Uri output = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "ComicExplorer_" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Comic Explorer");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                output = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (output == null) throw new IOException(I18n.t(R.string.ui_cannot_create_destination));
                try (OutputStream stream = getContentResolver().openOutputStream(output)) {
                    if (stream == null || !capture.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException(I18n.t(R.string.ui_cannot_write_image));
                }
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(output, publish, null, null);
                runOnUiThread(() -> Toast.makeText(this, I18n.t(R.string.ui_saved_to_pictures_comic_explorer), Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                if (output != null) getContentResolver().delete(output, null, null);
                runOnUiThread(() -> Toast.makeText(this, I18n.t(R.string.ui_could_not_save_image), Toast.LENGTH_SHORT).show());
            } finally {
                capture.recycle();
            }
        });
    }

    private void applyBrightness(int value) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = value < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(.08f, value / 100f);
        getWindow().setAttributes(attributes);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        chromeTop.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        chromeBottom.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        updatePageButtons();
    }

    private void toggleFullscreen() { fullScreen = !fullScreen; applyFullscreen(); refreshReaderActions(); }

    private void applyFullscreen() {
        Window window = getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        boolean cutout = AppState.enabled(this, getResources().getConfiguration().orientation == 2 ? "cutout_land" : "cutout_port", false);
        attributes.layoutInDisplayCutoutMode = cutout ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        window.setAttributes(attributes);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (fullScreen) {
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            int layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            int fullscreenFlags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            int iconFlags = Ui.light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
            window.getDecorView().setSystemUiVisibility(layoutFlags | iconFlags | (fullScreen ? fullscreenFlags : 0));
        }
        window.getDecorView().requestApplyInsets();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullScreen) applyFullscreen();
    }

    private void showLoading(boolean show) { loading.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void showError(String message) { errorPanel.setVisibility(message == null ? View.GONE : View.VISIBLE); if (message != null) errorText.setText(message); }
    private String readableError(Throwable error) {
        if (error instanceof SecurityException) return I18n.t(R.string.ui_file_access_was_revoked_select_the_folder_again_in_the);
        if (error instanceof OutOfMemoryError) return I18n.t(R.string.ui_the_page_does_not_fit_in_memory_close_other_apps);
        return error.getMessage() == null ? I18n.t(R.string.ui_cannot_open_file) : error.getMessage();
    }
    @Override public void onTap(float normalizedX) {
        if (!initialized) return;
        toggleChrome();
    }

    @Override public void onSwipe(int direction) {
        if (!initialized) return;
        if (Math.abs(direction) == 2) { if (direction < 0) forward(); else back(); return; }
        if (direction < 0) { if (AppState.direction(this) == AppState.DIRECTION_RTL) back(); else forward(); }
        else { if (AppState.direction(this) == AppState.DIRECTION_RTL) forward(); else back(); }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (AppState.prefs(this).contains("setting.key." + keyCode)) {
            switch (AppState.number(this, "key." + keyCode, 0)) {
                case 1: back(); break; case 2: forward(); break; case 3: toggleChrome(); break;
                case 4: toggleBookmark(); break; case 5: toggleFullscreen(); break; default: break;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_PAGE_UP) { onSwipe(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) { onSwipe(-1); return true; }
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER) { toggleChrome(); return true; }
        if (AppState.volumeNavigation(this) && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (AppState.reverseVolumeNavigation(this)) back(); else forward();
            return true;
        }
        if (AppState.volumeNavigation(this) && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (AppState.reverseVolumeNavigation(this)) forward(); else back();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onResume() {
        super.onResume();
        if (imageView == null) return;
        int savedLayout = AppState.pageLayout(this);
        boolean savedDivider = AppState.dualPageDivider(this);
        int savedCropPercent = AppState.cropPercent(this);
        boolean reloadLayout = initialized && (savedLayout != pageLayout || savedDivider != dualPageDivider || savedCropPercent != cropPercent);
        applyReaderPreferences();
        if (reloadLayout) refreshReader();
        if (autoDelayMs > 0) autoHandler.postDelayed(autoPage, autoDelayMs);
    }

    @Override protected void onPause() {
        autoHandler.removeCallbacks(autoPage);
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (initialized) { refreshReader(); showPageBrowser(drawerChapters, false, readerDrawer.isDrawerOpen(drawerPanel)); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("current_page", page);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (disposeDrawer != null) disposeDrawer.run();
        if (continuous != null) continuous.stop();
        loadToken++;
        stopAutoPage();
        if (imageView != null) imageView.setImageDrawable(null);
        pageCache.evictAll();
        worker.execute(() -> { if (pageSource != null) try { pageSource.close(); } catch (IOException ignored) { } });
        worker.shutdown();
        super.onDestroy();
    }

    static int adjacentPage(int index, boolean forward, int count) {
        int next = index + (forward ? 1 : -1);
        return next >= 0 && next < count ? next : -1;
    }

    static boolean usesDualPageLayout(int layout, int orientation) {
        return layout == AppState.PAGE_DUAL || layout == AppState.PAGE_AUTO && orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean usesDualPageLayout() {
        return !vertical() && usesDualPageLayout(pageLayout, getResources().getConfiguration().orientation);
    }

    private boolean vertical() { return AppState.readingFlow(this) == AppState.FLOW_VERTICAL; }
    private void refreshReader() {
        if (!initialized) return;
        cropPercent = AppState.cropPercent(this);
        pageLayout = AppState.pageLayout(this); dualPageDivider = AppState.dualPageDivider(this);
        imageView.setDoubleTapScale(AppState.doubleTapScale(this)/100f); imageView.setDoubleTapMode(AppState.doubleTapMode(this));
        imageView.setVisibility(vertical() ? View.GONE : View.VISIBLE); continuous.setVisibility(vertical() ? View.VISIBLE : View.GONE);
        continuous.setDividerHeight(dp(AppState.number(this, "page_gap", 0)) + (AppState.enabled(this, "scroll_divider", false) ? 1 : 0));
        if (vertical()) continuous.reset(totalPages, page);
        invalidatePages(); updatePageButtons(); loadPage(usesDualPageLayout() ? page - page % 2 : page, false); applyFullscreen();
    }
    private boolean pageButtonForward(boolean right) {
        boolean forward = AppState.enabled(this, "page_both_next", false) || right != (AppState.direction(this) == AppState.DIRECTION_RTL);
        if (AppState.enabled(this, "page_reverse", false)) forward = !forward;
        return forward;
    }
    private void pageButton(boolean right) {
        if (pageButtonForward(right)) forward(); else back();
    }
    private void nextBook(boolean forward) {
        ArrayList<Uri> books = getIntent().getParcelableArrayListExtra(EXTRA_BOOK_URIS);
        ArrayList<String> titles = getIntent().getStringArrayListExtra(EXTRA_BOOK_TITLES);
        int next = books == null ? -1 : books.indexOf(sourceUri) + (forward ? 1 : -1);
        if (books == null || titles == null || next < 0 || next >= books.size() || titles.size() != books.size()) {
            Toast.makeText(this, forward ? I18n.t(R.string.ui_last_page) : I18n.t(R.string.ui_first_page), Toast.LENGTH_SHORT).show(); return;
        }
        Intent intent = new Intent(this, ViewerActivity.class).setData(books.get(next));
        intent.putExtra(EXTRA_TITLE, titles.get(next)); intent.putParcelableArrayListExtra(EXTRA_BOOK_URIS, books);
        intent.putStringArrayListExtra(EXTRA_BOOK_TITLES, titles); intent.putExtra("next_book", true);
        AppState.addRecent(this, books.get(next), titles.get(next), ComicFile.kindFor(titles.get(next), null));
        startActivity(intent); finish();
    }
    private void showPageBrowser(boolean chapters) {
        showPageBrowser(chapters, false);
    }
    private void showPageBrowser(boolean chapters, boolean strip) { showPageBrowser(chapters, strip, true); }
    private void showPageBrowser(boolean chapters, boolean strip, boolean open) {
        if (!initialized) return;
        ArrayList<Integer> indices = new ArrayList<>(); ArrayList<String> labels = new ArrayList<>();
        String previous = null;
        if(chapters && !pageSource.chapters.isEmpty()) {
            for(PageSource.Chapter chapter:pageSource.chapters){indices.add(chapter.page);labels.add(chapter.title);}
        } else for (int i = 0; i < totalPages; i++) {
            String name = pageSource.pageName(i);
            String chapter = name.contains("/") ? name.substring(0, name.lastIndexOf('/')) : I18n.t(R.string.ui_first_chapter);
            if (!chapters || !chapter.equals(previous)) { indices.add(i); labels.add(chapters ? chapter : (i+1) + " · " + name); }
            previous = chapter;
        }
        boolean wide = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        int thumbnailWidth = wide ? 128 : 96, thumbnailHeight = wide ? 61 : 47;
        android.widget.ListView grid = new android.widget.ListView(this);
        grid.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        grid.setDividerHeight(0); grid.setVerticalScrollBarEnabled(false);
        final boolean[] closed = {false};
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return indices.size(); }
            @Override public Object getItem(int i) { return indices.get(i); }
            @Override public long getItemId(int i) { return indices.get(i); }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout cell = new LinearLayout(ViewerActivity.this); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(dp(4), dp(4), dp(4), dp(4));
                if (strip) cell.setLayoutParams(new android.widget.Gallery.LayoutParams(dp(thumbnailWidth), -1));
                android.widget.ImageView thumbnail = new android.widget.ImageView(ViewerActivity.this); thumbnail.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                if (!chapters) cell.addView(thumbnail, new LinearLayout.LayoutParams(-1, dp(strip ? thumbnailHeight : 130)));
                TextView label = text(chapters ? labels.get(position) : Integer.toString(indices.get(position) + 1), 12, Ui.TEXT_PRIMARY);
                label.setGravity(chapters ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
                label.setMinHeight(dp(chapters ? 48 : 24));
                cell.addView(label); cell.setContentDescription(labels.get(position));
                boolean current = indices.get(position) <= page && (position + 1 == indices.size() || indices.get(position + 1) > page);
                cell.setBackgroundColor(current ? Ui.BRAND_CONTAINER : Ui.SURFACE);
                cell.setSelected(current);
                if (chapters) return cell;
                worker.execute(() -> {
                    if (destroyed || closed[0]) return;
                    try {
                        Bitmap page = decodeSinglePage(indices.get(position)); int[] size = coverSize(page.getWidth(), page.getHeight());
                        Bitmap small = Bitmap.createScaledBitmap(page, size[0], size[1], true); if (small != page) page.recycle();
                        runOnUiThread(() -> { if (!destroyed && !closed[0]) thumbnail.setImageBitmap(small); else small.recycle(); });
                    } catch (Exception | OutOfMemoryError ignored) { }
                });
                return cell;
            }
        });
        int selected = 0; for (int i = 0; i < indices.size(); i++) if (indices.get(i) <= page) selected = i;
        if (!strip) {
            if (disposeDrawer != null) disposeDrawer.run();
            disposeDrawer = () -> closed[0] = true;
            drawerChapters = chapters;
            drawerPanel.removeAllViews(); drawerPanel.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            drawerPanel.setBackgroundColor(Ui.SURFACE);
            androidx.drawerlayout.widget.DrawerLayout.LayoutParams params = (androidx.drawerlayout.widget.DrawerLayout.LayoutParams)drawerPanel.getLayoutParams();
            params.width = Math.min(dp(chapters ? (wide ? 450 : 280) : thumbnailWidth), Math.round(getResources().getDisplayMetrics().widthPixels * .88f));
            drawerPanel.setLayoutParams(params);
            readerDrawer.setDrawerTitle(Gravity.LEFT, I18n.t(chapters ? R.string.ui_chapters : R.string.ui_page_thumbnails));
            grid.setItemChecked(selected, true); grid.setSelection(selected);
            grid.setOnItemClickListener((parent, view, position, id) -> { goToPage(indices.get(position)); readerDrawer.closeDrawer(drawerPanel); });
            if (open) readerDrawer.openDrawer(drawerPanel);
            return;
        }
        LinearLayout drawer = new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL); drawer.setBackgroundColor(Ui.SURFACE);
        android.widget.Gallery gallery = new android.widget.Gallery(this); gallery.setSpacing(dp(8)); gallery.setAdapter((android.widget.BaseAdapter)grid.getAdapter());
        drawer.addView(gallery, new LinearLayout.LayoutParams(-1, 0, 1));
        android.app.Dialog dialog = new android.app.Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Button close = Ui.button(this, I18n.t(R.string.ui_close), Ui.ButtonStyle.SURFACE_GHOST); close.setOnClickListener(v -> dialog.dismiss()); drawer.addView(close);
        dialog.setContentView(drawer); dialog.show();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.SURFACE));
        dialog.getWindow().setGravity(Gravity.BOTTOM); dialog.getWindow().setLayout(-1, dp(thumbnailHeight + 88));
        gallery.setSelection(selected); dialog.setOnDismissListener(d -> closed[0] = true);
        gallery.setOnItemClickListener((parent, view, position, id) -> { goToPage(indices.get(position)); ((android.widget.BaseAdapter)grid.getAdapter()).notifyDataSetChanged(); });
    }

    @Override public void onBackPressed() {
        if (readerDrawer != null && readerDrawer.isDrawerVisible(drawerPanel)) readerDrawer.closeDrawer(drawerPanel);
        else super.onBackPressed();
    }

    private void showCropEditor() {
        if (!initialized) return;
        final int selectedPage = page;
        worker.execute(() -> {
            File preview = null;
            try {
                Bitmap bitmap = pageSource.decode(selectedPage, 1080);
                try {
                    preview = File.createTempFile("crop-", ".png", getCacheDir());
                    try (FileOutputStream output = new FileOutputStream(preview)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Preview write failed");
                    }
                } finally { bitmap.recycle(); }
                File ready = preview;
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) { ready.delete(); return; }
                    Intent editor = new Intent(this, CropActivity.class).putExtra("preview", ready.getName());
                    if (customCrop != null) editor.putExtra("crop", new float[]{customCrop.left, customCrop.top, customCrop.right, customCrop.bottom});
                    startActivityForResult(editor, 82);
                });
            } catch (Exception | OutOfMemoryError e) {
                if (preview != null) preview.delete();
                runOnUiThread(() -> { if (!destroyed) Toast.makeText(this, I18n.t(R.string.ui_cannot_load_page), Toast.LENGTH_SHORT).show(); });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==85 && result==RESULT_OK && data!=null) {
            ArrayList<Uri> selected=new ArrayList<>();
            if(data.getClipData()!=null)for(int i=0;i<Math.min(64,data.getClipData().getItemCount());i++)selected.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null)selected.add(data.getData());
            showLoading(true);
            worker.execute(() -> {
                try {
                    for(Uri uri:selected) {
                        String name=uri.getLastPathSegment();
                        try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);}
                        if(name!=null && !name.contains("/") && !name.contains("\\"))archiveVolumes.put(name,BookCache.book(this,uri));
                    }
                    runOnUiThread(() -> {if(!destroyed)initializeSource();});
                }catch(Exception error){runOnUiThread(() -> {if(!destroyed){showLoading(false);showError(readableError(error));}});}
            });return;
        }
        if (request == 82 && result == RESULT_OK && data != null) {
            float[] crop = data.getFloatArrayExtra("crop");
            if (crop != null && crop.length == 4) {
                customCrop = data.getBooleanExtra("reset", false) ? null : new android.graphics.RectF(crop[0], crop[1], crop[2], crop[3]);
                AppState.setCrop(this, sourceUri, customCrop); refreshReader();
            }
        }
    }
    private void requestBookAccess(Throwable failure) {
        if(requestingBookAccess || destroyed)return;
        if(failure instanceof ArchivePages.PasswordRequired) {
            requestingBookAccess=true;
            EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setHint(I18n.t(R.string.ui_book_password));input.setContentDescription(I18n.t(R.string.ui_book_password));
            AlertDialog dialog=Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_book_password)).setView(input)
                .setNegativeButton(I18n.t(R.string.ui_cancel),null).setPositiveButton(I18n.t(R.string.ui_open),(d,w) -> {bookPassword=input.getText().toString();initializeSource();}));
            dialog.setOnDismissListener(d -> requestingBookAccess=false);
        } else if(failure instanceof ArchivePages.MissingVolume) {
            requestingBookAccess=true;
            AlertDialog dialog=Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_archive_volumes)).setMessage(failure.getMessage())
                .setNegativeButton(I18n.t(R.string.ui_cancel),null).setPositiveButton(I18n.t(R.string.ui_open),(d,w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),85)));
            dialog.setOnDismissListener(d -> requestingBookAccess=false);
        }
    }

    private Button button(String label, String description) {
        Button button = Ui.button(this, label, Ui.ButtonStyle.RAISED_SECONDARY);
        button.setContentDescription(description);
        return button;
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(this, value, size, color);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

}
