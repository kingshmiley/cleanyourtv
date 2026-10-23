package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The weather icons beside the status line and on the day cards (StatusRows.ICONS), drawn from circles and a few
 * strokes instead of shipped as images: a handful of shapes, no bitmaps, the same size at any density.
 */
final class WeatherIcon extends Drawable {

    private static final int SUN = 0xFFFFC446, CLOUD = 0xFFE1E6EB, CLOUD_DARK = 0xFFAAB4C3, RAIN = 0xFF8CBEFF,
            BOLT = 0xFFFFD250, MOON = 0xFFE8E4D2;

    private final String icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    WeatherIcon(String icon) {
        this.icon = icon == null ? "" : icon;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
        paint.setStyle(Paint.Style.FILL);
        switch (icon) {
            case "sun":
                circle(c, x + s * .5f, y + s * .5f, s * .3f, SUN);
                break;
            case "moon":
                moon(c, x + s * .5f, y + s * .5f, s * .3f);
                break;
            case "partly":
                circle(c, x + s * .62f, y + s * .32f, s * .22f, SUN);
                cloud(c, x, y + s * .12f, s * .88f, CLOUD);
                break;
            case "partly_night":
                moon(c, x + s * .62f, y + s * .32f, s * .2f);
                cloud(c, x, y + s * .12f, s * .88f, CLOUD);
                break;
            case "cloud":
                cloud(c, x, y + s * .05f, s, CLOUD);
                break;
            case "rain":
                cloud(c, x, y - s * .05f, s * .95f, CLOUD_DARK);
                drops(c, x, y, s);
                break;
            case "storm":
                cloud(c, x, y - s * .05f, s * .95f, CLOUD_DARK);
                drops(c, x, y, s);
                bolt(c, x, y, s);
                break;
            case "snow":
                cloud(c, x, y - s * .05f, s * .95f, CLOUD);
                for (int i = 0; i < 3; i++) circle(c, x + s * (.3f + i * .2f), y + s * (.88f - (i % 2) * .06f), s * .045f, CLOUD);
                break;
            case "drop":   // the chance-of-rain mark on the day cards, not a provider icon
                path.reset();
                path.moveTo(x + s * .5f, y + s * .05f);
                path.lineTo(x + s * .82f, y + s * .55f);
                path.lineTo(x + s * .18f, y + s * .55f);
                path.close();
                paint.setColor(RAIN);
                c.drawPath(path, paint);
                c.drawCircle(x + s * .5f, y + s * .62f, s * .33f, paint);
                break;
            case "fog":
                cloud(c, x, y - s * .08f, s * .9f, CLOUD_DARK);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(s * .06f);
                paint.setColor(CLOUD);
                c.drawLine(x + s * .12f, y + s * .78f, x + s * .88f, y + s * .78f, paint);
                c.drawLine(x + s * .25f, y + s * .92f, x + s * .75f, y + s * .92f, paint);
                break;
            default:
        }
    }

    private void circle(Canvas c, float cx, float cy, float r, int color) {
        paint.setColor(color);
        c.drawCircle(cx, cy, r, paint);
    }

    /** Three overlapping puffs on a flat base, in a box {@code s} wide from (x, y). */
    private void cloud(Canvas c, float x, float y, float s, int color) {
        paint.setColor(color);
        c.drawCircle(x + s * .3f, y + s * .65f, s * .22f, paint);
        c.drawCircle(x + s * .55f, y + s * .47f, s * .28f, paint);
        c.drawCircle(x + s * .77f, y + s * .66f, s * .2f, paint);
        c.drawRect(x + s * .3f, y + s * .6f, x + s * .77f, y + s * .86f, paint);
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + r * .55f, cy - r * .35f, r * .85f, Path.Direction.CW);
        path.op(bite, Path.Op.DIFFERENCE);
        paint.setColor(MOON);
        c.drawPath(path, paint);
    }

    private void drops(Canvas c, float x, float y, float s) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * .065f);
        paint.setColor(RAIN);
        for (int i = 0; i < 3; i++) {
            float dx = x + s * (.32f + i * .2f);
            c.drawLine(dx, y + s * .8f, dx - s * .06f, y + s * .96f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void bolt(Canvas c, float x, float y, float s) {
        path.reset();
        path.moveTo(x + s * .56f, y + s * .62f);
        path.lineTo(x + s * .42f, y + s * .84f);
        path.lineTo(x + s * .52f, y + s * .84f);
        path.lineTo(x + s * .45f, y + s * 1f);
        path.lineTo(x + s * .66f, y + s * .76f);
        path.lineTo(x + s * .56f, y + s * .76f);
        path.close();
        paint.setColor(BOLT);
        c.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
