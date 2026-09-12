package jp.yaman.comicexplorer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends BaseActivity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); buildUi(); }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data); ReadingSync.result(this, request, result, data);
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SETTINGS_TOOLBAR);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(Ui.SETTINGS_TOOLBAR);
        ImageButton back = new ImageButton(this); back.setImageResource(R.drawable.ic_arrow_back);
        Ui.styleToolbarButton(back, Ui.SETTINGS_TOOLBAR); back.setContentDescription(I18n.t(R.string.ui_close_settings)); back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        TextView title = Ui.text(this, I18n.t(R.string.ui_settings), 20, Ui.TOOLBAR_TEXT); toolbar.addView(title);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        PreferenceRows rows = new PreferenceRows(this);
        Runnable unchanged = () -> { };
        rows.section("GENERAL");
        String language = AppState.value(this, "language", "system");
        String languageName = language.equals("system") ? "System" : new java.util.Locale(language).getDisplayLanguage(new java.util.Locale(language));
        rows.action(I18n.t(R.string.ui_language), languageName, () -> {
            String[] labels = {"System", "日本語", "English", "한국어", "Русский"}; String[] codes = {"system", "ja", "en", "ko", "ru"};
            Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_language)).setItems(labels, (d, i) -> { AppState.put(this, "language", codes[i]); recreate(); }));
        });
        rows.choice(I18n.t(R.string.ui_encoding), ReaderOptions.ENCODINGS, "archive_encoding", 0, unchanged);
        rows.choice(I18n.t(R.string.ui_theme), new String[]{"System", "Light", "Dark"}, "theme", 0, this::recreate);
        rows.action(I18n.t(R.string.ui_reset_settings), I18n.t(R.string.ui_reset_all_settings_2), () -> confirm(I18n.t(R.string.ui_reset_all_settings), () -> { AppState.resetSettings(this); recreate(); }));
        rows.section("LIST VIEW");
        rows.check(I18n.t(R.string.ui_show_statusbar), I18n.t(R.string.ui_show_time_and_battery_status), "list_statusbar", true, unchanged);
        rows.check(I18n.t(R.string.ui_quick_view_floating_button), I18n.t(R.string.ui_open_the_last_compressed_file), "quick_view", true, unchanged);
        rows.check(I18n.t(R.string.ui_show_folder_path), null, "show_library_path", true, unchanged);
        rows.check(I18n.t(R.string.ui_left_scrollbar), I18n.t(R.string.ui_display_the_file_list_scrollbar_on_the_left_side), "left_library_scrollbar", false, unchanged);
        rows.action(I18n.t(R.string.ui_resume_page), I18n.t(R.string.ui_select_whether_to_use_the_resume_page), () -> ReaderOptions.resume(this));
        rows.action(I18n.t(R.string.ui_sync_resume_page_beta), I18n.t(R.string.ui_sync_your_resume_page_using_google_drive), () -> ReadingSync.show(this));
        rows.section("IMAGE VIEW");
        rows.check(I18n.t(R.string.ui_full_screen), I18n.t(R.string.ui_hide_status_and_navigation_bars), "start_fullscreen", true, unchanged);
        rows.check(I18n.t(R.string.ui_page_dividing_line_dual_pages), I18n.t(R.string.ui_show_a_dividing_line_between_dual_pages), "dual_page_divider", true, unchanged);
        rows.check(I18n.t(R.string.ui_page_dividing_line_scroll_pages), I18n.t(R.string.ui_show_a_dividing_line_between_scroll_pages), "scroll_divider", true, unchanged);
        rows.check(I18n.t(R.string.ui_punch_hole_display_portrait), I18n.t(R.string.ui_expands_the_image_area_to_punch_holes), "cutout_port", false, unchanged);
        rows.check(I18n.t(R.string.ui_punch_hole_display_landscape), I18n.t(R.string.ui_expands_the_image_area_to_punch_holes), "cutout_land", false, unchanged);
        rows.section("CACHE DATA");
        rows.action(I18n.t(R.string.ui_set_thumbnails_storage), AppState.number(this, "cache_mb", 100) + " MB", () -> {
            PreferenceRows options = new PreferenceRows(this); options.slider(I18n.t(R.string.ui_thumbnails), "cache_mb", 100, 10, 1000, " MB", unchanged); options.show(I18n.t(R.string.ui_cache_size));
        });
        rows.action(I18n.t(R.string.ui_clear_thumbnails), ComicFile.formatSize(BookCache.size(this, "thumbs")), () -> clearCache("thumbs"));
        rows.action(I18n.t(R.string.ui_set_zip_file_cache_retention_period), AppState.number(this, "cache_days", 7) + I18n.t(R.string.ui_days), () -> {
            PreferenceRows options = new PreferenceRows(this); options.slider(I18n.t(R.string.ui_retention), "cache_days", 7, 0, 365, I18n.t(R.string.ui_days), unchanged); options.show(I18n.t(R.string.ui_cache_retention));
        });
        rows.action(I18n.t(R.string.ui_delete_archive_cache), ComicFile.formatSize(BookCache.size(this, "books")), () -> clearCache("books"));
        rows.action(I18n.t(R.string.ui_clear_resume_cache), I18n.t(R.string.ui_delete_saved_reading_positions), () -> confirm(I18n.t(R.string.ui_delete_all_saved_reading_positions), () -> AppState.clearPositions(this)));
        rows.action(I18n.t(R.string.ui_clear_bookmarks), I18n.t(R.string.ui_clear_all_bookmarks), () -> confirm(I18n.t(R.string.ui_delete_all_bookmarks), () -> AppState.clearAllBookmarks(this)));
        rows.action(I18n.t(R.string.ui_clear_history_2), I18n.t(R.string.ui_clear_all_histories), () -> confirm(I18n.t(R.string.ui_delete_all_histories), () -> AppState.clearRecents(this)));
        rows.action(I18n.t(R.string.ui_clear_all_cache), I18n.t(R.string.ui_delete_all_user_data_including_bookmarks_and_thumbnails), () -> confirm(I18n.t(R.string.ui_delete_reading_data_and_library_registrations_book_files_will_be), () -> new Thread(() -> {
            try { BookCache.clear(this, "thumbs"); BookCache.clear(this, "books"); }
            catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); return; }
            AppState.clearLibrary(this); AppState.clearReadingData(this); runOnUiThread(this::finish);
        }, "clear-cache").start()));
        rows.section("INFORMATION");
        rows.action("About", I18n.t(R.string.ui_about), () -> Ui.show(new AlertDialog.Builder(this).setTitle("Comic Explorer")
                .setMessage(I18n.t(R.string.ui_comic_explorer_1_1_2_read_comics_and_pdfs_from))
                .setPositiveButton(I18n.t(R.string.ui_close), null)));
        ScrollView scroll = new ScrollView(this); scroll.addView(rows.content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); Ui.applySystemBarInsets(this, root);
    }
    private void clearCache(String kind) {
        confirm(I18n.t(R.string.ui_delete_cache), () -> new Thread(() -> {
            try { BookCache.clear(this, kind); runOnUiThread(() -> { if(!isFinishing() && !isDestroyed()) buildUi(); }); }
            catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
        }, "clear-cache").start());
    }
    private void confirm(String message, Runnable action) {
        Ui.show(new AlertDialog.Builder(this).setMessage(message).setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_apply), (d, i) -> action.run()));
    }
}
