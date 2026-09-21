package jp.yaman.comicexplorer;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

/** Semantic visual language shared by the library, settings and reader surfaces. */
public final class Ui {
    public static int BACKGROUND = 0xFFFAFAFA;
    public static int SURFACE = 0xFFFFFFFF;
    public static int SURFACE_RAISED = 0xFFF5F5F5;
    public static int TEXT_PRIMARY = 0xFF212121;
    public static int TEXT_SECONDARY = 0xFF616161;
    public static int BRAND = 0xFF2196F3;
    public static int BRAND_CONTAINER = 0xFFE3F2FD;
    public static int ON_BRAND_CONTAINER = 0xFF0D47A1;
    public static int OUTLINE = 0xFF9E9E9E;
    public static final int DANGER = 0xFFB3261E;
    public static final int SUCCESS = 0xFF2E6B45;
    public static final int INFO = 0xFF355F82;

    public static int TOOLBAR = 0xFF37474F;

    public enum ButtonStyle { PRIMARY, SECONDARY, TONAL, GHOST, DANGER, DANGER_TONAL, RAISED_SECONDARY, SURFACE_GHOST }

    private Ui() { }

    static final class Theme {
        final int id, name, style;
        Theme(int id, int name, int style) { this.id = id; this.name = name; this.style = style; }
    }

    // Persistent IDs are explicit: changing display order must not change saved preferences.
    static final Theme[] THEMES = {
            new Theme(0, R.string.ui_theme_system, R.style.AppTheme),
            new Theme(1, R.string.ui_light_theme, R.style.AppThemeLight),
            new Theme(2, R.string.ui_dark_theme, R.style.AppTheme),
            new Theme(3, R.string.ui_theme_red, R.style.AppThemeRed),
            new Theme(4, R.string.ui_theme_blue, R.style.AppThemeBlue),
            new Theme(5, R.string.ui_theme_green, R.style.AppThemeGreen),
            new Theme(6, R.string.ui_theme_purple, R.style.AppThemePurple),
            new Theme(7, R.string.ui_theme_pink, R.style.AppThemePink),
            new Theme(8, R.string.ui_theme_orange, R.style.AppThemeOrange),
            new Theme(9, R.string.ui_theme_gray, R.style.AppThemeGray)
    };
    public static boolean light;
    public static int themeStyle;
    public static int ON_BRAND = Color.WHITE;

    static int themeIndex(Context context) {
        int theme = AppState.number(context, "theme", 0);
        for (int index = 0; index < THEMES.length; index++) if (THEMES[index].id == theme) return index;
        return 0;
    }

    private static int themeStyle(Context context, int theme) {
        if (THEMES[theme].id == 0) return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? R.style.AppTheme : R.style.AppThemeLight;
        return THEMES[theme].style;
    }

    static int themeAccent(Context context, int theme) {
        android.content.res.TypedArray colors = new android.view.ContextThemeWrapper(context, themeStyle(context, theme))
                .obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
        int accent = colors.getColor(0, Color.GRAY);
        colors.recycle();
        return accent;
    }

