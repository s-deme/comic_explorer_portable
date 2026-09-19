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
        content.setBackgroundColor(Ui.BACKGROUND);
    }
    public void section(String name) {
        TextView title = Ui.text(activity, name, 12, Ui.BRAND);
        title.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), 0, Ui.dp(activity, 8));
        title.setBackgroundColor(Ui.SURFACE_RAISED);
        content.addView(title);
    }
    public LinearLayout action(String title, String summary, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(activity, 56));
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), Ui.dp(activity, 12));
        TextView label = Ui.text(activity, title, 16, Ui.TEXT_PRIMARY);
        row.addView(label);
        if (summary != null && !summary.isEmpty()) {
            TextView detail = Ui.text(activity, summary, 13, Ui.TEXT_SECONDARY);
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
        Ui.stylePaddedCheckable(box);
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
    public void compactCheck(String title, String key, boolean fallback, Runnable changed) {
        CheckBox box = new CheckBox(activity);
        box.setText(title); box.setTextColor(Ui.TEXT_PRIMARY); box.setTextSize(16);
        box.setMinHeight(Ui.dp(activity, 48)); box.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
        Ui.stylePaddedCheckable(box); box.setChecked(AppState.enabled(activity, key, fallback));
        box.setOnCheckedChangeListener((button, checked) -> { AppState.put(activity, key, checked); changed.run(); });
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }
    public void radio(String[] options, int[] values, String key, int fallback, boolean horizontal, Runnable changed) {
        android.widget.RadioGroup group = new android.widget.RadioGroup(activity);
        boolean inRow = horizontal && activity.getResources().getConfiguration().fontScale <= 1.2f;
        group.setOrientation(inRow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout labels = new LinearLayout(activity);
        if(inRow) content.addView(labels);
        int selected = AppState.number(activity, key, fallback);
        for (int i = 0; i < options.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(activity);
            button.setId(android.view.View.generateViewId()); button.setTag(values[i]);
            button.setText(options[i]); button.setTextColor(Ui.TEXT_PRIMARY);
            if (inRow) {
                TextView label=Ui.text(activity,options[i],14,Ui.TEXT_PRIMARY);label.setGravity(Gravity.CENTER);
                label.setPadding(0,Ui.dp(activity,8),0,0);labels.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                button.setText("");button.setContentDescription(options[i]);button.setMinWidth(0);
                button.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
                    int icon=button.getButtonDrawable()==null ? Ui.dp(activity,24) : button.getButtonDrawable().getIntrinsicWidth();
                    int padding=Math.max(0,(r-l-icon)/2); if(button.getPaddingLeft()!=padding)button.setPadding(padding,0,0,0);
                });
            }
            button.setMinHeight(Ui.dp(activity, 48));
            button.setPadding(Ui.dp(activity, inRow ? 4 : 12), 0, Ui.dp(activity, inRow ? 0 : 12), 0);
            Ui.stylePaddedCheckable(button);
            group.addView(button, inRow ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
            if (values[i] == selected) group.check(button.getId());
        }
        group.setOnCheckedChangeListener((view, id) -> {
            android.view.View button = view.findViewById(id);
            if (button != null) { AppState.put(activity, key, (Integer)button.getTag()); changed.run(); }
        });
        content.addView(group);
    }
    public void slider(String title, String key, int fallback, int min, int max, String suffix, Runnable changed) {
        TextView label = Ui.text(activity, "", 15, Ui.TEXT_PRIMARY);
        label.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), 0);
        SeekBar bar = new SeekBar(activity);
        bar.setMin(min); bar.setMax(max);
        bar.setProgress(Math.max(min, Math.min(max, AppState.number(activity, key, fallback))));
        label.setText(title + ": " + bar.getProgress() + suffix);
        bar.setContentDescription(title);
        Ui.styleSeekBar(bar);
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
