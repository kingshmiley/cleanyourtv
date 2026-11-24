package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HDMI inputs with something plugged in, for the rail home's carousel (docs/rail-home.md). A device that names
 * itself over HDMI-CEC ("PlayStation 5") has an input of its own whose parent is its port; that name is used, and
 * the port's label ("HDMI 1") goes in the chip. Only inputs in INPUT_STATE_CONNECTED count: TCL reports empty
 * ports as CONNECTED_STANDBY.
 */
final class InputCards {

    static final class Device {
        /** The port's input: what the TV switches to. */
        final String portId;
        final String name, port, slug;

        Device(String portId, String name, String port) {
            this.portId = portId;
            this.name = name;
            this.port = port;
            this.slug = slug(name);
        }
    }

    /** Card colours for devices we know; anything else gets the neutral look. */
    static final class Style {
        final int top, bottom, ink, accent, glow;
        /** top is the centre and bottom the edge of a radial fill, instead of a top-to-bottom one. */
        final boolean radial;

        Style(int top, int bottom, int ink, int accent, int glow) {
            this(top, bottom, ink, accent, glow, false);
        }

        Style(int top, int bottom, int ink, int accent, int glow, boolean radial) {
            this.top = top;
            this.bottom = bottom;
            this.ink = ink;
            this.accent = accent;
            this.glow = glow;
            this.radial = radial;
        }
    }

    private static final Map<String, Style> STYLES = new HashMap<>();

    static {
        // Grey for the colourful retro logo: lighter behind it, darker toward the corners.
        Style ps = new Style(0xFFAAABB0, 0xFF525359, 0xFF28282E, 0xFF2882FF, 0, true);
        STYLES.put("playstation-5", ps);
        STYLES.put("playstation-4", ps);
        STYLES.put("playstation", ps);
        // Steam's navy into its steel blue, with a soft light behind the logo.
        Style steam = new Style(0xFF171D25, 0xFF2A475E, 0xFFFFFFFF, 0xFF1A9FFF, 0x5A66C0F4);
        STYLES.put("steam-deck", steam);
        STYLES.put("steam", steam);
    }

    private static final Style NEUTRAL = new Style(0xFF26282E, 0xFF141518, 0xFFFFFFFF, 0xFFE6E8EC, 0);

    private InputCards() {
    }

    static Style style(String slug) {
        Style s = STYLES.get(slug);
        return s != null ? s : NEUTRAL;
    }

    /** "PlayStation 5" becomes "playstation-5": the name of its theme files, heroes/input-… and heroes/logo-…. */
    static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** Connected HDMI inputs, by port number. */
    static List<Device> connected(Context ctx) {
        List<Device> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            List<TvInputInfo> all = tim.getTvInputList();
            Map<String, TvInputInfo> cecByPort = new HashMap<>();
            for (TvInputInfo i : all) {
                if (i.getParentId() != null && i.isPassthroughInput() && !i.isHidden(ctx)
                        && tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) {
                    cecByPort.put(i.getParentId(), i);
                }
            }
            for (TvInputInfo i : all) {
                if (i.getType() != TvInputInfo.TYPE_HDMI || i.getParentId() != null || i.isHidden(ctx)) continue;
                TvInputInfo device = cecByPort.get(i.getId());
                boolean on = device != null || tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
                if (!on) continue;
                String port = label(ctx, i);
                if (!port.toUpperCase(Locale.ROOT).contains("HDMI")) port = "HDMI " + port;
                String name = port;
                if (device != null) {
                    CharSequence l = device.loadLabel(ctx);
                    if (l != null && l.length() > 0) name = l.toString();
                } else {
                    CharSequence custom = i.loadCustomLabel(ctx);
                    if (custom != null && custom.length() > 0) name = custom.toString();
                }
                out.add(new Device(i.getId(), StatusRows.clean(name, 24), StatusRows.clean(port, 10)));
            }
        } catch (RuntimeException ignored) {
        }
        out.sort((a, b) -> a.port.compareToIgnoreCase(b.port));
        return out;
    }

    private static String label(Context ctx, TvInputInfo i) {
        CharSequence l = i.loadLabel(ctx);
        return l == null ? i.getId() : l.toString();
    }

    /** The theme's heroes/<prefix>-<slug>.<ext>, or null. */
    static File themeFile(Context ctx, String prefix, String slug) {
        File dir = Config.dir(ctx);
        if (dir == null || slug.isEmpty()) return null;
        for (String ext : new String[]{"png", "jpg", "jpeg", "webp"}) {
            File f = new File(new File(dir, ThemeFiles.HEROES), prefix + "-" + slug + "." + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * The card in the carousel: the device's colours, its logo from the theme (heroes/logo-<slug>.png) or an input
     * glyph, its name at the bottom and the port in a chip. Drawn at {@code w}×{@code h} pixels.
     */
    static Bitmap card(Context ctx, Device d, int w, int h, Drawable glyph) {
        Style s = style(d.slug);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(s.radial
                ? new RadialGradient(w / 2f, h * 0.45f, w * 0.62f, s.top, s.bottom, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, 0, h, s.top, s.bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        if (s.glow != 0) {
            p.setShader(new RadialGradient(w / 2f, h * 0.4f, w * 0.45f, s.glow, s.glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
        File logoFile = themeFile(ctx, "logo", d.slug);
        Bitmap logo = logoFile == null ? null : BitmapFactory.decodeFile(logoFile.getPath());
        float boxW = w * 0.5f, boxH = h * 0.58f, top = h * 0.1f;
        if (logo != null) {
            float sc = Math.min(boxW / logo.getWidth(), boxH / logo.getHeight());
            float lw = logo.getWidth() * sc, lh = logo.getHeight() * sc;
            RectF r = new RectF((w - lw) / 2, top + (boxH - lh) / 2, (w + lw) / 2, top + (boxH + lh) / 2);
            c.drawBitmap(logo, null, r, p);
            logo.recycle();
        } else if (glyph != null) {
            int g = (int) (h * 0.34f);
            Drawable copy = glyph.mutate();
            copy.setTint(s.ink);
            copy.setBounds((w - g) / 2, (int) (top + (boxH - g) / 2), (w + g) / 2, (int) (top + (boxH + g) / 2));
            copy.draw(c);
        }
        Typeface face = ctx.getResources().getFont(R.font.outfit_medium);
        p.setTypeface(face);
        p.setColor(s.ink);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.1f);
        c.drawText(d.name, w / 2f, h * 0.9f, p);
        // the port chip, top right
        p.setTextSize(h * 0.06f);
        float tw = p.measureText(d.port), pad = h * 0.035f;
        RectF chip = new RectF(w - tw - pad * 3.2f, pad * 1.3f, w - pad * 1.2f, pad * 1.3f + h * 0.085f);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(s.ink == 0xFFFFFFFF ? 0x96000000 : 0xC8282830);
        c.drawRoundRect(chip, chip.height() / 3, chip.height() / 3, bg);
        p.setColor(0xFFE6E6E6);
        c.drawText(d.port, chip.centerX(), chip.centerY() + h * 0.021f, p);
        return bmp;
    }
}
