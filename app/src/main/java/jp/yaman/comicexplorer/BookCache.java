package jp.yaman.comicexplorer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

/** Only manages app-owned cache files; source documents are never deleted here. */
public final class BookCache {
    private BookCache() { }
    static File directory(Context context, String kind) throws IOException {
        if (!"books".equals(kind) && !"thumbs".equals(kind)) throw new IllegalArgumentException("Unknown cache");
        File dir = new File(context.getCacheDir(), kind);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(I18n.t(R.string.ui_cannot_create_cache));
        return dir;
    }
    public static synchronized File book(Context context, Uri uri) throws IOException {
        File dir = directory(context, "books");
        trim(dir, Long.MAX_VALUE, System.currentTimeMillis() - AppState.number(context, "cache_days", 7) * 86400000L);
        String revision = "";
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) revision = cursor.getString(0) + ":" + cursor.getString(1);
        } catch (RuntimeException ignored) { }
        File file = new File(dir, AppState.key(Uri.parse(uri + "#" + revision)) + ".book");
        if (file.isFile()) { file.setLastModified(System.currentTimeMillis()); return file; }
        File temp = File.createTempFile("download-", ".part", dir);
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IOException(I18n.t(R.string.ui_cannot_open_file_2));
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException(I18n.t(R.string.ui_canceled));
                    if (dir.getUsableSpace() < count + 16 * 1024 * 1024L) throw new IOException(I18n.t(R.string.ui_not_enough_free_space));
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new IOException(I18n.t(R.string.ui_cannot_save_cache));
            return file;
        } finally { if (temp.exists()) temp.delete(); }
    }
    public static synchronized Bitmap thumbnail(Context context, String key) {
        try {
            File file = new File(directory(context, "thumbs"), AppState.key(Uri.parse(key)) + ".jpg");
            Bitmap image = BitmapFactory.decodeFile(file.getPath());
            if (image != null) file.setLastModified(System.currentTimeMillis());
            return image;
        } catch (IOException ignored) { return null; }
    }
    public static synchronized void thumbnail(Context context, String key, Bitmap bitmap) throws IOException {
        File dir = directory(context, "thumbs");
        File file = new File(dir, AppState.key(Uri.parse(key)) + ".jpg");
        File temp = File.createTempFile("thumb-", ".part", dir);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw new IOException(I18n.t(R.string.ui_cannot_save_thumbnail));
            }
            if (file.exists() && !file.delete()) throw new IOException(I18n.t(R.string.ui_cannot_update_thumbnail));
            if (!temp.renameTo(file)) throw new IOException(I18n.t(R.string.ui_cannot_save_thumbnail));
        } finally { if (temp.exists()) temp.delete(); }
        trim(dir, AppState.number(context, "cache_mb", 100) * 1024L * 1024L, 0);
    }
    static void trim(File dir, long limit, long cutoff) {
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0; for (File file : files) bytes += file.length();
        for (File file : files) if (bytes > limit || file.lastModified() < cutoff) {
            long size = file.length(); if (file.delete()) bytes -= size;
        }
    }
    public static synchronized void clear(Context context, String kind) throws IOException {
        File[] files = directory(context, kind).listFiles();
        if (files == null) throw new IOException(I18n.t(R.string.ui_cannot_read_cache));
        for (File file : files) if (file.isFile() && !file.delete()) throw new IOException(I18n.t(R.string.ui_cannot_delete_cache));
    }
    public static long size(Context context, String kind) {
        try { File[] files = directory(context, kind).listFiles(); long size = 0; if (files != null) for (File file : files) size += file.length(); return size; }
        catch (IOException ignored) { return 0; }
    }
}
