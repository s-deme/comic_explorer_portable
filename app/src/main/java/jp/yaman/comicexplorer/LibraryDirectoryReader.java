package jp.yaman.comicexplorer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.List;

/** Reads one SAF directory without coupling file access to an activity or a screen. */
public final class LibraryDirectoryReader {
    private static final String[] CHILD_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private LibraryDirectoryReader() { }

    public static List<LibraryEntry> read(ContentResolver resolver, Uri treeUri, Uri directoryUri) {
        ArrayList<LibraryEntry> entries = new ArrayList<>();
        // ACTION_OPEN_DOCUMENT_TREE returns a tree URI for the folder the user chose.
        // It does not have a /document/ segment, so getDocumentId(treeUri) throws
        // "Invalid URI". Descendant folders use document URIs and need the other API.
        String documentId = treeUri.equals(directoryUri)
                ? DocumentsContract.getTreeDocumentId(treeUri)
                : DocumentsContract.getDocumentId(directoryUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(children, CHILD_PROJECTION, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("フォルダを読み取れません。");
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (directory || ComicFile.isSupported(name, mime)) {
                    entries.add(new LibraryEntry(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                            name,
                            mime,
                            directory ? "フォルダ" : ComicFile.kindFor(name, mime),
                            directory,
                            size,
                            modified));
                }
            }
        }
        return entries;
    }

    public static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "ライブラリ";
    }
}
