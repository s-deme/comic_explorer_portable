package jp.yaman.comicexplorer;

import android.graphics.Bitmap;
import android.util.LruCache;

/** Size-aware bitmap cache with explicit cleanup for reader-owned page bitmaps. */
public final class BitmapMemoryCache extends LruCache<String, Bitmap> {
    public BitmapMemoryCache(int maxSizeKb) {
        super(maxSizeKb);
    }

    @Override protected int sizeOf(String key, Bitmap bitmap) {
        return Math.max(1, bitmap.getByteCount() / 1024);
    }

    public void release() {
        for (Bitmap bitmap : snapshot().values()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        evictAll();
    }
}
