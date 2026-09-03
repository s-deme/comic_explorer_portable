package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

/** Dense ComicScreen-style preferences backed only by local app state. */
public final class SettingsActivity extends Activity {
    private LinearLayout content;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.TOOLBAR);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setBackgroundColor(Ui.TOOLBAR);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        Ui.styleToolbarButton(back, Ui.TOOLBAR);
        back.setContentDescription("設定を閉じる");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));
        TextView title = text("設定", 20, Ui.TOOLBAR_TEXT);
        Ui.title(title);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.DARK_BACKGROUND);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addGeneralSettings();
        addListSettings();
        addImageSettings();
        addDataSettings();
        addInformation();
        setContentView(root);
        Ui.applySystemBarInsets(this, root);
    }

    private void addGeneralSettings() {
        LinearLayout section = section("GENERAL");
        addCheck(section, "読書中は画面を消灯しない", AppState.keepScreenOn(this),
                (button, checked) -> AppState.setKeepScreenOn(this, checked));
        addCheck(section, "前回の位置から再開する", AppState.resumeLastPosition(this),
                (button, checked) -> AppState.setResumeLastPosition(this, checked));
        addCheck(section, "作品を開いたら全画面表示にする", AppState.startFullscreen(this),
                (button, checked) -> AppState.setStartFullscreen(this, checked));
        addCheck(section, "画面上にページ移動ボタンを表示", AppState.pageButtons(this),
                (button, checked) -> AppState.setPageButtons(this, checked));
        addChoice(section, "ページボタン透明度", new String[]{"30%", "50%", "70%", "100%"},
                opacityIndex(AppState.pageButtonOpacity(this)), index -> AppState.setPageButtonOpacity(this, new int[]{30, 50, 70, 100}[index]));
        addChoice(section, "ページボタンサイズ", new String[]{"小", "標準", "大"},
                AppState.pageButtonHeight(this) <= 72 ? 0 : AppState.pageButtonHeight(this) >= 128 ? 2 : 1,
                index -> AppState.setPageButtonHeight(this, new int[]{64, 96, 144}[index]));
        addCheck(section, "音量キーでページを移動する", AppState.volumeNavigation(this),
                (button, checked) -> AppState.setVolumeNavigation(this, checked));
        addCheck(section, "音量キーのページ移動を反転", AppState.reverseVolumeNavigation(this),
                (button, checked) -> AppState.setReverseVolumeNavigation(this, checked));
    }

    private void addListSettings() {
        LinearLayout section = section("LIST VIEW");
        addCheck(section, "サムネイルをグリッド表示", AppState.gridView(this),
                (button, checked) -> AppState.setGridView(this, checked));
        addChoice(section, "グリッド列数", new String[]{"2列", "3列", "4列"},
                AppState.gridColumns(this) - 2, index -> AppState.setGridColumns(this, index + 2));
        addCheck(section, "パスと件数を表示", AppState.showLibraryPath(this),
                (button, checked) -> AppState.setShowLibraryPath(this, checked));
        addCheck(section, "スクロールバーを左側に表示", AppState.leftLibraryScrollbar(this),
                (button, checked) -> AppState.setLeftLibraryScrollbar(this, checked));
    }

    private void addImageSettings() {
        LinearLayout section = section("IMAGE VIEW");
        addChoice(section, "ページ送り方向", new String[]{"左から右", "右から左（漫画向け）"},
                AppState.direction(this), index -> AppState.setDirection(this, index));
        addChoice(section, "ページ移動", new String[]{"横スワイプ", "縦スワイプ"},
                AppState.readingFlow(this), index -> AppState.setReadingFlow(this, index));
        addChoice(section, "ページレイアウト", new String[]{"単ページ", "見開き", "自動（横画面は見開き）"},
                AppState.pageLayout(this), index -> AppState.setPageLayout(this, index));
        addCheck(section, "見開きの中央に境界線を表示", AppState.dualPageDivider(this),
                (button, checked) -> AppState.setDualPageDivider(this, checked));
        addChoice(section, "表示方法", new String[]{"画面に合わせる", "幅に合わせる", "高さに合わせる", "画面いっぱいに伸縮"},
                AppState.fitMode(this), index -> AppState.setFitMode(this, index));
        addChoice(section, "ダブルタップ", new String[]{"無効", "拡大", "フィット", "拡大／フィット切替"},
                AppState.doubleTapMode(this), index -> AppState.setDoubleTapMode(this, index));
        String[] scales = {"1.5倍", "2.0倍", "2.25倍", "2.5倍", "3.0倍", "4.0倍"};
        int[] scaleValues = {150, 200, 225, 250, 300, 400};
        int currentScale = 2;
        for (int index = 0; index < scaleValues.length; index++) if (scaleValues[index] == AppState.doubleTapScale(this)) currentScale = index;
        addChoice(section, "ダブルタップ倍率", scales, currentScale,
                index -> AppState.setDoubleTapScale(this, scaleValues[index]));
        addChoice(section, "画像フィルター", new String[]{"なし", "グレースケール", "自動コントラスト", "セピア", "ブルーライト軽減"},
                AppState.imageFilter(this), index -> AppState.setImageFilter(this, index));
        int[] cropValues = {0, 2, 5, 10};
        int cropIndex = 0;
        for (int index = 0; index < cropValues.length; index++) if (cropValues[index] == AppState.cropPercent(this)) cropIndex = index;
        addChoice(section, "ページ余白を切り取る", new String[]{"なし", "2%", "5%", "10%"}, cropIndex,
                index -> AppState.setCropPercent(this, cropValues[index]));
        addChoice(section, "ZIP文字コード", new String[]{"UTF-8", "Shift_JIS"},
                AppState.archiveEncoding(this), index -> AppState.setArchiveEncoding(this, index));
    }

    private void addDataSettings() {
        LinearLayout section = section("CACHE DATA");
        addDanger(section, "カスタム表紙を消去", "設定したカスタム表紙をすべて消去しますか？ 作品ファイルは削除されません。", () -> AppState.clearCovers(this));
        addDanger(section, "復帰位置としおりを消去", "復帰位置としおりを消去しますか？", () -> AppState.clearReadingData(this));
        addDanger(section, "最近開いた作品を消去", "最近開いた作品の一覧を消去しますか？", () -> AppState.clearRecents(this));
        addDanger(section, "登録ディレクトリを解除", "登録したディレクトリの一覧を解除しますか？ フォルダや作品ファイルは削除されません。", () -> AppState.clearDirectories(this));
        addDanger(section, "ライブラリの選択を解除", "選択したフォルダ・お気に入り・カスタム表紙を解除しますか？ 作品ファイルは削除されません。", () -> {
            AppState.clearLibrary(this);
            finish();
        });
    }

    private void addInformation() {
        LinearLayout section = section("INFORMATION");
        TextView info = text("Comic Explorer 1.1.2\nローカル専用・広告なし・ネットワーク通信なし", 14, Ui.DARK_MUTED);
        info.setPadding(dp(16), dp(14), dp(16), dp(18));
        section.addView(info);
    }

    private int opacityIndex(int value) { return value <= 30 ? 0 : value <= 50 ? 1 : value <= 70 ? 2 : 3; }

    private void addCheck(LinearLayout parent, String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        CheckBox control = new CheckBox(this);
        control.setText(label);
        Ui.styleDarkCheckable(control);
        control.setChecked(checked);
        control.setOnCheckedChangeListener(listener);
        parent.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        divider(parent);
    }

    private void addChoice(LinearLayout parent, String title, String[] labels, int selected, IntConsumer setter) {
        int current = Math.max(0, Math.min(selected, labels.length - 1));
        Button button = preferenceButton(title + ": " + labels[current]);
        button.setContentDescription(title + "を変更。現在は" + labels[current]);
        button.setOnClickListener(view -> Ui.show(new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(labels, current, (dialog, index) -> {
                    setter.accept(index);
                    button.setText(title + ": " + labels[index]);
                    button.setContentDescription(title + "を変更。現在は" + labels[index]);
                    dialog.dismiss();
                })));
        parent.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        divider(parent);
    }

    private LinearLayout section(String title) {
        TextView titleView = text(title, 12, Ui.LIBRARY_ACCENT);
        Ui.label(titleView);
        titleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(16), 0, dp(16), 0);
        titleView.setBackgroundColor(Ui.DARK_SURFACE_RAISED);
        content.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(Ui.DARK_BACKGROUND);
        content.addView(section, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return section;
    }

    private Button preferenceButton(String label) {
        Button button = Ui.button(this, label, Ui.ButtonStyle.DARK_GHOST);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }

    private void addDanger(LinearLayout parent, String label, String message, Runnable action) {
        Button button = preferenceButton(label);
        button.setTextColor(0xFFFFB4AB);
        button.setOnClickListener(view -> confirm(message, action));
        parent.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        divider(parent);
    }

    private void divider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.DARK_OUTLINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(dp(16), 0, 0, 0);
        parent.addView(divider, params);
    }

    private void confirm(String message, Runnable action) {
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(this).setMessage(message).setNegativeButton("キャンセル", null)
                .setPositiveButton("消去", (ignored, which) -> {
                    action.run();
                    Toast.makeText(this, "端末内データを消去しました", Toast.LENGTH_SHORT).show();
                }));
        Ui.styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), Ui.ButtonStyle.DANGER);
    }

    private TextView text(String value, int size, int color) { return Ui.text(this, value, size, color); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
