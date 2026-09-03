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
    public static final int LIGHT_BACKGROUND = 0xFFFAFAFA;
    public static final int LIGHT_SURFACE = 0xFFFFFFFF;
    public static final int LIGHT_SURFACE_TINT = 0xFFF5F5F5;
    public static final int LIGHT_SURFACE_HIGH = 0xFFE7E7E7;
    public static final int TEXT_PRIMARY = 0xFF212121;
    public static final int TEXT_SECONDARY = 0xFF616161;
    public static final int TEXT_MUTED = 0xFF757575;
    public static final int BRAND = 0xFF2196F3;
    public static final int BRAND_DARK = 0xFF1565C0;
    public static final int BRAND_CONTAINER = 0xFFE3F2FD;
    public static final int ON_BRAND_CONTAINER = 0xFF0D47A1;
    public static final int OUTLINE = 0xFF9E9E9E;
    public static final int OUTLINE_VARIANT = 0xFFD0D0D0;
    public static final int DANGER = 0xFFB3261E;
    public static final int SUCCESS = 0xFF2E6B45;
    public static final int INFO = 0xFF355F82;

    public static final int TOOLBAR = 0xFF37474F;
    public static final int SETTINGS_TOOLBAR = 0xFF689F38;
    public static final int TOOLBAR_TEXT = 0xFFEEEEEE;
    public static final int DARK_BACKGROUND = 0xFF212121;
    public static final int DARK_SURFACE = 0xFF303030;
    public static final int DARK_SURFACE_RAISED = 0xFF3C3C3C;
    public static final int DARK_TEXT = 0xFFFAFAFA;
    public static final int DARK_MUTED = 0xFFB0BEC5;
    public static final int DARK_OUTLINE = 0xFF4A4A4A;
    public static final int READER_ACCENT = 0xFF90CAF9;
    public static final int LIBRARY_ACCENT = 0xFFB4A7E8;

    public enum ButtonStyle { PRIMARY, SECONDARY, TONAL, GHOST, DANGER, DANGER_TONAL, DARK_PRIMARY, DARK_SECONDARY, DARK_GHOST }

    private Ui() { }

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
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
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
                if (cutout != null) {
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
        final boolean darkStyle = style == ButtonStyle.DARK_PRIMARY || style == ButtonStyle.DARK_SECONDARY || style == ButtonStyle.DARK_GHOST;
        switch (style) {
            case PRIMARY:
                normalBackground = BRAND; normalText = 0xFFFFFFFF; normalStroke = BRAND; break;
            case DANGER:
                normalBackground = DANGER; normalText = 0xFFFFFFFF; normalStroke = DANGER; break;
            case DANGER_TONAL:
                normalBackground = 0xFFF9DEDC; normalText = 0xFF8C1D18; normalStroke = 0xFFF2B8B5; break;
            case DARK_PRIMARY:
                normalBackground = READER_ACCENT; normalText = 0xFF102027; normalStroke = READER_ACCENT; break;
            case DARK_SECONDARY:
                normalBackground = DARK_SURFACE_RAISED; normalText = DARK_TEXT; normalStroke = 0xFF555B6A; break;
            case DARK_GHOST:
                normalBackground = DARK_SURFACE; normalText = DARK_TEXT; normalStroke = DARK_SURFACE; break;
            case TONAL:
                normalBackground = BRAND_CONTAINER; normalText = ON_BRAND_CONTAINER; normalStroke = BRAND_CONTAINER; break;
            case GHOST:
                normalBackground = LIGHT_BACKGROUND; normalText = TEXT_PRIMARY; normalStroke = LIGHT_BACKGROUND; break;
            default:
                normalBackground = LIGHT_SURFACE; normalText = TEXT_PRIMARY; normalStroke = OUTLINE_VARIANT; break;
        }
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setMinWidth(dp(view.getContext(), 48));
        view.setPadding(dp(view.getContext(), 12), 0, dp(view.getContext(), 12), 0);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(colors(normalText, darkStyle ? 0xFFB6B1BD : 0xFF625D66));
        view.setBackground(controlBackground(normalBackground, normalStroke,
                darkStyle ? 0xFF555555 : 0xFFE0E0E0,
                darkStyle ? 0xFF777777 : 0xFFBDBDBD, 2,
                darkStyle ? READER_ACCENT : BRAND));
        view.setStateListAnimator(null);
        view.setElevation(0);
    }

    public static void styleSegment(Button view, boolean selected) {
        styleButton(view, selected ? ButtonStyle.PRIMARY : ButtonStyle.GHOST);
        view.setAutoSizeTextTypeUniformWithConfiguration(13, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        view.setPadding(dp(view.getContext(), 4), 0, dp(view.getContext(), 4), 0);
        int background = selected ? BRAND : LIGHT_BACKGROUND;
        int foreground = selected ? 0xFFFFFFFF : TEXT_SECONDARY;
        view.setTextColor(colors(foreground, 0xFF625D66));
        view.setBackground(controlBackground(background, background, 0xFFE0DAD2, 0xFFA19AA4, 18, BRAND));
        view.setMinHeight(dp(view.getContext(), 48));
        view.setSelected(selected);
        view.setContentDescription(view.getText() + (selected ? "、選択中" : "を表示"));
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(selected ? "選択中" : "未選択");
    }

    /** Bottom navigation keeps the library's stable destinations reachable without a top tab row. */
    public static void styleNavigationDestination(Button view, boolean selected) {
        styleButton(view, ButtonStyle.DARK_GHOST);
        view.setTextSize(11);
        view.setPadding(dp(view.getContext(), 4), 0, dp(view.getContext(), 4), 0);
        view.setCompoundDrawablePadding(dp(view.getContext(), 1));
        view.setTextColor(selected ? LIBRARY_ACCENT : DARK_TEXT);
        view.setCompoundDrawableTintList(ColorStateList.valueOf(selected ? LIBRARY_ACCENT : DARK_MUTED));
        view.setBackground(controlBackground(selected ? DARK_SURFACE : DARK_SURFACE_RAISED,
                selected ? DARK_SURFACE : DARK_SURFACE_RAISED, DARK_SURFACE, DARK_SURFACE, 0, LIBRARY_ACCENT));
        view.setSelected(selected);
        view.setContentDescription(view.getText() + (selected ? "、選択中" : "を表示"));
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(selected ? "選択中" : "未選択");
    }

    public static void styleTopTab(Button view, boolean selected) {
        view.setAllCaps(false);
        view.setTextSize(12);
        view.setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setPadding(dp(view.getContext(), 2), 0, dp(view.getContext(), 2), 0);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(selected ? LIBRARY_ACCENT : DARK_MUTED);
        view.setBackground(controlBackground(selected ? 0xFF34313E : DARK_SURFACE,
                selected ? LIBRARY_ACCENT : DARK_SURFACE, DARK_SURFACE, DARK_SURFACE, selected ? 1 : 0, LIBRARY_ACCENT));
        view.setStateListAnimator(null);
        view.setSelected(selected);
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(selected ? "選択中" : "未選択");
    }

    public static void styleSearch(EditText input) {
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_MUTED);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setBackground(inputBackground());
        input.setPadding(dp(input.getContext(), 16), 0, dp(input.getContext(), 16), 0);
        input.setMinHeight(dp(input.getContext(), 44));
    }

    public static void styleDarkSearch(EditText input) {
        input.setTextColor(DARK_TEXT);
        input.setHintTextColor(DARK_MUTED);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setBackground(darkInputBackground());
        input.setPadding(dp(input.getContext(), 14), 0, dp(input.getContext(), 14), 0);
        input.setMinHeight(dp(input.getContext(), 44));
    }

    public static void styleCheckable(CompoundButton control) {
        control.setTextColor(TEXT_PRIMARY);
        control.setTextSize(16);
        control.setMinHeight(dp(control.getContext(), 52));
        control.setButtonTintList(checkableColors());
        control.setPadding(dp(control.getContext(), 8), 0, dp(control.getContext(), 8), 0);
    }

    public static void styleDarkCheckable(CompoundButton control) {
        control.setTextColor(DARK_TEXT);
        control.setTextSize(15);
        control.setMinHeight(dp(control.getContext(), 52));
        control.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF777777, LIBRARY_ACCENT, DARK_MUTED}));
        control.setPadding(dp(control.getContext(), 12), 0, dp(control.getContext(), 12), 0);
    }

    public static void styleSeekBar(SeekBar bar, boolean onDarkSurface) {
        int accent = onDarkSurface ? READER_ACCENT : BRAND;
        int track = onDarkSurface ? 0xFF656A78 : 0xFFAAA3AD;
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

    public static void setVisibleAsDisabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(1f);
    }

    public static void styleCard(View view, boolean interactive) {
        Drawable content;
        if (interactive) {
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, shape(LIGHT_SURFACE, BRAND, 2, 0));
            states.addState(new int[]{}, shape(LIGHT_SURFACE, OUTLINE_VARIANT, 1, 0));
            content = new RippleDrawable(ColorStateList.valueOf(0x245940B6), states, null);
        } else {
            content = shape(LIGHT_SURFACE, OUTLINE_VARIANT, 1, 0);
        }
        view.setBackground(content);
        view.setElevation(dp(view.getContext(), 1));
    }

    public static void styleInsetPanel(View view) {
        view.setBackground(shape(LIGHT_SURFACE_TINT, OUTLINE_VARIANT, 1, 16));
    }

    public static void styleDarkPanel(View view) {
        view.setBackground(shape(DARK_SURFACE, 0xFF383C48, 1, 18));
    }

    public static void styleDarkChip(TextView view, boolean accent) {
        view.setTextColor(accent ? 0xFF102027 : DARK_TEXT);
        view.setBackground(shape(accent ? READER_ACCENT : DARK_SURFACE_RAISED,
                accent ? READER_ACCENT : 0xFF555555, 1, 2));
        view.setPadding(dp(view.getContext(), 8), 0, dp(view.getContext(), 8), 0);
        label(view);
    }

    public static void styleThumbnail(View view) {
        view.setBackground(shape(DARK_SURFACE_RAISED, DARK_OUTLINE, 1, 0));
        view.setClipToOutline(true);
    }

    public static void styleToolbarButton(ImageButton view, int background) {
        view.setImageTintList(ColorStateList.valueOf(TOOLBAR_TEXT));
        view.setBackground(controlBackground(background, background, background, background, 0, READER_ACCENT));
        view.setPadding(dp(view.getContext(), 12), dp(view.getContext(), 12), dp(view.getContext(), 12), dp(view.getContext(), 12));
        view.setMinimumWidth(dp(view.getContext(), 48));
        view.setMinimumHeight(dp(view.getContext(), 48));
    }

    public static void styleReaderAction(Button view) {
        view.setTextColor(DARK_TEXT);
        view.setTextSize(21);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, 0);
        view.setMinWidth(dp(view.getContext(), 48));
        view.setMinHeight(dp(view.getContext(), 48));
        view.setBackground(controlBackground(DARK_SURFACE_RAISED, DARK_SURFACE_RAISED,
                DARK_SURFACE, DARK_SURFACE, 0, READER_ACCENT));
        view.setStateListAnimator(null);
        view.setElevation(0);
    }

    public static void styleReaderPageButton(Button view) {
        view.setTextSize(28);
        view.setTextColor(DARK_TEXT);
        view.setAlpha(.72f);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(controlBackground(0x993C3C3C, 0x99555555,
                0x66303030, 0x66303030, 2, READER_ACCENT));
        view.setMinWidth(dp(view.getContext(), 48));
        view.setMinHeight(dp(view.getContext(), 96));
    }

    public static void styleListRow(View view) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(0xFF34313E, LIBRARY_ACCENT, 1, 0));
        states.addState(new int[]{}, shape(DARK_BACKGROUND, DARK_BACKGROUND, 0, 0));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33B4A7E8), states, null));
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

    private static ColorStateList checkableColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{0xFFA19AA4, BRAND, OUTLINE});
    }

    private static Drawable inputBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, shape(LIGHT_SURFACE, BRAND, 2, 16));
        state.addState(new int[]{}, shape(LIGHT_SURFACE, OUTLINE_VARIANT, 1, 16));
        return new RippleDrawable(ColorStateList.valueOf(0x1F5940B6), state, null);
    }

    private static Drawable darkInputBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_focused}, shape(DARK_SURFACE, LIBRARY_ACCENT, 2, 4));
        state.addState(new int[]{}, shape(DARK_SURFACE, DARK_OUTLINE, 1, 4));
        return new RippleDrawable(ColorStateList.valueOf(0x33B4A7E8), state, null);
    }

    private static Drawable controlBackground(int normal, int normalStroke, int disabled, int disabledStroke, int radius, int focus) {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{-android.R.attr.state_enabled}, shape(disabled, disabledStroke, 1, radius));
        state.addState(new int[]{android.R.attr.state_focused}, shape(normal, focus, 2, radius));
        state.addState(new int[]{}, shape(normal, normalStroke, 1, radius));
        return new RippleDrawable(ColorStateList.valueOf(0x2E5940B6), state, null);
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
}
