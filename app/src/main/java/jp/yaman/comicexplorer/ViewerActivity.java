package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Responsive, local-only page reader. Work that touches files or decodes images is off the UI thread. */
public final class ViewerActivity extends Activity implements ZoomImageView.InteractionListener {
    public static final String EXTRA_IMAGE_URIS = "image_uris";
    public static final String EXTRA_START_INDEX = "start_index";
    public static final String EXTRA_TITLE = "title";
    private static final int TYPE_IMAGES = 1;
    private static final int TYPE_PDF = 2;
    private static final int TYPE_ARCHIVE = 3;
    private static final int MAX_ARCHIVE_PAGES = 20_000;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 48L * 1024 * 1024;
    private static final int MAX_PAGE_DIMENSION = 8192;

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

    private Uri sourceUri;
    private String title;
    private ArrayList<Uri> imageUris;
    private ArrayList<String> archiveEntries;
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfDescriptor;
    private int type;
    private int page;
    private int totalPages;
    private int loadToken;
    private int autoDelayMs;
    private int maxBitmapPixels;
    private int pageLayout;
    private int filterMode;
    private boolean initialized;
    private boolean chromeVisible;
    private boolean fullScreen;
    private boolean inverted;
    private volatile boolean destroyed;

    private ZoomImageView imageView;
    private TextView pageText;
    private TextView errorText;
    private View chromeTop;
    private View chromeBottom;
    private LinearLayout readerMenuRow;
    private TextView readerMenuIndicator;
    private View errorPanel;
    private ProgressBar loading;
    private SeekBar pageSlider;
    private Button quickBookmarkButton;
    private Button leftPageButton;
    private Button rightPageButton;
    private int readerMenuPage;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int maxKb = manager == null ? 20 * 1024 : Math.min(32 * 1024, manager.getMemoryClass() * 1024 / 6);
        pageCache = new BitmapMemoryCache(Math.max(8 * 1024, maxKb));
        maxBitmapPixels = Math.max(2 * 1024 * 1024, maxKb * 1024 / 4);
        getWindow().setStatusBarColor(Ui.DARK_BACKGROUND);
        getWindow().setNavigationBarColor(Ui.DARK_BACKGROUND);
        sourceUri = getIntent().getData();
        if (sourceUri == null) { finish(); return; }
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty()) title = "Comic Explorer";
        imageUris = getIntent().getParcelableArrayListExtra(EXTRA_IMAGE_URIS);
        buildUi();
        applyDarkSystemBarIcons();
        applyReaderPreferences();
        initializeSource();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.DARK_BACKGROUND);

        LinearLayout topChrome = new LinearLayout(this);
        topChrome.setOrientation(LinearLayout.VERTICAL);
        topChrome.setBackgroundColor(Ui.DARK_SURFACE);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        top.setBackgroundColor(Ui.DARK_SURFACE);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_arrow_back);
        close.setContentDescription("作品を閉じる");
        Ui.styleToolbarButton(close, Ui.DARK_SURFACE);
        close.setOnClickListener(view -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(56)));
        TextView titleText = text(title, 16, Ui.DARK_TEXT);
        titleText.setGravity(Gravity.CENTER_VERTICAL);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleText.setPadding(dp(8), 0, dp(8), 0);
        top.addView(titleText, new LinearLayout.LayoutParams(0, dp(56), 1f));
        ImageButton more = new ImageButton(this);
        more.setImageResource(R.drawable.ic_toolbar_more);
        more.setContentDescription("次の読書メニューを表示");
        Ui.styleToolbarButton(more, Ui.DARK_SURFACE);
        more.setOnClickListener(view -> showReaderMenu());
        top.addView(more, new LinearLayout.LayoutParams(dp(48), dp(56)));
        topChrome.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        topChrome.addView(buildReaderMenu(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        chromeTop = topChrome;

        FrameLayout canvas = new FrameLayout(this);
        imageView = new ZoomImageView(this);
        imageView.setInteractionListener(this);
        canvas.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        leftPageButton = readerAction("−", "左側のページ操作");
        rightPageButton = readerAction("＋", "右側のページ操作");
        Ui.styleReaderPageButton(leftPageButton);
        Ui.styleReaderPageButton(rightPageButton);
        leftPageButton.setOnClickListener(view -> onTap(0f));
        rightPageButton.setOnClickListener(view -> onTap(1f));
        FrameLayout.LayoutParams leftPageParams = new FrameLayout.LayoutParams(dp(48), dp(96), Gravity.START | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rightPageParams = new FrameLayout.LayoutParams(dp(48), dp(96), Gravity.END | Gravity.CENTER_VERTICAL);
        canvas.addView(leftPageButton, leftPageParams);
        canvas.addView(rightPageButton, rightPageParams);
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.READER_ACCENT));
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        canvas.addView(loading, loadingParams);
        LinearLayout error = new LinearLayout(this);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(24), dp(24), dp(24));
        Ui.styleDarkPanel(error);
        errorText = text("", 16, Ui.DARK_TEXT);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(0, 1.08f);
        error.addView(errorText);
        Button retry = button("再試行", "ファイルを再度読み込む");
        Ui.styleButton(retry, Ui.ButtonStyle.DARK_PRIMARY);
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
        bottom.setBackgroundColor(Ui.DARK_SURFACE);

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(dp(8), 0, dp(8), 0);
        pageText = text("読み込み中…", 13, Ui.DARK_TEXT);
        pageText.setGravity(Gravity.CENTER);
        Ui.styleDarkChip(pageText, false);
        pageText.setContentDescription("ページ番号。タップして移動");
        pageText.setOnClickListener(view -> showPageJump());
        sliderRow.addView(pageText, new LinearLayout.LayoutParams(dp(88), dp(36)));
        pageSlider = new SeekBar(this);
        pageSlider.setMax(0);
        pageSlider.setContentDescription("ページスライダー");
        Ui.styleSeekBar(pageSlider, true);
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
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
    }

    private View buildReaderMenu() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Ui.DARK_SURFACE_RAISED);
        readerMenuRow = new LinearLayout(this);
        readerMenuRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(readerMenuRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        readerMenuIndicator = text("", 9, Ui.READER_ACCENT);
        readerMenuIndicator.setGravity(Gravity.CENTER);
        readerMenuIndicator.setContentDescription("読書メニュー 1 / 3");
        readerMenuIndicator.setOnClickListener(view -> showReaderMenu());
        panel.addView(readerMenuIndicator, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        showReaderMenuPage(0);
        return panel;
    }

    private void showReaderMenuPage(int target) {
        readerMenuPage = target;
        readerMenuRow.removeAllViews();
        quickBookmarkButton = null;
        if (target == 0) {
            addReaderMenuAction("⇆", "ページ送り方向", view -> showDirectionDialog());
            addReaderMenuAction("↻", "画面回転", view -> showOrientationDialog());
            addReaderMenuAction("▥", "ページレイアウト", view -> showPageLayoutDialog());
            addReaderMenuAction("□", "表示方法", view -> showFitDialog());
            addReaderMenuAction("◐", "明るさ", view -> showBrightnessDialog());
        } else if (target == 1) {
            addReaderMenuAction("◎", "拡大固定", view -> showZoomDialog());
            addReaderMenuAction("◒", "画像フィルター", view -> showFilterDialog());
            addReaderMenuAction("↕", "ページ移動", view -> showReadingFlowDialog());
            quickBookmarkButton = addReaderMenuAction("☆", "このページにしおりを追加", view -> toggleBookmark());
            addReaderMenuAction("☷", "しおり一覧", view -> showBookmarks());
            refreshQuickBookmark();
        } else {
            addReaderMenuAction("▣", "画面を画像として保存", view -> capturePage());
            addReaderMenuAction("#", "ページへ移動", view -> showPageJump());
            addReaderMenuAction("⛶", fullScreen ? "全画面を解除" : "全画面にする", view -> { toggleFullscreen(); showReaderMenuPage(2); });
            addReaderMenuAction(autoDelayMs > 0 ? "Ⅱ" : "▶", autoDelayMs > 0 ? "自動送りを停止" : "自動ページ送り", view -> {
                if (autoDelayMs > 0) { stopAutoPage(); showReaderMenuPage(2); }
                else showAutoPageDialog();
            });
            addReaderMenuAction("⋮", "その他の読書設定", view -> showMoreReaderSettings());
        }
        Button next = readerAction(target < 2 ? "›" : "‹", target < 2 ? "次のメニューページ" : "最初のメニューページ");
        next.setOnClickListener(view -> showReaderMenuPage(target < 2 ? target + 1 : 0));
        readerMenuRow.addView(next, new LinearLayout.LayoutParams(dp(48), dp(56)));
        readerMenuIndicator.setText(target == 0 ? "●  ○  ○" : target == 1 ? "○  ●  ○" : "○  ○  ●");
        readerMenuIndicator.setContentDescription("読書メニュー " + (target + 1) + " / 3。タップで次へ");
    }

    private Button addReaderMenuAction(String symbol, String description, View.OnClickListener listener) {
        Button button = readerAction(symbol, description);
        button.setTooltipText(description);
        button.setOnClickListener(listener);
        readerMenuRow.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1f));
        return button;
    }

    private void applyReaderPreferences() {
        if (AppState.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pageLayout = AppState.pageLayout(this);
        filterMode = AppState.imageFilter(this);
        imageView.setFitMode(AppState.fitMode(this));
        imageView.setDoubleTapScale(AppState.doubleTapScale(this) / 100f);
        imageView.setDoubleTapMode(AppState.doubleTapMode(this));
        imageView.setVerticalPaging(AppState.readingFlow(this) == AppState.FLOW_VERTICAL);
        imageView.setFilterMode(filterMode);
        updatePageButtons();
        applyBrightness(AppState.brightness(this));
        if (AppState.startFullscreen(this)) {
            fullScreen = true;
            imageView.post(this::applyFullscreen);
        }
    }

    private void updatePageButtons() {
        if (leftPageButton == null) return;
        boolean visible = AppState.pageButtons(this);
        leftPageButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        rightPageButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        float alpha = AppState.pageButtonOpacity(this) / 100f;
        leftPageButton.setAlpha(alpha);
        rightPageButton.setAlpha(alpha);
        leftPageButton.getLayoutParams().height = dp(AppState.pageButtonHeight(this));
        rightPageButton.getLayoutParams().height = dp(AppState.pageButtonHeight(this));
        leftPageButton.requestLayout();
        rightPageButton.requestLayout();
        boolean rtl = AppState.direction(this) == AppState.DIRECTION_RTL;
        leftPageButton.setContentDescription(rtl ? "次のページ" : "前のページ");
        rightPageButton.setContentDescription(rtl ? "前のページ" : "次のページ");
    }

    private void initializeSource() {
        initialized = false;
        showLoading(true);
        showError(null);
        int token = ++loadToken;
        worker.execute(() -> {
            if (destroyed) return;
            try {
                if (imageUris != null && !imageUris.isEmpty()) {
                    type = TYPE_IMAGES;
                    totalPages = imageUris.size();
                    int start = getIntent().getIntExtra(EXTRA_START_INDEX, AppState.getPosition(this, sourceUri));
                    page = Math.max(0, Math.min(start, totalPages - 1));
                } else {
                    String extension = ComicFile.extension(title);
                    if ("pdf".equals(extension)) {
                        closePdf();
                        pdfDescriptor = getContentResolver().openFileDescriptor(sourceUri, "r");
                        if (pdfDescriptor == null) throw new IOException("PDFを開けません。");
                        pdf = new PdfRenderer(pdfDescriptor);
                        type = TYPE_PDF;
                        totalPages = pdf.getPageCount();
                    } else if ("zip".equals(extension) || "cbz".equals(extension)) {
                        type = TYPE_ARCHIVE;
                        archiveEntries = readArchiveEntries();
                        totalPages = archiveEntries.size();
                    } else {
                        throw new IOException("この形式は表示できません。PDF、CBZ/ZIP、画像に対応しています。");
                    }
                    if (totalPages < 1) throw new IOException("表示できるページがありません。");
                    page = Math.max(0, Math.min(AppState.getPosition(this, sourceUri), totalPages - 1));
                }
                if (pageLayout == AppState.PAGE_DUAL) page -= page % 2;
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken || isFinishing()) return;
                    initialized = true;
                    pageSlider.setMax(Math.max(0, totalPages - 1));
                    updateControls();
                    loadPage(page, false);
                    if (!AppState.hasSeenReaderHint(this)) {
                        Toast.makeText(this, "左右をタップ／スワイプでページ移動、中央タップでメニューを表示します", Toast.LENGTH_LONG).show();
                        AppState.markReaderHintSeen(this);
                    }
                });
            } catch (Exception | OutOfMemoryError exception) {
                if (exception instanceof OutOfMemoryError) pageCache.evictAll();
                runOnUiThread(() -> {
                    if (destroyed || token != loadToken || isFinishing()) return;
                    showLoading(false);
                    showError(readableError(exception));
                });
            }
        });
    }

    private ArrayList<String> readArchiveEntries() throws IOException {
        ArrayList<String> entries = new ArrayList<>();
        InputStream input = getContentResolver().openInputStream(sourceUri);
        if (input == null) throw new IOException("CBZを開けません。");
        try (InputStream source = input; ZipInputStream zip = new ZipInputStream(source, archiveCharset())) {
            ZipEntry entry;
            while (!destroyed && (entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && ComicFile.isImage(entry.getName(), null)) {
                    if (entries.size() >= MAX_ARCHIVE_PAGES) throw new IOException("CBZのページ数が多すぎます（上限20,000ページ）。");
                    entries.add(entry.getName());
                }
            }
        }
        Collections.sort(entries, ComicFile.NATURAL_NAME_ORDER);
        return entries;
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
                displayBitmap(cached);
                AppState.updateReadingProgress(this, sourceUri, target, totalPages);
                prefetchAround(target);
            }
            return;
        }
        if (!prefetch) showLoading(true);
        worker.execute(() -> {
            if (destroyed) return;
            Bitmap bitmap = null;
            Throwable failure = null;
            try { bitmap = decodePage(target); if (bitmap == null) throw new IOException("画像を読み取れません。"); }
            catch (Exception | OutOfMemoryError exception) {
                if (exception instanceof OutOfMemoryError) pageCache.evictAll();
                failure = exception;
            }
            Bitmap finalBitmap = bitmap;
            Throwable finalFailure = failure;
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) return;
                if (finalBitmap != null) pageCache.put(cacheKey, finalBitmap);
                if (prefetch) return;
                if (token != loadToken) return;
                showLoading(false);
                if (finalFailure != null) {
                    stopAutoPage();
                    showError(readableError(finalFailure));
                }
                else {
                    displayBitmap(finalBitmap);
                    AppState.updateReadingProgress(this, sourceUri, target, totalPages);
                    prefetchAround(page);
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

    private Bitmap decodePage(int target) throws IOException {
        Bitmap first = decodeSinglePage(target);
        if (pageLayout != AppState.PAGE_DUAL || target + 1 >= totalPages) return first;
        Bitmap second = null;
        try {
            second = decodeSinglePage(target + 1);
            return combinePages(first, second, AppState.direction(this) == AppState.DIRECTION_RTL);
        } finally {
            first.recycle();
            if (second != null) second.recycle();
        }
    }

    private Bitmap decodeSinglePage(int target) throws IOException {
        if (type == TYPE_IMAGES) return decodeUri(imageUris.get(target));
        if (type == TYPE_PDF) return renderPdfPage(target);
        return decodeArchivePage(archiveEntries.get(target));
    }

    private Bitmap combinePages(Bitmap first, Bitmap second, boolean rightToLeft) {
        int sourceWidth = first.getWidth() + second.getWidth();
        int sourceHeight = Math.max(first.getHeight(), second.getHeight());
        double scale = Math.min(1d, Math.min(MAX_PAGE_DIMENSION / (double) Math.max(sourceWidth, sourceHeight),
                Math.sqrt(maxBitmapPixels / (double) ((long) sourceWidth * sourceHeight))));
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
        paint.setColor(0xFF424242);
        canvas.drawRect(Math.max(0, leftWidth - 1), 0, Math.min(width, leftWidth + 1), height, paint);
        return result;
    }

    private String cacheKey(int target) { return type + ":" + pageLayout + ":" + target; }

    private Bitmap decodeUri(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = getContentResolver().openInputStream(uri);
        if (first == null) throw new IOException("画像を開けません。");
        try (InputStream stream = first) { BitmapFactory.decodeStream(stream, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("画像が壊れているか、未対応の形式です。");
        InputStream second = getContentResolver().openInputStream(uri);
        if (second == null) throw new IOException("画像を開けません。");
        try (InputStream stream = second) { return BitmapFactory.decodeStream(stream, null, decodeOptions(bounds.outWidth, bounds.outHeight)); }
    }

    private Bitmap decodeArchivePage(String target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decodeArchiveEntry(target, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("CBZ内の画像が壊れています。");
        return decodeArchiveEntry(target, decodeOptions(bounds.outWidth, bounds.outHeight));
    }

    // ponytail: SAF ZIP streams are rescanned; spool to a temporary ZipFile only if late-page latency is measured.
    private Bitmap decodeArchiveEntry(String target, BitmapFactory.Options options) throws IOException {
        InputStream input = getContentResolver().openInputStream(sourceUri);
        if (input == null) throw new IOException("CBZを開けません。");
        try (InputStream source = input; ZipInputStream zip = new ZipInputStream(source, archiveCharset())) {
            ZipEntry entry;
            while (!destroyed && (entry = zip.getNextEntry()) != null) {
                if (target.equals(entry.getName())) {
                    if (entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES) throw new IOException("画像ページが大きすぎます（上限48MB）。");
                    return BitmapFactory.decodeStream(new BoundedInputStream(zip, MAX_ARCHIVE_ENTRY_BYTES), null, options);
                }
            }
        }
        throw new IOException("CBZ内のページが見つかりません。");
    }

    private Charset archiveCharset() {
        return Charset.forName(AppState.archiveEncoding(this) == 1 ? "Shift_JIS" : "UTF-8");
    }

    private Bitmap renderPdfPage(int index) throws IOException {
        if (pdf == null) throw new IOException("PDFを開けません。");
        PdfRenderer.Page current = pdf.openPage(index);
        try {
            if (current.getWidth() <= 0 || current.getHeight() <= 0) throw new IOException("PDFのページサイズが不正です。");
            int screenWidth = imageView.getWidth() > 0 ? imageView.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int[] size = pdfBitmapSize(current.getWidth(), current.getHeight(), screenWidth, maxBitmapPixels);
            Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFFFFFFFF);
            current.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } finally {
            current.close();
        }
    }

    private BitmapFactory.Options decodeOptions(int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = bitmapSampleSize(width, height, maxBitmapPixels);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return options;
    }

    private void displayBitmap(Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        imageView.setFilterMode(filterMode);
        imageView.setInverted(inverted);
    }

    private void goToPage(int target) {
        if (pageLayout == AppState.PAGE_DUAL) target -= target % 2;
        if (target < 0 || target >= totalPages || target == page && imageView.getDrawable() != null) return;
        loadPage(target, false);
    }

    private int nextIndex(int index, boolean forward) {
        int step = pageLayout == AppState.PAGE_DUAL ? 2 : 1;
        int next = index + (forward ? step : -step);
        return next >= 0 && next < totalPages ? next : -1;
    }

    private void forward() { int next = nextIndex(page, true); if (next >= 0) goToPage(next); else Toast.makeText(this, "最後のページです", Toast.LENGTH_SHORT).show(); }
    private void back() { int previous = nextIndex(page, false); if (previous >= 0) goToPage(previous); else Toast.makeText(this, "最初のページです", Toast.LENGTH_SHORT).show(); }

    private void updateControls() {
        int shownEnd = pageLayout == AppState.PAGE_DUAL ? Math.min(totalPages, page + 2) : page + 1;
        pageText.setText(totalPages > 0 ? (pageLayout == AppState.PAGE_DUAL ? (page + 1) + "-" + shownEnd : String.valueOf(page + 1))
                + " / " + totalPages + "  " + Math.round(shownEnd * 100f / totalPages) + "%" : "読み込み中…");
        pageSlider.setProgress(page);
        refreshQuickBookmark();
    }

    private void toggleBookmark() {
        if (!initialized) return;
        boolean next = !AppState.hasBookmark(this, sourceUri, page);
        AppState.setBookmark(this, sourceUri, page, next, title, ComicFile.kindFor(title, getContentResolver().getType(sourceUri)));
        refreshQuickBookmark();
        Toast.makeText(this, next ? "しおりに追加しました" : "しおりを削除しました", Toast.LENGTH_SHORT).show();
    }

    private void refreshQuickBookmark() {
        if (quickBookmarkButton == null) return;
        boolean marked = initialized && AppState.hasBookmark(this, sourceUri, page);
        quickBookmarkButton.setText(marked ? "★" : "☆");
        quickBookmarkButton.setContentDescription(marked ? "このページのしおりを削除" : "このページにしおりを追加");
    }

    private void showPageJump() {
        if (!initialized) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1〜" + totalPages);
        input.setText(String.valueOf(page + 1));
        Ui.styleSearch(input);
        Ui.show(new AlertDialog.Builder(this).setTitle("ページへ移動").setView(input).setNegativeButton("キャンセル", null).setPositiveButton("移動", (dialog, which) -> {
            try {
                int target = Integer.parseInt(input.getText().toString()) - 1;
                if (target < 0 || target >= totalPages) throw new NumberFormatException();
                goToPage(target);
            } catch (NumberFormatException error) { Toast.makeText(this, "1〜" + totalPages + " の番号を入力してください", Toast.LENGTH_SHORT).show(); }
        }));
    }

    private void showReaderMenu() {
        showReaderMenuPage((readerMenuPage + 1) % 3);
    }

    private void showMoreReaderSettings() {
        String[] actions = {"しおりメモを編集", inverted ? "色反転を戻す" : "色を反転", "復帰位置を先頭に戻す", "設定"};
        Ui.show(new AlertDialog.Builder(this).setTitle("その他").setItems(actions, (dialog, which) -> {
            if (which == 0) showBookmarkMemo();
            else if (which == 1) { inverted = !inverted; imageView.setInverted(inverted); }
            else if (which == 2) {
                AppState.clearPosition(this, sourceUri);
                goToPage(0);
                Toast.makeText(this, "復帰位置を先頭に戻しました", Toast.LENGTH_SHORT).show();
            } else startActivity(new Intent(this, SettingsActivity.class));
        }));
    }

    private void showPageLayoutDialog() {
        Ui.show(new AlertDialog.Builder(this).setTitle("ページレイアウト")
                .setSingleChoiceItems(new String[]{"単ページ", "見開き"}, pageLayout, (dialog, selected) -> {
                    pageLayout = selected;
                    AppState.setPageLayout(this, selected);
                    pageCache.evictAll();
                    int target = selected == AppState.PAGE_DUAL ? page - page % 2 : page;
                    dialog.dismiss();
                    loadPage(target, false);
                }));
    }

    private void showReadingFlowDialog() {
        Ui.show(new AlertDialog.Builder(this).setTitle("ページ移動")
                .setSingleChoiceItems(new String[]{"横スワイプ", "縦スワイプ"}, AppState.readingFlow(this), (dialog, selected) -> {
                    AppState.setReadingFlow(this, selected);
                    imageView.setVerticalPaging(selected == AppState.FLOW_VERTICAL);
                    dialog.dismiss();
                }));
    }

    private void showFilterDialog() {
        String[] choices = {"なし", "グレースケール", "自動コントラスト", "セピア", "ブルーライト軽減"};
        Ui.show(new AlertDialog.Builder(this).setTitle("画像フィルター").setSingleChoiceItems(choices, filterMode, (dialog, selected) -> {
            filterMode = selected;
            AppState.setImageFilter(this, selected);
            imageView.setFilterMode(selected);
            dialog.dismiss();
        }));
    }

    private void showZoomDialog() {
        String[] labels = {"無効", "1.5倍", "2.0倍", "2.25倍", "2.5倍", "3.0倍", "4.0倍"};
        int[] values = {0, 150, 200, 225, 250, 300, 400};
        int selected = 0;
        if (AppState.doubleTapMode(this) != AppState.DOUBLE_TAP_OFF) {
            int scale = AppState.doubleTapScale(this);
            for (int index = 1; index < values.length; index++) if (values[index] == scale) selected = index;
        }
        Ui.show(new AlertDialog.Builder(this).setTitle("ダブルタップ拡大").setSingleChoiceItems(labels, selected, (dialog, chosen) -> {
            AppState.setDoubleTapMode(this, chosen == 0 ? AppState.DOUBLE_TAP_OFF : AppState.DOUBLE_TAP_TOGGLE);
            if (chosen > 0) AppState.setDoubleTapScale(this, values[chosen]);
            imageView.setDoubleTapMode(AppState.doubleTapMode(this));
            imageView.setDoubleTapScale(AppState.doubleTapScale(this) / 100f);
            dialog.dismiss();
        }));
    }

    private void showBookmarkMemo() {
        if (!initialized) return;
        if (!AppState.hasBookmark(this, sourceUri, page))
            AppState.setBookmark(this, sourceUri, page, true, title, ComicFile.kindFor(title, getContentResolver().getType(sourceUri)));
        EditText input = new EditText(this);
        input.setHint("メモ（省略可）");
        input.setText(AppState.bookmarkMemo(this, sourceUri, page));
        Ui.styleSearch(input);
        Ui.show(new AlertDialog.Builder(this).setTitle((page + 1) + " ページのしおりメモ").setView(input)
                .setNegativeButton("キャンセル", null).setPositiveButton("保存", (dialog, which) -> {
                    AppState.setBookmarkMemo(this, sourceUri, page, input.getText().toString());
                    refreshQuickBookmark();
                }));
    }

    private void showFitDialog() {
        String[] choices = {"画面に合わせる", "幅に合わせる", "高さに合わせる", "画面いっぱいに伸縮"};
        Ui.show(new AlertDialog.Builder(this).setTitle("表示方法").setSingleChoiceItems(choices, AppState.fitMode(this), (dialog, chosen) -> {
            AppState.setFitMode(this, chosen);
            imageView.setFitMode(chosen);
            dialog.dismiss();
        }));
    }

    private void showBrightnessDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(24), dp(6), dp(24), dp(4));
        content.setOrientation(LinearLayout.VERTICAL);
        TextView value = text("", 16, Ui.TEXT_PRIMARY);
        int current = AppState.brightness(this);
        value.setText(current < 0 ? "システムの明るさ" : current + "%");
        content.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(current < 0 ? 50 : current);
        Ui.styleSeekBar(slider, false);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { value.setText(progress + "%"); applyBrightness(progress); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        content.addView(slider);
        Ui.show(new AlertDialog.Builder(this).setTitle("明るさ").setView(content).setNegativeButton("システム設定", (dialog, which) -> {
            AppState.setBrightness(this, -1);
            applyBrightness(-1);
        }).setPositiveButton("保存", (dialog, which) -> AppState.setBrightness(this, slider.getProgress())));
    }

    private void showOrientationDialog() {
        String[] choices = {"自動回転", "縦向きに固定", "横向きに固定"};
        Ui.show(new AlertDialog.Builder(this).setTitle("画面回転").setItems(choices, (dialog, selected) -> {
            setRequestedOrientation(selected == 1 ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : selected == 2 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }));
    }

    private void showDirectionDialog() {
        String[] choices = {"左から右", "右から左（漫画向け）"};
        Ui.show(new AlertDialog.Builder(this).setTitle("ページ送り方向")
                .setSingleChoiceItems(choices, AppState.direction(this), (dialog, selected) -> {
                    AppState.setDirection(this, selected);
                    updatePageButtons();
                    if (pageLayout == AppState.PAGE_DUAL) { pageCache.evictAll(); loadPage(page, false); }
                    dialog.dismiss();
                }));
    }

    private void showBookmarks() {
        Set<Integer> pages = AppState.bookmarks(this, sourceUri);
        if (pages.isEmpty()) { Toast.makeText(this, "しおりはまだありません", Toast.LENGTH_SHORT).show(); return; }
        List<Integer> ordered = new ArrayList<>(pages);
        Collections.sort(ordered);
        String[] labels = new String[ordered.size()];
        for (int index = 0; index < labels.length; index++) {
            int bookmarkedPage = ordered.get(index);
            String memo = AppState.bookmarkMemo(this, sourceUri, bookmarkedPage);
            labels[index] = (bookmarkedPage + 1) + " ページ" + (memo.isEmpty() ? "" : "  •  " + memo);
        }
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setTitle("しおり").setItems(labels, (ignored, selected) -> goToPage(ordered.get(selected)))
                .setNegativeButton("すべて削除", (ignored, which) -> { AppState.clearBookmarks(this, sourceUri); refreshQuickBookmark(); }));
        Ui.styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), Ui.ButtonStyle.DANGER);
    }

    private void showAutoPageDialog() {
        String[] labels = {"5秒ごと", "10秒ごと", "15秒ごと"};
        Ui.show(new AlertDialog.Builder(this).setTitle("自動ページ送り").setItems(labels, (dialog, selected) -> {
            autoDelayMs = new int[]{5000, 10000, 15000}[selected];
            autoHandler.removeCallbacks(autoPage);
            autoHandler.postDelayed(autoPage, autoDelayMs);
            if (readerMenuPage == 2) showReaderMenuPage(2);
        }));
    }

    private void stopAutoPage() {
        autoDelayMs = 0;
        autoHandler.removeCallbacks(autoPage);
    }

    private void capturePage() {
        if (!initialized || imageView.getWidth() == 0 || imageView.getHeight() == 0 || imageView.getDrawable() == null) {
            Toast.makeText(this, "ページの読み込み後に保存してください", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap capture = Bitmap.createBitmap(imageView.getWidth(), imageView.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(capture);
        canvas.drawColor(Ui.DARK_BACKGROUND);
        imageView.draw(canvas);
        worker.execute(() -> {
            Uri output = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "ComicExplorer_" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Comic Explorer");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                output = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (output == null) throw new IOException("保存先を作成できません。");
                try (OutputStream stream = getContentResolver().openOutputStream(output)) {
                    if (stream == null || !capture.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("画像を書き込めません。");
                }
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(output, publish, null, null);
                runOnUiThread(() -> Toast.makeText(this, "Pictures/Comic Explorer に保存しました", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                if (output != null) getContentResolver().delete(output, null, null);
                runOnUiThread(() -> Toast.makeText(this, "画像を保存できませんでした", Toast.LENGTH_SHORT).show());
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
    }

    private void toggleFullscreen() { fullScreen = !fullScreen; applyFullscreen(); }

    private void applyFullscreen() {
        Window window = getWindow();
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
            window.getDecorView().setSystemUiVisibility(layoutFlags | (fullScreen ? fullscreenFlags : 0));
        }
        window.getDecorView().requestApplyInsets();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullScreen) applyFullscreen();
    }

    private void applyDarkSystemBarIcons() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    private void showLoading(boolean show) { loading.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void showError(String message) { errorPanel.setVisibility(message == null ? View.GONE : View.VISIBLE); if (message != null) errorText.setText(message); }
    private String readableError(Throwable error) {
        if (error instanceof SecurityException) return "ファイルへのアクセス許可が失われました。ライブラリでフォルダを選び直してください。";
        if (error instanceof OutOfMemoryError) return "ページが大きすぎてメモリに収まりません。ほかのアプリを閉じるか、解像度を下げた本を使用してください。";
        return error.getMessage() == null ? "ファイルを開けません。" : error.getMessage();
    }
    @Override public void onTap(float normalizedX) {
        if (!initialized) return;
        if (normalizedX < .28f) { if (AppState.direction(this) == AppState.DIRECTION_RTL) forward(); else back(); }
        else if (normalizedX > .72f) { if (AppState.direction(this) == AppState.DIRECTION_RTL) back(); else forward(); }
        else toggleChrome();
    }

    @Override public void onSwipe(int direction) {
        if (!initialized) return;
        if (Math.abs(direction) == 2) { if (direction < 0) forward(); else back(); return; }
        if (direction < 0) { if (AppState.direction(this) == AppState.DIRECTION_RTL) back(); else forward(); }
        else { if (AppState.direction(this) == AppState.DIRECTION_RTL) forward(); else back(); }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        boolean reloadLayout = initialized && savedLayout != pageLayout;
        pageLayout = savedLayout;
        filterMode = AppState.imageFilter(this);
        imageView.setFitMode(AppState.fitMode(this));
        imageView.setDoubleTapScale(AppState.doubleTapScale(this) / 100f);
        imageView.setDoubleTapMode(AppState.doubleTapMode(this));
        imageView.setVerticalPaging(AppState.readingFlow(this) == AppState.FLOW_VERTICAL);
        imageView.setFilterMode(filterMode);
        updatePageButtons();
        applyBrightness(AppState.brightness(this));
        if (AppState.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (reloadLayout) { pageCache.evictAll(); loadPage(pageLayout == AppState.PAGE_DUAL ? page - page % 2 : page, false); }
        if (autoDelayMs > 0) autoHandler.postDelayed(autoPage, autoDelayMs);
    }

    @Override protected void onPause() {
        autoHandler.removeCallbacks(autoPage);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        loadToken++;
        stopAutoPage();
        if (imageView != null) imageView.setImageDrawable(null);
        pageCache.evictAll();
        worker.execute(this::closePdf);
        worker.shutdown();
        super.onDestroy();
    }

    static int adjacentPage(int index, boolean forward, int count) {
        int next = index + (forward ? 1 : -1);
        return next >= 0 && next < count ? next : -1;
    }

    static int bitmapSampleSize(int width, int height, int maxPixels) {
        int sample = 1;
        while ((long) Math.max(1, width / sample) * Math.max(1, height / sample) > maxPixels
                || Math.max(width / sample, height / sample) > MAX_PAGE_DIMENSION) sample *= 2;
        return sample;
    }

    static int[] pdfBitmapSize(int sourceWidth, int sourceHeight, int screenWidth, int maxPixels) {
        int width = Math.min(2048, Math.max(1080, Math.max(screenWidth, sourceWidth)));
        int height = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, Math.round(width * (sourceHeight / (double) sourceWidth))));
        double scale = Math.min(1d, Math.min(MAX_PAGE_DIMENSION / (double) Math.max(width, height),
                Math.sqrt(maxPixels / (double) ((long) width * height))));
        width = Math.max(1, (int) Math.floor(width * scale));
        height = Math.max(1, (int) Math.floor(height * scale));
        while ((long) width * height > maxPixels) { if (width >= height) width--; else height--; }
        return new int[]{width, height};
    }

    public static void main(String[] arguments) throws IOException {
        assert adjacentPage(0, true, 3) == 1;
        assert adjacentPage(0, false, 3) == -1;
        assert bitmapSampleSize(4000, 6000, 2_000_000) == 4;
        int[] tallPdf = pdfBitmapSize(1, 100_000, 1080, 2_000_000);
        assert tallPdf[0] <= MAX_PAGE_DIMENSION && tallPdf[1] <= MAX_PAGE_DIMENSION;
        assert (long) tallPdf[0] * tallPdf[1] <= 2_000_000;
        BoundedInputStream bounded = new BoundedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}), 2);
        assert bounded.read(new byte[2]) == 2;
        try { bounded.read(); assert false; } catch (IOException expected) { }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++bytesRead > maxBytes) throw new IOException("画像ページが大きすぎます（上限48MB）。");
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, maxBytes - bytesRead + 1);
            int count = super.read(buffer, offset, allowed);
            if (count > 0 && (bytesRead += count) > maxBytes) throw new IOException("画像ページが大きすぎます（上限48MB）。");
            return count;
        }

        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, maxBytes - bytesRead + 1));
            if (skipped > 0 && (bytesRead += skipped) > maxBytes) throw new IOException("画像ページが大きすぎます（上限48MB）。");
            return skipped;
        }
    }

    private void closePdf() {
        if (pdf != null) { pdf.close(); pdf = null; }
        if (pdfDescriptor != null) try { pdfDescriptor.close(); } catch (IOException ignored) { } finally { pdfDescriptor = null; }
    }

    private Button button(String label, String description) {
        Button button = Ui.button(this, label, Ui.ButtonStyle.DARK_SECONDARY);
        button.setContentDescription(description);
        return button;
    }

    private Button readerAction(String label, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(description);
        Ui.styleReaderAction(button);
        return button;
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(this, value, size, color);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

}
