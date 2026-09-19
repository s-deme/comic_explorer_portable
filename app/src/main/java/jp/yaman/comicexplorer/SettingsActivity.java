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
        super.onActivityResult(request, result, data);
        if(request==84 && result==RESULT_OK && data!=null && data.getData()!=null) {
            android.net.Uri source=data.getData();
            new Thread(() -> {
                try {
                    ReferenceImport plan=ReferenceImport.read(this,source);
                    runOnUiThread(() -> {
                        if(isFinishing() || isDestroyed())return;
                        String summary=String.format(java.util.Locale.getDefault(),I18n.t(R.string.ui_reference_import_summary),plan.settings.size(),plan.history.size(),plan.entries.size(),plan.skipped);
                        Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_reference_import)).setMessage(summary)
                            .setNegativeButton(I18n.t(R.string.ui_cancel),null).setPositiveButton(I18n.t(R.string.ui_apply),(dialog,which) -> {
                                new Thread(() -> {plan.apply(this);runOnUiThread(() -> {if(!isFinishing() && !isDestroyed())recreate();});},"reference-import").start();
                            }));
                    });
                } catch(Exception error) {runOnUiThread(() -> {if(!isFinishing())Toast.makeText(this,I18n.t(R.string.ui_reference_import_failed),Toast.LENGTH_LONG).show();});}
            },"reference-inspect").start();
        } else ReadingSync.result(this, request, result, data);
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.TOOLBAR);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(Ui.TOOLBAR);
        ImageButton back = new ImageButton(this); back.setImageResource(R.drawable.ic_arrow_back);
        Ui.styleToolbarButton(back, Ui.TOOLBAR); back.setContentDescription(I18n.t(R.string.ui_close_settings)); back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        TextView title = Ui.text(this, I18n.t(R.string.ui_settings), 20, Ui.TEXT_PRIMARY); toolbar.addView(title);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        PreferenceRows rows = new PreferenceRows(this);
        Runnable unchanged = () -> { };
        rows.section("GENERAL");
        String language = AppState.value(this, "language", "system");
        String languageName = language.equals("system") ? I18n.t(R.string.ui_system_settings) : new java.util.Locale(language).getDisplayLanguage(new java.util.Locale(language));
        rows.action(I18n.t(R.string.ui_language), languageName, () -> {
            String[] labels = {I18n.t(R.string.ui_system_settings), "日本語", "English", "한국어", "Русский"}; String[] codes = {"system", "ja", "en", "ko", "ru"};
            Ui.show(new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_language)).setItems(labels, (d, i) -> { AppState.put(this, "language", codes[i]); recreate(); }));
        });
        rows.choice(I18n.t(R.string.ui_encoding), ReaderOptions.ENCODINGS, "archive_encoding", 0, unchanged);
        rows.action(I18n.t(R.string.ui_theme), I18n.t(Ui.THEMES[Ui.themeIndex(this)].name), this::showThemePicker);
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
        rows.check(I18n.t(R.string.ui_page_dividing_line_scroll_pages), I18n.t(R.string.ui_show_a_dividing_line_between_scroll_pages), "scroll_divider", false, unchanged);
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
        rows.action(I18n.t(R.string.ui_reference_import),I18n.t(R.string.ui_reference_import_detail),() -> startActivityForResult(
                new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"),84));
        rows.action("About", I18n.t(R.string.ui_about), () -> Ui.show(new AlertDialog.Builder(this).setTitle("Comic Explorer")
                .setMessage(I18n.t(R.string.ui_comic_explorer_1_1_2_read_comics_and_pdfs_from))
                .setPositiveButton(I18n.t(R.string.ui_close), null)));
        ScrollView scroll = new ScrollView(this); scroll.addView(rows.content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); Ui.applySystemBarInsets(this, root);
    }
    private void showThemePicker() {
        int selected = Ui.themeIndex(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(I18n.t(R.string.ui_theme))
                .setView(scroll).setNegativeButton(I18n.t(R.string.ui_cancel), null).create();
        android.content.res.Configuration config = getResources().getConfiguration();
        int width = Math.min(560, config.screenWidthDp) - 72;
        int columns = Math.max(1, Math.min(3, (int)(width / (92 * config.fontScale))));
        LinearLayout row = null;
        for (int index = 0; index < Ui.THEMES.length; index++) {
            final int theme = index;
            if (index == 0 || (index - 1) % columns == 0) {
                row = new LinearLayout(this);
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            String name = I18n.t(Ui.THEMES[index].name);
            android.widget.Button option = Ui.button(this, name + (index == selected ? " ✓" : ""),
                    index == selected ? Ui.ButtonStyle.TONAL : Ui.ButtonStyle.SECONDARY);
            option.setMinHeight(Ui.dp(this, 80));
            option.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            option.setSelected(index == selected);
            option.setContentDescription(name + (index == selected ? I18n.t(R.string.ui_selected_2) : ""));
            if (android.os.Build.VERSION.SDK_INT >= 30)
                option.setStateDescription(I18n.t(index == selected ? R.string.ui_selected : R.string.ui_not_selected));
            android.graphics.drawable.GradientDrawable swatch = new android.graphics.drawable.GradientDrawable();
            if (index == 0) {
                swatch.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
                swatch.setColors(new int[]{0xFFFAFAFA, 0xFF18181B});
            } else swatch.setColor(index == 1 ? 0xFFFAFAFA : index == 2 ? 0xFF18181B : Ui.themeAccent(this, index));
            swatch.setCornerRadius(Ui.dp(this, 6));
            swatch.setStroke(Ui.dp(this, 1), Ui.OUTLINE);
            swatch.setBounds(0, 0, Ui.dp(this, 40), Ui.dp(this, 20));
            option.setCompoundDrawables(null, swatch, null, null);
            option.setCompoundDrawablePadding(Ui.dp(this, 8));
            option.setOnClickListener(view -> {
                dialog.dismiss();
                if (theme != selected) { AppState.put(this, "theme", Ui.THEMES[theme].id); recreate(); }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            row.addView(option, params);
        }
        // Keep the last row the same width as the complete rows at large font sizes.
        while (row.getChildCount() < columns) row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1));
        dialog.show();
        Ui.styleDialog(dialog);
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
