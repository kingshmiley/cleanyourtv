package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** One day's temperature range on the week's scale: a dim track with a cool-to-warm bar from low to high. */
final class RangeBar extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float from, to;   // 0..1 along the week's range

    RangeBar(Context ctx) {
        super(ctx);
        track.setColor(0xFF303237);
    }

    void set(int lo, int hi, int weekLo, int weekHi) {
        float span = Math.max(1, weekHi - weekLo);
        from = Math.max(0, Math.min(1, (lo - weekLo) / span));
        to = Math.max(from, Math.min(1, (hi - weekLo) / span));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), t = Math.min(h, 3 * getResources().getDisplayMetrics().density);
        float y = (h - t) / 2;
        r.set(0, y, w, y + t);
        c.drawRoundRect(r, t / 2, t / 2, track);
        r.set(from * w, y, Math.max(from * w + t, to * w), y + t);
        bar.setShader(new LinearGradient(r.left, 0, r.right, 0, 0xFF6EAAFF, 0xFFFFC840, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, t / 2, t / 2, bar);
    }
}