    static Drawable themePreview(Context context, int theme) {
        android.content.res.TypedArray colors = new android.view.ContextThemeWrapper(context,themeStyle(context,theme))
                .obtainStyledAttributes(new int[]{android.R.attr.colorBackground,android.R.attr.colorPrimary,
                        android.R.attr.colorBackgroundFloating,android.R.attr.colorAccent,android.R.attr.colorForeground});
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(dp(context,76),dp(context,52),android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
        canvas.scale(bitmap.getWidth()/76f,bitmap.getHeight()/52f);
        android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(colors.getColor(0,Color.BLACK));
        paint.setColor(colors.getColor(1,Color.DKGRAY)); canvas.drawRect(0,0,76,11,paint);
        paint.setColor(colors.getColor(4,Color.WHITE)); canvas.drawRect(5,4,28,6,paint);
        paint.setColor(colors.getColor(2,Color.DKGRAY)); canvas.drawRect(4,15,72,23,paint);
        for(int i=0;i<3;i++) {
            paint.setColor(colors.getColor(i==0 ? 3 : 2,Color.GRAY));
            canvas.drawRect(4+i*24,27,24+i*24,44,paint);
            paint.setColor(colors.getColor(4,Color.WHITE)); canvas.drawRect(4+i*24,47,20+i*24,48,paint);
        }
        colors.recycle();
        return new android.graphics.drawable.BitmapDrawable(context.getResources(),bitmap);
    }

    public static void configure(Context context) {
        themeStyle = themeStyle(context, themeIndex(context));
        android.content.res.TypedArray colors = new android.view.ContextThemeWrapper(context, themeStyle)
                .obtainStyledAttributes(new int[]{android.R.attr.colorAccent, android.R.attr.colorPrimary,
                        android.R.attr.colorBackground, android.R.attr.colorBackgroundFloating,
                        android.R.attr.colorButtonNormal, android.R.attr.colorForeground,
                        android.R.attr.textColorSecondary, android.R.attr.colorControlNormal});
        BRAND = colors.getColor(0, Color.GRAY);
        TOOLBAR = colors.getColor(1, Color.DKGRAY);
        BACKGROUND = colors.getColor(2, Color.BLACK);
        SURFACE = colors.getColor(3, Color.DKGRAY);
        SURFACE_RAISED = colors.getColor(4, Color.DKGRAY);
        TEXT_PRIMARY = colors.getColor(5, Color.WHITE);
        TEXT_SECONDARY = colors.getColor(6, Color.LTGRAY);
        OUTLINE = colors.getColor(7, Color.GRAY);
        colors.recycle();
        light = androidx.core.graphics.ColorUtils.calculateLuminance(BACKGROUND) > .5;
        ON_BRAND = androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE,BRAND) >= 4.5 ? Color.WHITE : 0xFF161616;
        BRAND_CONTAINER = androidx.core.graphics.ColorUtils.blendARGB(SURFACE, BRAND, .16f);
        ON_BRAND_CONTAINER = BRAND;
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Keeps app chrome clear of status bars, navigation bars and display cutouts.
     * Android 15 enforces edge-to-edge for target SDK 35, so every activity must
     * explicitly turn the reported insets into usable padding.
     */
    public static void applySystemBarInsets(Activity activity, View root) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            boolean allowCutout = activity instanceof ViewerActivity && AppState.enabled(activity,
                    activity.getResources().getConfiguration().orientation == 2 ? "cutout_land" : "cutout_port", false);
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | (allowCutout ? 0 : WindowInsets.Type.displayCutout()));
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
                DisplayCutout cutout = windowInsets.getDisplayCutout();
                if (cutout != null && !allowCutout) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            int paddedLeft = baseLeft + left;
            int paddedTop = baseTop + top;
            int paddedRight = baseRight + right;
            int paddedBottom = baseBottom + bottom;
            if (view.getPaddingLeft() != paddedLeft || view.getPaddingTop() != paddedTop
                    || view.getPaddingRight() != paddedRight || view.getPaddingBottom() != paddedBottom) {
                view.setPadding(paddedLeft, paddedTop, paddedRight, paddedBottom);
            }
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    public static TextView text(Context context, String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        return view;
    }

    public static void title(TextView view) {
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(-0.015f);
    }

