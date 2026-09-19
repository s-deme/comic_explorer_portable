package jp.yaman.comicexplorer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns library thumbnail work and caches for one activity lifetime. */
final class LibraryThumbnails implements AutoCloseable {
    private final Activity activity;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final BitmapMemoryCache cache = new BitmapMemoryCache(12 * 1024);
    private volatile int generation;
    private volatile boolean closed;

    LibraryThumbnails(Activity activity) { this.activity = activity; }

    void clear() { generation++; cache.evictAll(); }

    void bind(ImageView view, TextView formatMark, LibraryEntry item) {
        // Each binding has an identity, including repeated binds of the same URI.
        Object binding = new Object();
        view.setTag(binding);
        view.setImageDrawable(null);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        formatMark.setVisibility(View.GONE);
        if (item.directory) { view.setImageResource(R.drawable.ic_folder); return; }
        if (AppState.number(activity, "list_type", 1) == 0) {
            view.setImageResource(ComicFile.isImage(item.name, item.mime) ? R.drawable.ic_image_file : R.drawable.ic_archive);
            return;
        }
        formatMark.setText(item.kind);
        formatMark.setVisibility(View.VISIBLE);
        File cover = AppState.coverFile(activity, item.uri);
        boolean custom = cover.isFile();
        String key = custom ? "cover:" + item.uri : item.uri + "#" + item.modified + ":" + item.size;
        view.setImageResource(custom ? R.drawable.ic_archive : R.drawable.ic_image_file);
        Bitmap cached = cache.get(key);
        if (cached != null) { display(view, cached); return; }
        if (closed) return;
        int token = generation;
        worker.execute(() -> {
            if (closed || token != generation) return;
            try {
                Bitmap bitmap = custom ? BitmapFactory.decodeFile(cover.getAbsolutePath()) : load(item, key);
                if (bitmap == null) return;
                activity.runOnUiThread(() -> {
                    if (closed || token != generation || activity.isFinishing()) return;
                    cache.put(key, bitmap);
                    if (view.getTag() == binding) display(view, bitmap);
                });
            } catch (Exception ignored) { }
        });
    }

    private Bitmap load(LibraryEntry item, String key) throws Exception {
        Bitmap bitmap = BookCache.thumbnail(activity, key);
        if (bitmap != null) return bitmap;
        if (ComicFile.isImage(item.name, item.mime))
            bitmap = activity.getContentResolver().loadThumbnail(item.uri, new Size(Ui.dp(activity, 112), Ui.dp(activity, 144)), null);
        else try (PageSource source = new PageSource(activity, item.uri, item.name, null,
                java.nio.charset.Charset.forName(ReaderOptions.ENCODINGS[Math.max(0, Math.min(ReaderOptions.ENCODINGS.length - 1, AppState.archiveEncoding(activity)))]), 240 * 480)) {
            bitmap = source.decode(0, 240);
        }
        if (bitmap != null) BookCache.thumbnail(activity, key, bitmap);
        return bitmap;
    }

    private static void display(ImageView view, Bitmap bitmap) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bitmap);
    }

    @Override public void close() { closed = true; clear(); worker.shutdownNow(); }
}
