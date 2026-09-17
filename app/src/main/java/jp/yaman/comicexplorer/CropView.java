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
    private int dragEdges;
    private RectF before;
    private Runnable changed;
    public void setOnSelectionChanged(Runnable listener) { changed = listener; }
    public CropView(Context context, Bitmap bitmap) {
        super(context); this.bitmap = bitmap; setMinimumHeight(Ui.dp(context, 320));
        setContentDescription(I18n.t(R.string.ui_drag_to_select_a_crop_area));
    }
    public RectF selection() { return new RectF(selected); }
    public void setSelection(RectF crop) {
        if (crop != null && Float.isFinite(crop.left) && Float.isFinite(crop.top) && Float.isFinite(crop.right) && Float.isFinite(crop.bottom)
                && crop.left >= 0 && crop.top >= 0 && crop.right <= 1 && crop.bottom <= 1 && crop.width() >= .009f && crop.height() >= .009f) selected.set(crop);
        invalidate();
    }
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
        paint.setColor(0xff42a5f5); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(), 2)); canvas.drawRect(crop, paint);
        paint.setStyle(Paint.Style.FILL);
        for(float x : new float[]{crop.left,crop.right}) for(float y : new float[]{crop.top,crop.bottom}) canvas.drawCircle(x,y,Ui.dp(getContext(),6),paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image.width() <= 0 || image.height() <= 0) return true;
        float x = Math.max(0, Math.min(1, (event.getX()-image.left)/image.width()));
        float y = Math.max(0, Math.min(1, (event.getY()-image.top)/image.height()));
        int action=event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX=x; startY=y; before=new RectF(selected); dragEdges=0;
            float handle=Ui.dp(getContext(),24);
            if(Math.abs(x-selected.left)*image.width()<handle) dragEdges|=1;
            if(Math.abs(x-selected.right)*image.width()<handle) dragEdges|=2;
            if(Math.abs(y-selected.top)*image.height()<handle) dragEdges|=4;
            if(Math.abs(y-selected.bottom)*image.height()<handle) dragEdges|=8;
            if(dragEdges==0 && selected.contains(x,y)) dragEdges=16;
            if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && before!=null) {
            if(dragEdges==16) {
                float dx=Math.max(-before.left,Math.min(1-before.right,x-startX));
                float dy=Math.max(-before.top,Math.min(1-before.bottom,y-startY));
                selected.set(before); selected.offset(dx,dy);
            } else if(dragEdges!=0) {
                if((dragEdges&1)!=0)setEdge(0,x); if((dragEdges&2)!=0)setEdge(2,x);
                if((dragEdges&4)!=0)setEdge(1,y); if((dragEdges&8)!=0)setEdge(3,y);
            } else if (Math.abs(x-startX) > .01f && Math.abs(y-startY) > .01f) selected.set(Math.min(x,startX), Math.min(y,startY), Math.max(x,startX), Math.max(y,startY));
            invalidate(); if(changed!=null)changed.run();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
            before=null; if(action==MotionEvent.ACTION_UP)performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
