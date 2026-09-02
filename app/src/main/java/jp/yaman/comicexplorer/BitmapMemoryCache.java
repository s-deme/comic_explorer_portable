package jp.yaman.comicexplorer;

import android.graphics.Bitmap;
import android.util.LruCache;

/** Size-aware bitmap cache for reader-owned page bitmaps. */
public final class BitmapMemoryCache extends LruCache<String, Bitmap> {
    public BitmapMemoryCache(int maxSizeKb) {
        super(maxSizeKb);
    }

    @Override protected int sizeOf(String key, Bitmap bitmap) {
        return Math.max(1, bitmap.getByteCount() / 1024);
    }
}
