package jp.yaman.comicexplorer;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** App-owned albums reference originals; removing an album never deletes source images. */
final class Albums {
    static JSONObject read(Context context) {
        try { return new JSONObject(AppState.prefs(context).getString("albums", "{}")); }
        catch (org.json.JSONException e) { throw new IllegalStateException("Invalid album data", e); }
    }
    static void save(Context context, JSONObject albums) { AppState.prefs(context).edit().putString("albums", albums.toString()).apply(); }
    static ArrayList<LibraryEntry> list(Context context, Uri album) {
        ArrayList<LibraryEntry> result = new ArrayList<>(); JSONObject albums = read(context);
        try {
            if (album == null) {
                java.util.Iterator<String> ids = albums.keys();
                while (ids.hasNext()) { String id = ids.next(); JSONObject entry = albums.getJSONObject(id); result.add(new LibraryEntry(Uri.parse("album://" + id), entry.getString("name"), null, "", true, 0, 0)); }
            } else {
                JSONObject selected = albums.optJSONObject(album.getAuthority()); if (selected == null) return result;
                JSONArray items = selected.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) { JSONObject item = items.getJSONObject(i); result.add(new LibraryEntry(Uri.parse(item.getString("uri")), item.getString("name"), item.optString("mime", "image/*"), "画像", false, 0, 0)); }
            }
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return result;
    }
    static Uri rename(Context context, Uri album, String name) {
        try {
            JSONObject albums = read(context); String id = album == null ? java.util.UUID.randomUUID().toString() : album.getAuthority();
            JSONObject value = album == null ? new JSONObject().put("items", new JSONArray()) : albums.getJSONObject(id);
            albums.put(id, value.put("name", name)); save(context, albums); return Uri.parse("album://" + id);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    static void delete(Context context, Uri album) { JSONObject albums = read(context); albums.remove(album.getAuthority()); save(context, albums); }
    static void update(Context context, Uri album, java.util.List<LibraryEntry> changes, boolean add) {
        try {
            JSONObject albums = read(context), selected = albums.getJSONObject(album.getAuthority());
            java.util.LinkedHashMap<String, LibraryEntry> items = new java.util.LinkedHashMap<>();
            for (LibraryEntry item : list(context, album)) items.put(item.uri.toString(), item);
            for (LibraryEntry item : changes) { if (add && ComicFile.isImage(item.name, item.mime)) items.put(item.uri.toString(), item); else if (!add) items.remove(item.uri.toString()); }
            JSONArray values = new JSONArray();
            for (LibraryEntry item : items.values()) values.put(new JSONObject().put("uri", item.uri.toString()).put("name", item.name).put("mime", item.mime == null ? "image/*" : item.mime));
            selected.put("items", values); save(context, albums);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    static void relocate(Context context, Uri from, Uri to, String title) {
        for (LibraryEntry album : list(context, null)) {
            ArrayList<LibraryEntry> items = list(context, album.uri);
            for (LibraryEntry item : items) if (item.uri.equals(from)) {
                update(context, album.uri, java.util.Collections.singletonList(item), false);
                update(context, album.uri, java.util.Collections.singletonList(new LibraryEntry(to, title, item.mime, item.kind, false, 0, 0)), true);
            }
        }
    }
}
