package jp.yaman.comicexplorer;

import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import java.util.concurrent.ExecutorService;

/** At most one pending frame per visible page. All decoding and filters run on the reader worker. */
final class AnimatedPageDrawable extends Drawable implements Animatable,Runnable {
    interface Frames { Bitmap frame(long milliseconds) throws Exception; }
    private final ExecutorService worker;
    private final Frames frames;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;
    private boolean running,pending;
    private long start;
    private int generation;
    AnimatedPageDrawable(Bitmap first,ExecutorService worker,Frames frames){bitmap=first;this.worker=worker;this.frames=frames;}
    public void draw(Canvas canvas){canvas.drawBitmap(bitmap,null,getBounds(),paint);}
    public int getIntrinsicWidth(){return bitmap.getWidth();}
    public int getIntrinsicHeight(){return bitmap.getHeight();}
    public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    public boolean isRunning(){return running;}
    public void start(){if(running)return;running=true;generation++;start=SystemClock.uptimeMillis();run();}
    public void stop(){running=false;generation++;unscheduleSelf(this);}
    @Override public boolean setVisible(boolean visible,boolean restart){boolean changed=super.setVisible(visible,restart);if(visible)start();else stop();return changed;}
    public void run(){
        if(!running || pending)return;
        if(worker.isShutdown() || getCallback()==null){stop();return;}
        pending=true;int token=generation;long elapsed=SystemClock.uptimeMillis()-start;
        worker.execute(() -> {
            Bitmap next=null;
            try{next=frames.frame(elapsed);}catch(Exception|OutOfMemoryError error){/* Keep the last valid frame and stop playback. */}
            Bitmap result=next;
            main.post(() -> {
                pending=false;
                if(!running || token!=generation){if(running)run();return;}
                if(result==null){stop();return;}
                bitmap=result;invalidateSelf();scheduleSelf(this,SystemClock.uptimeMillis()+33);
            });
        });
    }
}
