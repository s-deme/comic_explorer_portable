package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** The same compact, accessible preference rows are used in settings and reader dialogs. */
public final class PreferenceRows {
    private final Activity activity;
    public final LinearLayout content;
    public PreferenceRows(Activity activity) {
        this.activity = activity;
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.DARK_BACKGROUND);
    }
    public void section(String name) {
        TextView title = Ui.text(activity, name, 12, Ui.LIBRARY_ACCENT);
        title.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), 0, Ui.dp(activity, 8));
        title.setBackgroundColor(Ui.DARK_SURFACE_RAISED);
        content.addView(title);
    }
    public LinearLayout action(String title, String summary, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 56));
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 12));
        TextView label = Ui.text(activity, title, 16, Ui.DARK_TEXT);
        row.addView(label);
        if (summary != null && !summary.isEmpty()) {
            TextView detail = Ui.text(activity, summary, 13, Ui.DARK_MUTED);
            detail.setPadding(0, Ui.dp(activity, 4), 0, 0);
            row.addView(detail);
        }
        row.setFocusable(true);
        row.setContentDescription(title + (summary == null ? "" : ", " + summary));
        row.setOnClickListener(view -> action.run());
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
    public void check(String title, String summary, String key, boolean fallback, Runnable changed) {
        LinearLayout row = action(title, summary, () -> { });
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        while (row.getChildCount() > 0) {
            android.view.View child = row.getChildAt(0);
            row.removeView(child);
            labels.addView(child);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox box = new CheckBox(activity);
        Ui.styleDarkCheckable(box);
        box.setContentDescription(title);
        box.setChecked(AppState.enabled(activity, key, fallback));
        box.setOnCheckedChangeListener((button, checked) -> { AppState.put(activity, key, checked); changed.run(); });
        row.addView(box, new LinearLayout.LayoutParams(Ui.dp(activity, 52), -2));
        row.setOnClickListener(view -> box.setChecked(!box.isChecked()));
    }
    public void choice(String title, String[] options, String key, int fallback, Runnable changed) {
        int selected = Math.max(0, Math.min(options.length - 1, AppState.number(activity, key, fallback)));
        final LinearLayout[] row = new LinearLayout[1];
        row[0] = action(title, options[selected], () -> Ui.show(new AlertDialog.Builder(activity).setTitle(title)
                .setSingleChoiceItems(options, Math.max(0, Math.min(options.length - 1, AppState.number(activity, key, fallback))), (dialog, which) -> {
                    AppState.put(activity, key, which);
                    ((TextView) row[0].getChildAt(1)).setText(options[which]);
                    row[0].setContentDescription(title + ", " + options[which]);
                    dialog.dismiss(); changed.run();
                })));
    }
    public void slider(String title, String key, int fallback, int min, int max, String suffix, Runnable changed) {
        TextView label = Ui.text(activity, "", 15, Ui.DARK_TEXT);
        label.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), 0);
        SeekBar bar = new SeekBar(activity);
        bar.setMin(min); bar.setMax(max);
        bar.setProgress(Math.max(min, Math.min(max, AppState.number(activity, key, fallback))));
        label.setText(title + ": " + bar.getProgress() + suffix);
        bar.setContentDescription(title);
        Ui.styleSeekBar(bar, true);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean user) { label.setText(title + ": " + value + suffix); }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { AppState.put(activity, key, view.getProgress()); changed.run(); }
        });
        content.addView(label); content.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 48)));
    }
    public AlertDialog show(String title) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(content);
        return Ui.show(new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton(I18n.t(R.string.ui_close), null));
    }
}
