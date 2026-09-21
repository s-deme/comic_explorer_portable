package jp.yaman.comicexplorer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.util.*;

/** Exposes disposable fixtures only while the isolated validation build enables it. */
public class TransferTestProvider extends DocumentsProvider {
    public static final class Second extends TransferTestProvider { }
    private static final String[] COLUMNS = {"document_id","_display_name","mime_type","flags","_size","last_modified"};
    private File root;
    public boolean onCreate() {
        try { root = new File(getContext().getCacheDir(), "transfer-fixtures").getCanonicalFile(); return root.isDirectory() || root.mkdirs(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    private File file(String id) throws FileNotFoundException {
        try {
            if (!id.equals("root") && !id.startsWith("root/")) throw new IOException("Outside fixtures");
            File file = new File(root, id.equals("root") ? "" : id.substring(5)).getCanonicalFile();
            if (!file.equals(root.getCanonicalFile()) && !file.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Outside fixtures");
            return file;
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    private String id(File file) { return "root" + file.getAbsolutePath().substring(root.getAbsolutePath().length()).replace(File.separatorChar, '/'); }
    private void row(MatrixCursor cursor, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = column.equals("document_id") ? id(file) : column.equals("_display_name") ? file.getName() : column.equals("mime_type") ? (file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream") : column.equals("_size") ? file.length() : column.equals("last_modified") ? file.lastModified() : 0;
            row.add(column, value);
        }
    }
    public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[]{"root_id"} : projection); }
    public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException { File file = file(id); if (!file.exists()) throw new FileNotFoundException(id); MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection); row(cursor, file); return cursor; }
    public Cursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection); File[] children = file(id).listFiles();
        if (children == null) throw new FileNotFoundException(id); for (File child : children) row(cursor, child); return cursor;
    }
    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!mode.equals("r") && id.contains("/target/") && id.endsWith("fail.png")) throw new FileNotFoundException("Injected write failure");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw new FileNotFoundException("Invalid name");
        File created = new File(file(parent), name);
        try { if (!(mime.equals(DocumentsContract.Document.MIME_TYPE_DIR) ? created.mkdir() : created.createNewFile())) throw new IOException("Exists"); return id(created); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
    public void deleteDocument(String id) throws FileNotFoundException { if (id.equals("root")) throw new FileNotFoundException("Root"); remove(file(id)); }
    private void remove(File file) throws FileNotFoundException { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); if (!file.delete()) throw new FileNotFoundException(file.toString()); }
    public String renameDocument(String id, String name) throws FileNotFoundException {
        File from = file(id); File to = new File(from.getParentFile(), name);
        if (name.contains("/") || name.contains("\\") || to.exists() || !from.renameTo(to)) throw new FileNotFoundException("Rename failed"); return id(to);
    }
    public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
    public DocumentsContract.Path findDocumentPath(String parent, String id) {
        ArrayList<String> parts = new ArrayList<>(); String current = "";
        for (String part : id.split("/")) { current += (current.isEmpty() ? "" : "/") + part; parts.add(current); }
        return new DocumentsContract.Path(null, parts);
    }
}
