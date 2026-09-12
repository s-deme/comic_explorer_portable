package jp.yaman.comicexplorer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

/** Recycles only visible page views, so large books do not allocate one bitmap per page. */
public final class ContinuousReader extends ListView {
    public interface Decoder { Bitmap decode(int page) throws Exception; }
    private final Activity activity;
    private final ExecutorService worker;
    private final Decoder decoder;
    private final IntConsumer position;
    private final ZoomImageView.InteractionListener interaction;
    private int count, generation;
    private boolean stopped;
    public ContinuousReader(Activity activity, ExecutorService worker, Decoder decoder, IntConsumer position, ZoomImageView.InteractionListener interaction) {
        super(activity); this.activity = activity; this.worker = worker; this.decoder = decoder; this.position = position; this.interaction = interaction;
        setBackgroundColor(0xff101114);
        setDivider(new android.graphics.drawable.ColorDrawable(0xff555555));
        setDividerHeight(Ui.dp(activity, AppState.number(activity, "page_gap", 0)) + (AppState.enabled(activity, "scroll_divider", true) ? 1 : 0));
        setAdapter(adapter);
        setOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { if (visible > 0) position.accept(first); }
        });
    }
    public void reset(int pages, int page) { generation++; count = pages; adapter.notifyDataSetChanged(); setSelection(page); }
    public void stop() { stopped = true; generation++; }
    public void move(boolean forward) {
        int distance = Math.round(getHeight() * AppState.number(activity, "scroll_length", 90) / 100f) * (forward ? 1 : -1);
        if (AppState.enabled(activity, "scroll_smooth", true)) smoothScrollBy(distance, 250); else scrollListBy(distance);
    }
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return count; }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int index, View convert, ViewGroup parent) {
            ZoomImageView image = convert instanceof ZoomImageView ? (ZoomImageView) convert : new ZoomImageView(activity);
            if (image.getTag() instanceof java.util.concurrent.atomic.AtomicBoolean) ((java.util.concurrent.atomic.AtomicBoolean)image.getTag()).set(true);
            int token = generation; java.util.concurrent.atomic.AtomicBoolean binding = new java.util.concurrent.atomic.AtomicBoolean(); image.setTag(binding);
            image.setImageDrawable(null); image.setFitMode(AppState.FIT_WIDTH); image.setVerticalPaging(false);
            image.setDoubleTapMode(AppState.doubleTapMode(activity)); image.setDoubleTapScale(AppState.doubleTapScale(activity)/100f);
            image.setInteractionListener(interaction); image.setContentDescription((index+1) + I18n.t(R.string.ui_pages_2));
            image.setLayoutParams(new AbsListView.LayoutParams(-1, Math.max(1, getHeight())));
            worker.execute(() -> {
                if (stopped || token != generation || binding.get()) return;
                Bitmap bitmap = null; String error = null;
                try { bitmap = decoder.decode(index); } catch (Exception | OutOfMemoryError e) { error = e.getMessage(); }
                Bitmap result = bitmap; String failure = error;
                activity.runOnUiThread(() -> {
                    if (stopped || token != generation || image.getTag() != binding || activity.isFinishing()) return;
                    if (result == null) { image.setContentDescription((index+1) + I18n.t(R.string.ui_could_not_load_page) + failure); return; }
                    image.getLayoutParams().height = Math.max(48, Math.round(getWidth() * result.getHeight() / (float)result.getWidth()));
                    image.setImageBitmap(result); image.requestLayout();
                });
            });
            return image;
        }
    };
}
