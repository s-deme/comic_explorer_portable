package jp.yaman.comicexplorer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

/** Shared visual language. Every interactive element has an explicit, high-contrast surface. */
public final class Ui {
    public static final int LIGHT_BACKGROUND = 0xFFF4F6FA;
    public static final int LIGHT_SURFACE = 0xFFFFFFFF;
    public static final int LIGHT_SURFACE_TINT = 0xFFE9F1FF;
    public static final int TEXT_PRIMARY = 0xFF17212B;
    public static final int TEXT_SECONDARY = 0xFF3D4A5A;
    public static final int TEXT_MUTED = 0xFF526174;
    public static final int BRAND = 0xFF0B57D0;
    public static final int BRAND_DARK = 0xFF063B8E;
    public static final int OUTLINE = 0xFF738198;
    public static final int DANGER = 0xFFB3261E;
    public static final int DARK_BACKGROUND = 0xFF0B0D10;
    public static final int DARK_SURFACE = 0xFF171C25;
    public static final int DARK_SURFACE_RAISED = 0xFF2A3445;
    public static final int DARK_TEXT = 0xFFF6F8FC;
    public static final int DARK_MUTED = 0xFFC4CFDE;

    public enum ButtonStyle { PRIMARY, SECONDARY, DANGER, DARK_PRIMARY, DARK_SECONDARY }

    private Ui() { }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        return view;
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
                normalBackground = BRAND; normalText = 0xFFFFFFFF; normalStroke = BRAND; break;
            case DANGER:
                normalBackground = DANGER; normalText = 0xFFFFFFFF; normalStroke = DANGER; break;
            case DARK_PRIMARY:
                normalBackground = 0xFF2769D8; normalText = 0xFFFFFFFF; normalStroke = 0xFF8AB4F8; break;
            case DARK_SECONDARY:
                normalBackground = DARK_SURFACE_RAISED; normalText = DARK_TEXT; normalStroke = 0xFF9AA9BC; break;
            default:
                normalBackground = LIGHT_SURFACE; normalText = BRAND_DARK; normalStroke = BRAND; break;
        }
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setMinWidth(dp(view.getContext(), 48));
        view.setPadding(dp(view.getContext(), 10), 0, dp(view.getContext(), 10), 0);
        view.setTextColor(colors(normalText, 0xFF536174));
        view.setBackground(controlBackground(normalBackground, normalStroke, 0xFFD9E0EA, 0xFF8995A7));
    }

    public static void styleSearch(EditText input) {
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_MUTED);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setBackground(shape(LIGHT_SURFACE, OUTLINE, 1, dp(input.getContext(), 10)));
        input.setPadding(dp(input.getContext(), 14), 0, dp(input.getContext(), 14), 0);
        input.setMinHeight(dp(input.getContext(), 48));
    }

    public static void styleCheckable(CompoundButton control) {
        control.setTextColor(TEXT_PRIMARY);
        control.setTextSize(16);
        control.setMinHeight(dp(control.getContext(), 52));
        control.setButtonTintList(colors(BRAND, TEXT_MUTED));
        control.setPadding(dp(control.getContext(), 6), 0, dp(control.getContext(), 6), 0);
    }

    public static void styleSeekBar(SeekBar bar, boolean onDarkSurface) {
        int accent = onDarkSurface ? 0xFF8AB4F8 : BRAND;
        int track = onDarkSurface ? 0xFF64748B : 0xFFABB7C7;
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

    private static ColorStateList colors(int enabled, int disabled) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{disabled, enabled});
    }

    private static Drawable controlBackground(int normal, int normalStroke, int disabled, int disabledStroke) {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{-android.R.attr.state_enabled}, shape(disabled, disabledStroke, 1, 10));
        state.addState(new int[]{}, shape(normal, normalStroke, 1, 10));
        return new RippleDrawable(ColorStateList.valueOf(0x33758FB2), state, null);
    }

    private static GradientDrawable shape(int fill, int stroke, int widthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dpRadius(radiusDp));
        drawable.setStroke(widthDp, stroke);
        return drawable;
    }

    private static float dpRadius(int value) {
        return value * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }
}