    public static void label(TextView view) {
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public static Button button(Context context, String label, ButtonStyle style) {
        Button view = new Button(context);
        view.setText(label);
        styleButton(view, style);
        return view;
    }

    public static void styleButton(Button view, ButtonStyle style) {
        if (view == null) return;
        final int normalBackground;
        final int normalText;
        final int normalStroke;
        switch (style) {
            case PRIMARY:
                normalBackground = BRAND; normalText = ON_BRAND; normalStroke = BRAND; break;
            case DANGER:
                normalBackground = DANGER; normalText = 0xFFFFFFFF; normalStroke = DANGER; break;
            case DANGER_TONAL:
                normalBackground = 0xFFF9DEDC; normalText = 0xFF8C1D18; normalStroke = 0xFFF2B8B5; break;
            case RAISED_SECONDARY:
                normalBackground = SURFACE_RAISED; normalText = TEXT_PRIMARY; normalStroke = OUTLINE; break;
            case SURFACE_GHOST:
                normalBackground = SURFACE; normalText = TEXT_PRIMARY; normalStroke = SURFACE; break;
            case TONAL:
                normalBackground = BRAND_CONTAINER; normalText = ON_BRAND_CONTAINER; normalStroke = BRAND_CONTAINER; break;
            case GHOST:
                normalBackground = BACKGROUND; normalText = TEXT_PRIMARY; normalStroke = BACKGROUND; break;
            default:
                normalBackground = SURFACE; normalText = TEXT_PRIMARY; normalStroke = OUTLINE; break;
        }
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setMinWidth(dp(view.getContext(), 48));
        view.setPadding(dp(view.getContext(), 12), 0, dp(view.getContext(), 12), 0);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(colors(normalText, TEXT_SECONDARY));
        view.setBackground(controlBackground(normalBackground, normalStroke,
                SURFACE_RAISED, OUTLINE, 2,
                BRAND));
        view.setStateListAnimator(null);
        view.setElevation(0);
    }

    public static void styleTopTab(Button view, boolean selected) {
        view.setAllCaps(false);
        view.setTextSize(12);
        view.setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setPadding(dp(view.getContext(), 2), 0, dp(view.getContext(), 2), 0);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(selected ? BRAND : TEXT_SECONDARY);
        view.setBackground(controlBackground(selected ? SURFACE_RAISED : SURFACE,
                selected ? BRAND : SURFACE, SURFACE, SURFACE, selected ? 1 : 0, BRAND));
        view.setStateListAnimator(null);
        view.setSelected(selected);
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(selected ? I18n.t(R.string.ui_selected) : I18n.t(R.string.ui_not_selected));
    }

    public static void styleSearch(EditText input) {
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_SECONDARY);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setBackground(inputBackground());
        input.setPadding(dp(input.getContext(), 16), 0, dp(input.getContext(), 16), 0);
        input.setMinHeight(dp(input.getContext(), 44));
    }

