package jp.yaman.comicexplorer;

import android.net.Uri;

/** Immutable item displayed by the local library. */
public final class LibraryEntry {
    public final Uri uri;
    public final String name;
    public final String mime;
    public final String kind;
    public final boolean directory;
    public final long size;
    public final long modified;

    public LibraryEntry(Uri uri, String name, String mime, String kind, boolean directory, long size, long modified) {
        this.uri = uri;
        this.name = name == null || name.trim().isEmpty() ? "名称なし" : name;
        this.mime = mime;
        this.kind = kind;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
    }
}
