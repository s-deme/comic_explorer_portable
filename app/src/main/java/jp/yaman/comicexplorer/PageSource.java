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
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipMethod;

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
    private ArchivePages extraArchive;
    private java.io.File decryptedPdf;
    final ArrayList<Chapter> chapters=new ArrayList<>();
    static final class Chapter {
        final String title; final int page;
        Chapter(String title,int page){this.title=title;this.page=page;}
    }
    private boolean closed;
    private final java.util.LinkedHashMap<Integer,android.graphics.Movie> movies=new java.util.LinkedHashMap<Integer,android.graphics.Movie>(4,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer,android.graphics.Movie> entry){return size()>4;}
    };
    private final int count;

    PageSource(Context context, Uri uri, String title, ArrayList<Uri> images, Charset charset, int maxBitmapPixels) throws IOException {
        this(context,uri,title,images,charset,maxBitmapPixels,null,java.util.Collections.emptyMap());
    }
    PageSource(Context context, Uri uri, String title, ArrayList<Uri> images, Charset charset, int maxBitmapPixels,String password,java.util.Map<String,java.io.File> volumes) throws IOException {
        this.context = context.getApplicationContext(); this.maxBitmapPixels = maxBitmapPixels;
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
        try {
            String format = ComicFile.formatExtension(title, this.context.getContentResolver().getType(uri));
            if (!this.images.isEmpty()) count = this.images.size();
            else if (format.equals("pdf")) {
                java.io.File pdfFile=readPdf(BookCache.book(this.context,uri),password);
                pdfDescriptor = ParcelFileDescriptor.open(pdfFile,ParcelFileDescriptor.MODE_READ_ONLY);
                if (pdfDescriptor == null) throw new IOException(I18n.t(R.string.ui_cannot_open_pdf));
                pdf = new PdfRenderer(pdfDescriptor); count = pdf.getPageCount();
                if(decryptedPdf!=null && decryptedPdf.delete())decryptedPdf=null;
            } else if (ComicFile.isArchive(format, null)) {
                java.io.File cached=BookCache.book(this.context,uri);
                if((format.equals("zip") || format.equals("cbz")) && password==null) {
                    try {
                        archive=ZipFile.builder().setFile(cached).setCharset(charset).get();
                        java.util.Enumeration<ZipArchiveEntry> entries=archive.getEntries();
                        while(entries.hasMoreElements()) {
                            ZipArchiveEntry entry=entries.nextElement();
                            if(entry.isDirectory() || !ComicFile.isImage(entry.getName(),null))continue;
                            if(requiresSevenZip(entry)) {
                                if(!supportsSevenZipZipMethod(entry.getMethod()))throw new UnsupportedZipMethod(entry.getMethod());
                                archive.close();archive=null;break;
                            }
                        }
                    }catch(UnsupportedZipMethod unsupported){
                        if(archive!=null)archive.close();archive=null;throw unsupported;
                    }catch(IOException unsupported){if(archive!=null)archive.close();archive=null;}
                }
                java.util.Map<String,java.io.File> available=new java.util.HashMap<>(volumes);
                if("file".equals(uri.getScheme())) {
                    java.io.File parent=new java.io.File(uri.getPath()).getParentFile();
                    java.io.File[] siblings=parent==null ? null : parent.listFiles();
                    if(siblings!=null)for(java.io.File sibling:siblings)if(sibling.isFile())available.put(sibling.getName(),sibling);
                }
                if(archive==null){extraArchive = new ArchivePages(cached,title,password,available);archiveEntries=extraArchive.names;}
                else archiveEntries=readArchiveEntries();
                count = archiveEntries.size();
            } else throw new IOException(I18n.t(R.string.ui_unsupported_format_choose_pdf_cbz_zip_or_images));
            if (count < 1) throw new IOException(I18n.t(R.string.ui_no_readable_pages)+" "+title);
        } catch (IOException | RuntimeException | Error error) {
            try { close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    int pageCount() { return count; }
    static boolean requiresSevenZip(ZipArchiveEntry entry) {
        return entry.getGeneralPurposeBit().usesEncryption() || !supportsDirectZipMethod(entry.getMethod());
    }
    private static boolean supportsDirectZipMethod(int method) {
        return method==ZipMethod.STORED.getCode() || method==ZipMethod.UNSHRINKING.getCode()
                || method==ZipMethod.IMPLODING.getCode() || method==ZipMethod.DEFLATED.getCode() || method==ZipMethod.ENHANCED_DEFLATED.getCode()
                || method==ZipMethod.BZIP2.getCode() || method==ZipMethod.ZSTD_DEPRECATED.getCode() || method==ZipMethod.ZSTD.getCode()
                || method==ZipMethod.XZ.getCode();
    }
    static boolean supportsSevenZipZipMethod(int method) {
        return method==ZipMethod.STORED.getCode() || method==ZipMethod.UNSHRINKING.getCode() || method==ZipMethod.IMPLODING.getCode()
                || method==ZipMethod.DEFLATED.getCode() || method==ZipMethod.ENHANCED_DEFLATED.getCode() || method==ZipMethod.BZIP2.getCode()
                || method==ZipMethod.XZ.getCode() || method==ZipMethod.LZMA.getCode() || method==98 || method==99;
    }
    static final class UnsupportedZipMethod extends IOException {
        UnsupportedZipMethod(int method) { super(I18n.t(R.string.ui_zip_compression_method_not_supported)+" ("+method+")"); }
    }
    boolean isLandscape(int index) throws IOException {
        if (closed) throw new IOException("Page source is closed");
        if (pdf != null) {
            try (PdfRenderer.Page page = pdf.openPage(index)) { return page.getWidth() > page.getHeight(); }
        }
        java.io.File temporary = null;
        try {
            Uri uri = images.isEmpty() ? null : images.get(index);
            if (extraArchive != null) {
                temporary = java.io.File.createTempFile("page-bounds-", ".image", context.getCacheDir());
                extraArchive.extract(archiveEntries.get(index), temporary);
                uri = Uri.fromFile(temporary);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = openImage(uri, uri == null ? archiveEntries.get(index) : null)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            return bounds.outWidth > bounds.outHeight && bounds.outHeight > 0;
        } finally { if (temporary != null) temporary.delete(); }
    }
    private java.io.File readPdf(java.io.File file,String password) throws IOException {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);
        try(com.tom_roush.pdfbox.pdmodel.PDDocument document=com.tom_roush.pdfbox.pdmodel.PDDocument.load(file,password==null ? "" : password,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))) {
            com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline outline=document.getDocumentCatalog().getDocumentOutline();
            java.util.ArrayDeque<com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem> pending=new java.util.ArrayDeque<>();
            java.util.Set<Object> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            if(outline!=null && outline.getFirstChild()!=null)pending.push(outline.getFirstChild());
            while(!pending.isEmpty() && seen.size()<5000) {
                com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem item=pending.pop();
                if(!seen.add(item.getCOSObject()))continue;
                try {
                    com.tom_roush.pdfbox.pdmodel.PDPage target=item.findDestinationPage(document);
                    int index=target==null ? -1 : document.getPages().indexOf(target);
                    if(index>=0 && item.getTitle()!=null)chapters.add(new Chapter(item.getTitle(),index));
                }catch(IOException malformedDestination){/* Keep readable pages and other outline entries. */}
                if(item.getNextSibling()!=null)pending.push(item.getNextSibling());
                if(item.getFirstChild()!=null)pending.push(item.getFirstChild());
            }
            if(document.isEncrypted()) {
                decryptedPdf=java.io.File.createTempFile("reader-pdf-",".pdf",context.getCacheDir());
                document.setAllSecurityToBeRemoved(true);document.save(decryptedPdf);return decryptedPdf;
            }
            return file;
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException error){throw new ArchivePages.PasswordRequired();}
    }
    String pageName(int index) {
        if (archiveEntries != null) return archiveEntries.get(index);
        if (!images.isEmpty()) { String name=images.get(index).getLastPathSegment(); if(name!=null)return name; }
        return Integer.toString(index + 1);
    }
    Bitmap decode(int index, int screenWidth) throws IOException {
        return decode(index,screenWidth,-1);
    }
    boolean isGif(int index) {
        return pageName(index).toLowerCase(java.util.Locale.ROOT).endsWith(".gif") || !images.isEmpty() && "image/gif".equals(context.getContentResolver().getType(images.get(index)));
    }
    Bitmap decode(int index,int screenWidth,long milliseconds) throws IOException {
        if (closed) throw new IOException("Page source is closed");
        if (index < 0 || index >= count) throw new IOException("Page index out of range");
        if(milliseconds>=0 && isGif(index)) {
            android.graphics.Movie movie=movies.get(index);
            if(movie==null) {
                java.io.File temp=null;
                try {
                    InputStream input;
                    if(!images.isEmpty())input=context.getContentResolver().openInputStream(images.get(index));
                    else if(archive!=null)input=archive.getInputStream(archive.getEntry(archiveEntries.get(index)));
                    else {temp=java.io.File.createTempFile("gif-page-",".gif",context.getCacheDir());extraArchive.extract(archiveEntries.get(index),temp);input=new java.io.FileInputStream(temp);}
                    if(input==null)throw new IOException("Missing GIF");
                    try(InputStream limited=new BoundedInputStream(input,MAX_ARCHIVE_ENTRY_BYTES);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
                        StreamCopy.copy(limited, bytes, null);
                        byte[] encoded=bytes.toByteArray();movie=android.graphics.Movie.decodeByteArray(encoded,0,encoded.length);
                    }
                    if(movie==null || movie.width()<1 || movie.height()<1)throw new IOException("Invalid GIF");
                    movies.put(index,movie);
                } finally {if(temp!=null)temp.delete();}
            }
            movie.setTime((int)(milliseconds%Math.max(1,movie.duration())));
            int sample=bitmapSampleSize(movie.width(),movie.height(),maxBitmapPixels);
            Bitmap frame=Bitmap.createBitmap(Math.max(1,movie.width()/sample),Math.max(1,movie.height()/sample),Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(frame);canvas.scale(1f/sample,1f/sample);movie.draw(canvas,0,0);return frame;
        }
        if (!images.isEmpty()) return decodeImage(images.get(index), null);
        if (pdf != null) return renderPdfPage(index, screenWidth);
        if (extraArchive != null) {
            java.io.File temporary = java.io.File.createTempFile("archive-page-", ".image", context.getCacheDir());
            try { extraArchive.extract(archiveEntries.get(index), temporary); return decodeImage(Uri.fromFile(temporary), null); }
            finally { temporary.delete(); }
        }
        return decodeImage(null, archiveEntries.get(index));
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        movies.clear();
        try { if (pdf != null) pdf.close(); }
        finally {
            pdf = null;
            try { if (pdfDescriptor != null) pdfDescriptor.close(); }
            finally {
                pdfDescriptor = null;
                try {if(extraArchive!=null){extraArchive.close();extraArchive=null;}}
                finally {if(decryptedPdf!=null)decryptedPdf.delete();if (archive != null) { ZipFile old=archive;archive=null;old.close(); }}
            }
        }
    }
    private ArrayList<String> readArchiveEntries() throws IOException {
        ArrayList<String> entries = new ArrayList<>();
        java.util.Enumeration<ZipArchiveEntry> enumeration = archive.getEntries();
        {
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                if (!entry.isDirectory() && ComicFile.isImage(entry.getName(), null)) {
                    if (entries.size() >= MAX_ARCHIVE_PAGES) throw new IOException(I18n.t(R.string.ui_the_archive_exceeds_the_limit_of_20_000_pages));
                    entries.add(entry.getName());
                }
            }
        }
        Collections.sort(entries, ComicFile.NATURAL_NAME_ORDER);
        return entries;
    }

    private Bitmap decodeImage(Uri uri, String target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = openImage(uri, target)) { BitmapFactory.decodeStream(input, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException(I18n.t(uri == null ? R.string.ui_invalid_image_in_archive : R.string.ui_the_image_is_invalid_or_unsupported));
        try (InputStream input = openImage(uri, target)) {
            return BitmapFactory.decodeStream(input, null, decodeOptions(bounds.outWidth, bounds.outHeight));
        }
    }

    private InputStream openImage(Uri uri, String target) throws IOException {
        if (uri != null) {
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException(I18n.t(R.string.ui_cannot_open_image));
            return input;
        }
        ZipArchiveEntry entry = archive.getEntry(target);
        if (entry == null) throw new IOException(I18n.t(R.string.ui_page_not_found_in_archive));
        if (entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
        return new BoundedInputStream(archive.getInputStream(entry), MAX_ARCHIVE_ENTRY_BYTES);
    }

    private Bitmap renderPdfPage(int index, int screenWidth) throws IOException {
        if (pdf == null) throw new IOException(I18n.t(R.string.ui_cannot_open_pdf));
        try (PdfRenderer.Page current = pdf.openPage(index)) {
            if (current.getWidth() <= 0 || current.getHeight() <= 0) throw new IOException(I18n.t(R.string.ui_invalid_pdf_page_size));
            int[] size = pdfBitmapSize(current.getWidth(), current.getHeight(), screenWidth, maxBitmapPixels);
            Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFFFFFFFF);
            current.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
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

    static double bitmapScale(int width, int height, int maxPixels) {
        return Math.min(1d, Math.min(MAX_PAGE_DIMENSION / (double) Math.max(width, height),
                Math.sqrt(maxPixels / (double) ((long) width * height))));
    }

    static int[] pdfBitmapSize(int sourceWidth, int sourceHeight, int screenWidth, int maxPixels) {
        int width = Math.min(2048, Math.max(1080, Math.max(screenWidth, sourceWidth)));
        int height = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, Math.round(width * (sourceHeight / (double) sourceWidth))));
        double scale = bitmapScale(width, height, maxPixels);
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
