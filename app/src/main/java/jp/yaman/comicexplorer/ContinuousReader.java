package jp.yaman.comicexplorer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Recycles only visible page views, so large books do not allocate one bitmap per page. */
public final class ContinuousReader extends ListView {
    public interface Decoder { android.graphics.drawable.Drawable decode(int page) throws Exception; }
    private final Activity activity;
    private final ExecutorService worker;
    private final Decoder decoder;
    private final IntConsumer position;
    private final Consumer<Boolean> edgeNavigation;
    private final ZoomImageView.InteractionListener interaction;
    private final int edgeSlop;
    private int count, generation;
    private boolean stopped, edgeAtStart, edgeAtEnd, edgeMultiTouch;
    private float edgeDownY;
    private android.animation.ValueAnimator scrollAnimation;
    public ContinuousReader(Activity activity, ExecutorService worker, Decoder decoder, IntConsumer position, Consumer<Boolean> edgeNavigation, ZoomImageView.InteractionListener interaction) {
        super(activity); this.activity = activity; this.worker = worker; this.decoder = decoder; this.position = position; this.edgeNavigation = edgeNavigation; this.interaction = interaction;
        edgeSlop=android.view.ViewConfiguration.get(activity).getScaledTouchSlop()*4;
        setBackgroundColor(0xff101114);
        setDivider(new android.graphics.drawable.ColorDrawable(0xff555555));
        setDividerHeight(Ui.dp(activity, AppState.number(activity, "page_gap", 0)) + (AppState.enabled(activity, "scroll_divider", false) ? 1 : 0));
        setAdapter(adapter);
        setOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { if (visible > 0) position.accept(first); }
        });
    }
    public void reset(int pages, int page) { cancelScroll();generation++; count = pages; adapter.notifyDataSetChanged(); setSelection(page); }
    public void setPageCount(int pages) { if(count!=pages){count=pages;adapter.notifyDataSetChanged();} }
    public void stop() {
        cancelScroll();stopped = true; generation++;
        for(int i=0;i<getChildCount();i++)if(getChildAt(i) instanceof android.widget.ImageView) {
            android.graphics.drawable.Drawable drawable=((android.widget.ImageView)getChildAt(i)).getDrawable();
            if(drawable instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)drawable).stop();
        }
    }
    private void cancelScroll(){if(scrollAnimation!=null){scrollAnimation.cancel();scrollAnimation=null;}}
    static int edgeDirection(boolean atStart, boolean atEnd, float distance, int slop) {
        if (Math.abs(distance) <= slop) return 0;
        return distance < 0 && atEnd ? 1 : distance > 0 && atStart ? -1 : 0;
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action=event.getActionMasked();
        if(action==android.view.MotionEvent.ACTION_DOWN) {
            cancelScroll(); edgeAtStart=!canScrollVertically(-1); edgeAtEnd=!canScrollVertically(1); edgeDownY=event.getY(); edgeMultiTouch=false;
        } else if(action==android.view.MotionEvent.ACTION_POINTER_DOWN) edgeMultiTouch=true;
        int direction=action==android.view.MotionEvent.ACTION_UP && !edgeMultiTouch ? edgeDirection(edgeAtStart,edgeAtEnd,event.getY()-edgeDownY,edgeSlop) : 0;
        boolean handled=super.dispatchTouchEvent(event);
        if(direction!=0)edgeNavigation.accept(direction>0);
        return handled;
    }
    public void move(boolean forward) {
        int overlap=Math.round(AppState.number(activity,"scroll_overlap",23)*getResources().getDisplayMetrics().scaledDensity);
        int length=Math.max(1,getHeight()-overlap);
        if(!AppState.prefs(activity).contains("setting.scroll_overlap") && AppState.prefs(activity).contains("setting.scroll_length"))length=Math.round(getHeight()*AppState.number(activity,"scroll_length",90)/100f);
        int distance=length*(forward ? 1 : -1);
        int duration=Math.min(2000,(int)((length/(float)Math.max(1,getHeight())+1)*300));
        cancelScroll();
        if (AppState.enabled(activity, "scroll_smooth", false)) {
            scrollAnimation=android.animation.ValueAnimator.ofInt(0,distance);scrollAnimation.setDuration(duration);
            scrollAnimation.setInterpolator(value -> {float t=value-1;return t*t*t*t*t+1;});
            int[] previous={0};scrollAnimation.addUpdateListener(value -> {int next=(Integer)value.getAnimatedValue();scrollListBy(next-previous[0]);previous[0]=next;});scrollAnimation.start();
        } else scrollListBy(distance);
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
                android.graphics.drawable.Drawable bitmap = null; String error = null;
                try { bitmap = decoder.decode(index); } catch (Exception | OutOfMemoryError e) { error = e.getMessage(); }
                android.graphics.drawable.Drawable result = bitmap; String failure = error;
                activity.runOnUiThread(() -> {
                    if (stopped || token != generation || image.getTag() != binding || activity.isFinishing()) return;
                    if (result == null) { image.setContentDescription((index+1) + I18n.t(R.string.ui_could_not_load_page) + failure); return; }
                    image.getLayoutParams().height = Math.max(48, Math.round(getWidth() * result.getIntrinsicHeight() / (float)result.getIntrinsicWidth()));
                    image.setImageDrawable(result); image.requestLayout();image.post(image::fitImage);
                });
            });
            return image;
        }
    };
}
