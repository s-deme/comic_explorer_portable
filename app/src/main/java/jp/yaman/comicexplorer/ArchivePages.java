package jp.yaman.comicexplorer;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import java.io.*;
import java.util.ArrayList;

/** Additional archive formats; names are never used as filesystem paths. */
final class ArchivePages {
    final ArrayList<String> names = new ArrayList<>();
    private final File file;
    private final boolean rar;
    private static final long LIMIT = 48L * 1024 * 1024;

    ArchivePages(File file, boolean rar) throws IOException {
        this.file = file; this.rar = rar;
        try {
            if (rar) try (Archive archive = new Archive(file)) {
                for (FileHeader entry : archive.getFileHeaders())
                    if (!entry.isDirectory()) add(entry.getFileName().replace('\\', '/'));
            } else try (SevenZFile archive = openSeven()) {
                for (SevenZArchiveEntry entry : archive.getEntries())
                    if (!entry.isDirectory() && entry.getName() != null) add(entry.getName().replace('\\', '/'));
            }
        } catch (Exception e) { throw failure(e); }
        names.sort(ComicFile.NATURAL_NAME_ORDER);
    }
    private SevenZFile openSeven() throws IOException {
        return SevenZFile.builder().setFile(file).setMaxMemoryLimitKiB(64 * 1024).get();
    }
    private void add(String name) throws IOException {
        if (!ComicFile.isImage(name, null)) return;
        if (names.size() >= 20_000) throw new IOException(I18n.t(R.string.ui_the_archive_exceeds_the_limit_of_20_000_pages));
        names.add(name);
    }
    void extract(String name, File destination) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            if (rar) try (Archive archive = new Archive(file)) {
                // ponytail: replay solid archives to reach a page; cache extracted pages if profiling warrants it.
                for (FileHeader entry : archive.getFileHeaders()) {
                    if (entry.isDirectory()) continue;
                    boolean target = name.equals(entry.getFileName().replace('\\', '/'));
                    if (target && entry.getFullUnpackSize() > LIMIT) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
                    if (target || archive.getMainHeader().isSolid()) archive.extractFile(entry, limited(target ? output : new OutputStream() { public void write(int b) {} public void write(byte[] b, int o, int n) {} }));
                    if (target) return;
                }
            } else try (SevenZFile archive = openSeven()) {
                for (SevenZArchiveEntry entry : archive.getEntries()) {
                    if (entry.getName() == null || !name.equals(entry.getName().replace('\\', '/'))) continue;
                    if (entry.getSize() > LIMIT) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
                    try (InputStream input = archive.getInputStream(entry)) {
                        byte[] buffer = new byte[65536]; int count; OutputStream bounded = limited(output);
                        while ((count = input.read(buffer)) != -1) bounded.write(buffer, 0, count);
                    }
                    return;
                }
            }
            throw new IOException(I18n.t(R.string.ui_page_not_found_in_archive));
        } catch (Exception e) { throw failure(e); }
    }
    private static OutputStream limited(OutputStream output) {
        return new FilterOutputStream(output) {
            long size;
            public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
            public void write(byte[] b, int off, int len) throws IOException {
                if (Thread.currentThread().isInterrupted() || (size += len) > LIMIT) throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
                out.write(b, off, len);
            }
        };
    }
    private static IOException failure(Exception e) {
        return e instanceof IOException ? (IOException)e : new IOException(I18n.t(R.string.ui_archive_error) + " " + e.getClass().getSimpleName(), e);
    }
}
