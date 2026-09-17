package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.Toast;

public final class ReaderOptions {
    public static final String[] ENCODINGS = {"UTF-8", "Shift_JIS", "IBM437", "GB18030", "Big5", "EUC-KR", "windows-1252"};
    private ReaderOptions() { }
    public static void resume(Activity activity) {
        PreferenceRows rows = new PreferenceRows(activity);
        String[] modes = {I18n.t(R.string.ui_ask), I18n.t(R.string.ui_resume_page), I18n.t(R.string.ui_first_page_2)};
        rows.choice(I18n.t(R.string.ui_file_explorer), modes, "resume_open", AppState.resumeMode(activity, false), () -> { });
        rows.choice(I18n.t(R.string.ui_open_the_next_file), modes, "resume_next", AppState.resumeMode(activity, true), () -> { });
        rows.show(I18n.t(R.string.ui_resume_page));
    }
    public static void list(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        rows.choice(I18n.t(R.string.ui_list_type), new String[]{I18n.t(R.string.ui_icon), I18n.t(R.string.ui_thumbnails), I18n.t(R.string.ui_grid)}, "list_type", AppState.gridView(activity) ? 2 : 1, () -> {
            AppState.setGridView(activity, AppState.number(activity, "list_type", 1) == 2); changed.run();
        });
        rows.check(I18n.t(R.string.ui_show_filename), null, "grid_name", true, changed);
        rows.check(I18n.t(R.string.ui_square), null, "grid_square", false, changed);
        rows.slider(I18n.t(R.string.ui_columns), "grid_columns", 3, 1, 10, "", changed);
        rows.action(I18n.t(R.string.ui_background), String.format("#%06X", AppState.number(activity, "grid_color", Ui.DARK_BACKGROUND) & 0xFFFFFF), () -> color(activity, "grid_color", changed));
        rows.show(I18n.t(R.string.ui_list_type));
    }
    public static void color(Activity activity, String key, Runnable changed) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity); layout.setOrientation(android.widget.LinearLayout.VERTICAL); layout.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        EditText input = new EditText(activity); input.setSingleLine(true); input.setHint("#RRGGBB");
        input.setText(String.format("#%06X", AppState.number(activity, key, Ui.DARK_BACKGROUND) & 0xFFFFFF));
        input.setContentDescription(I18n.t(R.string.ui_background_color));
        int[] colors = {0xFF000000, 0xFF212121, 0xFF555555, 0xFFFFFFFF, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF689F38, 0xFF26A69A, 0xFF42A5F5, 0xFF7E57C2, 0xFFEC407A};
        for (int row = 0; row < 3; row++) {
            android.widget.LinearLayout palette = new android.widget.LinearLayout(activity);
            for (int column = 0; column < 4; column++) {
                int color = colors[row * 4 + column]; android.widget.Button swatch = new android.widget.Button(activity);
                String hex = String.format("#%06X", color & 0xFFFFFF);
                swatch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color)); swatch.setContentDescription(hex);
                swatch.setOnClickListener(v -> input.setText(hex));
                palette.addView(swatch, new android.widget.LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1));
            }
            layout.addView(palette);
        }
        layout.addView(input);
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_background_color)).setView(layout).setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_ok), null));
        dialog.getButton(-1).setOnClickListener(v -> {
            if (!input.getText().toString().matches("#[0-9a-fA-F]{6}")) { input.setError(I18n.t(R.string.ui_enter_a_color_as_rrggbb)); return; }
            AppState.put(activity, key, android.graphics.Color.parseColor(input.getText().toString())); changed.run(); dialog.dismiss();
        });
    }
    public static void pageButtons(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        rows.check(I18n.t(R.string.ui_enable), null, "page_buttons", true, changed);
        rows.check("+,+", I18n.t(R.string.ui_both_buttons_move_forward), "page_both_next", false, changed);
        rows.check(I18n.t(R.string.ui_reverse), null, "page_reverse", false, changed);
        rows.check(I18n.t(R.string.ui_fixed), I18n.t(R.string.ui_keep_buttons_visible_with_menus), "page_fixed", true, changed);
        rows.choice(I18n.t(R.string.ui_type), new String[]{"Type0", "Type1", "Type2", "Type3"}, "page_type", 0, changed);
        rows.choice(I18n.t(R.string.ui_position), new String[]{I18n.t(R.string.ui_bottom), I18n.t(R.string.ui_top), I18n.t(R.string.ui_left), I18n.t(R.string.ui_right)}, "page_position", 0, changed);
        rows.slider(I18n.t(R.string.ui_opacity), "page_button_opacity", 70, 0, 100, "%", changed);
        rows.slider(I18n.t(R.string.ui_size), "page_button_height", 96, 48, 160, " dp", changed);
        rows.check(I18n.t(R.string.ui_scroll_animation), null, "scroll_smooth", true, changed);
        rows.slider(I18n.t(R.string.ui_scroll_length_vertical), "scroll_length", 90, 50, 100, "%", changed);
        rows.show(I18n.t(R.string.ui_page_button_area));
    }
    public static void zoom(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        rows.radio(new String[]{I18n.t(R.string.ui_disabled), I18n.t(R.string.ui_fit_screen_2), I18n.t(R.string.ui_zoom), I18n.t(R.string.ui_zoom_inside_fit_screen_outside)}, new int[]{0, 2, 1, 3}, "double_tap_mode", 3, false, changed);
        rows.slider(I18n.t(R.string.ui_scale), "double_tap_scale", 225, 100, 600, "%", changed);
        rows.show(I18n.t(R.string.ui_double_tap_zoom));
    }
    public static void filters(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        rows.compactCheck(I18n.t(R.string.ui_grayscale), "filter_gray", AppState.imageFilter(activity) == 1, changed);
        rows.compactCheck(I18n.t(R.string.ui_auto_contrast), "filter_contrast", AppState.imageFilter(activity) == 2, changed);
        rows.compactCheck(I18n.t(R.string.ui_upscaling), "filter_upscale", false, changed);
        rows.radio(new String[]{"Linear", "Bicubic", "Lanczos"}, new int[]{0, 1, 2}, "filter_interpolation", 0, true, changed);
        rows.compactCheck(I18n.t(R.string.ui_sharpen_filter), "filter_sharp", false, changed);
        rows.slider(I18n.t(R.string.ui_sharpening_strength), "sharp_strength", 5, 0, 20, "", changed);
        rows.compactCheck(I18n.t(R.string.ui_invert_color), "filter_invert", false, changed);
        rows.compactCheck(I18n.t(R.string.ui_blue_light), "filter_blue", AppState.imageFilter(activity) == 4, changed);
        rows.slider(I18n.t(R.string.ui_blue_light), "blue_strength", 30, 0, 100, "%", changed);
        rows.show(I18n.t(R.string.ui_filter));
    }
    public static void hardware(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        rows.check(I18n.t(R.string.ui_use_volume_keys), null, "volume_navigation", false, changed);
        rows.check(I18n.t(R.string.ui_reverse_volume_keys), null, "reverse_volume_navigation", false, changed);
        String[] actions = {I18n.t(R.string.ui_disabled), I18n.t(R.string.ui_previous_page), I18n.t(R.string.ui_next_page), I18n.t(R.string.ui_menu), I18n.t(R.string.ui_add_bookmark), I18n.t(R.string.ui_fullscreen)};
        for (String key : AppState.prefs(activity).getAll().keySet()) if (key.startsWith("setting.key.")) {
            int code = Integer.parseInt(key.substring("setting.key.".length()));
            rows.action(KeyEvent.keyCodeToString(code), actions[Math.max(0, Math.min(5, AppState.number(activity, "key." + code, 0)))], () -> {
                Ui.show(new AlertDialog.Builder(activity).setTitle(KeyEvent.keyCodeToString(code)).setItems(new String[]{I18n.t(R.string.ui_change), I18n.t(R.string.ui_delete)}, (d, i) -> {
                    if (i == 1) { AppState.prefs(activity).edit().remove(key).apply(); changed.run(); }
                    else bind(activity, code, actions, changed);
                }));
            });
        }
        rows.action(I18n.t(R.string.ui_add_hardware_key), I18n.t(R.string.ui_press_a_key_to_bind), () -> {
            AlertDialog capture = Ui.show(new AlertDialog.Builder(activity).setMessage(I18n.t(R.string.ui_press_a_key_to_bind)).setNegativeButton(I18n.t(R.string.ui_cancel), null));
            capture.setOnKeyListener((dialog, code, event) -> {
                if (code == KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) { dialog.dismiss(); bind(activity, code, actions, changed); }
                return true;
            });
        });
        rows.show(I18n.t(R.string.ui_hardware_key));
    }
    private static void bind(Activity activity, int code, String[] actions, Runnable changed) {
        Ui.show(new AlertDialog.Builder(activity).setTitle(KeyEvent.keyCodeToString(code)).setItems(actions, (d, i) -> {
            AppState.put(activity, "key." + code, i); changed.run(); Toast.makeText(activity, I18n.t(R.string.ui_key_binding_saved), Toast.LENGTH_SHORT).show();
        }));
    }
}
