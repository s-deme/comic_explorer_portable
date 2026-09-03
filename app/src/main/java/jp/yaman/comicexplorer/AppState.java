package jp.yaman.comicexplorer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All user data is kept locally in private SharedPreferences. */
public final class AppState {
    private static final String PREFS = "comic_explorer";
    private static final String TREE_URI = "library.tree";
    private static final String FAVORITES = "library.favorites";
    private static final String BOOKMARKED_ITEMS = "library.bookmarked_items";
    private static final int RECENT_LIMIT = 100;

    public static final int DIRECTION_LTR = 0;
    public static final int DIRECTION_RTL = 1;
    public static final int FIT_SCREEN = 0;
    public static final int FIT_WIDTH = 1;
    public static final int FIT_HEIGHT = 2;
    public static final int FIT_STRETCH = 3;
    public static final int FLOW_HORIZONTAL = 0;
    public static final int FLOW_VERTICAL = 1;
    public static final int PAGE_SINGLE = 0;
    public static final int PAGE_DUAL = 1;
    public static final int FILTER_NONE = 0;
    public static final int FILTER_GRAYSCALE = 1;
    public static final int FILTER_CONTRAST = 2;
    public static final int FILTER_SEPIA = 3;
    public static final int FILTER_BLUE_LIGHT = 4;
    public static final int DOUBLE_TAP_OFF = 0;
    public static final int DOUBLE_TAP_ZOOM = 1;
    public static final int DOUBLE_TAP_FIT = 2;
    public static final int DOUBLE_TAP_TOGGLE = 3;

    private AppState() { }

    public static final class SavedItem {
        public final Uri uri;
        public final String title;
        public final String kind;
        public final long timestamp;
        public final int position;
        public final int totalPages;

