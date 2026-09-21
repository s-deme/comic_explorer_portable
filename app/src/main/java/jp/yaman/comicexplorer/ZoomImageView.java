package jp.yaman.comicexplorer;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Dependency-free image canvas with safe fit modes, zoom, pan, tap zones and page swipes. */
public final class ZoomImageView extends ImageView {
    public interface InteractionListener {
        void onTap(float normalizedX);
        void onSwipe(int direction); // -1 = left, 1 = right
    }

    private final Matrix matrix = new Matrix();
    private final android.widget.OverScroller panScroller;
    private android.animation.ValueAnimator zoomAnimator;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float lastX;
    private float lastY;
    private float relativeScale = 1f;
    private float doubleTapScale = 1.8f;
    private int doubleTapMode = AppState.DOUBLE_TAP_OFF;
    private boolean dragging;
    private boolean multiTouch;
    private int activePointer;
    private final int touchSlop;
    private boolean verticalPaging;
    private int filterMode;
    private int fitMode = AppState.FIT_SCREEN;
    private InteractionListener listener;

    public ZoomImageView(Context context) { this(context, null); }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        panScroller = new android.widget.OverScroller(context);
        touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(0xFF101114);
        setFocusable(true);
        setContentDescription(I18n.t(R.string.ui_book_page_swipe_or_tap_the_sides_to_turn_pages));
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(1f, Math.min(maximumScale(), relativeScale * detector.getScaleFactor()));
                float factor = next / relativeScale;
                relativeScale = next;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                constrainImage();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!multiTouch && listener != null && getWidth() > 0) listener.onTap(Math.max(0f, Math.min(1f, event.getX() / getWidth())));
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                if (doubleTapMode == AppState.DOUBLE_TAP_OFF) return true;
                android.graphics.RectF bounds = new android.graphics.RectF();
                if (getDrawable() != null) { bounds.set(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight()); matrix.mapRect(bounds); }
                boolean inside = bounds.contains(event.getX(), event.getY());
                boolean fit = doubleTapMode == AppState.DOUBLE_TAP_FIT
                        || doubleTapMode == AppState.DOUBLE_TAP_TOGGLE && !inside
                        || doubleTapMode == AppState.DOUBLE_TAP_ZOOM && extremeAspect();
                float fill = fillScale();
                float target = fit ? (Math.abs(relativeScale-fill)<.01f ? 1.8f : fill) : relativeScale*doubleTapScale;
                animateZoom(Math.max(1f,Math.min(maximumScale(),target)),event.getX(),event.getY(),false);
                return true;
            }

            @Override public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (multiTouch || start == null) return false;
                if (canPan(true) || canPan(false)) {
                    android.graphics.RectF bounds = imageBounds();
                    panScroller.fling(Math.max(0,Math.round(-bounds.left)),Math.max(0,Math.round(-bounds.top)),
                            canPan(true) ? -(int)velocityX : 0,canPan(false) ? -(int)velocityY : 0,
                            0,Math.max(0,Math.round(bounds.width()-getWidth())),0,Math.max(0,Math.round(bounds.height()-getHeight())));
                    postInvalidateOnAnimation(); return true;
                }
                if (listener == null || relativeScale > 1.05f) return false;
                float dx = end.getX() - start.getX();
                float dy = end.getY() - start.getY();
                if (verticalPaging && !canPan(false) && Math.abs(dy) > touchSlop * 4 && Math.abs(dy) > Math.abs(dx) * 1.5f && Math.abs(velocityY) > 400) {
                    listener.onSwipe(dy < 0 ? -2 : 2);
                    return true;
                }
                if (!verticalPaging && !canPan(true) && Math.abs(dx) > touchSlop * 4 && Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(velocityX) > 400) {
                    listener.onSwipe(dx < 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setInteractionListener(InteractionListener value) { listener = value; }
    public void setFitMode(int mode) { fitMode = mode; fitImage(); }
    public void setDoubleTapScale(float scale) { doubleTapScale = Math.max(1f, Math.min(6f, scale)); }
    public void setDoubleTapMode(int mode) { doubleTapMode = mode; }
    public void setVerticalPaging(boolean enabled) { verticalPaging = enabled; }
    public void setFilterMode(int mode) { filterMode = mode; applyColorFilter(); }

    private void applyColorFilter() {
        ColorMatrix matrix = new ColorMatrix();
        if (filterMode == AppState.FILTER_GRAYSCALE) {
            matrix.setSaturation(0f);
        } else if (filterMode == AppState.FILTER_CONTRAST) {
            matrix.set(new float[]{
                    1.25f, 0, 0, 0, -31.875f,
                    0, 1.25f, 0, 0, -31.875f,
                    0, 0, 1.25f, 0, -31.875f,
                    0, 0, 0, 1, 0
            });
        } else if (filterMode == AppState.FILTER_SEPIA) {
            matrix.set(new float[]{
                    .393f, .769f, .189f, 0, 0,
                    .349f, .686f, .168f, 0, 0,
                    .272f, .534f, .131f, 0, 0,
                    0, 0, 0, 1, 0
            });
        } else if (filterMode == AppState.FILTER_BLUE_LIGHT) {
            matrix.set(new float[]{
                    1.04f, 0, 0, 0, 8,
                    0, .96f, 0, 0, 3,
                    0, 0, .72f, 0, 0,
                    0, 0, 0, 1, 0
            });
        }
        if (filterMode == AppState.FILTER_NONE) clearColorFilter();
        else setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    @Override public void setImageBitmap(android.graphics.Bitmap bitmap) {
        if(getDrawable() instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)getDrawable()).stop();
        super.setImageBitmap(bitmap);
        post(this::fitImage);
    }
    @Override public void setImageDrawable(Drawable drawable) {
        if(getDrawable() instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)getDrawable()).stop();
        super.setImageDrawable(drawable);
        if(drawable instanceof android.graphics.drawable.Animatable && isAttachedToWindow() && getWindowVisibility()==VISIBLE)((android.graphics.drawable.Animatable)drawable).start();
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(getDrawable() instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)getDrawable()).start();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(getDrawable() instanceof android.graphics.drawable.Animatable){if(visibility==VISIBLE)((android.graphics.drawable.Animatable)getDrawable()).start();else ((android.graphics.drawable.Animatable)getDrawable()).stop();}}

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::fitImage);
    }

    public void fitImage() {
        stopMotion();
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        float imageWidth = drawable.getIntrinsicWidth();
        float imageHeight = drawable.getIntrinsicHeight();
        if (imageWidth <= 0 || imageHeight <= 0) return;
        float widthScale = getWidth() / imageWidth;
        float heightScale = getHeight() / imageHeight;
        if (fitMode == AppState.FIT_STRETCH) {
            matrix.reset();
            matrix.postScale(widthScale, heightScale);
            relativeScale = 1f;
            setImageMatrix(matrix);
            return;
        }
        float scale = fitMode == AppState.FIT_WIDTH ? widthScale : fitMode == AppState.FIT_HEIGHT ? heightScale : Math.min(widthScale, heightScale);
        float dx = (getWidth() - imageWidth * scale) / 2f;
        float dy = (getHeight() - imageHeight * scale) / 2f;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        relativeScale = 1f;
        setImageMatrix(matrix);
    }

    private boolean extremeAspect() {
        Drawable image=getDrawable();
        if(image==null || image.getIntrinsicHeight()<=0)return false;
        float ratio=image.getIntrinsicWidth()/(float)image.getIntrinsicHeight();return ratio>2.8f || ratio<.3f;
    }
    private float fillScale() {
        Drawable image=getDrawable();
        if(image==null || getWidth()==0 || getHeight()==0)return 1.8f;
        float ratio=image.getIntrinsicWidth()/(float)image.getIntrinsicHeight()/(getWidth()/(float)getHeight());
        float scale=Math.max(ratio,1/ratio);return !Float.isFinite(scale) || scale<=1 ? 1.8f : scale;
    }
    private float maximumScale() {return extremeAspect() ? Math.max(5f,fillScale()*2) : 5f;}

    private void stopMotion() {
        panScroller.abortAnimation();
        if (zoomAnimator != null) { zoomAnimator.cancel(); zoomAnimator = null; }
    }
    private void animateZoom(float scale, float x, float y, boolean fit) {
        stopMotion();
        float[] start=new float[9], end=new float[9]; matrix.getValues(start);
        float from=relativeScale;
        if(fit) fitImage(); else {matrix.postScale(scale/from,scale/from,x,y); constrainImage();}
        matrix.getValues(end); matrix.setValues(start); relativeScale=from; setImageMatrix(matrix);
        zoomAnimator=android.animation.ValueAnimator.ofFloat(0,1); zoomAnimator.setDuration(150);
        zoomAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        zoomAnimator.addUpdateListener(animation -> {
            float progress=(Float)animation.getAnimatedValue(); float[] values=new float[9];
            for(int i=0;i<9;i++)values[i]=start[i]+(end[i]-start[i])*progress;
            matrix.setValues(values);relativeScale=from+(scale-from)*progress;constrainImage();
        });
        zoomAnimator.start();
    }
    @Override public void computeScroll() {
        if(panScroller.computeScrollOffset()) {
            android.graphics.RectF bounds=imageBounds();
            matrix.postTranslate(canPan(true) ? -panScroller.getCurrX()-bounds.left : 0,
                    canPan(false) ? -panScroller.getCurrY()-bounds.top : 0);
            constrainImage();postInvalidateOnAnimation();
        }
    }
    @Override protected void onDetachedFromWindow() {stopMotion();if(getDrawable() instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)getDrawable()).stop();super.onDetachedFromWindow();}

    private android.graphics.RectF imageBounds() {
        android.graphics.RectF bounds = new android.graphics.RectF();
        Drawable drawable = getDrawable();
        if (drawable != null) {
            bounds.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            matrix.mapRect(bounds);
        }
        return bounds;
    }

    private boolean canPan(boolean horizontal) {
        android.graphics.RectF bounds = imageBounds();
        return horizontal ? bounds.width() > getWidth() + 1 : bounds.height() > getHeight() + 1;
    }

    private void constrainImage() {
        android.graphics.RectF bounds = imageBounds();
        if (!bounds.isEmpty()) {
            float dx = bounds.width() <= getWidth() ? (getWidth() - bounds.width()) / 2 - bounds.left
                    : bounds.left > 0 ? -bounds.left : bounds.right < getWidth() ? getWidth() - bounds.right : 0;
            float dy = bounds.height() <= getHeight() ? (getHeight() - bounds.height()) / 2 - bounds.top
                    : bounds.top > 0 ? -bounds.top : bounds.bottom < getHeight() ? getHeight() - bounds.bottom : 0;
            matrix.postTranslate(dx, dy);
        }
        setImageMatrix(matrix);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopMotion();
            activePointer = event.getPointerId(0);
            lastX = event.getX(); lastY = event.getY();
            dragging = false; multiTouch = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(relativeScale > 1.01f);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            stopMotion();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_UP) {
            int lifted = event.getActionIndex();
            if (event.getPointerId(lifted) == activePointer) {
                int remaining = lifted == 0 ? 1 : 0;
                activePointer = event.getPointerId(remaining);
                lastX = event.getX(remaining); lastY = event.getY(remaining);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            int pointer = event.findPointerIndex(activePointer);
            if (pointer < 0) return true;
            float x = event.getX(pointer), y = event.getY(pointer);
            float dx = x - lastX, dy = y - lastY;
            if (!scaleDetector.isInProgress() && (canPan(true) || canPan(false))) {
                if (dragging || Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    matrix.postTranslate(dx, dy);
                    constrainImage();
                    lastX = x; lastY = y;
                }
            } else { lastX = x; lastY = y; }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP && !dragging && !multiTouch) performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
