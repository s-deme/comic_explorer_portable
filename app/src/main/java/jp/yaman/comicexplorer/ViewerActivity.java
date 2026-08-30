package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private LruBitmapCache pageCache;
    private final Runnable autoPage = new Runnable() {
        @Override public void run() {
            if (autoDelayMs <= 0 || isFinishing()) return;
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
    private boolean initialized;
    private boolean chromeVisible = true;
    private boolean fullScreen;
    private boolean inverted;

    private ZoomImageView imageView;
    private TextView titleText;
    private TextView pageText;
    private TextView errorText;
    private View chromeTop;
    private View chromeBottom;
    private View errorPanel;
    private ProgressBar loading;
    private SeekBar pageSlider;
    private Button previousButton;
    private Button nextButton;
    private Button bookmarkButton;
    private Button autoButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int maxKb = manager == null ? 20 * 1024 : Math.min(32 * 1024, manager.getMemoryClass() * 1024 / 6);
        pageCache = new LruBitmapCache(Math.max(8 * 1024, maxKb));
        getWindow().setStatusBarColor(Ui.DARK_BACKGROUND);
        getWindow().setNavigationBarColor(Ui.DARK_BACKGROUND);
        applyDarkSystemBarIcons();
        sourceUri = getIntent().getData();
        if (sourceUri == null) { finish(); return; }
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty()) title = "Comic Explorer";
        imageUris = getIntent().getParcelableArrayListExtra(EXTRA_IMAGE_URIS);
        buildUi();
        applyReaderPreferences();
        initializeSource();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.DARK_BACKGROUND);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.setBackgroundColor(Ui.DARK_SURFACE);
        Button close = button("戻る", "作品を閉じる");
        Ui.styleButton(close, Ui.ButtonStyle.DARK_GHOST);
        close.setTextSize(14);
        close.setOnClickListener(view -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(64), dp(48)));
        titleText = text(title, 16, Ui.DARK_TEXT);
        Ui.label(titleText);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleText.setPadding(dp(8), 0, dp(8), 0);
        top.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bookmarkButton = button("しおり", "このページをしおりに追加");
        bookmarkButton.setTextSize(14);
        bookmarkButton.setOnClickListener(view -> toggleBookmark());
        top.addView(bookmarkButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        Button more = button("メニュー", "読書メニューを開く");
        Ui.styleButton(more, Ui.ButtonStyle.DARK_GHOST);
        more.setTextSize(14);
        more.setOnClickListener(view -> showReaderMenu());
        top.addView(more, new LinearLayout.LayoutParams(dp(80), dp(48)));
        chromeTop = top;
        root.addView(top);

        FrameLayout canvas = new FrameLayout(this);
        imageView = new ZoomImageView(this);
        imageView.setInteractionListener(this);
        canvas.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        retry.setOnClickListener(view -> initializeSource());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        retryParams.setMargins(0, dp(16), 0, 0);
        error.addView(retry, retryParams);
        errorPanel = error;
        errorPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        errorParams.setMargins(dp(18), 0, dp(18), 0);
        canvas.addView(errorPanel, errorParams);
        root.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(12));
        bottom.setBackgroundColor(Ui.DARK_SURFACE);
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        pageText = text("読み込み中…", 15, Ui.DARK_TEXT);
        pageText.setGravity(Gravity.CENTER);
        Ui.styleDarkChip(pageText, false);
        pageText.setContentDescription("ページ番号。タップして移動");
        pageText.setOnClickListener(view -> showPageJump());
        sliderRow.addView(pageText, new LinearLayout.LayoutParams(dp(112), dp(48)));
        pageSlider = new SeekBar(this);
        pageSlider.setMax(0);
        pageSlider.setContentDescription("ページスライダー");
        Ui.styleSeekBar(pageSlider, true);
        pageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && totalPages > 0) pageText.setText((progress + 1) + " / " + totalPages);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (initialized) goToPage(bar.getProgress()); }
        });
        sliderRow.addView(pageSlider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(sliderRow);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        previousButton = button("前へ", "前のページ");
        Ui.styleButton(previousButton, Ui.ButtonStyle.DARK_PRIMARY);
        previousButton.setOnClickListener(view -> back());
        LinearLayout.LayoutParams previousParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        previousParams.setMargins(0, 0, dp(6), 0);
        buttons.addView(previousButton, previousParams);
        autoButton = button("自動送り", "自動ページ送りを設定");
        autoButton.setOnClickListener(view -> {
            if (autoDelayMs > 0) stopAutoPage(); else showAutoPageDialog();
        });
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(0, dp(48), .9f);
        autoParams.setMargins(0, 0, dp(6), 0);
        buttons.addView(autoButton, autoParams);
        nextButton = button("次へ", "次のページ");
        Ui.styleButton(nextButton, Ui.ButtonStyle.DARK_PRIMARY);
        nextButton.setOnClickListener(view -> forward());
        buttons.addView(nextButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        bottom.addView(buttons);
        chromeBottom = bottom;
        root.addView(bottom);
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
    }

    private void applyReaderPreferences() {
        if (AppState.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        imageView.setFitMode(AppState.fitMode(this));
        applyBrightness(AppState.brightness(this));
        if (AppState.startFullscreen(this)) {
            fullScreen = true;
            imageView.post(this::applyFullscreen);
        }
    }

    private void initializeSource() {
        initialized = false;
        showLoading(true);
        showError(null);
        int token = ++loadToken;
        worker.execute(() -> {
            try {
                if (imageUris != null && !imageUris.isEmpty()) {
                    type = TYPE_IMAGES;
                    totalPages = imageUris.size();
                    int start = getIntent().getIntExtra(EXTRA_START_INDEX, AppState.getPosition(this, sourceUri));
                    page = Math.max(0, Math.min(start, totalPages - 1));
                } else {
                    String extension = MainActivity.extension(title);
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
                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing()) return;
                    initialized = true;
                    pageSlider.setMax(Math.max(0, totalPages - 1));
                    updateControls();
                    loadPage(page, false);
                    if (!AppState.hasSeenReaderHint(this)) {
                        Toast.makeText(this, "左右をタップ／スワイプでページ移動、中央タップでメニューを表示します", Toast.LENGTH_LONG).show();
                        AppState.markReaderHintSeen(this);
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing()) return;
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
        try (InputStream source = input; ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) if (!entry.isDirectory() && MainActivity.isImage(entry.getName(), null)) entries.add(entry.getName());
        }
        Collections.sort(entries, new NaturalNameComparator());
        return entries;
    }

    private void loadPage(int target, boolean prefetch) {
        if (!initialized || target < 0 || target >= totalPages) return;
        String cacheKey = type + ":" + target;
        Bitmap cached = pageCache.get(cacheKey);
        if (!prefetch) {
            page = target;
            updateControls();
            showError(null);
        }
        if (cached != null) {
            if (!prefetch) displayBitmap(cached);
            if (!prefetch) prefetchAround(target);
            return;
        }
        int token = ++loadToken;
        if (!prefetch) showLoading(true);
        worker.execute(() -> {
            Bitmap bitmap = null;
            Exception failure = null;
            try { bitmap = decodePage(target); if (bitmap == null) throw new IOException("画像を読み取れません。"); }
            catch (Exception exception) { failure = exception; }
            Bitmap finalBitmap = bitmap;
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (finalBitmap != null) pageCache.put(cacheKey, finalBitmap);
                if (prefetch) return;
                if (token != loadToken) return;
                showLoading(false);
                if (finalFailure != null) showError(readableError(finalFailure));
                else {
                    displayBitmap(finalBitmap);
                    AppState.setPosition(this, sourceUri, page);
                    refreshBookmark();
                    prefetchAround(page);
                }
            });
        });
    }

    private void prefetchAround(int current) {
        int next = nextIndex(current, true);
        int previous = nextIndex(current, false);
        if (next >= 0 && pageCache.get(type + ":" + next) == null) loadPage(next, true);
        if (previous >= 0 && pageCache.get(type + ":" + previous) == null) loadPage(previous, true);
    }

    private Bitmap decodePage(int target) throws IOException {
        if (type == TYPE_IMAGES) return decodeUri(imageUris.get(target));
        if (type == TYPE_PDF) return renderPdfPage(target);
        return decodeArchivePage(archiveEntries.get(target));
    }

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
        InputStream input = getContentResolver().openInputStream(sourceUri);
        if (input == null) throw new IOException("CBZを開けません。");
        try (InputStream source = input; ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (target.equals(entry.getName())) {
                    byte[] bytes = readEntry(zip);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("CBZ内の画像が壊れています。");
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decodeOptions(bounds.outWidth, bounds.outHeight));
                }
            }
        }
        throw new IOException("CBZ内のページが見つかりません。");
    }

    private byte[] readEntry(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int count;
        int total = 0;
        while ((count = stream.read(buffer)) != -1) {
            total += count;
            if (total > 48 * 1024 * 1024) throw new IOException("画像ページが大きすぎます（上限48MB）。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private Bitmap renderPdfPage(int index) throws IOException {
        if (pdf == null) throw new IOException("PDFを開けません。");
        PdfRenderer.Page current = pdf.openPage(index);
        try {
            int screenWidth = imageView.getWidth() > 0 ? imageView.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(2048, Math.max(1080, Math.max(screenWidth, current.getWidth())));
            int height = Math.max(1, Math.round(width * (current.getHeight() / (float) current.getWidth())));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFFFFFFFF);
            current.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } finally {
            current.close();
        }
    }

    private BitmapFactory.Options decodeOptions(int width, int height) {
        int target = Math.max(1080, getResources().getDisplayMetrics().widthPixels * 2);
        int sample = 1;
        while (width / sample > target * 2 || height / sample > target * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return options;
    }

    private void displayBitmap(Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        imageView.setInverted(inverted);
    }

    private void goToPage(int target) {
        if (target < 0 || target >= totalPages || target == page && imageView.getDrawable() != null) return;
        loadPage(target, false);
    }

    private int nextIndex(int index, boolean forward) {
        int delta = forward ? (AppState.direction(this) == AppState.DIRECTION_RTL ? -1 : 1) : (AppState.direction(this) == AppState.DIRECTION_RTL ? 1 : -1);
        int next = index + delta;
        return next >= 0 && next < totalPages ? next : -1;
    }

    private void forward() { int next = nextIndex(page, true); if (next >= 0) goToPage(next); else Toast.makeText(this, "最後のページです", Toast.LENGTH_SHORT).show(); }
    private void back() { int previous = nextIndex(page, false); if (previous >= 0) goToPage(previous); else Toast.makeText(this, "最初のページです", Toast.LENGTH_SHORT).show(); }

    private void updateControls() {
        pageText.setText(totalPages > 0 ? (page + 1) + " / " + totalPages + "  •  " + Math.round((page + 1) * 100f / totalPages) + "%" : "読み込み中…");
        pageSlider.setProgress(page);
        previousButton.setEnabled(nextIndex(page, false) >= 0);
        nextButton.setEnabled(nextIndex(page, true) >= 0);
        refreshBookmark();
    }

    private void refreshBookmark() {
        if (bookmarkButton == null) return;
        boolean marked = initialized && AppState.hasBookmark(this, sourceUri, page);
        bookmarkButton.setText(marked ? "しおり済" : "しおり");
        bookmarkButton.setContentDescription(marked ? "このページのしおりを削除" : "このページをしおりに追加");
        Ui.styleButton(bookmarkButton, marked ? Ui.ButtonStyle.DARK_PRIMARY : Ui.ButtonStyle.DARK_SECONDARY);
        bookmarkButton.setTextSize(14);
    }

    private void toggleBookmark() {
        if (!initialized) return;
        boolean next = !AppState.hasBookmark(this, sourceUri, page);
        AppState.setBookmark(this, sourceUri, page, next);
        refreshBookmark();
        Toast.makeText(this, next ? "しおりに追加しました" : "しおりを削除しました", Toast.LENGTH_SHORT).show();
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
        String[] actions = {"ページへ移動", fullScreen ? "全画面を解除" : "全画面にする", "表示方法: " + fitLabel(AppState.fitMode(this)), "明るさ", inverted ? "色反転を戻す" : "色を反転", "画面回転", "しおり一覧", autoDelayMs > 0 ? "自動送りを停止" : "自動ページ送り", "復帰位置を先頭に戻す", "設定"};
        Ui.show(new AlertDialog.Builder(this).setTitle("読書メニュー").setItems(actions, (dialog, which) -> {
            switch (which) {
                case 0: showPageJump(); break;
                case 1: toggleFullscreen(); break;
                case 2: showFitDialog(); break;
                case 3: showBrightnessDialog(); break;
                case 4: inverted = !inverted; imageView.setInverted(inverted); break;
                case 5: showOrientationDialog(); break;
                case 6: showBookmarks(); break;
                case 7: if (autoDelayMs > 0) stopAutoPage(); else showAutoPageDialog(); break;
                case 8: AppState.clearPosition(this, sourceUri); goToPage(0); Toast.makeText(this, "復帰位置を先頭に戻しました", Toast.LENGTH_SHORT).show(); break;
                case 9: startActivity(new Intent(this, SettingsActivity.class)); break;
            }
        }));
    }

    private void showFitDialog() {
        String[] choices = {"画面に合わせる", "幅に合わせる", "高さに合わせる"};
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

    private void showBookmarks() {
        Set<Integer> pages = AppState.bookmarks(this, sourceUri);
        if (pages.isEmpty()) { Toast.makeText(this, "しおりはまだありません", Toast.LENGTH_SHORT).show(); return; }
        List<Integer> ordered = new ArrayList<>(pages);
        Collections.sort(ordered);
        String[] labels = new String[ordered.size()];
        for (int index = 0; index < labels.length; index++) labels[index] = (ordered.get(index) + 1) + " ページ";
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setTitle("しおり").setItems(labels, (ignored, selected) -> goToPage(ordered.get(selected)))
                .setNegativeButton("すべて削除", (ignored, which) -> { AppState.clearBookmarks(this, sourceUri); refreshBookmark(); }));
        Ui.styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), Ui.ButtonStyle.DANGER);
    }

    private void showAutoPageDialog() {
        String[] labels = {"5秒ごと", "10秒ごと", "15秒ごと"};
        Ui.show(new AlertDialog.Builder(this).setTitle("自動ページ送り").setItems(labels, (dialog, selected) -> {
            autoDelayMs = new int[]{5000, 10000, 15000}[selected];
            autoButton.setText("停止");
            autoButton.setContentDescription("自動ページ送りを停止");
            Ui.styleButton(autoButton, Ui.ButtonStyle.DARK_PRIMARY);
            autoHandler.removeCallbacks(autoPage);
            autoHandler.postDelayed(autoPage, autoDelayMs);
        }));
    }

    private void stopAutoPage() {
        autoDelayMs = 0;
        autoHandler.removeCallbacks(autoPage);
        autoButton.setText("自動送り");
        autoButton.setContentDescription("自動ページ送りを設定");
        Ui.styleButton(autoButton, Ui.ButtonStyle.DARK_SECONDARY);
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
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    private void showLoading(boolean show) { loading.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void showError(String message) { errorPanel.setVisibility(message == null ? View.GONE : View.VISIBLE); if (message != null) errorText.setText(message); }
    private String readableError(Exception error) { return error instanceof SecurityException ? "ファイルへのアクセス許可が失われました。ライブラリでフォルダを選び直してください。" : error.getMessage() == null ? "ファイルを開けません。" : error.getMessage(); }
    private String fitLabel(int mode) { return mode == AppState.FIT_WIDTH ? "幅に合わせる" : mode == AppState.FIT_HEIGHT ? "高さに合わせる" : "画面に合わせる"; }

    @Override public void onTap(float normalizedX) {
        if (!initialized) return;
        if (normalizedX < .28f) { if (AppState.direction(this) == AppState.DIRECTION_RTL) forward(); else back(); }
        else if (normalizedX > .72f) { if (AppState.direction(this) == AppState.DIRECTION_RTL) back(); else forward(); }
        else toggleChrome();
    }

    @Override public void onSwipe(int direction) {
        if (direction < 0) { if (AppState.direction(this) == AppState.DIRECTION_RTL) back(); else forward(); }
        else { if (AppState.direction(this) == AppState.DIRECTION_RTL) forward(); else back(); }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_PAGE_UP) { onSwipe(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) { onSwipe(-1); return true; }
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER) { toggleChrome(); return true; }
        if (AppState.volumeNavigation(this) && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { forward(); return true; }
        if (AppState.volumeNavigation(this) && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { back(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (!chromeVisible) toggleChrome(); else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (initialized) AppState.setPosition(this, sourceUri, page);
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopAutoPage();
        worker.shutdownNow();
        closePdf();
        pageCache.release();
        super.onDestroy();
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

    private TextView text(String value, int size, int color) {
        return Ui.text(this, value, size, color);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class LruBitmapCache extends android.util.LruCache<String, Bitmap> {
        LruBitmapCache(int size) { super(size); }
        @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024); }
        void release() {
            for (Bitmap bitmap : snapshot().values()) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            evictAll();
        }
    }

    private static final class NaturalNameComparator implements Comparator<String> {
        @Override public int compare(String left, String right) {
            int li = 0, ri = 0;
            while (li < left.length() && ri < right.length()) {
                char lc = left.charAt(li), rc = right.charAt(ri);
                if (Character.isDigit(lc) && Character.isDigit(rc)) {
                    int ls = li, rs = ri;
                    while (li < left.length() && Character.isDigit(left.charAt(li))) li++;
                    while (ri < right.length() && Character.isDigit(right.charAt(ri))) ri++;
                    try {
                        long ln = Long.parseLong(left.substring(ls, li)), rn = Long.parseLong(right.substring(rs, ri));
                        if (ln != rn) return ln < rn ? -1 : 1;
                    } catch (NumberFormatException ignored) { }
                } else {
                    int difference = Character.toLowerCase(lc) - Character.toLowerCase(rc);
                    if (difference != 0) return difference;
                    li++; ri++;
                }
            }
            return left.length() - right.length();
        }
    }
}
