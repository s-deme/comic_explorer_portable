package jp.yaman.comicexplorer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import org.json.JSONObject;

public final class NetworkDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOTS = {DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOCUMENTS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOTS : projection);
        JSONObject hosts = NetworkStorage.hosts(getContext()); java.util.Iterator<String> keys = hosts.keys();
        while (keys.hasNext()) {
            String id=keys.next(); JSONObject host=hosts.optJSONObject(id);
            cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID,id).add(DocumentsContract.Root.COLUMN_DOCUMENT_ID,id+":")
                    .add(DocumentsContract.Root.COLUMN_TITLE,host.optString("name")).add(DocumentsContract.Root.COLUMN_ICON,R.drawable.ic_folder)
                    .add(DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                    .add(DocumentsContract.Root.COLUMN_MIME_TYPES,"*/*");
        }
        return cursor;
    }
    private String root(String id) throws FileNotFoundException {
        int colon=id.indexOf(':'); if (colon<1) throw new FileNotFoundException(I18n.t(R.string.ui_invalid_connection));
        String root=id.substring(0,colon); if (!NetworkStorage.hosts(getContext()).has(root)) throw new FileNotFoundException(I18n.t(R.string.ui_connection_was_removed));
        try { NetworkStorage.validatePath(relative(id)); } catch (IllegalArgumentException e) { throw new FileNotFoundException(e.getMessage()); }
        return root;
    }
    private String relative(String id) { return id.substring(id.indexOf(':')+1); }
    private NetworkStorage.Remote remote(String id) throws Exception { return new NetworkStorage.Remote(NetworkStorage.hosts(getContext()).getJSONObject(root(id))); }
    private String child(String id, String name) { return id + (relative(id).isEmpty() ? "" : "/") + name; }
    @Override public DocumentsContract.Path findDocumentPath(String parent, String id) throws FileNotFoundException {
        String connection = root(id);
        String start = parent == null ? connection + ":" : parent;
        if (!start.equals(id) && !isChildDocument(start, id)) throw new FileNotFoundException("Outside selected folder");
        java.util.ArrayList<String> path = new java.util.ArrayList<>();
        path.add(id);
        while (!id.equals(start)) {
            String relative = relative(id); int slash = relative.lastIndexOf('/');
            id = connection + ":" + (slash < 0 ? "" : relative.substring(0, slash));
            path.add(0, id);
        }
        return new DocumentsContract.Path(parent == null ? connection : null, path);
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null ? DOCUMENTS : projection);
        try (NetworkStorage.Remote remote=remote(parent)) { for (NetworkStorage.Entry entry:remote.list(relative(parent))) add(cursor,child(parent,entry.name),entry); }
        catch(Exception e) { throw failure(e); }
        return cursor;
    }
    private NetworkStorage.Entry stat(String id, NetworkStorage.Remote remote) throws Exception {
        String path=relative(id);
        if (path.isEmpty()) return new NetworkStorage.Entry(NetworkStorage.hosts(getContext()).getJSONObject(root(id)).getString("name"),true,0,0);
        int slash=path.lastIndexOf('/'); String parent=slash<0 ? "" : path.substring(0,slash), name=path.substring(slash+1);
        for (NetworkStorage.Entry entry:remote.list(parent)) if (entry.name.equals(name)) return entry;
        throw new FileNotFoundException(I18n.t(R.string.ui_file_not_found));
    }
    private void add(MatrixCursor cursor,String id,NetworkStorage.Entry entry) {
        int flags = relative(id).isEmpty() ? 0 : DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME | DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        if (entry.directory) flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        String mime=entry.directory ? DocumentsContract.Document.MIME_TYPE_DIR : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ComicFile.extension(entry.name));
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,id).add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,entry.name)
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE,mime==null ? "application/octet-stream" : mime).add(DocumentsContract.Document.COLUMN_FLAGS,flags)
                .add(DocumentsContract.Document.COLUMN_SIZE,entry.size).add(DocumentsContract.Document.COLUMN_LAST_MODIFIED,entry.modified);
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null ? DOCUMENTS : projection);
        try (NetworkStorage.Remote remote=remote(id)) { add(cursor,id,stat(id,remote)); } catch(Exception e) { throw failure(e); }
        return cursor;
    }
    @Override public boolean isChildDocument(String parent,String id) { return id.equals(parent) || id.startsWith(parent + (relative(parent).isEmpty() ? "" : "/")); }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if (!mode.equals("r")) throw new FileNotFoundException(I18n.t(R.string.ui_writing_network_files_is_not_supported));
        try (NetworkStorage.Remote remote=remote(id)) {
            if (signal!=null) signal.throwIfCanceled();
            NetworkStorage.Entry entry=stat(id,remote); if (entry.directory) throw new FileNotFoundException(I18n.t(R.string.ui_cannot_open_a_folder_as_a_file));
            File directory=BookCache.directory(getContext(),"books");
            File file=new File(directory, AppState.key(android.net.Uri.parse(id+"#"+entry.size+":"+entry.modified))+".remote");
            synchronized(NetworkDocumentsProvider.class) {
                if (!file.isFile()) {
                    File temporary=File.createTempFile("remote-", ".part", directory);
                    try { remote.download(relative(id),temporary); if(signal!=null) signal.throwIfCanceled(); if(!temporary.renameTo(file)) throw new java.io.IOException(I18n.t(R.string.ui_cannot_save)); }
                    finally { if(temporary.exists()) temporary.delete(); }
                }
            }
            file.setLastModified(System.currentTimeMillis()); return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        } catch(Exception e) { throw failure(e); }
    }
    private void checkName(String name) throws FileNotFoundException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains("\r") || name.contains("\n") || name.indexOf(0)>=0) throw new FileNotFoundException(I18n.t(R.string.ui_invalid_name));
    }
    private void checkAvailable(NetworkStorage.Remote remote,String parent,String name) throws Exception {
        checkName(name); for(NetworkStorage.Entry item:remote.list(parent)) if(item.name.equalsIgnoreCase(name)) throw new java.io.IOException(I18n.t(R.string.ui_an_item_with_that_name_already_exists));
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        if(!mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) throw new FileNotFoundException(I18n.t(R.string.ui_only_folders_can_be_created));
        try(NetworkStorage.Remote remote=remote(parent)) { checkAvailable(remote,relative(parent),name); String id=child(parent,name); remote.mkdir(relative(id)); return id; } catch(Exception e) { throw failure(e); }
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        try(NetworkStorage.Remote remote=remote(id)) { remote.delete(relative(id),stat(id,remote).directory); } catch(Exception e) { throw failure(e); }
    }
    @Override public String renameDocument(String id,String name) throws FileNotFoundException {
        try(NetworkStorage.Remote remote=remote(id)) {
            String path=relative(id); int slash=path.lastIndexOf('/'); String parent=slash<0 ? "" : path.substring(0,slash);
            checkAvailable(remote,parent,name); String to=(parent.isEmpty()?"":parent+"/")+name;
            remote.rename(path,to,stat(id,remote).directory); return root(id)+":"+to;
        } catch(Exception e) { throw failure(e); }
    }
    @Override public String moveDocument(String id,String oldParent,String target) throws FileNotFoundException {
        if(!root(id).equals(root(target))) throw new FileNotFoundException(I18n.t(R.string.ui_cannot_move_between_connections));
        if(isChildDocument(id,target)) throw new FileNotFoundException(I18n.t(R.string.ui_cannot_move_a_folder_into_itself));
        try(NetworkStorage.Remote remote=remote(id)) {
            NetworkStorage.Entry entry=stat(id,remote); checkAvailable(remote,relative(target),entry.name); String to=child(target,entry.name);
            remote.rename(relative(id),relative(to),entry.directory); return to;
        } catch(Exception e) { throw failure(e); }
    }
    private FileNotFoundException failure(Exception error) { return new FileNotFoundException(error.getMessage()==null ? I18n.t(R.string.ui_cannot_connect) : error.getMessage()); }
}
