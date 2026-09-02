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
import android.widget.Toast;

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
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.setBackgroundColor(Ui.LIGHT_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("戻る");
        Ui.styleButton(back, Ui.ButtonStyle.GHOST);
        back.setContentDescription("設定を閉じる");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(64), dp(48)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(6), 0, 0, 0);
        TextView eyebrow = text("PREFERENCES", 14, Ui.BRAND_DARK);
        Ui.label(eyebrow);
        eyebrow.setLetterSpacing(.08f);
        identity.addView(eyebrow);
        TextView title = text("設定", 28, Ui.TEXT_PRIMARY);
        Ui.title(title);
        identity.addView(title);
        header.addView(identity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        TextView intro = text("読むときの動きと、端末内に残す情報を管理します。", 14, Ui.TEXT_SECONDARY);
        intro.setPadding(dp(70), 0, 0, dp(10));
        root.addView(intro);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addReadingSettings();
        addStorageSettings();

        LinearLayout privacyCard = new LinearLayout(this);
        privacyCard.setOrientation(LinearLayout.VERTICAL);
        privacyCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        Ui.styleInsetPanel(privacyCard);
        TextView privacyTitle = text("PRIVACY BY DESIGN", 14, Ui.BRAND_DARK);
        Ui.label(privacyTitle);
        privacyTitle.setLetterSpacing(.07f);
        privacyCard.addView(privacyTitle);
        TextView privacy = text("選択したフォルダだけを読み取ります。広告・課金・アカウント・ネットワーク通信はありません。", 14, Ui.TEXT_SECONDARY);
        privacy.setLineSpacing(0, 1.08f);
        privacy.setPadding(0, dp(4), 0, 0);
        privacyCard.addView(privacy);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyParams.setMargins(0, dp(18), 0, dp(6));
        content.addView(privacyCard, privacyParams);
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
    }

    private void addReadingSettings() {
        LinearLayout card = section("読書体験", "ページ送りと画面表示");
        CheckBox keepScreen = new CheckBox(this);
        keepScreen.setText("読書中は画面を消灯しない");
        Ui.styleCheckable(keepScreen);
        keepScreen.setChecked(AppState.keepScreenOn(this));
        keepScreen.setOnCheckedChangeListener((button, checked) -> AppState.setKeepScreenOn(this, checked));
        card.addView(keepScreen);
        divider(card);

        CheckBox fullscreen = new CheckBox(this);
        fullscreen.setText("作品を開いたら全画面表示にする");
        Ui.styleCheckable(fullscreen);
        fullscreen.setChecked(AppState.startFullscreen(this));
        fullscreen.setOnCheckedChangeListener((button, checked) -> AppState.setStartFullscreen(this, checked));
        card.addView(fullscreen);
        divider(card);

        CheckBox volume = new CheckBox(this);
        volume.setText("音量キーでページを移動する");
        Ui.styleCheckable(volume);
        volume.setChecked(AppState.volumeNavigation(this));
        volume.setOnCheckedChangeListener((button, checked) -> AppState.setVolumeNavigation(this, checked));
        card.addView(volume);
        divider(card);

        TextView directionLabel = text("ページ送り方向", 14, Ui.TEXT_SECONDARY);
        Ui.label(directionLabel);
        directionLabel.setPadding(dp(14), dp(12), dp(14), dp(2));
        card.addView(directionLabel);
        RadioGroup direction = new RadioGroup(this);
        direction.setOrientation(RadioGroup.VERTICAL);
        direction.setPadding(dp(6), 0, dp(6), dp(6));
        RadioButton ltr = new RadioButton(this);
        ltr.setText("左から右");
        Ui.styleCheckable(ltr);
        ltr.setId(View.generateViewId());
        RadioButton rtl = new RadioButton(this);
        rtl.setText("右から左（漫画向け）");
        Ui.styleCheckable(rtl);
        rtl.setId(View.generateViewId());
        direction.addView(ltr);
        direction.addView(rtl);
        direction.check(AppState.direction(this) == AppState.DIRECTION_RTL ? rtl.getId() : ltr.getId());
        direction.setOnCheckedChangeListener((group, id) -> AppState.setDirection(this, id == rtl.getId() ? AppState.DIRECTION_RTL : AppState.DIRECTION_LTR));
        card.addView(direction);
        divider(card);

        Button fit = wideButton("初期表示: " + fitLabel(AppState.fitMode(this)));
        fit.setContentDescription("初期表示方法を変更");
        fit.setOnClickListener(view -> Ui.show(new AlertDialog.Builder(this)
                .setTitle("初期表示")
                .setSingleChoiceItems(new String[]{"画面に合わせる", "幅に合わせる", "高さに合わせる"}, AppState.fitMode(this), (dialog, index) -> {
                    AppState.setFitMode(this, index);
                    fit.setText("初期表示: " + fitLabel(index));
                    dialog.dismiss();
                })));
        LinearLayout.LayoutParams fitParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        fitParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        card.addView(fit, fitParams);
    }

    private void addStorageSettings() {
        LinearLayout card = section("端末内データ", "履歴や保存位置の整理");
        Button clearProgress = dangerWideButton("復帰位置としおりを消去");
        clearProgress.setOnClickListener(view -> confirm("復帰位置としおりを消去しますか？", () -> AppState.clearReadingData(this)));
        addAction(card, clearProgress, true);
        Button clearRecent = dangerWideButton("最近開いた作品を消去");
        clearRecent.setOnClickListener(view -> confirm("最近開いた作品の一覧を消去しますか？", () -> AppState.clearRecents(this)));
        addAction(card, clearRecent, false);
        Button resetLibrary = dangerWideButton("ライブラリの選択を解除");
        resetLibrary.setOnClickListener(view -> confirm("選択したフォルダとお気に入りを解除しますか？ 作品ファイルは削除されません。", () -> {
            AppState.clearLibrary(this);
            finish();
        }));
        addAction(card, resetLibrary, false);
    }

    private LinearLayout section(String title, String subtitle) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(2), dp(18), dp(2), dp(8));
        TextView titleView = text(title, 20, Ui.TEXT_PRIMARY);
        Ui.title(titleView);
        heading.addView(titleView);
        TextView subtitleView = text(subtitle, 14, Ui.TEXT_SECONDARY);
        heading.addView(subtitleView);
        content.addView(heading);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(4), dp(6), dp(4));
        Ui.styleCard(card, false);
        content.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void divider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.OUTLINE_VARIANT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(dp(14), 0, dp(14), 0);
        parent.addView(divider, params);
    }

    private void addAction(LinearLayout parent, Button button, boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(dp(8), first ? dp(8) : dp(4), dp(8), dp(4));
        parent.addView(button, params);
    }

    private void confirm(String message, Runnable action) {
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setMessage(message).setNegativeButton("キャンセル", null).setPositiveButton("消去", (ignored, which) -> {
            action.run();
            Toast.makeText(this, "端末内データを消去しました", Toast.LENGTH_SHORT).show();
        }));
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
        Ui.styleButton(button, Ui.ButtonStyle.DANGER_TONAL);
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
