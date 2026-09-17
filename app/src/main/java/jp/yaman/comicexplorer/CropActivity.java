package jp.yaman.comicexplorer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import java.io.File;

/** Private preview file keeps original book bytes untouched and survives rotation. */
public final class CropActivity extends BaseActivity {
    private CropView crop;
    private Bitmap bitmap;
    private File preview;
    private boolean reset;
    private final SeekBar[] edges = new SeekBar[4];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String name = getIntent().getStringExtra("preview");
        if (name == null || !name.matches("crop-[A-Za-z0-9-]+\\.png")) { finish(); return; }
        preview = new File(getCacheDir(), name);
        bitmap = BitmapFactory.decodeFile(preview.getPath());
        if (bitmap == null) { finish(); return; }
        crop = new CropView(this, bitmap); crop.setMinimumHeight(0);
        float[] initial = state == null ? getIntent().getFloatArrayExtra("crop") : state.getFloatArray("crop");
        if (initial != null && initial.length == 4) crop.setSelection(new RectF(initial[0], initial[1], initial[2], initial[3]));
        reset = state != null && state.getBoolean("reset");
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.DARK_BACKGROUND);
        root.addView(Ui.text(this, I18n.t(R.string.ui_margin_cropping), 20, Ui.DARK_TEXT));
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        int[] names = {R.string.ui_left, R.string.ui_top, R.string.ui_right, R.string.ui_bottom};
        for (int i=0; i<4; i++) {
            final int edge=i;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(Ui.text(this,I18n.t(names[i]),14,Ui.DARK_TEXT),new LinearLayout.LayoutParams(Ui.dp(this,64),-2));
            SeekBar bar = new SeekBar(this); edges[i]=bar; bar.setMax(100); bar.setContentDescription(I18n.t(names[i])); Ui.styleSeekBar(bar,true);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar view,int value,boolean user) { if(user) {reset=false; crop.setEdge(edge,value/100f); syncEdges();} }
                public void onStartTrackingTouch(SeekBar view) { }
                public void onStopTrackingTouch(SeekBar view) { }
            });
            row.addView(bar,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1)); controls.addView(row);
        }
        crop.setOnSelectionChanged(() -> {reset=false; syncEdges();}); syncEdges();
        ScrollView scroll = new ScrollView(this); scroll.addView(controls);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(Ui.dp(this,192),getResources().getDisplayMetrics().heightPixels/3)));
        LinearLayout actions = new LinearLayout(this);
        addAction(actions,R.string.ui_cancel, this::finish);
        addAction(actions,R.string.ui_default, () -> {crop.setSelection(new RectF(0,0,1,1)); reset=true; syncEdges();});
        addAction(actions,R.string.ui_crop, () -> {RectF r=crop.selection(); setResult(RESULT_OK,new Intent().putExtra("crop",new float[]{r.left,r.top,r.right,r.bottom}).putExtra("reset",reset)); finish();});
        root.addView(actions); setContentView(root); Ui.applySystemBarInsets(this,root);
    }
    private void syncEdges() {
        RectF r=crop.selection(); float[] values={r.left,r.top,r.right,r.bottom};
        for(int i=0;i<4;i++) if(edges[i]!=null) edges[i].setProgress(Math.round(values[i]*100));
    }
    private void addAction(LinearLayout row,int label,Runnable action) {
        Button button=Ui.button(this,I18n.t(label),Ui.ButtonStyle.DARK_SECONDARY);
        button.setOnClickListener(v -> action.run());row.addView(button,new LinearLayout.LayoutParams(0,-2,1));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if(crop!=null) {RectF r=crop.selection();state.putFloatArray("crop",new float[]{r.left,r.top,r.right,r.bottom});state.putBoolean("reset",reset);}
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        if(isFinishing() && preview!=null) preview.delete();
        // Bitmap belongs to the detached view; let GC release it after pending render work.
    }
}
