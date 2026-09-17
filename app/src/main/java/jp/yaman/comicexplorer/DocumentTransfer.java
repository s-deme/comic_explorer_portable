package jp.yaman.comicexplorer;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Copy, verify, then remove the source for moves. Never overwrite an existing document in place. */
final class DocumentTransfer {
    static final int KEEP_BOTH = 0, REPLACE = 1, SKIP = 2;
    private final Context context;
    private int entries;
    DocumentTransfer(Context context) { this.context = context; }

    static void rename(Context context, LibraryEntry item, String name) throws Exception {
        DocumentsContract.Path path = DocumentsContract.findDocumentPath(context.getContentResolver(), item.uri);
        if (path == null || path.getPath().size() < 2) throw new IOException(I18n.t(R.string.ui_cannot_rename));
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(item.uri, path.getPath().get(path.getPath().size() - 2));
        for (LibraryEntry sibling : LibraryDirectoryReader.read(context.getContentResolver(), parent, parent, false))
            if (!sameDocument(item.uri, sibling.uri) && sibling.name.equalsIgnoreCase(name)) throw new IOException(I18n.t(R.string.ui_an_item_with_that_name_already_exists));
        LinkedHashMap<String, LibraryEntry> before = new LinkedHashMap<>(); snapshot(context, item, "", before, 0);
        Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), item.uri, name);
        if (renamed == null) throw new IOException(I18n.t(R.string.ui_cannot_rename));
        LinkedHashMap<String, LibraryEntry> after = new LinkedHashMap<>();
        snapshot(context, new LibraryEntry(renamed, name, item.mime, item.kind, item.directory, item.size, item.modified), "", after, 0);
        for (Map.Entry<String, LibraryEntry> entry : before.entrySet()) {
            LibraryEntry target = after.get(entry.getKey());
            if (target != null) AppState.relocate(context, entry.getValue().uri, target.uri, target.name);
        }
    }
    private static void snapshot(Context context, LibraryEntry item, String relative, Map<String, LibraryEntry> items, int depth) throws Exception {
        if (depth > 64 || items.size() >= 20000) throw new IOException(I18n.t(R.string.ui_transfer_limit));
        items.put(relative, item);
        if (item.directory) for (LibraryEntry child : LibraryDirectoryReader.read(context.getContentResolver(), item.uri, item.uri, false)) snapshot(context, child, relative + "/" + child.name, items, depth + 1);
    }

    Uri transfer(LibraryEntry item, Uri parent, boolean move, int conflict) throws Exception {
        validateDestination(item.uri, parent, item.directory);
        List<LibraryEntry> siblings = LibraryDirectoryReader.read(context.getContentResolver(), parent, parent, false);
        LibraryEntry existing = null;
        for (LibraryEntry sibling : siblings) if (sibling.name.equalsIgnoreCase(item.name)) { existing = sibling; break; }
        if (existing != null && sameDocument(item.uri, existing.uri)) throw new IOException(I18n.t(R.string.ui_same_destination));
        if (existing != null && conflict == SKIP) return null;
        String name = item.name;
        if (existing != null && conflict == KEEP_BOTH) {
            Set<String> used = new HashSet<>(); for (LibraryEntry sibling : siblings) used.add(sibling.name.toLowerCase(Locale.ROOT));
            name = availableName(item.name, used);
        }
        // A replacement is staged under a distinct name so a failed copy leaves the old file intact.
        String stagedName = existing != null && conflict == REPLACE ? ".comic-copy-" + UUID.randomUUID() + "-" + name : name;
        LinkedHashMap<LibraryEntry, Uri> mapping = new LinkedHashMap<>(); entries = 0;
        Uri copied = copyTree(item, parent, stagedName, mapping, new HashSet<>(), 0);
        if (existing != null && conflict == REPLACE) {
            Uri backup = DocumentsContract.renameDocument(context.getContentResolver(), existing.uri, ".comic-backup-" + UUID.randomUUID() + "-" + existing.name);
            if (backup == null) { DocumentsContract.deleteDocument(context.getContentResolver(), copied); throw new IOException(I18n.t(R.string.ui_cannot_rename)); }
            try {
                Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), copied, name);
                if (renamed == null) throw new IOException(I18n.t(R.string.ui_cannot_rename));
                mapping = remapCopiedTree(item, renamed);
                copied = renamed;
            } catch (Exception error) {
                try { DocumentsContract.renameDocument(context.getContentResolver(), backup, existing.name); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), backup)) throw new IOException(I18n.t(R.string.ui_cannot_delete));
        }
        if (move) {
            verifyTree(item, copied, 0);
            if (Thread.currentThread().isInterrupted()) throw new IOException(I18n.t(R.string.ui_source_retained));
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), item.uri)) throw new IOException(I18n.t(R.string.ui_source_retained));
            for (Map.Entry<LibraryEntry, Uri> entry : mapping.entrySet()) AppState.relocate(context, entry.getKey().uri, entry.getValue(), entry.getKey() == item ? name : entry.getKey().name);
        }
        return copied;
    }
    private LinkedHashMap<LibraryEntry, Uri> remapCopiedTree(LibraryEntry root, Uri newRoot) throws Exception {
        LinkedHashMap<LibraryEntry, Uri> result = new LinkedHashMap<>();
        mapTree(root, newRoot, result, 0); return result;
    }
    private void verifyTree(LibraryEntry source, Uri destination, int depth) throws Exception {
        if (depth > 64) throw new IOException(I18n.t(R.string.ui_transfer_limit));
        if (!source.directory) {
            try (InputStream from = openFresh(source.uri); InputStream to = openFresh(destination)) {
                if (!MessageDigest.isEqual(copyAndHash(from, null, null), copyAndHash(to, null, null))) throw new IOException(I18n.t(R.string.ui_verification_failed));
            }
            return;
        }
        List<LibraryEntry> before = LibraryDirectoryReader.read(context.getContentResolver(), source.uri, source.uri, false);
        List<LibraryEntry> after = LibraryDirectoryReader.read(context.getContentResolver(), destination, destination, false);
        if (before.size() != after.size()) throw new IOException(I18n.t(R.string.ui_verification_failed));
        for (LibraryEntry child : before) {
            LibraryEntry match = null; for (LibraryEntry candidate : after) if (candidate.name.equals(child.name) && candidate.directory == child.directory) { match = candidate; break; }
            if (match == null) throw new IOException(I18n.t(R.string.ui_verification_failed));
            verifyTree(child, match.uri, depth + 1);
        }
    }
    private InputStream openFresh(Uri uri) throws Exception {
        if (!uri.getAuthority().equals(context.getPackageName() + ".network")) return context.getContentResolver().openInputStream(uri);
        File temporary = File.createTempFile("transfer-read-", ".part", context.getCacheDir());
        try {
            NetworkDocumentsProvider.download(context, uri, temporary);
            return new FilterInputStream(new FileInputStream(temporary)) {
                public void close() throws IOException { try { super.close(); } finally { temporary.delete(); } }
            };
        } catch (Exception error) { temporary.delete(); throw error; }
    }
    private void mapTree(LibraryEntry source, Uri destination, Map<LibraryEntry, Uri> result, int depth) throws Exception {
        if (depth > 64) throw new IOException(I18n.t(R.string.ui_transfer_limit));
        result.put(source, destination);
        if (!source.directory) return;
        List<LibraryEntry> children = LibraryDirectoryReader.read(context.getContentResolver(), destination, destination, false);
        for (LibraryEntry item : LibraryDirectoryReader.read(context.getContentResolver(), source.uri, source.uri, false)) {
            LibraryEntry match = null; for (LibraryEntry child : children) if (child.name.equals(item.name)) { match = child; break; }
            if (match == null) throw new IOException(I18n.t(R.string.ui_verification_failed));
            mapTree(item, match.uri, result, depth + 1);
        }
    }
    private Uri copyTree(LibraryEntry item, Uri parent, String name, Map<LibraryEntry, Uri> mapping, Set<String> visited, int depth) throws Exception {
        if (++entries > 20000 || depth > 64 || Thread.currentThread().isInterrupted() || !visited.add(item.uri.toString())) throw new IOException(I18n.t(R.string.ui_transfer_limit));
        String mime = item.directory ? DocumentsContract.Document.MIME_TYPE_DIR : item.mime;
        if (mime == null) mime = "application/octet-stream";
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
        if (created == null) throw new IOException(I18n.t(R.string.ui_cannot_save));
        try {
            if (item.directory) {
                for (LibraryEntry child : LibraryDirectoryReader.read(context.getContentResolver(), item.uri, item.uri, false)) copyTree(child, created, child.name, mapping, visited, depth + 1);
            } else copyFile(item.uri, created);
            mapping.put(item, created); return created;
        } catch (Exception error) {
            try { if (!DocumentsContract.deleteDocument(context.getContentResolver(), created)) error.addSuppressed(new IOException("Partial destination retained: " + created)); }
            catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    private void copyFile(Uri source, Uri destination) throws Exception {
        File temporary = File.createTempFile("transfer-", ".part", context.getCacheDir());
        try {
            byte[] expected;
            try (InputStream input = openFresh(source); FileOutputStream output = new FileOutputStream(temporary)) {
                expected = copyAndHash(input, output, temporary); output.getFD().sync();
            }
            if (destination.getAuthority().equals(context.getPackageName() + ".network")) {
                NetworkDocumentsProvider.upload(context, destination, temporary);
            } else try (InputStream input = new FileInputStream(temporary); OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException(I18n.t(R.string.ui_cannot_save));
                copyAndHash(input, output, null);
            }
            try (InputStream input = openFresh(destination)) {
                byte[] actual = copyAndHash(input, null, null);
                if (!MessageDigest.isEqual(expected, actual)) throw new IOException(I18n.t(R.string.ui_verification_failed));
            }
        } finally { temporary.delete(); }
    }
    static byte[] copyAndHash(InputStream input, OutputStream output, File space) throws Exception {
        if (input == null) throw new IOException(I18n.t(R.string.ui_cannot_open_file_2));
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException(I18n.t(R.string.ui_canceled));
            if (space != null && space.getParentFile().getUsableSpace() < count + 16 * 1024 * 1024L) throw new IOException(I18n.t(R.string.ui_not_enough_free_space));
            digest.update(buffer, 0, count); if (output != null) output.write(buffer, 0, count);
        }
        return digest.digest();
    }
    private void validateDestination(Uri source, Uri target, boolean directory) throws Exception {
        if (sameDocument(source, target)) throw new IOException(I18n.t(R.string.ui_same_destination));
        if (!directory || !Objects.equals(source.getAuthority(), target.getAuthority())) return;
        DocumentsContract.Path path = DocumentsContract.findDocumentPath(context.getContentResolver(), target);
        if (path == null) throw new IOException(I18n.t(R.string.ui_cannot_verify_destination));
        if (path.getPath().contains(DocumentsContract.getDocumentId(source))) throw new IOException(I18n.t(R.string.ui_cannot_move_a_folder_into_itself));
    }
    static boolean sameDocument(Uri left, Uri right) {
        return Objects.equals(left.getAuthority(), right.getAuthority()) && DocumentsContract.getDocumentId(left).equals(DocumentsContract.getDocumentId(right));
    }
    static String availableName(String name, Set<String> used) {
        int dot = name.lastIndexOf('.'); if (dot <= 0) dot = name.length();
        for (int i = 1; ; i++) { String candidate = name.substring(0, dot) + " (" + i + ")" + name.substring(dot); if (!used.contains(candidate.toLowerCase(Locale.ROOT))) return candidate; }
    }
}
