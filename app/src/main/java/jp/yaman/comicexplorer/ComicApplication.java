package jp.yaman.comicexplorer;

/** Initialize localized resources before any activity or background operation uses them. */
public final class ComicApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        I18n.configure(this);
    }
}
