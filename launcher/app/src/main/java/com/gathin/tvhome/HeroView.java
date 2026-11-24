package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * The picture behind the rail home's carousel: centre-cropped, darkened, and faded into the background toward
 * the rail (left) and the app grid (bottom) so text never sits on the busiest part. A new picture cross-fades
 * over the old one.
 */
final class HeroView extends View {

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final Paint fadeLeft = new Paint(), fadeBottom = new Paint();
    private final Matrix matrix = new Matrix();
    private Bitmap current, previous;
    private float mix = 1f;            // 0 = previous only, 1 = current only
    private ValueAnimator fade;
    private int background = 0xFF08090B;
    private int dim = 0x80;            // black over the picture, 0..255

    HeroView(Context ctx) {
        super(ctx);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setBackgroundColorBelow(int color) {
        background = color | 0xFF000000;
        shaders();
        invalidate();
    }

    /** Shows {@code bmp} (null: nothing), cross-fading from what was there. */
    void show(Bitmap bmp, int dimAlpha, boolean animate) {
        if (bmp == current) return;
        if (fade != null) fade.cancel();
        previous = current;
        current = bmp;
        dim = dimAlpha;
        if (!animate || previous == null) {
            mix = 1f;
            previous = null;
            invalidate();
            return;
        }
        mix = 0f;
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(420);
        fade.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            if (mix >= 1f) previous = null;
            invalidate();
        });
        fade.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shaders();
    }

    private void shaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int clear = background & 0x00FFFFFF;
        fadeLeft.setShader(new LinearGradient(0, 0, w * 0.22f, 0, background, clear, Shader.TileMode.CLAMP));
        fadeBottom.setShader(new LinearGradient(0, h * 0.5f, 0, h, clear, background, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (previous != null) draw(c, previous, (int) (255 * (1 - mix)), w, h);
        if (current != null) draw(c, current, (int) (255 * mix), w, h);
        if (current == null && previous == null) return;
        shade.setColor(dim << 24);
        c.drawRect(0, 0, w, h, shade);
        c.drawRect(0, 0, w, h, fadeLeft);
        c.drawRect(0, 0, w, h, fadeBottom);
    }

    private void draw(Canvas c, Bitmap b, int alpha, int w, int h) {
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate((w - b.getWidth() * s) / 2, (h - b.getHeight() * s) / 2);
        paint.setAlpha(alpha);
        c.drawBitmap(b, matrix, paint);
    }
}
