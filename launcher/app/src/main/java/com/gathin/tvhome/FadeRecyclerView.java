package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * A horizontal row that stays inside its own bounds and fades its items out at an edge with more content past
 * it. Clip bounds and a drawn fade, because on some TVs (TCL G08) items with an outline clip or elevation draw on
 * their own layers and ignore both clipChildren and the built-in fading edge, so they slid under the rail.
 */
public class FadeRecyclerView extends RecyclerView {

    private final Paint left = new Paint(), right = new Paint();
    private final float fade;

    public FadeRecyclerView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        fade = 70 * ctx.getResources().getDisplayMetrics().density;
        PorterDuffXfermode erase = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
        left.setXfermode(erase);
        right.setXfermode(erase);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        setClipBounds(new Rect(0, 0, r - l, b - t));
        left.setShader(new LinearGradient(0, 0, fade, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        right.setShader(new LinearGradient(r - l - fade, 0, r - l, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int layer = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        // Each edge fades in as content scrolls past it, so the first card is not dimmed at rest.
        left.setAlpha((int) (255 * Math.min(1f, computeHorizontalScrollOffset() / fade)));
        int rest = computeHorizontalScrollRange() - computeHorizontalScrollExtent() - computeHorizontalScrollOffset();
        right.setAlpha((int) (255 * Math.max(0f, Math.min(1f, rest / fade))));
        canvas.drawRect(0, 0, fade, h, left);
        canvas.drawRect(w - fade, 0, w, h, right);
        canvas.restoreToCount(layer);
    }
}
