package jp.yaman.comicexplorer;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

/** Uses Android resource fallback for the selected app language. */
public final class I18n {
    private static volatile Resources resources;
    private I18n() { }
    static void configure(Context context) {
        String language = AppState.value(context, "language", "system");
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(language.equals("system") ? Locale.getDefault() : Locale.forLanguageTag(language));
        resources = context.createConfigurationContext(configuration).getResources();
    }
    public static String t(int id) { return resources.getString(id); }
}
