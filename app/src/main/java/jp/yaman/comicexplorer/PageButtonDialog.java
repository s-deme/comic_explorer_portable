package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** ComicScreen 2300 page-button controls; edits remain local until OK. */
final class PageButtonDialog {
    private final Activity activity;
    private final Runnable changed;
    private final View content;
    private final FrameLayout preview;
    private final CheckBox[] checks = new CheckBox[5];
    private final String[] keys = {"page_buttons", "page_both_next", "page_reverse", "page_fixed", "scroll_smooth"};
    private final boolean[] defaults = {true, false, false, false, false};
    private final int[] typeIds = {R.id.pop_pagebtn_rdo_type0, R.id.pop_pagebtn_rdo_type1, R.id.pop_pagebtn_rdo_type2, R.id.pop_pagebtn_rdo_type3};
    private final RadioGroup types, horizontal, vertical;
    private final SeekBar opacity, size, offset;
    private final TextView minus, plus;

    PageButtonDialog(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        content = activity.getLayoutInflater().inflate(R.layout.dialog_page_buttons, null);
        preview = content.findViewById(R.id.pop_pagebtn_layout_btn);
        types = content.findViewById(R.id.pop_pagebtn_rdgp_type);
        horizontal = content.findViewById(R.id.pop_pagebtn_rdgp_position1);
        vertical = content.findViewById(R.id.pop_pagebtn_rdgp_position2);
        int[] ids = {R.id.pop_pagebtn_use_chk, R.id.pop_pagebtn_plpl_chk, R.id.pop_pagebtn_reverse_chk, R.id.pop_pagebtn_fix_chk, R.id.pop_pagebtn_smooth};
        for (int i = 0; i < ids.length; i++) {
            checks[i] = content.findViewById(ids[i]);
            checks[i].setChecked(AppState.enabled(activity, keys[i], defaults[i]));
            checks[i].setOnCheckedChangeListener((button, checked) -> update());
        }
        minus = button(activity); plus = button(activity);
        preview.addView(minus); preview.addView(plus);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        opacity = slider(R.id.pop_pagebtn_alpha_seek, R.id.pop_pagebtn_alpha_value, AppState.pageButtonOpacity(activity), R.string.ui_opacity, false);
        size = slider(R.id.pop_pagebtn_thick_seek, R.id.pop_pagebtn_thick_value, sizePercent(activity), R.string.ui_size, false);
        offset = slider(R.id.pop_pagebtn_offset_seek, R.id.pop_pagebtn_offset_value, 50 - AppState.number(activity, "scroll_overlap", 23), R.string.ui_scroll_overlap, true);
        types.check(typeIds[Math.max(0, Math.min(3, AppState.number(activity, "page_type", 0)))]);
        horizontal.check(AppState.enabled(activity, "page_bottom", AppState.number(activity,"page_position",0) != 1) ? R.id.pop_pagebtn_rdo_position_bottom : R.id.pop_pagebtn_rdo_position_top);
        vertical.check(AppState.enabled(activity, "page_left", AppState.number(activity,"page_position",0) != 3) ? R.id.pop_pagebtn_rdo_position_left : R.id.pop_pagebtn_rdo_position_right);
        types.setOnCheckedChangeListener((group, id) -> update());
        horizontal.setOnCheckedChangeListener((group, id) -> update());
        vertical.setOnCheckedChangeListener((group, id) -> update());
        preview.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (r-l != or-ol || b-t != ob-ot) update(); });
    }

    void show() {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setView(scroll)
                .setPositiveButton(I18n.t(R.string.ui_ok), (dialog, which) -> save())
                .setNegativeButton(I18n.t(R.string.ui_cancel), null)
                .setNeutralButton(I18n.t(R.string.ui_default), null);
        if (activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || activity.getResources().getConfiguration().smallestScreenWidthDp >= 600) builder.setTitle(I18n.t(R.string.ui_page_button_area));
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            for (int i=0;i<checks.length;i++) checks[i].setChecked(defaults[i]);
            types.check(typeIds[0]);
            horizontal.check(R.id.pop_pagebtn_rdo_position_bottom);
            vertical.check(R.id.pop_pagebtn_rdo_position_left);
            opacity.setProgress(100); size.setProgress(10); offset.setProgress(27);
            update();
        });
        content.post(this::update);
    }

    private SeekBar slider(int id, int labelId, int value, int description, boolean inverted) {
        SeekBar bar = content.findViewById(id);
        TextView label = content.findViewById(labelId);
        bar.setContentDescription(I18n.t(description));
        bar.setProgress(value);
        label.setText(inverted ? "ScreenSize - " + (50-bar.getProgress()) : bar.getProgress()+"%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar view,int progress,boolean user) {
                label.setText(inverted ? "ScreenSize - " + (50-progress) : progress+"%"); update();
            }
            public void onStartTrackingTouch(SeekBar view) { }
            public void onStopTrackingTouch(SeekBar view) { }
        });
        return bar;
    }

    private int type() {
        for (int i=0;i<typeIds.length;i++) if (types.getCheckedRadioButtonId()==typeIds[i]) return i;
        return 0;
    }

    private void update() {
        if (offset == null) return;
        int type = type();
        horizontal.setVisibility(type==0 ? View.VISIBLE : View.GONE);
        vertical.setVisibility(type==1 ? View.VISIBLE : View.GONE);
        checks[3].setVisibility(type==0 || type==2 ? View.VISIBLE : View.INVISIBLE);
        boolean enabled = checks[0].isChecked();
        for (int i=1;i<checks.length;i++) checks[i].setEnabled(enabled);
        for (RadioGroup group : new RadioGroup[]{types,horizontal,vertical}) {
            for(int i=0;i<group.getChildCount();i++) group.getChildAt(i).setEnabled(enabled);
        }
        opacity.setEnabled(enabled); size.setEnabled(enabled); offset.setEnabled(enabled);
        FrameLayout.LayoutParams[] params = layouts(type, horizontal.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_bottom,
                vertical.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_left, size.getProgress(), preview.getWidth(), preview.getHeight());
        boolean rtl = AppState.direction(activity)==AppState.DIRECTION_RTL;
        TextView[] buttons = {minus,plus};
        for(int i=0;i<2;i++) {
            buttons[i].setLayoutParams(params[i]);
            boolean forward = forward(i==1,type,checks[1].isChecked(),checks[2].isChecked(),checks[3].isChecked(),rtl);
            buttons[i].setText(forward ? "+" : "-");
            boolean swapped = checks[2].isChecked() ^ ((type==0 || type==2) && !checks[3].isChecked() && rtl);
            buttons[i].setBackgroundColor((i==1)^swapped ? 0x33b71c1c : checks[1].isChecked() ? 0x33ff6f00 : 0x330091ea);
            buttons[i].setAlpha(opacity.getProgress()/100f);
            buttons[i].setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void save() {
        android.content.SharedPreferences.Editor editor = AppState.prefs(activity).edit();
        for(int i=0;i<checks.length;i++) editor.putBoolean("setting."+keys[i],checks[i].isChecked());
        editor.putInt("setting.page_type",type())
                .putBoolean("setting.page_bottom",horizontal.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_bottom)
                .putBoolean("setting.page_left",vertical.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_left)
                .putInt("setting.page_button_opacity",opacity.getProgress())
                .putInt("setting.page_button_percent",size.getProgress())
                .putInt("setting.scroll_overlap",50-offset.getProgress()).apply();
        changed.run();
    }

    static int sizePercent(Context context) { return Math.max(0,Math.min(65,AppState.number(context,"page_button_percent",10))); }

    static TextView button(Context context) {
        TextView button = new TextView(context);
        button.setTextSize(20); button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(android.graphics.Color.WHITE); button.setGravity(Gravity.CENTER);
        button.setSoundEffectsEnabled(false);
        return button;
    }

    static boolean forward(boolean second,int type,boolean both,boolean reverse,boolean fixed,boolean rtl) {
        if (both) return true;
        return second ^ reverse ^ ((type==0 || type==2) && !fixed && rtl);
    }

    static FrameLayout.LayoutParams[] layouts(int type,boolean bottom,boolean left,int percent,int width,int height) {
        int w, h, first, second;
        if (type==0) {
            w=width/2; h=height*percent/100;
            first=(bottom ? Gravity.BOTTOM : Gravity.TOP)|Gravity.LEFT;
            second=(bottom ? Gravity.BOTTOM : Gravity.TOP)|Gravity.RIGHT;
        } else if(type==1) {
            w=width*percent/100; h=height/2;
            first=(left ? Gravity.LEFT : Gravity.RIGHT)|Gravity.TOP;
            second=(left ? Gravity.LEFT : Gravity.RIGHT)|Gravity.BOTTOM;
        } else if(type==2) {
            w=width*percent/200; h=height; first=Gravity.LEFT; second=Gravity.RIGHT;
        } else {
            w=width; h=height*percent/200; first=Gravity.TOP; second=Gravity.BOTTOM;
        }
        return new FrameLayout.LayoutParams[]{new FrameLayout.LayoutParams(w,h,first),new FrameLayout.LayoutParams(w,h,second)};
    }
}
