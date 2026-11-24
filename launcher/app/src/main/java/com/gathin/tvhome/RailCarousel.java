package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * The rail home's favourites row: the focused app large in the centre, its neighbours smaller and dimmer to
 * each side. Focus stays in the centre and the row slides under it. Left on the first app is not consumed, so
 * the activity can move focus to the rail; Right on the last stops.
 */
final class RailCarousel extends FrameLayout {

    interface Listener {
        void onCarouselFocus(int index);

        void onCarouselOpen(int index);
    }

    /** Scale and brightness by distance from the centre, 0..3. */
    private static final float[] SCALE = {1f, 0.62f, 0.46f, 0.36f};
    private static final float[] ALPHA = {1f, 0.67f, 0.47f, 0.31f};
    private final float cardW, cardH, gap, radius, ringPad, ringWidth;
    private final List<ImageView> cards = new ArrayList<>();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Erases the cards toward each edge, so they fade out instead of being cut by a hard line beside the rail. */
    private final Paint edgeFade = new Paint();
    private final float fadeWidth;
    private final RectF box = new RectF();
    private Listener listener;
    private float position;          // the index at the centre; fractional while sliding
    private int index;
    private ValueAnimator slide;
    private boolean animate = true;

    RailCarousel(Context ctx) {
        super(ctx);
        float dp = ctx.getResources().getDisplayMetrics().density;
        cardW = 280 * dp;
        cardH = 158 * dp;
        gap = 13 * dp;
        radius = 5 * dp;
        ringPad = 4 * dp;
        ringWidth = 1.5f * dp;
        fadeWidth = 90 * dp;
        edgeFade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringWidth);
        ring.setColor(0xFFFFFFFF);
        setFocusable(true);
        // Cards slide out at the column's left edge instead of over the rail.
        setClipChildren(true);
        setClipToPadding(false);
        setWillNotDraw(false);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setAnimate(boolean on) {
        animate = on;
    }

    void setBanners(List<Drawable> banners) {
        removeAllViews();
        cards.clear();
        for (Drawable d : banners) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(d);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            iv.setClipToOutline(true);
            addView(iv, new LayoutParams((int) cardW, (int) cardH));
            cards.add(iv);
        }
        index = Math.max(0, Math.min(index, cards.size() - 1));
        position = index;
        requestLayout();
        place();
    }

    int index() {
        return index;
    }

    int count() {
        return cards.size();
    }

    void setRingColor(int color) {
        ring.setColor(color);
        invalidate();
    }

    /** Moves to {@code i} (clamped), sliding unless asked not to. */
    void select(int i, boolean smooth) {
        if (cards.isEmpty()) return;
        i = Math.max(0, Math.min(i, cards.size() - 1));
        index = i;
        if (slide != null) slide.cancel();
        if (!smooth || !animate) {
            position = i;
            place();
        } else {
            slide = ValueAnimator.ofFloat(position, i);
            slide.setDuration(260);
            slide.setInterpolator(new DecelerateInterpolator(1.6f));
            slide.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                place();
            });
            slide.start();
        }
        if (listener != null) listener.onCarouselFocus(i);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (cards.isEmpty()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (index == 0) return false;          // the activity takes focus to the rail
                select(index - 1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (index < cards.size() - 1) select(index + 1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (listener != null) listener.onCarouselOpen(index);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Cards slide out at the column's left edge instead of over the rail. clipChildren alone lets the
        // outline-clipped cards (their own render layers) draw past it on this TV; clip bounds do not.
        setClipBounds(new android.graphics.Rect(0, 0, r - l, b - t));
        place();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        place();
        invalidate();
    }

    /** x of a card's centre at distance {@code d} (signed, may be fractional) from the middle. */
    private float offsetX(float d) {
        float sign = Math.signum(d), a = Math.abs(d);
        int lo = (int) Math.floor(a);
        float frac = a - lo, x = 0;
        for (int k = 1; k <= Math.min(lo, 3); k++) x += cardW * (SCALE[k - 1] + SCALE[k]) / 2 + gap;
        if (lo < 3 && frac > 0) x += frac * (cardW * (SCALE[lo] + SCALE[lo + 1]) / 2 + gap);
        return sign * x;
    }

    private static float lerp(float[] table, float a) {
        if (a >= 3) return table[3];
        int lo = (int) Math.floor(a);
        float f = a - lo;
        return table[lo] + (table[lo + 1] - table[lo]) * f;
    }

    private void place() {
        if (getWidth() == 0) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        for (int i = 0; i < cards.size(); i++) {
            ImageView v = cards.get(i);
            float d = i - position, a = Math.abs(d);
            float s = lerp(SCALE, a);
            v.setPivotX(cardW / 2);
            v.setPivotY(cardH / 2);
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationX(cx + offsetX(d) - cardW / 2);
            v.setTranslationY(cy - cardH / 2);
            v.setAlpha(a > 3.4f ? 0f : lerp(ALPHA, a));
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Opaque at the edges (erasing fully), clear in the middle; one shader across the whole width.
        float f = Math.min(fadeWidth / Math.max(1, w), 0.45f);
        edgeFade.setShader(new android.graphics.LinearGradient(0, 0, w, 0,
                new int[]{0xFF000000, 0x00000000, 0x00000000, 0xFF000000}, new float[]{0f, f, 1f - f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), edgeFade);
        canvas.restoreToCount(layer);
        if (cards.isEmpty() || !hasFocus()) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float s = lerp(SCALE, Math.abs(index - position));
        float w = cardW * s / 2 + ringPad, h = cardH * s / 2 + ringPad;
        box.set(cx - w, cy - h, cx + w, cy + h);
        canvas.drawRoundRect(box, radius + ringPad, radius + ringPad, ring);
    }
}
