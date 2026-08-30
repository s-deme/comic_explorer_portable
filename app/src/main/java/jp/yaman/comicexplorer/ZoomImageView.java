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
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float lastX;
    private float lastY;
    private float relativeScale = 1f;
    private boolean dragging;
    private int fitMode = AppState.FIT_SCREEN;
    private InteractionListener listener;

    public ZoomImageView(Context context) { this(context, null); }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(0xFF101114);
        setFocusable(true);
        setContentDescription("作品ページ。左右へスワイプまたは左右をタップしてページを移動。中央タップでメニューを表示。");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(1f, Math.min(5f, relativeScale * detector.getScaleFactor()));
                float factor = next / relativeScale;
                relativeScale = next;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (listener != null && getWidth() > 0) listener.onTap(Math.max(0f, Math.min(1f, event.getX() / getWidth())));
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                if (relativeScale > 1.05f) fitImage();
                else {
                    relativeScale = 2.25f;
                    matrix.postScale(relativeScale, relativeScale, event.getX(), event.getY());
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (listener == null || relativeScale > 1.05f) return false;
                float dx = end.getX() - start.getX();
                if (Math.abs(dx) > 96 && Math.abs(dx) > Math.abs(end.getY() - start.getY()) * 1.5f && Math.abs(velocityX) > 400) {
                    listener.onSwipe(dx < 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setInteractionListener(InteractionListener value) { listener = value; }
    public void setFitMode(int mode) { fitMode = mode; fitImage(); }

    public void setInverted(boolean inverted) {
        if (inverted) {
            setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            })));
        } else {
            clearColorFilter();
        }
    }

    @Override public void setImageBitmap(android.graphics.Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        post(this::fitImage);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::fitImage);
    }

    public void fitImage() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        float imageWidth = drawable.getIntrinsicWidth();
        float imageHeight = drawable.getIntrinsicHeight();
        if (imageWidth <= 0 || imageHeight <= 0) return;
        float widthScale = getWidth() / imageWidth;
        float heightScale = getHeight() / imageHeight;
        float scale = fitMode == AppState.FIT_WIDTH ? widthScale : fitMode == AppState.FIT_HEIGHT ? heightScale : Math.min(widthScale, heightScale);
        float dx = (getWidth() - imageWidth * scale) / 2f;
        float dy = (getHeight() - imageHeight * scale) / 2f;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        relativeScale = 1f;
        setImageMatrix(matrix);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && relativeScale > 1.01f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                        dragging = true;
                        matrix.postTranslate(dx, dy);
                        setImageMatrix(matrix);
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
