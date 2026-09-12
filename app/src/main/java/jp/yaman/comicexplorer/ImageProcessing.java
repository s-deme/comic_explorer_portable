package jp.yaman.comicexplorer;

import android.content.Context;
import android.graphics.Bitmap;

/** CPU filters run on the reader worker, never on the UI thread. */
public final class ImageProcessing {
    private ImageProcessing() { }
    public static Bitmap apply(Context context, Bitmap source, int targetWidth, int maxPixels) {
        int legacy = AppState.imageFilter(context);
        boolean gray = AppState.enabled(context, "filter_gray", legacy == 1);
        boolean contrast = AppState.enabled(context, "filter_contrast", legacy == 2);
        boolean invert = AppState.enabled(context, "filter_invert", false);
        boolean sharp = AppState.enabled(context, "filter_sharp", false);
        boolean blue = AppState.enabled(context, "filter_blue", legacy == 4);
        boolean upscale = AppState.enabled(context, "filter_upscale", false);
        Bitmap bitmap = source;
        if (upscale && targetWidth > source.getWidth()) {
            double scale = Math.min(targetWidth / (double) source.getWidth(), Math.sqrt(maxPixels / (double) (source.getWidth() * (long) source.getHeight())));
            if (scale > 1.01) bitmap = resize(source, Math.max(1, (int) (source.getWidth() * scale)), Math.max(1, (int) (source.getHeight() * scale)), AppState.number(context, "filter_interpolation", 0));
        }
        if (!gray && !contrast && !invert && !sharp && !blue) return bitmap;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        if (contrast) autoContrast(pixels);
        if (sharp) sharpen(pixels, width, height, AppState.number(context, "sharp_strength", 5) / 10f);
        int strength = blue ? Math.max(0, Math.min(100, AppState.number(context, "blue_strength", 30))) : 0;
        for (int i = 0; i < pixels.length; i++) pixels[i] = color(pixels[i], gray, invert, strength);
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
    static int color(int pixel, boolean gray, boolean invert, int blue) {
        int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
        if (gray) { int luminance = (r * 299 + g * 587 + b * 114 + 500) / 1000; r = g = b = luminance; }
        if (invert) { r = 255 - r; g = 255 - g; b = 255 - b; }
        g = Math.round(g * (1 - blue / 500f)); b = Math.round(b * (1 - blue / 125f));
        return pixel & 0xff000000 | r << 16 | g << 8 | b;
    }
    static void autoContrast(int[] pixels) {
        int low = 255, high = 0;
        for (int p : pixels) if ((p >>> 24) > 0) {
            low = Math.min(low, Math.min(p >> 16 & 255, Math.min(p >> 8 & 255, p & 255)));
            high = Math.max(high, Math.max(p >> 16 & 255, Math.max(p >> 8 & 255, p & 255)));
        }
        if (high <= low) return;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i]; if ((p >>> 24) == 0) continue;
            int r = ((p >> 16 & 255) - low) * 255 / (high - low);
            int g = ((p >> 8 & 255) - low) * 255 / (high - low);
            int b = ((p & 255) - low) * 255 / (high - low);
            pixels[i] = p & 0xff000000 | r << 16 | g << 8 | b;
        }
    }
    static void sharpen(int[] pixels, int width, int height, float amount) {
        int[] original = pixels.clone();
        for (int y = 1; y < height - 1; y++) for (int x = 1; x < width - 1; x++) {
            int index = y * width + x, value = original[index] & 0xff000000;
            for (int shift = 0; shift <= 16; shift += 8) {
                int center = original[index] >> shift & 255;
                float average = ((original[index - 1] >> shift & 255) + (original[index + 1] >> shift & 255)
                        + (original[index - width] >> shift & 255) + (original[index + width] >> shift & 255)) / 4f;
                value |= clamp(Math.round(center + amount * (center - average))) << shift;
            }
            pixels[index] = value;
        }
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    static double kernel(double x, int mode) {
        x = Math.abs(x);
        if (mode == 0) return Math.max(0, 1 - x);
        if (mode == 1) return x < 1 ? 1.5*x*x*x - 2.5*x*x + 1 : x < 2 ? -.5*x*x*x + 2.5*x*x - 4*x + 2 : 0;
        return x == 0 ? 1 : x >= 3 ? 0 : Math.sin(Math.PI*x) * Math.sin(Math.PI*x/3) / (Math.PI*Math.PI*x*x/3);
    }
    private static Bitmap resize(Bitmap source, int width, int height, int mode) {
        if (mode == 0) return Bitmap.createScaledBitmap(source, width, height, true);
        int sw = source.getWidth(), sh = source.getHeight(), radius = mode == 1 ? 2 : 3;
        int[] original = new int[sw*sh]; source.getPixels(original, 0, sw, 0, 0, sw, sh);
        int[] horizontal = new int[width*sh], output = new int[width*height];
        for (int y = 0; y < sh; y++) for (int x = 0; x < width; x++) horizontal[y*width+x] = sample(original, y*sw, 1, sw, (x+.5)*sw/width-.5, radius, mode);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) output[y*width+x] = sample(horizontal, x, width, sh, (y+.5)*sh/height-.5, radius, mode);
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888);
    }
    private static int sample(int[] pixels, int offset, int stride, int count, double center, int radius, int mode) {
        double alpha = 0, red = 0, green = 0, blue = 0, weight = 0;
        for (int i = (int)Math.floor(center)-radius+1; i <= (int)Math.floor(center)+radius; i++) {
            double w = kernel(center-i, mode); int p = pixels[offset+Math.max(0, Math.min(count-1, i))*stride];
            double a = (p >>> 24) / 255d;
            weight += w; alpha += a*w; red += (p >> 16 & 255)*a*w; green += (p >> 8 & 255)*a*w; blue += (p & 255)*a*w;
        }
        if (alpha <= 0 || Math.abs(weight) < .000001) return 0;
        return clamp((int)Math.round(alpha*255/weight)) << 24 | clamp((int)Math.round(red/alpha)) << 16
                | clamp((int)Math.round(green/alpha)) << 8 | clamp((int)Math.round(blue/alpha));
    }
    public static void main(String[] args) {
        int[] pixels = {0xff404040, 0xff808080, 0xffc0c0c0}; autoContrast(pixels);
        assert pixels[0] == 0xff000000 && pixels[2] == 0xffffffff;
        int[] flat = {0xff777777, 0xff777777}; autoContrast(flat); assert flat[0] == 0xff777777;
        assert color(0xff000000, false, true, 0) == 0xffffffff;
        assert color(0xffff0000, true, false, 0) == 0xff4c4c4c;
        assert kernel(0, 2) == 1 && kernel(3, 2) == 0;
        assert sample(new int[]{0xff555555, 0xff555555}, 0, 1, 2, .5, 3, 2) == 0xff555555;
    }
}
