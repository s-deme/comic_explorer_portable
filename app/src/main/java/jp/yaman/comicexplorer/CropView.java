package jp.yaman.comicexplorer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Drag out a crop rectangle; normalized coordinates keep the crop resolution independent. */
public final class CropView extends View {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF image = new RectF(), selected = new RectF(0, 0, 1, 1);
    private float startX, startY;
    public CropView(Context context, Bitmap bitmap) {
        super(context); this.bitmap = bitmap; setMinimumHeight(Ui.dp(context, 320));
        setContentDescription(I18n.t(R.string.ui_drag_to_select_a_crop_area));
    }
    public RectF selection() { return new RectF(selected); }
    public void setEdge(int edge, float value) {
        value=Math.max(0,Math.min(1,value));
        if(edge==0)selected.left=Math.min(value,selected.right-.01f);
        if(edge==1)selected.top=Math.min(value,selected.bottom-.01f);
        if(edge==2)selected.right=Math.max(value,selected.left+.01f);
        if(edge==3)selected.bottom=Math.max(value,selected.top+.01f);
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(0xff101114);
        float scale = Math.min(getWidth()/(float)bitmap.getWidth(), getHeight()/(float)bitmap.getHeight());
        float w = bitmap.getWidth()*scale, h = bitmap.getHeight()*scale;
        image.set((getWidth()-w)/2, (getHeight()-h)/2, (getWidth()+w)/2, (getHeight()+h)/2);
        paint.setStyle(Paint.Style.FILL); canvas.drawBitmap(bitmap, null, image, paint);
        RectF crop = new RectF(image.left+selected.left*w, image.top+selected.top*h, image.left+selected.right*w, image.top+selected.bottom*h);
        paint.setColor(0x99000000);
        canvas.drawRect(image.left, image.top, image.right, crop.top, paint); canvas.drawRect(image.left, crop.bottom, image.right, image.bottom, paint);
        canvas.drawRect(image.left, crop.top, crop.left, crop.bottom, paint); canvas.drawRect(crop.right, crop.top, image.right, crop.bottom, paint);
        paint.setColor(0xffffffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(), 2)); canvas.drawRect(crop, paint);
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image.width() <= 0 || image.height() <= 0) return true;
        float x = Math.max(0, Math.min(1, (event.getX()-image.left)/image.width()));
        float y = Math.max(0, Math.min(1, (event.getY()-image.top)/image.height()));
        if (event.getAction() == MotionEvent.ACTION_DOWN) { startX=x; startY=y; getParent().requestDisallowInterceptTouchEvent(true); }
        if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP) {
            if (Math.abs(x-startX) > .01f && Math.abs(y-startY) > .01f) selected.set(Math.min(x,startX), Math.min(y,startY), Math.max(x,startX), Math.max(y,startY));
            invalidate();
        }
        if (event.getAction() == MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
