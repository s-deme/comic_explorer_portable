package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small local settings surface; it deliberately contains no account or network options. */
public final class SettingsActivity extends Activity {
    private LinearLayout content;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(16));
        root.setBackgroundColor(Ui.LIGHT_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("戻る");
        back.setContentDescription("設定を閉じる");
        back.setOnClickListener(view -> finish());
        header.addView(back);
        TextView title = text("設定", 24, Ui.TEXT_PRIMARY);
        title.setPadding(dp(8), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addReadingSettings();
        addStorageSettings();

        TextView privacy = text("このアプリは端末内で選択したフォルダだけを読み取ります。広告・課金・アカウント・ネットワーク通信はありません。", 14, Ui.TEXT_SECONDARY);
        privacy.setPadding(0, dp(16), 0, dp(8));
        content.addView(privacy);
        setContentView(root);
    }

    private void addReadingSettings() {
        section("読書設定");
        CheckBox keepScreen = new CheckBox(this);
        keepScreen.setText("読書中は画面を消灯しない");
        Ui.styleCheckable(keepScreen);
        keepScreen.setChecked(AppState.keepScreenOn(this));
        keepScreen.setOnCheckedChangeListener((button, checked) -> AppState.setKeepScreenOn(this, checked));
        content.addView(keepScreen);

        CheckBox fullscreen = new CheckBox(this);
        fullscreen.setText("作品を開いたら全画面表示にする");
        Ui.styleCheckable(fullscreen);
        fullscreen.setChecked(AppState.startFullscreen(this));
        fullscreen.setOnCheckedChangeListener((button, checked) -> AppState.setStartFullscreen(this, checked));
        content.addView(fullscreen);

        CheckBox volume = new CheckBox(this);
        volume.setText("音量キーでページを移動する");
        Ui.styleCheckable(volume);
        volume.setChecked(AppState.volumeNavigation(this));
        volume.setOnCheckedChangeListener((button, checked) -> AppState.setVolumeNavigation(this, checked));
        content.addView(volume);

        TextView directionLabel = text("ページ送り方向", 16, Ui.TEXT_PRIMARY);
        directionLabel.setPadding(0, dp(12), 0, dp(2));
        content.addView(directionLabel);
        RadioGroup direction = new RadioGroup(this);
        direction.setOrientation(RadioGroup.VERTICAL);
        RadioButton ltr = new RadioButton(this);
        ltr.setText("左から右");
        Ui.styleCheckable(ltr);
        ltr.setId(1);
        RadioButton rtl = new RadioButton(this);
        rtl.setText("右から左（漫画向け）");
        Ui.styleCheckable(rtl);
        rtl.setId(2);
        direction.addView(ltr);
        direction.addView(rtl);
        direction.check(AppState.direction(this) == AppState.DIRECTION_RTL ? 2 : 1);
        direction.setOnCheckedChangeListener((group, id) -> AppState.setDirection(this, id == 2 ? AppState.DIRECTION_RTL : AppState.DIRECTION_LTR));
        content.addView(direction);

        Button fit = wideButton("初期表示: " + fitLabel(AppState.fitMode(this)));
        fit.setContentDescription("初期表示方法を変更");
        fit.setOnClickListener(view -> Ui.show(new AlertDialog.Builder(this)
                .setTitle("初期表示")
                .setSingleChoiceItems(new String[]{"画面に合わせる", "幅に合わせる", "高さに合わせる"}, AppState.fitMode(this), (dialog, index) -> {
                    AppState.setFitMode(this, index);
                    fit.setText("初期表示: " + fitLabel(index));
                    dialog.dismiss();
                })));
        content.addView(fit);
    }

    private void addStorageSettings() {
        section("端末内データ");
        Button clearProgress = dangerWideButton("復帰位置としおりを消去");
        clearProgress.setOnClickListener(view -> confirm("復帰位置としおりを消去しますか？", () -> AppState.clearReadingData(this)));
        content.addView(clearProgress);
        Button clearRecent = dangerWideButton("最近開いた作品を消去");
        clearRecent.setOnClickListener(view -> confirm("最近開いた作品の一覧を消去しますか？", () -> AppState.clearRecents(this)));
        content.addView(clearRecent);
        Button resetLibrary = dangerWideButton("ライブラリの選択を解除");
        resetLibrary.setOnClickListener(view -> confirm("選択したフォルダとお気に入りを解除しますか？ 作品ファイルは削除されません。", () -> {
            AppState.clearLibrary(this);
            finish();
        }));
        content.addView(resetLibrary);
    }

    private void section(String title) {
        TextView heading = text(title, 18, Ui.BRAND_DARK);
        heading.setPadding(0, dp(18), 0, dp(6));
        content.addView(heading);
    }

    private void confirm(String message, Runnable action) {
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setMessage(message).setNegativeButton("キャンセル", null).setPositiveButton("消去", (ignored, which) -> action.run()));
        Ui.styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), Ui.ButtonStyle.DANGER);
    }

    private String fitLabel(int mode) {
        return mode == AppState.FIT_WIDTH ? "幅に合わせる" : mode == AppState.FIT_HEIGHT ? "高さに合わせる" : "画面に合わせる";
    }

    private Button wideButton(String label) {
        Button button = button(label);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setAllCaps(false);
        return button;
    }

    private Button dangerWideButton(String label) {
        Button button = wideButton(label);
        Ui.styleButton(button, Ui.ButtonStyle.DANGER);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return button;
    }

    private Button button(String value) {
        return Ui.button(this, value, Ui.ButtonStyle.SECONDARY);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(this, value, size, color);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
