package jp.yaman.comicexplorer;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.Toast;

public final class ReaderOptions {
    public static final String[] ENCODINGS = {"UTF-8", "Shift_JIS", "IBM437", "GB18030", "Big5", "EUC-KR", "windows-1252"};
    private ReaderOptions() { }

    public static void direction(Activity activity, Runnable changed) {
        int[] directions = {AppState.PAGE_SWIPE_RIGHT, AppState.PAGE_SWIPE_LEFT, AppState.PAGE_SWIPE_UP, AppState.PAGE_SWIPE_DOWN};
        String[] choices = {I18n.t(R.string.ui_right), I18n.t(R.string.ui_left), I18n.t(R.string.ui_up), I18n.t(R.string.ui_down)};
        int checked = 0;
        for (int i = 0; i < directions.length; i++) if (directions[i] == AppState.pageSwipeDirection(activity)) checked = i;
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_reading_direction))
                .setSingleChoiceItems(choices, checked, (dialog, selected) -> {
                    int direction = directions[selected];
                    AppState.setPageSwipeDirection(activity, direction);
                    if (direction == AppState.PAGE_SWIPE_RIGHT) AppState.setDirection(activity, AppState.DIRECTION_RTL);
                    if (direction == AppState.PAGE_SWIPE_LEFT) AppState.setDirection(activity, AppState.DIRECTION_LTR);
                    dialog.dismiss();
                    changed.run();
                }));
    }

    public static void orientation(Activity activity) {
        String[] choices = {I18n.t(R.string.ui_auto_rotate), I18n.t(R.string.ui_lock_portrait), I18n.t(R.string.ui_lock_landscape)};
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_screen_rotation)).setItems(choices, (dialog, selected) -> {
            activity.setRequestedOrientation(selected == 1 ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : selected == 2 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }));
    }

    public static void fit(Activity activity, Runnable changed) {
        String[] choices = {I18n.t(R.string.ui_image_fit_to_screen), I18n.t(R.string.ui_fit_width), I18n.t(R.string.ui_fit_height), I18n.t(R.string.ui_stretch_to_fill_screen)};
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_fit_screen)).setSingleChoiceItems(choices, AppState.fitMode(activity), (dialog, chosen) -> {
            AppState.setFitMode(activity, chosen);
            changed.run();
            dialog.dismiss();
        }));
    }

    public static void readingFlow(Activity activity, Runnable changed) {
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_scroll_mode))
                .setSingleChoiceItems(new String[]{I18n.t(R.string.ui_horizontal_swipe), I18n.t(R.string.ui_continuous_vertical_scrolling)}, AppState.readingFlow(activity), (dialog, selected) -> {
                    AppState.setReadingFlow(activity, selected);
                    changed.run();
                    dialog.dismiss();
                }));
    }

    public static void pageLayout(Activity activity, Runnable changed) {
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_page_layout))
                .setSingleChoiceItems(new String[]{I18n.t(R.string.ui_single_page), I18n.t(R.string.ui_two_pages), I18n.t(R.string.ui_auto_two_pages_in_landscape), I18n.t(R.string.ui_force_single_page)}, AppState.pageLayout(activity), (dialog, selected) -> {
                    AppState.setPageLayout(activity, selected);
                    dialog.dismiss();
                    changed.run();
                }));
    }

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
        rows.slider(I18n.t(R.string.ui_columns), "grid_columns", 4, 1, 10, "", changed);
        rows.action(I18n.t(R.string.ui_background), AppState.prefs(activity).contains("setting.grid_color")
                ? String.format("#%06X", AppState.number(activity, "grid_color", Ui.BACKGROUND) & 0xFFFFFF) : I18n.t(R.string.ui_match_theme), () -> color(activity, "grid_color", changed));
        rows.show(I18n.t(R.string.ui_list_type));
    }
    public static void color(Activity activity, String key, Runnable changed) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity); layout.setOrientation(android.widget.LinearLayout.VERTICAL); layout.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        EditText input = new EditText(activity); input.setSingleLine(true); input.setHint("#RRGGBB");
        input.setText(String.format("#%06X", AppState.number(activity, key, Ui.BACKGROUND) & 0xFFFFFF));
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
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_background_color)).setView(layout)
                .setNeutralButton(I18n.t(R.string.ui_match_theme), (d,which) -> { AppState.prefs(activity).edit().remove("setting."+key).apply(); changed.run(); })
                .setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_ok), null));
        dialog.getButton(-1).setOnClickListener(v -> {
            if (!input.getText().toString().matches("#[0-9a-fA-F]{6}")) { input.setError(I18n.t(R.string.ui_enter_a_color_as_rrggbb)); return; }
            AppState.put(activity, key, android.graphics.Color.parseColor(input.getText().toString())); changed.run(); dialog.dismiss();
        });
    }
    public static void pageButtons(Activity activity, Runnable changed) {
        new PageButtonDialog(activity, changed).show();
    }
    public static void filters(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        Runnable visibility = () -> {
            for(int i=0;i<rows.content.getChildCount();i++) {
                android.view.View child=rows.content.getChildAt(i);
                if(child.getTag() instanceof String) {
                    String key=(String)child.getTag();
                    boolean fallback=key.equals("filter_blue") && AppState.imageFilter(activity)==4;
                    child.setVisibility(AppState.enabled(activity,key,fallback) ? android.view.View.VISIBLE : android.view.View.GONE);
                }
            }
        };
        Runnable update=() -> {visibility.run();changed.run();};
        rows.compactCheck(I18n.t(R.string.ui_grayscale), "filter_gray", AppState.imageFilter(activity) == 1, update);
        rows.compactCheck(I18n.t(R.string.ui_auto_contrast), "filter_contrast", AppState.imageFilter(activity) == 2, update);
        rows.compactCheck(I18n.t(R.string.ui_upscaling), "filter_upscale", false, update);
        int first=rows.content.getChildCount();
        rows.radio(new String[]{"Linear", "Bicubic", "Lanczos"}, new int[]{0, 1, 2}, "filter_interpolation", 2, true, update);
        tagOptions(rows,first,"filter_upscale");
        rows.compactCheck(I18n.t(R.string.ui_sharpen_filter), "filter_sharp", false, update);
        first=rows.content.getChildCount();
        rows.slider(I18n.t(R.string.ui_sharpening_strength), "sharp_strength", 0, 0, 20, "", update);
        tagOptions(rows,first,"filter_sharp");
        rows.compactCheck(I18n.t(R.string.ui_invert_color), "filter_invert", false, update);
        rows.compactCheck(I18n.t(R.string.ui_blue_light), "filter_blue", AppState.imageFilter(activity) == 4, update);
        first=rows.content.getChildCount();
        rows.slider(I18n.t(R.string.ui_blue_light), "blue_strength", 50, 0, 100, "%", update);
        tagOptions(rows,first,"filter_blue"); visibility.run();
        AlertDialog dialog=rows.show(I18n.t(R.string.ui_filter));
        if(dialog.getWindow()!=null)dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }
    private static void tagOptions(PreferenceRows rows,int first,String key) {
        for(int i=first;i<rows.content.getChildCount();i++)rows.content.getChildAt(i).setTag(key);
    }
    static int keyAction(Activity activity, int code) {
        int action = AppState.number(activity, "key." + code, 0);
        return action >= 0 && action <= 4 ? action : 0;
    }
    public static void hardware(Activity activity, Runnable changed) {
        PreferenceRows rows = new PreferenceRows(activity);
        rows.check(I18n.t(R.string.ui_use_volume_keys), null, "volume_navigation", false, changed);
        rows.check(I18n.t(R.string.ui_reverse_volume_keys), null, "reverse_volume_navigation", false, changed);
        String[] actions = {I18n.t(R.string.ui_disabled), I18n.t(R.string.ui_previous_page), I18n.t(R.string.ui_next_page), I18n.t(R.string.ui_menu), I18n.t(R.string.ui_add_bookmark)};
        for (String key : AppState.prefs(activity).getAll().keySet()) if (key.startsWith("setting.key.")) {
            int code = Integer.parseInt(key.substring("setting.key.".length()));
            rows.action(KeyEvent.keyCodeToString(code), actions[keyAction(activity, code)], () -> {
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
