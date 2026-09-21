package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Page-button controls; edits remain local until Save. */
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
    private final TextView[] previewAreas = new TextView[4];
    private final TextView[] previewIcons = new TextView[4];

    PageButtonDialog(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        android.content.res.Configuration configuration = new android.content.res.Configuration(activity.getResources().getConfiguration());
        String language = AppState.value(activity,"language","system");
        configuration.setLocale(language.equals("system") ? java.util.Locale.getDefault() : java.util.Locale.forLanguageTag(language));
        Context localized = new android.view.ContextThemeWrapper(activity.createConfigurationContext(configuration),Ui.themeStyle);
        content = activity.getLayoutInflater().cloneInContext(localized).inflate(R.layout.dialog_page_buttons, null);
        preview = content.findViewById(R.id.pop_pagebtn_layout_btn);
        if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            preview.getLayoutParams().height = Ui.dp(activity,80);
        types = content.findViewById(R.id.pop_pagebtn_rdgp_type);
        horizontal = content.findViewById(R.id.pop_pagebtn_rdgp_position1);
        vertical = content.findViewById(R.id.pop_pagebtn_rdgp_position2);
        int[] ids = {R.id.pop_pagebtn_use_chk, R.id.pop_pagebtn_plpl_chk, R.id.pop_pagebtn_reverse_chk, R.id.pop_pagebtn_fix_chk, R.id.pop_pagebtn_smooth};
        for (int i = 0; i < ids.length; i++) {
            checks[i] = content.findViewById(ids[i]);
            checks[i].setChecked(AppState.enabled(activity, keys[i], defaults[i]));
            checks[i].setOnCheckedChangeListener((button, checked) -> update());
        }
        minus = new TextView(activity); plus = new TextView(activity);
        previewAreas[0]=minus; previewAreas[1]=plus;
        for (int i=0;i<previewAreas.length;i++) {
            if (i>=2) previewAreas[i]=new TextView(activity);
            preview.addView(previewAreas[i]);
        }
        for (int i=0;i<previewIcons.length;i++) {
            previewIcons[i] = button(activity);
            previewIcons[i].setIncludeFontPadding(false);
            previewIcons[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,20);
            preview.addView(previewIcons[i]);
        }
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i=0;i<typeIds.length;i++) {
            TextView choice = content.findViewById(typeIds[i]);
            choice.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, layoutIcon(i), null);
        }
        opacity = slider(R.id.pop_pagebtn_alpha_seek, R.id.pop_pagebtn_alpha_value, AppState.pageButtonOpacity(activity), R.string.ui_opacity, false);
        size = slider(R.id.pop_pagebtn_thick_seek, R.id.pop_pagebtn_thick_value, sizePercent(activity), R.string.ui_size, false);
        offset = slider(R.id.pop_pagebtn_offset_seek, R.id.pop_pagebtn_offset_value, AppState.number(activity, "scroll_overlap", 23), R.string.pb_overlap, true);
        types.check(typeIds[Math.max(0, Math.min(3, AppState.number(activity, "page_type", 0)))]);
        horizontal.check(AppState.enabled(activity,"page_horizontal_both",false) ? R.id.pop_pagebtn_rdo_position_horizontal_both
                : AppState.enabled(activity, "page_bottom", AppState.number(activity,"page_position",0) != 1) ? R.id.pop_pagebtn_rdo_position_bottom : R.id.pop_pagebtn_rdo_position_top);
        vertical.check(AppState.enabled(activity,"page_vertical_both",false) ? R.id.pop_pagebtn_rdo_position_vertical_both
                : AppState.enabled(activity, "page_left", AppState.number(activity,"page_position",0) != 3) ? R.id.pop_pagebtn_rdo_position_left : R.id.pop_pagebtn_rdo_position_right);
        types.setOnCheckedChangeListener((group, id) -> update());
        horizontal.setOnCheckedChangeListener((group, id) -> update());
        vertical.setOnCheckedChangeListener((group, id) -> update());
        preview.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (r-l != or-ol || b-t != ob-ot) update(); });
    }

    void show() {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setView(scroll)
                .setPositiveButton(I18n.t(R.string.ui_save), (dialog, which) -> save())
                .setNegativeButton(I18n.t(R.string.ui_cancel), null)
                .setNeutralButton(I18n.t(R.string.pb_reset), null);
        if (activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || activity.getResources().getConfiguration().smallestScreenWidthDp >= 600) builder.setTitle(I18n.t(R.string.ui_page_button_area));
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            for (int i=0;i<checks.length;i++) checks[i].setChecked(defaults[i]);
            types.check(typeIds[0]);
            horizontal.check(R.id.pop_pagebtn_rdo_position_bottom);
            vertical.check(R.id.pop_pagebtn_rdo_position_left);
            opacity.setProgress(100); size.setProgress(10); offset.setProgress(23);
            update();
        });
        content.post(this::update);
    }

    private SeekBar slider(int id, int labelId, int value, int description, boolean overlap) {
        SeekBar bar = content.findViewById(id);
        TextView label = content.findViewById(labelId);
        bar.setContentDescription(I18n.t(description));
        bar.setProgress(value);
        label.setText(bar.getProgress() + (overlap ? " sp" : "%"));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar view,int progress,boolean user) {
                label.setText(progress + (overlap ? " sp" : "%")); update();
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
        checks[3].setVisibility(type==0 || type==2 ? View.VISIBLE : View.GONE);
        boolean enabled = checks[0].isChecked();
        for (int i=1;i<checks.length;i++) checks[i].setEnabled(enabled);
        checks[2].setEnabled(enabled && !checks[1].isChecked());
        checks[3].setEnabled(enabled && !checks[1].isChecked());
        for (RadioGroup group : new RadioGroup[]{types,horizontal,vertical}) {
            for(int i=0;i<group.getChildCount();i++) group.getChildAt(i).setEnabled(enabled);
        }
        opacity.setEnabled(enabled); size.setEnabled(enabled); offset.setEnabled(enabled);
        boolean bothEdges = type==0 ? horizontal.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_horizontal_both
                : type==1 && vertical.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_vertical_both;
        FrameLayout.LayoutParams[] params = layouts(type, horizontal.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_bottom,
                vertical.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_left, bothEdges, size.getProgress(), preview.getWidth(), preview.getHeight());
        boolean rtl = AppState.direction(activity)==AppState.DIRECTION_RTL;
        int sizeLabel = type==1 || type==2 ? R.string.pb_width : R.string.pb_height;
        ((TextView) content.findViewById(R.id.pop_pagebtn_thick_txt)).setText(I18n.t(sizeLabel));
        size.setContentDescription(I18n.t(sizeLabel));
        String sizeValue = size.getProgress()+"%";
        if (type>=2 || bothEdges) sizeValue = String.format(I18n.t(R.string.pb_each_edge),
                Integer.toString(size.getProgress()), new java.text.DecimalFormat("0.#").format(size.getProgress()/2f));
        ((TextView) content.findViewById(R.id.pop_pagebtn_thick_value)).setText(sizeValue);
        String first = I18n.t(forward(false,type,checks[1].isChecked(),checks[2].isChecked(),checks[3].isChecked(),rtl) ? R.string.pb_next : R.string.pb_previous);
        String second = I18n.t(forward(true,type,checks[1].isChecked(),checks[2].isChecked(),checks[3].isChecked(),rtl) ? R.string.pb_next : R.string.pb_previous);
        ((TextView) content.findViewById(R.id.page_button_summary)).setText(enabled
                ? String.format(I18n.t(type==0 || type==2 ? R.string.pb_horizontal_summary : R.string.pb_vertical_summary),first,second)
                : I18n.t(R.string.pb_disabled));
        ((TextView) content.findViewById(R.id.page_button_direction)).setText(I18n.t(rtl ? R.string.pb_direction_rtl : R.string.pb_direction_ltr));
        ((TextView) content.findViewById(R.id.page_button_hint)).setText(I18n.t(size.getProgress()==0 ? R.string.pb_zero_size : R.string.pb_preview_hint));
        content.findViewById(R.id.page_button_hint).setVisibility(enabled ? View.VISIBLE : View.GONE);
        for(int i=0;i<previewAreas.length;i++) {
            previewAreas[i].setVisibility(enabled && i<params.length ? View.VISIBLE : View.INVISIBLE);
            previewIcons[i].setVisibility(enabled && size.getProgress()>0 && i<params.length ? View.VISIBLE : View.INVISIBLE);
            if (i>=params.length) continue;
            previewAreas[i].setLayoutParams(params[i]);
            boolean next = forward(i%2==1,type,checks[1].isChecked(),checks[2].isChecked(),checks[3].isChecked(),rtl);
            int color = previewColor(next);
            GradientDrawable area = new GradientDrawable();
            area.setColor((Math.round(96*opacity.getProgress()/100f)<<24) | (color & 0x00ffffff));
            area.setStroke(Ui.dp(activity,1), color);
            previewAreas[i].setBackground(area);
            TextView icon = previewIcons[i];
            icon.setText(next ? "+" : "−");
            icon.setTextColor(Ui.ON_BRAND);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL); badge.setColor(color);
            icon.setBackground(badge);
            // Editing markers stay readable even when the actual tap area is thin or transparent.
            int diameter = Ui.dp(activity,28);
            Rect region = new Rect();
            Gravity.apply(params[i].gravity,params[i].width,params[i].height,
                    new Rect(0,0,preview.getWidth(),preview.getHeight()),region);
            FrameLayout.LayoutParams marker = new FrameLayout.LayoutParams(diameter,diameter,Gravity.TOP|Gravity.LEFT);
            marker.leftMargin = Math.max(0,Math.min(preview.getWidth()-diameter,region.centerX()-diameter/2));
            marker.topMargin = Math.max(0,Math.min(preview.getHeight()-diameter,region.centerY()-diameter/2));
            icon.setLayoutParams(marker);
        }
    }

    private static int previewColor(boolean next) {
        return next ? (Ui.light ? 0xff1565c0 : 0xff64b5f6) : (Ui.light ? 0xff9c4d00 : 0xffffb74d);
    }

    private void save() {
        android.content.SharedPreferences.Editor editor = AppState.prefs(activity).edit();
        for(int i=0;i<checks.length;i++) editor.putBoolean("setting."+keys[i],checks[i].isChecked());
        editor.putInt("setting.page_type",type())
                .putBoolean("setting.page_bottom",horizontal.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_bottom)
                .putBoolean("setting.page_left",vertical.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_left)
                .putBoolean("setting.page_horizontal_both",horizontal.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_horizontal_both)
                .putBoolean("setting.page_vertical_both",vertical.getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_vertical_both)
                .putInt("setting.page_button_opacity",opacity.getProgress())
                .putInt("setting.page_button_percent",size.getProgress())
                .putInt("setting.scroll_overlap",offset.getProgress()).apply();
        changed.run();
    }

    static int sizePercent(Context context) { return Math.max(0,Math.min(65,AppState.number(context,"page_button_percent",10))); }

    private BitmapDrawable layoutIcon(int type) {
        int width=Ui.dp(activity,36), height=Ui.dp(activity,44), inset=Ui.dp(activity,2);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Ui.OUTLINE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(activity,1));
        Rect screen=new Rect(inset,inset,width-inset,height-inset);
        canvas.drawRect(screen,paint);
        paint.setColor(Ui.BRAND); paint.setStyle(Paint.Style.FILL);
        for(FrameLayout.LayoutParams region:layouts(type,true,true,false,30,screen.width(),screen.height())) {
            Rect bounds=new Rect();
            Gravity.apply(region.gravity,region.width,region.height,screen,bounds);
            bounds.inset(Ui.dp(activity,1),Ui.dp(activity,1));
            canvas.drawRect(bounds,paint);
        }
        return new BitmapDrawable(activity.getResources(),bitmap);
    }

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

    static FrameLayout.LayoutParams[] layouts(int type,boolean bottom,boolean left,boolean bothEdges,int percent,int width,int height) {
        int w, h, first, second;
        if (type==0) {
            w=width/2; h=height*percent/(bothEdges ? 200 : 100);
            first=(bottom ? Gravity.BOTTOM : Gravity.TOP)|Gravity.LEFT;
            second=(bottom ? Gravity.BOTTOM : Gravity.TOP)|Gravity.RIGHT;
        } else if(type==1) {
            w=width*percent/(bothEdges ? 200 : 100); h=height/2;
            first=(left ? Gravity.LEFT : Gravity.RIGHT)|Gravity.TOP;
            second=(left ? Gravity.LEFT : Gravity.RIGHT)|Gravity.BOTTOM;
        } else if(type==2) {
            w=width*percent/200; h=height; first=Gravity.LEFT; second=Gravity.RIGHT;
        } else {
            w=width; h=height*percent/200; first=Gravity.TOP; second=Gravity.BOTTOM;
        }
        if (bothEdges && type<2) {
            int oppositeFirst=type==0 ? (bottom ? Gravity.TOP : Gravity.BOTTOM)|Gravity.LEFT : (left ? Gravity.RIGHT : Gravity.LEFT)|Gravity.TOP;
            int oppositeSecond=type==0 ? (bottom ? Gravity.TOP : Gravity.BOTTOM)|Gravity.RIGHT : (left ? Gravity.RIGHT : Gravity.LEFT)|Gravity.BOTTOM;
            return new FrameLayout.LayoutParams[]{new FrameLayout.LayoutParams(w,h,first),new FrameLayout.LayoutParams(w,h,second),
                    new FrameLayout.LayoutParams(w,h,oppositeFirst),new FrameLayout.LayoutParams(w,h,oppositeSecond)};
        }
        return new FrameLayout.LayoutParams[]{new FrameLayout.LayoutParams(w,h,first),new FrameLayout.LayoutParams(w,h,second)};
    }
}
