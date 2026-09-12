package jp.yaman.comicexplorer;

import android.app.Activity;
import android.os.Bundle;

/** Recreate existing screens when the global language or theme changes. */
public abstract class BaseActivity extends Activity {
    private String appearance;
    @Override public void onCreate(Bundle state) {
        appearance = appearance();
        I18n.configure(this);
        Ui.configure(this);
        setTheme(Ui.light ? R.style.AppThemeLight : R.style.AppTheme);
        super.onCreate(state);
    }
    private String appearance() {
        return AppState.number(this, "theme", 0) + ":" + AppState.value(this, "language", "system")
                + ":" + (getResources().getConfiguration().uiMode & 48);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!appearance.equals(appearance())) recreate();
    }
}