        SavedItem(Uri uri, String title, String kind, long timestamp, int position, int totalPages) {
            this.uri = uri;
            this.title = title;
            this.kind = kind;
            this.timestamp = timestamp;
            this.position = Math.max(0, position);
            this.totalPages = Math.max(0, totalPages);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Uri getTree(Context context) {
        String raw = prefs(context).getString(TREE_URI, null);
        return raw == null ? null : Uri.parse(raw);
    }

    public static void setTree(Context context, Uri uri) {
        prefs(context).edit().putString(TREE_URI, uri == null ? null : uri.toString()).apply();
    }

    public static boolean isFavorite(Context context, Uri uri) {
        return prefs(context).getStringSet(FAVORITES, Collections.<String>emptySet()).contains(uri.toString());
    }

    public static void setFavorite(Context context, Uri uri, String title, String kind, boolean favorite) {
        SharedPreferences pref = prefs(context);
        Set<String> values = new HashSet<>(pref.getStringSet(FAVORITES, Collections.<String>emptySet()));
        String raw = uri.toString();
        SharedPreferences.Editor editor = pref.edit();
        if (favorite) {
            values.add(raw);
            editor.putString("favorite." + key(uri) + ".title", title);
            editor.putString("favorite." + key(uri) + ".kind", kind);
            editor.putLong("favorite." + key(uri) + ".time", System.currentTimeMillis());
        } else {
            values.remove(raw);
            editor.remove("favorite." + key(uri) + ".title");
            editor.remove("favorite." + key(uri) + ".kind");
            editor.remove("favorite." + key(uri) + ".time");
        }
        editor.putStringSet(FAVORITES, values).apply();
    }

    public static List<SavedItem> favorites(Context context) {
        SharedPreferences pref = prefs(context);
        ArrayList<SavedItem> result = new ArrayList<>();
        for (String raw : pref.getStringSet(FAVORITES, Collections.<String>emptySet())) {
            Uri uri = Uri.parse(raw);
            String id = key(uri);
            result.add(new SavedItem(uri,
                    pref.getString("favorite." + id + ".title", "名称なし"),
                    pref.getString("favorite." + id + ".kind", "ファイル"),
                    pref.getLong("favorite." + id + ".time", 0),
                    getPosition(context, uri), totalPages(context, uri)));
        }
        Collections.sort(result, (left, right) -> Long.compare(right.timestamp, left.timestamp));
        return result;
    }

    public static void addRecent(Context context, Uri uri, String title, String kind) {
        ArrayList<SavedItem> items = new ArrayList<>();
        items.add(new SavedItem(uri, title, kind, System.currentTimeMillis(),
                getPosition(context, uri), totalPages(context, uri)));
        for (SavedItem item : recents(context)) if (!item.uri.equals(uri) && items.size() < RECENT_LIMIT) items.add(item);
        saveRecents(context, items);
    }

    public static List<SavedItem> recents(Context context) {
        SharedPreferences pref = prefs(context);
        ArrayList<SavedItem> result = new ArrayList<>();
        for (int index = 0; index < RECENT_LIMIT; index++) {
            String base = "recent." + index + ".";
            String raw = pref.getString(base + "uri", null);
            if (raw == null) continue;
            result.add(new SavedItem(Uri.parse(raw),
                    pref.getString(base + "title", "名称なし"),
                    pref.getString(base + "kind", "ファイル"),
                    pref.getLong(base + "time", 0),
                    pref.getInt(base + "position", 0),
                    pref.getInt(base + "total", 0)));
        }
        return result;
    }

    public static void removeRecent(Context context, Uri uri) {
        ArrayList<SavedItem> items = new ArrayList<>();
        for (SavedItem item : recents(context)) if (!item.uri.equals(uri)) items.add(item);
        saveRecents(context, items);
    }

    public static void clearRecentsSince(Context context, long cutoff) {
        ArrayList<SavedItem> remaining = new ArrayList<>();
        for (SavedItem item : recents(context)) if (item.timestamp < cutoff) remaining.add(item);
        saveRecents(context, remaining);
    }

    public static void clearRecents(Context context) {
        SharedPreferences pref = prefs(context);
        SharedPreferences.Editor editor = pref.edit();
        for (String name : pref.getAll().keySet()) if (name.startsWith("recent.")) editor.remove(name);
        editor.apply();
    }

    public static int getPosition(Context context, Uri uri) {
        return Math.max(0, prefs(context).getInt("position." + key(uri), 0));
    }

    public static void setPosition(Context context, Uri uri, int page) {
        prefs(context).edit().putInt("position." + key(uri), Math.max(0, page)).apply();
    }

    public static int totalPages(Context context, Uri uri) {
        return Math.max(0, prefs(context).getInt("total." + key(uri), 0));
    }

    public static void updateReadingProgress(Context context, Uri uri, int page, int totalPages) {
        SharedPreferences pref = prefs(context);
        SharedPreferences.Editor editor = pref.edit()
                .putInt("position." + key(uri), Math.max(0, page))
                .putInt("total." + key(uri), Math.max(0, totalPages));
        for (int index = 0; index < RECENT_LIMIT; index++) {
            String base = "recent." + index + ".";
            if (uri.toString().equals(pref.getString(base + "uri", null))) {
                editor.putInt(base + "position", Math.max(0, page));
                editor.putInt(base + "total", Math.max(0, totalPages));
                break;
            }
        }
        editor.apply();
    }

    public static void clearPosition(Context context, Uri uri) {
        prefs(context).edit().remove("position." + key(uri)).apply();
    }

    public static Set<Integer> bookmarks(Context context, Uri uri) {
        Set<String> values = prefs(context).getStringSet("bookmark." + key(uri), Collections.<String>emptySet());
        Set<Integer> result = new HashSet<>();
        for (String value : values) try { result.add(Integer.parseInt(value)); } catch (NumberFormatException ignored) { }
        return result;
    }

    public static boolean hasBookmark(Context context, Uri uri, int page) {
        return bookmarks(context, uri).contains(page);
    }

    public static void setBookmark(Context context, Uri uri, int page, boolean bookmarked) {
        setBookmark(context, uri, page, bookmarked, "名称なし", "ファイル");
    }

    public static void setBookmark(Context context, Uri uri, int page, boolean bookmarked, String title, String kind) {
        Set<Integer> pages = bookmarks(context, uri);
        if (bookmarked) pages.add(page); else pages.remove(page);
        Set<String> values = new HashSet<>();
        for (Integer value : pages) values.add(String.valueOf(value));
        SharedPreferences pref = prefs(context);
        Set<String> catalog = new HashSet<>(pref.getStringSet(BOOKMARKED_ITEMS, Collections.<String>emptySet()));
        String id = key(uri);
        SharedPreferences.Editor editor = pref.edit().putStringSet("bookmark." + id, values);
        if (!bookmarked) editor.remove("bookmark_memo." + id + "." + page);
        if (pages.isEmpty()) {
            catalog.remove(uri.toString());
            editor.remove("bookmark_meta." + id + ".title")
                    .remove("bookmark_meta." + id + ".kind")
                    .remove("bookmark_meta." + id + ".time");
        } else {
            catalog.add(uri.toString());
            editor.putString("bookmark_meta." + id + ".title", title)
                    .putString("bookmark_meta." + id + ".kind", kind)
                    .putLong("bookmark_meta." + id + ".time", System.currentTimeMillis());
        }
        editor.putStringSet(BOOKMARKED_ITEMS, catalog).apply();
    }

    public static List<SavedItem> bookmarkedItems(Context context) {
        SharedPreferences pref = prefs(context);
        ArrayList<SavedItem> result = new ArrayList<>();
        Set<String> items = new HashSet<>(pref.getStringSet(BOOKMARKED_ITEMS, Collections.<String>emptySet()));
        ArrayList<SavedItem> known = new ArrayList<>(recents(context));
        known.addAll(favorites(context));
        for (SavedItem item : known) if (!bookmarks(context, item.uri).isEmpty()) items.add(item.uri.toString());
        for (String raw : items) {
            Uri uri = Uri.parse(raw);
            String id = key(uri);
            if (bookmarks(context, uri).isEmpty()) continue;
            String fallbackTitle = "名称なし";
            String fallbackKind = "ファイル";
            for (SavedItem item : known) if (item.uri.equals(uri)) { fallbackTitle = item.title; fallbackKind = item.kind; break; }
            result.add(new SavedItem(uri,
                    pref.getString("bookmark_meta." + id + ".title", fallbackTitle),
                    pref.getString("bookmark_meta." + id + ".kind", fallbackKind),
                    pref.getLong("bookmark_meta." + id + ".time", 0),
                    getPosition(context, uri), totalPages(context, uri)));
        }
        Collections.sort(result, (left, right) -> Long.compare(right.timestamp, left.timestamp));
        return result;
    }

    public static String bookmarkMemo(Context context, Uri uri, int page) {
        return prefs(context).getString("bookmark_memo." + key(uri) + "." + page, "");
    }

    public static void setBookmarkMemo(Context context, Uri uri, int page, String memo) {
        String name = "bookmark_memo." + key(uri) + "." + page;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (memo == null || memo.trim().isEmpty()) editor.remove(name); else editor.putString(name, memo.trim());
        editor.apply();
    }

    public static void clearBookmarks(Context context, Uri uri) {
        SharedPreferences pref = prefs(context);
        String id = key(uri);
        Set<String> catalog = new HashSet<>(pref.getStringSet(BOOKMARKED_ITEMS, Collections.<String>emptySet()));
        catalog.remove(uri.toString());
        SharedPreferences.Editor editor = pref.edit().remove("bookmark." + id)
                .remove("bookmark_meta." + id + ".title")
                .remove("bookmark_meta." + id + ".kind")
                .remove("bookmark_meta." + id + ".time")
                .putStringSet(BOOKMARKED_ITEMS, catalog);
        for (String name : pref.getAll().keySet()) if (name.startsWith("bookmark_memo." + id + ".")) editor.remove(name);
        editor.apply();
    }

    public static int direction(Context context) {
        return prefs(context).getInt("setting.direction", DIRECTION_LTR);
    }

    public static void setDirection(Context context, int direction) {
        prefs(context).edit().putInt("setting.direction", direction).apply();
    }

    public static int fitMode(Context context) {
        return prefs(context).getInt("setting.fit", FIT_SCREEN);
    }

    public static void setFitMode(Context context, int mode) {
        prefs(context).edit().putInt("setting.fit", mode).apply();
    }

    public static boolean keepScreenOn(Context context) {
        return prefs(context).getBoolean("setting.keep_screen_on", true);
    }

    public static void setKeepScreenOn(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("setting.keep_screen_on", enabled).apply();
    }

    public static boolean startFullscreen(Context context) {
        return prefs(context).getBoolean("setting.start_fullscreen", true);
    }

    public static void setStartFullscreen(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("setting.start_fullscreen", enabled).apply();
    }

    public static boolean volumeNavigation(Context context) {
        return prefs(context).getBoolean("setting.volume_navigation", false);
    }

    public static void setVolumeNavigation(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("setting.volume_navigation", enabled).apply();
    }
    public static boolean reverseVolumeNavigation(Context context) { return prefs(context).getBoolean("setting.reverse_volume_navigation", false); }
    public static void setReverseVolumeNavigation(Context context, boolean enabled) { prefs(context).edit().putBoolean("setting.reverse_volume_navigation", enabled).apply(); }

    public static boolean gridView(Context context) { return prefs(context).getBoolean("setting.grid_view", false); }
    public static void setGridView(Context context, boolean enabled) { prefs(context).edit().putBoolean("setting.grid_view", enabled).apply(); }
    public static int gridColumns(Context context) { return Math.max(2, Math.min(4, prefs(context).getInt("setting.grid_columns", 3))); }
    public static void setGridColumns(Context context, int columns) { prefs(context).edit().putInt("setting.grid_columns", Math.max(2, Math.min(4, columns))).apply(); }
    public static boolean pageButtons(Context context) { return prefs(context).getBoolean("setting.page_buttons", true); }
    public static void setPageButtons(Context context, boolean enabled) { prefs(context).edit().putBoolean("setting.page_buttons", enabled).apply(); }
    public static int pageButtonOpacity(Context context) { return Math.max(30, Math.min(100, prefs(context).getInt("setting.page_button_opacity", 70))); }
    public static void setPageButtonOpacity(Context context, int opacity) { prefs(context).edit().putInt("setting.page_button_opacity", Math.max(30, Math.min(100, opacity))).apply(); }
    public static int pageButtonHeight(Context context) { return Math.max(64, Math.min(160, prefs(context).getInt("setting.page_button_height", 96))); }
    public static void setPageButtonHeight(Context context, int height) { prefs(context).edit().putInt("setting.page_button_height", Math.max(64, Math.min(160, height))).apply(); }
    public static int readingFlow(Context context) { return prefs(context).getInt("setting.reading_flow", FLOW_HORIZONTAL); }
    public static void setReadingFlow(Context context, int mode) { prefs(context).edit().putInt("setting.reading_flow", mode).apply(); }
    public static int pageLayout(Context context) { return prefs(context).getInt("setting.page_layout", PAGE_SINGLE); }
    public static void setPageLayout(Context context, int mode) { prefs(context).edit().putInt("setting.page_layout", mode).apply(); }
    public static int doubleTapScale(Context context) { return Math.max(150, Math.min(400, prefs(context).getInt("setting.double_tap_scale", 225))); }
    public static void setDoubleTapScale(Context context, int percent) { prefs(context).edit().putInt("setting.double_tap_scale", Math.max(150, Math.min(400, percent))).apply(); }
    public static int doubleTapMode(Context context) { return prefs(context).getInt("setting.double_tap_mode", DOUBLE_TAP_TOGGLE); }
    public static void setDoubleTapMode(Context context, int mode) { prefs(context).edit().putInt("setting.double_tap_mode", mode).apply(); }
    public static int imageFilter(Context context) { return prefs(context).getInt("setting.image_filter", FILTER_NONE); }
    public static void setImageFilter(Context context, int filter) { prefs(context).edit().putInt("setting.image_filter", filter).apply(); }
    public static int archiveEncoding(Context context) { return prefs(context).getInt("setting.archive_encoding", 0); }
    public static void setArchiveEncoding(Context context, int encoding) { prefs(context).edit().putInt("setting.archive_encoding", encoding).apply(); }

    public static boolean hasSeenReaderHint(Context context) {
        return prefs(context).getBoolean("hint.reader_gestures", false);
    }

    public static void markReaderHintSeen(Context context) {
        prefs(context).edit().putBoolean("hint.reader_gestures", true).apply();
    }

    public static int brightness(Context context) {
        return prefs(context).getInt("setting.brightness", -1);
    }

    public static void setBrightness(Context context, int value) {
        prefs(context).edit().putInt("setting.brightness", value).apply();
    }

    public static void clearReadingData(Context context) {
        SharedPreferences pref = prefs(context);
        SharedPreferences.Editor editor = pref.edit();
        for (String name : pref.getAll().keySet()) {
            if (name.startsWith("position.") || name.startsWith("total.") || name.startsWith("bookmark.")
                    || name.startsWith("bookmark_meta.") || name.startsWith("bookmark_memo.")) editor.remove(name);
        }
        editor.remove(BOOKMARKED_ITEMS);
        editor.apply();
    }

    public static void clearLibrary(Context context) {
        SharedPreferences pref = prefs(context);
        SharedPreferences.Editor editor = pref.edit();
        for (String name : pref.getAll().keySet()) if (name.startsWith("favorite.")) editor.remove(name);
        editor.remove(TREE_URI).remove(FAVORITES).apply();
        clearRecents(context);
    }

    private static String key(Uri uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(uri.toString().hashCode());
        }
    }

    private static void saveRecents(Context context, List<SavedItem> items) {
        SharedPreferences pref = prefs(context);
        SharedPreferences.Editor editor = pref.edit();
        for (String name : pref.getAll().keySet()) if (name.startsWith("recent.")) editor.remove(name);
        for (int index = 0; index < items.size() && index < RECENT_LIMIT; index++) {
            SavedItem item = items.get(index);
            String base = "recent." + index + ".";
            editor.putString(base + "uri", item.uri.toString());
            editor.putString(base + "title", item.title);
            editor.putString(base + "kind", item.kind);
            editor.putLong(base + "time", item.timestamp);
            editor.putInt(base + "position", item.position);
            editor.putInt(base + "total", item.totalPages);
        }
        editor.apply();
    }
}
