package jp.yaman.comicexplorer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Owns file handles and raw decoding. Open, decode and close on the reader's single worker. */
final class PageSource implements AutoCloseable {
    private static final int MAX_ARCHIVE_PAGES = 20_000;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 48L * 1024 * 1024;
    static final int MAX_PAGE_DIMENSION = 8192;
    private final Context context;
    private final int maxBitmapPixels;
    private final ArrayList<Uri> images;
    private ArrayList<String> archiveEntries;
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfDescriptor;
    private ZipFile archive;
    private boolean closed;
    private final int count;

    PageSource(Context context, Uri uri, String title, ArrayList<Uri> images, Charset charset, int maxBitmapPixels) throws IOException {
        this.context = context.getApplicationContext(); this.maxBitmapPixels = maxBitmapPixels;
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
        try {
            if (!this.images.isEmpty()) count = this.images.size();
            else if (ComicFile.extension(title).equals("pdf")) {
                pdfDescriptor = this.context.getContentResolver().openFileDescriptor(uri, "r");
                if (pdfDescriptor == null) throw new IOException(I18n.t(R.string.ui_cannot_open_pdf));
                pdf = new PdfRenderer(pdfDescriptor); count = pdf.getPageCount();
            } else if (ComicFile.extension(title).equals("zip") || ComicFile.extension(title).equals("cbz")) {
                archive = new ZipFile(BookCache.book(this.context, uri), charset);
                archiveEntries = readArchiveEntries(); count = archiveEntries.size();
            } else throw new IOException(I18n.t(R.string.ui_unsupported_format_choose_pdf_cbz_zip_or_images));
            if (count < 1) throw new IOException(I18n.t(R.string.ui_no_readable_pages));
        } catch (IOException | RuntimeException | Error error) {
            try { close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    int pageCount() { return count; }
    String pageName(int index) {
        if (archiveEntries != null) return archiveEntries.get(index);
        if (!images.isEmpty()) { String name=images.get(index).getLastPathSegment(); if(name!=null)return name; }
        return Integer.toString(index + 1);
    }
    Bitmap decode(int index, int screenWidth) throws IOException {
        if (closed) throw new IOException("Page source is closed");
        if (index < 0 || index >= count) throw new IOException("Page index out of range");
        if (!images.isEmpty()) return decodeUri(images.get(index));
        if (pdf != null) return renderPdfPage(index, screenWidth);
        return decodeArchivePage(archiveEntries.get(index));
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { if (pdf != null) pdf.close(); }
        finally {
            pdf = null;
            try { if (pdfDescriptor != null) pdfDescriptor.close(); }
            finally { pdfDescriptor = null; if (archive != null) { ZipFile old=archive;archive=null;old.close(); } }
        }
    }
    private ArrayList<String> readArchiveEntries() throws IOException {
        ArrayList<String> entries = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> enumeration = archive.entries();
        {
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entry.isDirectory() && ComicFile.isImage(entry.getName(), null)) {
                    if (entries.size() >= MAX_ARCHIVE_PAGES) throw new IOException(I18n.t(R.string.ui_the_archive_exceeds_the_limit_of_20_000_pages));
                    entries.add(entry.getName());
                }
            }
        }
        Collections.sort(entries, ComicFile.NATURAL_NAME_ORDER);
        return entries;
    }

    private Bitmap decodeUri(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = context.getContentResolver().openInputStream(uri);
        if (first == null) throw new IOException(I18n.t(R.string.ui_cannot_open_image));
        try (InputStream stream = first) { BitmapFactory.decodeStream(stream, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException(I18n.t(R.string.ui_the_image_is_invalid_or_unsupported));
        InputStream second = context.getContentResolver().openInputStream(uri);
        if (second == null) throw new IOException(I18n.t(R.string.ui_cannot_open_image));
        try (InputStream stream = second) { return BitmapFactory.decodeStream(stream, null, decodeOptions(bounds.outWidth, bounds.outHeight)); }
    }

    private Bitmap decodeArchivePage(String target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decodeArchiveEntry(target, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException(I18n.t(R.string.ui_invalid_image_in_archive));
        return decodeArchiveEntry(target, decodeOptions(bounds.outWidth, bounds.outHeight));
    }

    private Bitmap decodeArchiveEntry(String target, BitmapFactory.Options options) throws IOException {
        ZipEntry entry = archive.getEntry(target);
        if (entry == null) throw new IOException(I18n.t(R.string.ui_page_not_found_in_archive));
        if (entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
        try (InputStream input = archive.getInputStream(entry)) { return BitmapFactory.decodeStream(new BoundedInputStream(input, MAX_ARCHIVE_ENTRY_BYTES), null, options); }
    }

    private Bitmap renderPdfPage(int index, int screenWidth) throws IOException {
        if (pdf == null) throw new IOException(I18n.t(R.string.ui_cannot_open_pdf));
        PdfRenderer.Page current = pdf.openPage(index);
        try {
            if (current.getWidth() <= 0 || current.getHeight() <= 0) throw new IOException(I18n.t(R.string.ui_invalid_pdf_page_size));
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

    static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        BoundedInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++bytesRead > maxBytes) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, maxBytes - bytesRead + 1);
            int count = super.read(buffer, offset, allowed);
            if (count > 0 && (bytesRead += count) > maxBytes) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
            return count;
        }

        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, maxBytes - bytesRead + 1));
            if (skipped > 0 && (bytesRead += skipped) > maxBytes) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
            return skipped;
        }
    }

}
