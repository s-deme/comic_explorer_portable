package jp.yaman.comicexplorer;

import java.util.Comparator;
import java.util.Locale;

/** File-format rules shared by the library and reader. */
public final class ComicFile {
    public static final Comparator<String> NATURAL_NAME_ORDER = ComicFile::compareNaturally;

    private ComicFile() { }

    public static boolean isSupported(String name, String mime) {
        String extension = extension(name);
        return isImage(name, mime) || isPdf(extension, mime) || isArchive(extension, mime);
    }

    public static boolean isImage(String name, String mime) {
        if (mime != null && mime.startsWith("image/")) return true;
        String extension = extension(name);
        return "jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension)
                || "gif".equals(extension) || "bmp".equals(extension) || "webp".equals(extension)
                || "avif".equals(extension);
    }

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String kindFor(String name, String mime) {
        if (isImage(name, mime)) return "画像";
        String extension = extension(name);
        if (isPdf(extension, mime)) return "PDF";
        return "cbz".equals(extension) ? "CBZ" : "ZIP";
    }

    private static boolean isPdf(String extension, String mime) {
        return "pdf".equals(extension) || "application/pdf".equals(mime);
    }

    private static boolean isArchive(String extension, String mime) {
        return "zip".equals(extension) || "cbz".equals(extension) || "application/zip".equals(mime)
                || "application/x-cbz".equals(mime) || "application/vnd.comicbook+zip".equals(mime);
    }

    public static String formatSize(long bytes) {
        return bytes < 1024 * 1024
                ? Math.max(1, bytes / 1024) + " KB"
                : String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    private static int compareNaturally(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftStart = leftIndex;
                int rightStart = rightIndex;
                while (leftIndex < left.length() && Character.isDigit(left.charAt(leftIndex))) leftIndex++;
                while (rightIndex < right.length() && Character.isDigit(right.charAt(rightIndex))) rightIndex++;
                String leftDigits = left.substring(leftStart, leftIndex);
                String rightDigits = right.substring(rightStart, rightIndex);
                try {
                    long leftNumber = Long.parseLong(leftDigits);
                    long rightNumber = Long.parseLong(rightDigits);
                    if (leftNumber != rightNumber) return leftNumber < rightNumber ? -1 : 1;
                } catch (NumberFormatException ignored) {
                    int difference = leftDigits.compareToIgnoreCase(rightDigits);
                    if (difference != 0) return difference;
                }
            } else {
                int difference = Character.toLowerCase(leftChar) - Character.toLowerCase(rightChar);
                if (difference != 0) return difference;
                leftIndex++;
                rightIndex++;
            }
        }
        return left.length() - right.length();
    }
}