    public static void styleDarkSearch(EditText input) {
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_SECONDARY);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setBackground(darkInputBackground());
        input.setPadding(dp(input.getContext(), 14), 0, dp(input.getContext(), 14), 0);
        input.setMinHeight(dp(input.getContext(), 44));
    }

    public static void stylePaddedCheckable(CompoundButton control) {
        control.setTextColor(TEXT_PRIMARY);
        control.setTextSize(15);
        control.setMinHeight(dp(control.getContext(), 52));
        control.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF777777, BRAND, TEXT_SECONDARY}));
        control.setPadding(dp(control.getContext(), 12), 0, dp(control.getContext(), 12), 0);
    }

    public static void styleSeekBar(SeekBar bar) {
        int accent = BRAND;
        int track = OUTLINE;
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setSecondaryProgressTintList(ColorStateList.valueOf(track));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(track));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setMinHeight(dp(bar.getContext(), 48));
    }

    public static void styleDialog(AlertDialog dialog) {
        if (dialog == null) return;
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) styleButton(positive, ButtonStyle.PRIMARY);
        if (negative != null) styleButton(negative, ButtonStyle.SECONDARY);
        if (neutral != null) styleButton(neutral, ButtonStyle.SECONDARY);
    }

    public static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> styleDialog(dialog));
        dialog.show();
        return dialog;
    }

    public static void styleDarkPanel(View view) {
        view.setBackground(shape(SURFACE, OUTLINE, 1, 18));
    }

    public static void styleChip(TextView view, boolean accent) {
        view.setTextColor(accent ? ON_BRAND : TEXT_PRIMARY);
        view.setBackground(shape(accent ? BRAND : SURFACE_RAISED,
                accent ? BRAND : OUTLINE, 1, 2));
        view.setPadding(dp(view.getContext(), 8), 0, dp(view.getContext(), 8), 0);
        label(view);
    }

    public static void styleThumbnail(View view) {
        view.setBackground(shape(SURFACE_RAISED, OUTLINE, 1, 0));
        view.setClipToOutline(true);
    }

    public static void styleToolbarButton(ImageButton view, int background) {
        view.setImageTintList(ColorStateList.valueOf(TEXT_PRIMARY));
        view.setBackground(controlBackground(background, background, background, background, 0, BRAND));
        view.setPadding(dp(view.getContext(), 12), dp(view.getContext(), 12), dp(view.getContext(), 12), dp(view.getContext(), 12));
        view.setMinimumWidth(dp(view.getContext(), 48));
        view.setMinimumHeight(dp(view.getContext(), 48));
    }

    public static ImageButton iconButton(Context context, int icon, String description) {
        ImageButton button = new ImageButton(context);
        styleIconButton(button);
        updateIconButton(button, icon, description, false, TEXT_PRIMARY);
        return button;
    }

    public static void updateIconButton(ImageButton button, int icon, String description, boolean selected, int tint) {
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setSelected(selected);
        button.setContentDescription(description);
        button.setTooltipText(description);
    }

    private static void styleIconButton(ImageButton view) {
        view.setImageTintList(ColorStateList.valueOf(TEXT_PRIMARY));
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        view.setPadding(0, 0, 0, 0);
        view.setMinimumWidth(dp(view.getContext(), 48));
        view.setMinimumHeight(dp(view.getContext(), 48));
        view.setBackground(controlBackground(SURFACE_RAISED, SURFACE_RAISED,
                SURFACE, SURFACE, 0, BRAND));
        view.setStateListAnimator(null);
        view.setElevation(0);
    }

    public static void styleListRow(View view) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(SURFACE_RAISED, BRAND, 1, 0));
        states.addState(new int[]{}, shape(BACKGROUND, BACKGROUND, 0, 0));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(BRAND, 46)), states, null));
        view.setElevation(0);
    }

    public static TextView badge(Context context, String value, int foreground, int background) {
        TextView view = text(context, value, 14, foreground);
        label(view);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2));
        view.setBackground(shape(background, background, 0, 10));
        return view;
    }

    private static ColorStateList colors(int enabled, int disabled) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{disabled, enabled});
    }

    private static Drawable inputBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, shape(SURFACE, BRAND, 2, 16));
        state.addState(new int[]{}, shape(SURFACE, OUTLINE, 1, 16));
        return new RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(BRAND, 46)), state, null);
    }

    private static Drawable darkInputBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, shape(SURFACE, BRAND, 2, 4));
        state.addState(new int[]{}, shape(SURFACE, OUTLINE, 1, 4));
        return new RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(BRAND, 46)), state, null);
    }

    private static Drawable controlBackground(int normal, int normalStroke, int disabled, int disabledStroke, int radius, int focus) {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{-android.R.attr.state_enabled}, shape(disabled, disabledStroke, 1, radius));
        state.addState(new int[]{android.R.attr.state_focused}, shape(normal, focus, 2, radius));
        state.addState(new int[]{}, shape(normal, normalStroke, 1, radius));
        return new RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(BRAND, 46)), state, null);
    }

    private static GradientDrawable shape(int fill, int stroke, int widthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dpRadius(radiusDp));
        if (widthDp > 0) drawable.setStroke(Math.max(1, Math.round(dpRadius(widthDp))), stroke);
        return drawable;
    }

    private static float dpRadius(int value) {
        return value * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    /** Labels are presentation only; each item carries its own action. */
    public static final class Actions {
        private final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        private final java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();
        public void add(String label, Runnable action) { labels.add(label); actions.add(action); }
        public AlertDialog show(Activity activity, String title) {
            return Ui.show(new AlertDialog.Builder(activity).setTitle(title)
                    .setItems(labels.toArray(new String[0]), (dialog, index) -> actions.get(index).run()));
        }
    }
}
