package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
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

import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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

import java.io.IOException;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Responsive page reader. Work that touches files or decodes images is off the UI thread. */
public final class ViewerActivity extends BaseActivity implements ZoomImageView.InteractionListener {
    public static final String EXTRA_IMAGE_URIS = "image_uris";
    public static final String EXTRA_START_INDEX = "start_index";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BOOK_URIS = "book_uris";
    public static final String EXTRA_BOOK_TITLES = "book_titles";

    private static final int PREFETCH_PAGES = 2;
    private final LinkedBlockingDeque<Runnable> workerQueue = new LinkedBlockingDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,workerQueue);
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
    private PageSequence sequence;
    private volatile int loadToken;
    private volatile int sourceToken;
    private int autoDelayMs;
    private int maxBitmapPixels;
    private int pageLayout;

    private int cropPercent;
    private boolean initialized;
    private boolean chromeVisible;
    private boolean prefetchForward = true;

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
    private TextView leftPageButton;
    private TextView rightPageButton;
    private final TextView[] extraPageButtons = new TextView[2];
    private FrameLayout pageCanvas;
    private ContinuousReader continuous;
    private String bookPassword;
    private final java.util.Map<String,File> archiveVolumes=new java.util.HashMap<>();
    private boolean requestingBookAccess;

    private AlertDialog pageListDialog;
    private android.graphics.RectF customCrop;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.containsKey("current_page")) { getIntent().putExtra(EXTRA_START_INDEX, state.getInt("current_page")); getIntent().putExtra("start_half",state.getInt("current_half")); }
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
            page = index; updateControls(); saveReadingPosition(page);
        }, forward -> { if (forward) forward(); else back(); }, this);
        continuous.setVisibility(View.GONE);
        canvas.addView(continuous, new FrameLayout.LayoutParams(-1, -1));
        leftPageButton = PageButtonDialog.button(this);
        rightPageButton = PageButtonDialog.button(this);
        leftPageButton.setOnClickListener(view -> pageButton(false));
        rightPageButton.setOnClickListener(view -> pageButton(true));
        FrameLayout.LayoutParams leftPageParams = new FrameLayout.LayoutParams(dp(48), dp(96), Gravity.START | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rightPageParams = new FrameLayout.LayoutParams(dp(48), dp(96), Gravity.END | Gravity.CENTER_VERTICAL);
        canvas.addView(leftPageButton, leftPageParams);
        canvas.addView(rightPageButton, rightPageParams);
        for (int i=0;i<extraPageButtons.length;i++) {
            boolean second=i==1;
            extraPageButtons[i]=PageButtonDialog.button(this);
            extraPageButtons[i].setOnClickListener(view -> pageButton(second));
            extraPageButtons[i].setVisibility(View.GONE);
            canvas.addView(extraPageButtons[i]);
        }
        canvas.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if(r-l!=or-ol || b-t!=ob-ot) updatePageButtons();
        });
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
        pageText.setMaxWidth(getResources().getDisplayMetrics().widthPixels / 2);
        pageText.setMinHeight(dp(48));
        sliderRow.addView(pageText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        bottom.addView(sliderRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chromeBottom = bottom;
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(topChrome, topParams);
        root.addView(bottom, bottomParams);
        chromeTop.setVisibility(View.GONE);
        chromeBottom.setVisibility(View.GONE);
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
    }

    private View buildReaderMenu() {
        readerMenuRow = new LinearLayout(this);
        readerMenuRow.setGravity(Gravity.CENTER_VERTICAL);
        readerMenuRow.setBackgroundColor(Ui.SURFACE_RAISED);
        addReaderMenuAction(R.drawable.ic_reader_pages, I18n.t(R.string.ui_page_thumbnails), view -> showPageList());
        addReaderMenuAction(R.drawable.ic_reader_flow, I18n.t(R.string.ui_reading_direction), view -> showDirectionDialog());
        addReaderMenuAction(R.drawable.ic_reader_layout, I18n.t(R.string.ui_page_layout), view -> showPageLayoutDialog());
        addReaderMenuAction(R.drawable.ic_reader_filters, I18n.t(R.string.ui_image_filters), view -> showFilterDialog());
        return readerMenuRow;
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
        imageView.setVerticalPaging(AppState.verticalPageSwipe(this));
        imageView.setFilterMode(AppState.FILTER_NONE);
        updatePageButtons();
        applyBrightness(AppState.brightness(this));
        applyDisplayCutout();
    }

    private void updatePageButtons() {
        if (leftPageButton == null) return;
        boolean visible = AppState.pageButtons(this) && !chromeVisible;
        float alpha = AppState.pageButtonOpacity(this) / 100f;
        int type = AppState.number(this, "page_type", 0);
        FrameLayout.LayoutParams[] layout = PageButtonDialog.layouts(type,
                AppState.enabled(this,"page_bottom",AppState.number(this,"page_position",0)!=1),
                AppState.enabled(this,"page_left",AppState.number(this,"page_position",0)!=3),
                type<2 && AppState.enabled(this,type==0 ? "page_horizontal_both" : "page_vertical_both",false),
                PageButtonDialog.sizePercent(this), pageCanvas.getWidth(), pageCanvas.getHeight());
        TextView[] buttons={leftPageButton,rightPageButton,extraPageButtons[0],extraPageButtons[1]};
        for (int i=0;i<buttons.length;i++) {
            TextView button=buttons[i];
            button.setVisibility(visible && i<layout.length ? View.VISIBLE : View.GONE);
            if (i>=layout.length) continue;
            boolean next=pageButtonForward(i%2==1);
            button.setAlpha(alpha);
            button.setText(next ? "+" : "-");
            button.setLayoutParams(layout[i]);
            button.setContentDescription(I18n.t(next ? R.string.ui_next_page : R.string.ui_previous_page));
            button.setTooltipText(button.getContentDescription());
        }
    }

    private void initializeSource() {
        int saved = getIntent().getIntExtra(EXTRA_START_INDEX, AppState.resumeMode(this, getIntent().getBooleanExtra("next_book", false)) == 2 ? 0 : AppState.getPosition(this, sourceUri));
        String remembered = AppState.prefs(this).getString("position_half."+AppState.key(sourceUri), "");
        int half = getIntent().hasExtra(EXTRA_START_INDEX) ? getIntent().getIntExtra("start_half",0)
                : AppState.resumeMode(this,getIntent().getBooleanExtra("next_book",false)) != 2 && remembered.equals(saved+":1") ? 1 : 0;
        initializeSource(saved,half);
    }

    private void initializeSource(int savedPage, int savedHalf) {
        if(imageView!=null)imageView.setImageDrawable(null);
        if(continuous!=null)continuous.reset(0,0);
        initialized = false;
        showLoading(true);
        showError(null);
        int token = ++loadToken;
        int openToken = ++sourceToken;
        boolean split = pageLayout == AppState.PAGE_FORCE_SINGLE;
        runWorker(true,() -> {
            if (destroyed) return;
            try {
                if (pageSource != null) pageSource.close();
                pageSource = new PageSource(this, sourceUri, title, imageUris,
                        Charset.forName(ReaderOptions.ENCODINGS[Math.max(0, Math.min(ReaderOptions.ENCODINGS.length-1, AppState.archiveEncoding(this)))]), maxBitmapPixels,bookPassword,archiveVolumes);
                PageSequence openedSequence = new PageSequence(pageSource,split);
                int openedPage=openedSequence.display(pageSource,savedPage,savedHalf);
                PageSource openedSource=pageSource;
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken || isFinishing()) return;
                    sequence = openedSequence;
                    totalPages = sequence.count();
                    page = openedPage;
                    if (usesDualPageLayout()) page -= page % 2;
                    initialized = true;
                    continuous.setVisibility(vertical() ? View.VISIBLE : View.GONE);
                    imageView.setVisibility(vertical() ? View.GONE : View.VISIBLE);
                    if (vertical()) continuous.reset(totalPages, page);
                    updatePageCount(totalPages);
                    loadPage(page, false);
                    if (!openedSequence.complete()) scanRemainingPages(openedSequence,openedSource,openToken);
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
        runWorker(!prefetch,() -> {
            if (destroyed || prefetch && token != loadToken) return;
            Bitmap bitmap = null;
            Throwable failure = null;
            try { bitmap = decodeLayout(target); if (bitmap == null) throw new IOException(I18n.t(R.string.ui_cannot_read_image)); }
            catch (Exception | OutOfMemoryError exception) {
                if (exception instanceof OutOfMemoryError) pageCache.evictAll();
                failure = exception;
            }
            Bitmap finalBitmap = bitmap;
            Throwable finalFailure = failure;
            int decodedPageCount = sequence.count();
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || !cacheKey.equals(cacheKey(target))) return;
                if (finalBitmap != null) pageCache.put(cacheKey, finalBitmap);
                updatePageCount(decodedPageCount);
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
        for(int next:prefetchTargets(current,prefetchForward,totalPages,usesDualPageLayout() ? 2 : 1))
            if(pageCache.get(cacheKey(next))==null)loadPage(next,true);
    }

    private void scanRemainingPages(PageSequence pending,PageSource source,int token) {
        runWorker(false,() -> {
            if(destroyed || sourceToken!=token || pageSource!=source || pending.complete())return;
            Throwable failure=null;int count=pending.count();
            try { pending.scanNext(source);count=pending.count(); }
            catch(Exception | OutOfMemoryError error) { failure=error; }
            Throwable finalFailure=failure;int finalCount=count;
            runOnUiThread(() -> {
                if(destroyed || isFinishing() || sourceToken!=token || pageSource!=source)return;
                if(finalFailure!=null) { showError(readableError(finalFailure));return; }
                updatePageCount(finalCount);
                if(!pending.complete())scanRemainingPages(pending,source,token);
            });
        });
    }

    static int[] prefetchTargets(int current,boolean forward,int count,int step) {
        int[] targets=new int[PREFETCH_PAGES];int size=0;
        while(size<targets.length) {
            current+=forward ? step : -step;
            if(current<0 || current>=count)break;
            targets[size++]=current;
        }
        return java.util.Arrays.copyOf(targets,size);
    }

    private Bitmap processedSinglePage(int target,long milliseconds) throws IOException {
        Bitmap raw = cropMargins(decodeSinglePage(target,milliseconds));
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
        return decodeSinglePage(target,-1);
    }
    private Bitmap decodeSinglePage(int target,long milliseconds) throws IOException {
        Bitmap raw=applyCustomCrop(pageSource.decode(sequence.original(pageSource,target),getResources().getDisplayMetrics().widthPixels*(sequence.split ? 2 : 1),milliseconds));
        return sequence.crop(raw,target,AppState.direction(this)==AppState.DIRECTION_RTL);
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

    private int originalPage() { return sequence==null ? page : sequence.original(page); }

    private void saveReadingPosition(int target) {
        int original=sequence.original(target);
        AppState.updateReadingProgress(this, sourceUri, original, pageSource.pageCount());
        AppState.prefs(this).edit().putString("position_half."+AppState.key(sourceUri),original+":"+sequence.half(target)).apply();
    }

    private void showLoadedPage(int target, Bitmap bitmap) {
        displayBitmap(bitmap);
        saveReadingPosition(target);
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
                if(!destroyed && token==loadToken && generation==renderGeneration){imageView.setImageDrawable(drawable);imageView.fitImage();((AnimatedPageDrawable)drawable).start();}
            });
        });
    }
    private android.graphics.drawable.Drawable readerDrawable(int target,Bitmap first) {
        PageSource owner=pageSource;
        if(owner.isGif(sequence.original(target)) || usesDualPageLayout() && target+1<totalPages && owner.isGif(sequence.original(target+1)))
            return new AnimatedPageDrawable(first,worker,time -> destroyed || pageSource!=owner ? null : decodeLayout(target,time));
        return new android.graphics.drawable.BitmapDrawable(getResources(),first);
    }

    private void goToPage(int target) {
        if (vertical() && target >= 0 && target < totalPages) continuous.setSelection(target);
        if (usesDualPageLayout()) target -= target % 2;
        if (target < 0 || target >= totalPages || target == page && imageView.getDrawable() != null) return;
        prefetchForward = target > page;
        loadPage(target, false);
    }

    private int nextIndex(int index, boolean forward) {
        int step = usesDualPageLayout() ? 2 : 1;
        int next = index + (forward ? step : -step);
        return next >= 0 && next < totalPages ? next : -1;
    }

    private void forward() { prefetchForward=true;if (vertical()) { if (continuous.canScrollVertically(1)) { continuous.move(true); return; } if(pageCountReady()){nextBook(true);return;} } int next = nextIndex(page, true); if (next >= 0) goToPage(next); else if(pageCountReady())nextBook(true); }
    private void back() { prefetchForward=false;if (vertical()) { if (continuous.canScrollVertically(-1)) { continuous.move(false); return; } if(pageCountReady()){nextBook(false);return;} } int previous = nextIndex(page, false); if (previous >= 0) goToPage(previous); else nextBook(false); }

    private void updateControls() {
        boolean dualPage = usesDualPageLayout();
        int shownEnd = dualPage ? Math.min(totalPages, page + 2) : page + 1;
        pageText.setText(totalPages > 0 ? (dualPage ? (page + 1) + "-" + shownEnd : String.valueOf(page + 1))
                + " / " + (pageCountReady() ? totalPages : "…") : I18n.t(R.string.ui_loading));
        pageSlider.setProgress(page);
    }

    private boolean pageCountReady() { return sequence!=null && sequence.complete(); }
    private void updatePageCount(int count) {
        if(totalPages!=count) {
            totalPages=count;
            if(vertical())continuous.setPageCount(totalPages);
        }
        pageSlider.setMax(Math.max(0,totalPages-1));
        pageSlider.setEnabled(pageCountReady());updateControls();
    }
    private boolean requirePageCount() {
        if(pageCountReady())return true;
        Toast.makeText(this,I18n.t(R.string.ui_loading),Toast.LENGTH_SHORT).show();return false;
    }

    private void toggleBookmark() {
        if (!initialized) return;
        boolean next = !AppState.hasBookmark(this, sourceUri, originalPage());
        AppState.setBookmark(this, sourceUri, originalPage(), next, title, ComicFile.kindFor(title, getContentResolver().getType(sourceUri)));
        Toast.makeText(this, next ? I18n.t(R.string.ui_bookmark_added) : I18n.t(R.string.ui_bookmark_removed), Toast.LENGTH_SHORT).show();
    }

    private void showPageJump() {
        if (!initialized || !requirePageCount()) return;
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
        if (initialized) menu.add(I18n.t(autoDelayMs > 0 ? R.string.ui_stop_auto_page_turn : R.string.ui_auto_page_turn), () -> {
            if (autoDelayMs > 0) stopAutoPage(); else showAutoPageDialog();
        });
        menu.add(I18n.t(R.string.ui_scroll_mode), this::showReadingFlowDialog);
        menu.add(I18n.t(R.string.ui_fit_screen), this::showFitDialog);
        menu.add(I18n.t(R.string.ui_screen_rotation), this::showOrientationDialog);
        if (vertical()) menu.add(I18n.t(R.string.ui_page_spacing), () -> {
            PreferenceRows rows = new PreferenceRows(this);
            rows.slider(I18n.t(R.string.ui_page_spacing), "page_gap", 0, 0, 100, " dp", this::refreshReader);
            rows.show(I18n.t(R.string.ui_page_spacing));
        });
        menu.add(I18n.t(R.string.ui_page_button_area), () -> ReaderOptions.pageButtons(this, this::refreshReader));
        menu.add(I18n.t(R.string.ui_hardware_key), () -> ReaderOptions.hardware(this, () -> { }));
        menu.show(this, I18n.t(R.string.ui_reader_menu));
    }

    private void showPageNavigation() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(R.string.ui_page_thumbnails), this::showPageList);
        menu.add(I18n.t(R.string.ui_chapters), this::showChapters);
        menu.add(I18n.t(R.string.ui_go_to_page), this::showPageJump);
        menu.show(this, I18n.t(R.string.ui_find_page));
    }

    private void showPageList() {
        if (!initialized || !requirePageCount()) return;
        stopAutoPage();
        if (pageListDialog != null) pageListDialog.dismiss();
        int width = Math.min(dp(600), getResources().getDisplayMetrics().widthPixels - dp(32));
        android.widget.GridView grid = new android.widget.GridView(this);
        grid.setId(android.R.id.list);
        grid.setNumColumns(Math.max(1, width / dp(Math.round(104 * getResources().getConfiguration().fontScale))));
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        grid.setHorizontalSpacing(dp(8)); grid.setVerticalSpacing(dp(8));
        grid.setBackgroundColor(Ui.SURFACE);
        grid.setContentDescription(I18n.t(R.string.ui_page_thumbnails));
        PageListAdapter adapter = new PageListAdapter();
        grid.setAdapter(adapter);
        FrameLayout content = new FrameLayout(this);
        content.addView(grid, new FrameLayout.LayoutParams(-1, Math.max(dp(120), Math.min(dp(480), getResources().getDisplayMetrics().heightPixels - dp(200)))));
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_page_thumbnails)).setView(content)
                .setNegativeButton(I18n.t(R.string.ui_close), null));
        pageListDialog = dialog;
        dialog.getWindow().setGravity(Gravity.CENTER);
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setOnDismissListener(ignored -> { adapter.closed = true; if (pageListDialog == dialog) pageListDialog = null; });
        grid.setSelection(page);
        grid.setOnItemClickListener((parent, view, position, id) -> { goToPage(position); dialog.dismiss(); });
    }

    private final class PageListAdapter extends android.widget.BaseAdapter {
        private final PageSource owner = pageSource;
        private volatile boolean closed;
        @Override public int getCount() { return sequence.count(); }
        @Override public Integer getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout cell = new LinearLayout(ViewerActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            android.widget.ImageView thumbnail = new android.widget.ImageView(ViewerActivity.this);
            thumbnail.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(thumbnail, new LinearLayout.LayoutParams(-1, dp(128)));
            TextView label = text(Integer.toString(position + 1), 14, Ui.TEXT_PRIMARY);
            label.setGravity(Gravity.CENTER); label.setMinHeight(dp(32)); cell.addView(label);
            cell.setContentDescription((position + 1) + " · " + owner.pageName(sequence.original(position)));
            cell.setBackgroundColor(position == page ? Ui.BRAND_CONTAINER : Ui.SURFACE_RAISED);
            cell.setSelected(position == page);
            worker.execute(() -> {
                if (destroyed || closed || pageSource != owner) return;
                try {
                    Bitmap decoded = decodeSinglePage(position); int[] size = coverSize(decoded.getWidth(), decoded.getHeight());
                    Bitmap small = Bitmap.createScaledBitmap(decoded, size[0], size[1], true); if (small != decoded) decoded.recycle();
                    runOnUiThread(() -> { if (!destroyed && !closed && pageSource == owner) thumbnail.setImageBitmap(small); else small.recycle(); });
                } catch (Exception | OutOfMemoryError ignored) { }
            });
            return cell;
        }
    }

    private void showChapters() {
        if(!requirePageCount())return;
        ArrayList<Integer> indices = new ArrayList<>(); ArrayList<String> labels = new ArrayList<>();
        if (!pageSource.chapters.isEmpty()) {
            for (PageSource.Chapter chapter : pageSource.chapters) { indices.add(sequence.display(chapter.page,0)); labels.add(chapter.title); }
        } else {
            String previous = null;
            for (int i = 0; i < totalPages; i++) {
                String name = pageSource.pageName(sequence.original(i));
                String chapter = name.contains("/") ? name.substring(0, name.lastIndexOf('/')) : I18n.t(R.string.ui_first_chapter);
                if (!chapter.equals(previous)) { indices.add(i); labels.add(chapter); }
                previous = chapter;
            }
        }
        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_chapters)).setItems(labels.toArray(new String[0]),
                (dialog, selected) -> goToPage(indices.get(selected))).setNegativeButton(I18n.t(R.string.ui_close), null));
    }

    private void showBookmarkActions() {
        Ui.Actions menu = new Ui.Actions();
        menu.add(I18n.t(AppState.hasBookmark(this, sourceUri, originalPage()) ? R.string.ui_remove_this_bookmark : R.string.ui_bookmark_this_page), this::toggleBookmark);
        menu.add(I18n.t(R.string.ui_bookmarks_2), this::showBookmarks);
        menu.add(I18n.t(R.string.ui_edit_bookmark_note), this::showBookmarkMemo);
        menu.show(this, I18n.t(R.string.ui_bookmark_actions));
    }

    static int[] coverSize(int width, int height) {
        float scale = Math.min(1f, Math.min(320f / Math.max(1, width), 480f / Math.max(1, height)));
        return new int[]{Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }

    private void showPageLayoutDialog() { ReaderOptions.pageLayout(this, this::refreshReader); }

    private void showReadingFlowDialog() { ReaderOptions.readingFlow(this, this::refreshReader); }

    private void showFilterDialog() { ReaderOptions.filters(this, this::refreshReader); }

    private void showBookmarkMemo() {
        if (!initialized) return;
        if (!AppState.hasBookmark(this, sourceUri, originalPage()))
            AppState.setBookmark(this, sourceUri, originalPage(), true, title, ComicFile.kindFor(title, getContentResolver().getType(sourceUri)));
        EditText input = new EditText(this);
        input.setHint(I18n.t(R.string.ui_note_optional));
        input.setText(AppState.bookmarkMemo(this, sourceUri, originalPage()));
        Ui.styleSearch(input);
        Ui.show(new AlertDialog.Builder(this).setTitle((page + 1) + I18n.t(R.string.ui_bookmark_note)).setView(input)
                .setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_save), (dialog, which) -> {
                    AppState.setBookmarkMemo(this, sourceUri, originalPage(), input.getText().toString());
                            }));
    }

    private void showFitDialog() { ReaderOptions.fit(this, () -> imageView.setFitMode(AppState.fitMode(this))); }

    private void showOrientationDialog() { ReaderOptions.orientation(this); }

    private void showDirectionDialog() { ReaderOptions.direction(this, this::refreshReader); }

    private void showBookmarks() {
        if(!requirePageCount())return;
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
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_bookmarks_2)).setItems(labels, (ignored, selected) -> goToPage(sequence.display(ordered.get(selected),0)))
                .setNegativeButton(I18n.t(R.string.ui_delete_all), (ignored, which) -> { AppState.clearBookmarks(this, sourceUri); }));
        Ui.styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), Ui.ButtonStyle.DANGER);
    }

    private void showAutoPageDialog() {
        String[] labels = {I18n.t(R.string.ui_every_5_seconds), I18n.t(R.string.ui_every_10_seconds), I18n.t(R.string.ui_every_15_seconds)};
        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_auto_page_turn)).setItems(labels, (dialog, selected) -> {
            autoDelayMs = new int[]{5000, 10000, 15000}[selected];
            autoHandler.removeCallbacks(autoPage);
            autoHandler.postDelayed(autoPage, autoDelayMs);
        }));
    }

    private void stopAutoPage() {
        autoDelayMs = 0;
        autoHandler.removeCallbacks(autoPage);
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

    private void applyDisplayCutout() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        boolean cutout = AppState.enabled(this, getResources().getConfiguration().orientation == 2 ? "cutout_land" : "cutout_port", false);
        attributes.layoutInDisplayCutoutMode = cutout ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        getWindow().setAttributes(attributes);
        getWindow().getDecorView().requestApplyInsets();
    }

    private void showLoading(boolean show) { loading.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void showError(String message) { errorPanel.setVisibility(message == null ? View.GONE : View.VISIBLE); if (message != null) errorText.setText(message); }
    private void runWorker(boolean priority,Runnable task) {
        if(priority) { workerQueue.offerFirst(task);worker.prestartCoreThread(); }
        else worker.execute(task);
    }
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
        if (direction == AppState.pageSwipeDirection(this)) forward(); else back();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (AppState.prefs(this).contains("setting.key." + keyCode)) {
            switch (ReaderOptions.keyAction(this, keyCode)) {
                case 1: back(); break; case 2: forward(); break; case 3: toggleChrome(); break;
                case 4: toggleBookmark(); break; default: break;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_PAGE_UP) { if (AppState.direction(this) == AppState.DIRECTION_RTL) forward(); else back(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) { if (AppState.direction(this) == AppState.DIRECTION_RTL) back(); else forward(); return true; }
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
        if (initialized) {
            refreshReader();
            if (pageListDialog != null && pageListDialog.isShowing()) { pageListDialog.dismiss(); showPageList(); }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("current_page", originalPage()); state.putInt("current_half", sequence==null ? 0 : sequence.half(page));
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (pageListDialog != null) pageListDialog.dismiss();
        if (continuous != null) continuous.stop();
        loadToken++;
        sourceToken++;
        stopAutoPage();
        if (imageView != null) imageView.setImageDrawable(null);
        pageCache.evictAll();
        worker.execute(() -> { if (pageSource != null) try { pageSource.close(); } catch (IOException ignored) { } });
        worker.shutdown();
        super.onDestroy();
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
        if (sequence.split != (AppState.pageLayout(this)==AppState.PAGE_FORCE_SINGLE)) {
            int original=originalPage(), half=sequence.half(page);
            pageLayout=AppState.pageLayout(this);
            invalidatePages();
            if(pageListDialog!=null)pageListDialog.dismiss();
            initializeSource(original,half);
            return;
        }
        cropPercent = AppState.cropPercent(this);
        pageLayout = AppState.pageLayout(this); dualPageDivider = AppState.dualPageDivider(this);
        imageView.setDoubleTapScale(AppState.doubleTapScale(this)/100f); imageView.setDoubleTapMode(AppState.doubleTapMode(this));
        imageView.setVerticalPaging(AppState.verticalPageSwipe(this));
        imageView.setVisibility(vertical() ? View.GONE : View.VISIBLE); continuous.setVisibility(vertical() ? View.VISIBLE : View.GONE);
        continuous.setDividerHeight(dp(AppState.number(this, "page_gap", 0)) + (AppState.enabled(this, "scroll_divider", false) ? 1 : 0));
        if (vertical()) continuous.reset(totalPages, page);
        invalidatePages(); updatePageButtons(); loadPage(usesDualPageLayout() ? page - page % 2 : page, false); applyDisplayCutout();
    }
    private boolean pageButtonForward(boolean right) {
        return PageButtonDialog.forward(right,AppState.number(this,"page_type",0),
                AppState.enabled(this,"page_both_next",false),AppState.enabled(this,"page_reverse",false),
                AppState.enabled(this,"page_fixed",false),AppState.direction(this)==AppState.DIRECTION_RTL);
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
