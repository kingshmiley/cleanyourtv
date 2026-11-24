package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The rail home (docs/rail-home.md): clock, weather and the Inputs / Wi-Fi / Settings rows in a frosted rail on
 * the left; the favourites (dock_apps) in a carousel over their artwork; every app in a grid below. The accent,
 * the glow and the focus rings take the colour of the focused app. Chosen with "home_layout": "rail"; MainActivity
 * stays the package's Home and hands every Home press over to this one.
 */
public class RailActivity extends Activity implements RailCarousel.Listener {

    private static final int COLUMNS = 6;
    private static final long STATUS_MS = 5 * 60_000L;
    private static final String[] STATUS_COLUMNS = {"kind", "icon", "title", "value", "detail", "chance"};
    /** The art helper (launcher/weather, docs/specs/art.md); absent is fine, the hero falls back. */
    private static final String ART_AUTHORITY = "io.github.kingshmiley.tvweather.art";
    private static final int INK = 0xFFF5F6F8, SUB = 0xFF969AA0;

    static final class App {
        final String pkg, label;
        final ComponentName component;
        final Drawable banner, icon;
        int color;

        App(String pkg, String label, ComponentName component, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** A carousel picture and the line under the app's name. */
    private static final class Hero {
        final Bitmap bitmap;
        final String line;
        final int dim;

        Hero(Bitmap bitmap, String line, int dim) {
            this.bitmap = bitmap;
            this.line = line;
            this.dim = dim;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService heroWorker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Hero> heroes = new LruCache<>(4);
    private Config cfg;
    private float dp;
    private final List<App> apps = new ArrayList<>();
    private final List<App> favourites = new ArrayList<>();
    private RailCarousel carousel;
    private HeroView hero;
    private RecyclerView grid;
    private GridAdapter adapter;
    private TextView amPm, counter, favName, favSub, greeting;
    private LinearLayout dots, rows, days;
    private View glow;
    private final List<View> railRows = new ArrayList<>();
    private TextView inputsDetail, wifiTitle, wifiDetail;
    private int accent = 0xFFFFFFFF;
    private ValueAnimator accentAnim;
    private String heroPkg;
    private boolean animations;
    private HandlerThread statusThread;
    private volatile Handler statusBg;
    private ContentObserver statusObserver, artObserver;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            heroes.evictAll();
            loadApps();
        }
    };
    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            main.postDelayed(this, 60_000L);
        }
    };

    // ---- switching layouts (home_layout) ------------------------------------------------------------------

    static boolean wanted(Config cfg) {
        return "rail".equals(cfg.homeLayout);
    }

    private boolean handedBack;

    // ---- lifecycle ----------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.load(this);
        if (!wanted(cfg)) {
            // The theme went back to the classic home: step aside.
            handedBack = true;
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        dp = getResources().getDisplayMetrics().density;
        animations = cfg.animations && !cfg.reducedMotion;
        setContentView(R.layout.activity_rail);
        greeting = findViewById(R.id.railGreeting);
        amPm = findViewById(R.id.railAmPm);
        counter = findViewById(R.id.railCounter);
        favName = findViewById(R.id.favName);
        favSub = findViewById(R.id.favSub);
        dots = findViewById(R.id.favDots);
        rows = findViewById(R.id.railRows);
        days = findViewById(R.id.railDays);
        glow = findViewById(R.id.railGlow);
        // These sit over the artwork: a soft shadow keeps them readable on a bright picture.
        for (TextView t : new TextView[]{counter, favName, favSub}) t.setShadowLayer(8 * dp, 0, dp, 0xE6000000);

        hero = new HeroView(this);
        ((FrameLayout) findViewById(R.id.heroHolder)).addView(hero,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        carousel = new RailCarousel(this);
        carousel.setAnimate(animations);
        carousel.setListener(this);
        carousel.setOnFocusChangeListener((v, has) -> {
            if (has && carousel.count() > 0) setAccent(favourites.get(carousel.index()).color);
        });
        ((FrameLayout) findViewById(R.id.carouselHolder)).addView(carousel,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        grid = findViewById(R.id.railGrid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);

        buildRows();
        loadApps();
        setAccent(accent);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        // After the first layout: asked earlier, the grid's first tile takes focus as it attaches.
        carousel.post(carousel::requestFocus);
    }

    private boolean focusedOnce;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !focusedOnce) {
            focusedOnce = true;
            carousel.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home while Home is showing: back to the first favourite, the grid back to its top.
        if (hasWindowFocus() && carousel.count() > 0) {
            grid.scrollToPosition(0);
            carousel.select(0, true);
            carousel.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGreeting();
        updateInputs();
        updateNetwork();
        main.postDelayed(minute, 60_000L);
        startStatus();
        artObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                // A picture finished downloading: drop that app's entry and show it if it is in front.
                String pkg = uri == null ? null : uri.getLastPathSegment();
                if (pkg != null) heroes.remove(pkg);
                if (pkg != null && pkg.equals(heroPkg)) loadHero(pkg, false);
            }
        };
        try {
            getContentResolver().registerContentObserver(Uri.parse("content://" + ART_AUTHORITY + "/app"), true, artObserver);
        } catch (SecurityException e) {
            artObserver = null;
        }
        if (heroPkg != null) {
            heroes.remove(heroPkg);   // a show watched since may have a new Continue Watching picture
            loadHero(heroPkg, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(minute);
        stopStatus();
        if (artObserver != null) getContentResolver().unregisterContentObserver(artObserver);
        artObserver = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handedBack) return;
        unregisterReceiver(packages);
        heroWorker.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to.
    }

    // ---- apps ---------------------------------------------------------------------------------------------

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> seen = new HashSet<>();
        apps.clear();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || !ai.exported || ai.packageName.equals(getPackageName())
                    || cfg.hiddenApps.contains(ai.packageName) || !seen.add(ai.packageName)) continue;
            String label = cfg.appLabels.containsKey(ai.packageName) ? cfg.appLabels.get(ai.packageName)
                    : Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), ai.packageName);
            Drawable banner = banner(pm, ri);
            App app = new App(ai.packageName, label, new ComponentName(ai.packageName, ai.name), banner,
                    banner == null ? ri.loadIcon(pm) : null);
            app.color = AppAdapter.vividColor(banner != null ? banner : app.icon);
            apps.add(app);
        }
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        favourites.clear();
        for (String pkg : cfg.dockApps) {
            for (App a : apps) if (a.pkg.equals(pkg)) favourites.add(a);
        }
        List<Drawable> banners = new ArrayList<>();
        for (App a : favourites) banners.add(copy(a.banner != null ? a.banner : a.icon));
        carousel.setBanners(banners);
        buildDots();
        adapter.notifyDataSetChanged();
        if (!favourites.isEmpty()) onCarouselFocus(Math.min(carousel.index(), favourites.size() - 1));
    }

    private Drawable banner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ri.activityInfo.applicationInfo.banner;
        Drawable d = null;
        if (id != 0) {
            try {
                Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                d = r.getDrawableForDensity(id, Math.max(DisplayMetrics.DENSITY_XHIGH,
                        getResources().getDisplayMetrics().densityDpi), null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (d == null) d = ri.activityInfo.loadBanner(pm);
        return d != null && AppAdapter.isBannerShaped(d.getIntrinsicWidth(), d.getIntrinsicHeight()) ? d : null;
    }

    private static Drawable copy(Drawable d) {
        return d == null || d.getConstantState() == null ? d : d.getConstantState().newDrawable().mutate();
    }

    private void open(App app) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(app.component);
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            LastOpened.record(this, app.pkg);
        } catch (RuntimeException ignored) {
        }
    }

    // ---- carousel -----------------------------------------------------------------------------------------

    @Override
    public void onCarouselFocus(int index) {
        if (index < 0 || index >= favourites.size()) return;
        App app = favourites.get(index);
        favName.setText(app.label);
        favSub.setText(lastOpenedLine(app.pkg));
        counter.setText(String.format(Locale.ROOT, "%02d / %02d", index + 1, favourites.size()));
        for (int i = 0; i < dots.getChildCount(); i++) styleDot(dots.getChildAt(i), i == index);
        setAccent(app.color);
        loadHero(app.pkg, true);
        // Warm the neighbours so sliding to them shows their picture at once.
        if (index > 0) prefetch(favourites.get(index - 1).pkg);
        if (index < favourites.size() - 1) prefetch(favourites.get(index + 1).pkg);
    }

    @Override
    public void onCarouselOpen(int index) {
        if (index >= 0 && index < favourites.size()) open(favourites.get(index));
    }

    private String lastOpenedLine(String pkg) {
        long when = LastOpened.get(this, pkg);
        return when == 0 ? "" : getString(R.string.last_opened, LastOpened.describe(this, when));
    }

    private void buildDots() {
        dots.removeAllViews();
        for (int i = 0; i < favourites.size(); i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (3 * dp), (int) (3 * dp));
            lp.leftMargin = lp.rightMargin = (int) (4 * dp);
            dots.addView(d, lp);
            styleDot(d, i == carousel.index());
        }
    }

    private void styleDot(View d, boolean current) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(2 * dp);
        g.setColor(current ? accent : 0xFF5A5E64);
        d.setBackground(g);
        ViewGroup.LayoutParams lp = d.getLayoutParams();
        lp.width = (int) ((current ? 10 : 3) * dp);
        d.setLayoutParams(lp);
    }

    // ---- hero art -----------------------------------------------------------------------------------------

    private void loadHero(String pkg, boolean animate) {
        heroPkg = pkg;
        Hero cached = heroes.get(pkg);
        if (cached != null) {
            showHero(pkg, cached, animate);
            return;
        }
        App app = appFor(pkg);
        heroWorker.execute(() -> {
            Hero h = makeHero(pkg, app);
            main.post(() -> {
                if (h != null) heroes.put(pkg, h);
                if (pkg.equals(heroPkg)) showHero(pkg, h, animate);
            });
        });
    }

    private void prefetch(String pkg) {
        if (heroes.get(pkg) != null) return;
        App app = appFor(pkg);
        heroWorker.execute(() -> {
            Hero h = makeHero(pkg, app);
            if (h != null) main.post(() -> heroes.put(pkg, h));
        });
    }

    private void showHero(String pkg, Hero h, boolean animate) {
        hero.show(h == null ? null : h.bitmap, h == null ? 0 : h.dim, animate && animations);
        String line = h != null && !h.line.isEmpty() ? h.line : lastOpenedLine(pkg);
        favSub.setText(line);
    }

    private App appFor(String pkg) {
        for (App a : favourites) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /**
     * Off the main thread. The first source that has a picture wins: the theme's heroes/<package>.jpg, then the
     * art helper's Continue Watching or preview picture, then a field made from the app's own banner.
     */
    private Hero makeHero(String pkg, App app) {
        File dir = Config.dir(this);
        if (dir != null) {
            for (String ext : new String[]{"jpg", "jpeg", "png", "webp"}) {
                File f = new File(new File(dir, ThemeFiles.HEROES), pkg + "." + ext);
                if (f.isFile()) {
                    Bitmap b = decode(Uri.fromFile(f));
                    if (b != null) return new Hero(b, "", 0x78);
                }
            }
        }
        String line = "";
        try (Cursor c = getContentResolver().query(Uri.parse("content://" + ART_AUTHORITY + "/app/" + pkg),
                new String[]{"kind", "title", "image"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String kind = c.getString(0), title = StatusRows.clean(c.getString(1), 60), image = c.getString(2);
                if (!title.isEmpty()) {
                    line = getString("continue".equals(kind) ? R.string.rail_continue : R.string.rail_featured, title);
                }
                if (image != null && image.startsWith("content://" + ART_AUTHORITY + "/img/")) {
                    Bitmap b = decode(Uri.parse(image));
                    if (b != null) return new Hero(b, line, 0xB4);
                }
            }
        } catch (RuntimeException ignored) {   // helper missing, no permission, or a bad row: fall back
        }
        Drawable d = app == null ? null : app.banner;
        if (d == null) return new Hero(null, line, 0);
        Bitmap small = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
        Drawable copy = copy(d);
        copy.setBounds(0, 0, 48, 27);
        copy.draw(new Canvas(small));
        // Enlarged with filtering, the few pixels left become a soft field of the banner's colours.
        Bitmap field = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, 12, 7, true), 480, 270, true);
        small.recycle();
        return new Hero(field, line, 0x60);
    }

    /** A picture decoded no larger than about twice the hero's width. */
    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || !WallpaperSize.accepted(o.outWidth, o.outHeight)) return null;
            int target = (int) (720 * dp);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- accent -------------------------------------------------------------------------------------------

    /** Fades the accent (AM/PM, counter, dots, rings, glow) to {@code color}. */
    private void setAccent(int color) {
        final int target = lift(color);
        if (accentAnim != null) accentAnim.cancel();
        if (!animations) {
            applyAccent(target);
            return;
        }
        accentAnim = ValueAnimator.ofObject(new ArgbEvaluator(), accent, target);
        accentAnim.setDuration(320);
        accentAnim.addUpdateListener(a -> applyAccent((int) a.getAnimatedValue()));
        accentAnim.start();
    }

    /** Dark banner colours (navy, near black) would vanish on black: keep the accent bright enough. */
    private static int lift(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(hsv[2], 0.85f);
        if (hsv[1] < 0.15f) hsv[2] = 0.95f;   // greys become a soft white
        return android.graphics.Color.HSVToColor(hsv);
    }

    private void applyAccent(int color) {
        accent = color;
        amPm.setTextColor(color);
        counter.setTextColor(color);
        carousel.setRingColor(color);
        for (int i = 0; i < dots.getChildCount(); i++) {
            if (i == carousel.index()) styleDot(dots.getChildAt(i), true);
        }
        Tile.accent = color;
        View f = grid.getFocusedChild();
        if (f != null) f.invalidate();
        drawGlow(color);
    }

    private Bitmap glowMask;

    private void drawGlow(int color) {
        if (glowMask == null) {
            int w = 480, h = 270;
            glowMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(w * 0.64f, h * 0.30f, w * 0.48f,
                    new int[]{0x4CFFFFFF, 0x1AFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            new Canvas(glowMask).drawRect(0, 0, w, h, p);
            BitmapDrawable bd = new BitmapDrawable(getResources(), glowMask);
            bd.setGravity(Gravity.FILL);
            glow.setBackground(bd);
        }
        glow.getBackground().setTint(color | 0xFF000000);
    }

    // ---- rail ---------------------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning
                : hour < 17 ? R.string.good_afternoon : hour < 22 ? R.string.good_evening : R.string.good_night;
        greeting.setText(cfg.greetingName.isEmpty() ? getString(res)
                : getString(R.string.greeting_with_name, getString(res), cfg.greetingName));
    }

    private void buildRows() {
        View inputs = row(R.drawable.ic_input, getString(R.string.inputs), true, () -> startActivity(
                new Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        inputsDetail = (TextView) inputs.getTag();
        View wifi = row(R.drawable.ic_wifi, getString(R.string.wifi), true,
                () -> startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifiDetail = (TextView) wifi.getTag();
        wifiTitle = (TextView) ((ViewGroup) wifi).getChildAt(1);
        row(R.drawable.ic_settings, getString(R.string.settings), false,
                () -> startSafely(new Intent(Settings.ACTION_SETTINGS)));
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** One rail row: icon, title and a detail (or a chevron) on the right. The detail view is the row's tag. */
    private View row(int icon, String title, boolean detail, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.rail_row);
        r.setFocusable(true);
        r.setPadding((int) (9 * dp), 0, (int) (10 * dp), 0);
        r.setOnClickListener(v -> action.run());
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(INK);
        r.addView(iv, new LinearLayout.LayoutParams((int) (13 * dp), (int) (13 * dp)));
        TextView t = text(title, 10.5f, INK, R.font.outfit_regular);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = (int) (10 * dp);
        r.addView(t, tl);
        TextView d = text(detail ? "" : "›", detail ? 8f : 13f, SUB, R.font.outfit_regular);
        d.setMaxWidth((int) (90 * dp));
        r.addView(d);
        r.setTag(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (32 * dp));
        lp.bottomMargin = (int) (8 * dp);
        rows.addView(r, lp);
        railRows.add(r);
        return r;
    }

    private TextView text(String s, float sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(font));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** "PS5 · HDMI 1" for the first connected HDMI input with a name the owner gave it, else its port name. */
    private void updateInputs() {
        String detail = "";
        try {
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim != null) {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.getType() != TvInputInfo.TYPE_HDMI || info.isHidden(this)) continue;
                    if (tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_CONNECTED) continue;
                    CharSequence custom = info.loadCustomLabel(this), port = info.loadLabel(this);
                    detail = custom != null && custom.length() > 0 && !custom.toString().contentEquals(port)
                            ? custom + " · " + port : String.valueOf(port);
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        inputsDetail.setText(detail);
    }

    private void updateNetwork() {
        String title = getString(R.string.wifi), detail = getString(R.string.rail_offline);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                title = getString(R.string.rail_ethernet);
                detail = "";
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiInfo wi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo();
                String ssid = wi == null ? null : wi.getSSID();
                if (ssid != null && !ssid.contains("unknown ssid")) title = ssid.replace("\"", "");
                int level = wi == null ? 0 : WifiManager.calculateSignalLevel(wi.getRssi(), 5);
                detail = getString(level >= 4 ? R.string.rail_signal_strong
                        : level >= 2 ? R.string.rail_signal_good : R.string.rail_signal_weak);
            }
        } catch (RuntimeException ignored) {
        }
        wifiTitle.setText(title);
        wifiDetail.setText(detail);
    }

    // ---- weather (the theme's status_provider, docs/specs/status.md) --------------------------------------

    private void startStatus() {
        if (cfg.statusProvider.isEmpty() || statusThread != null) return;
        Uri uri = Uri.parse("content://" + cfg.statusProvider + "/status"
                + (cfg.statusQuery.isEmpty() ? "" : "?" + cfg.statusQuery));
        statusThread = new HandlerThread("rail-status");
        statusThread.start();
        Handler bg = new Handler(statusThread.getLooper());
        statusBg = bg;
        Runnable read = new Runnable() {
            @Override
            public void run() {
                if (statusBg != bg) return;
                bg.removeCallbacks(this);
                StatusRows.Status s = readStatus(uri);
                main.post(() -> {
                    if (statusBg == bg) bindWeather(s);
                });
                bg.postDelayed(this, STATUS_MS);
            }
        };
        statusObserver = new ContentObserver(bg) {
            @Override
            public void onChange(boolean selfChange) {
                read.run();
            }
        };
        try {
            getContentResolver().registerContentObserver(uri.buildUpon().clearQuery().build(), true, statusObserver);
        } catch (SecurityException e) {
            statusObserver = null;
        }
        bg.post(read);
    }

    private void stopStatus() {
        if (statusThread == null) return;
        if (statusObserver != null) getContentResolver().unregisterContentObserver(statusObserver);
        statusObserver = null;
        statusBg.removeCallbacksAndMessages(null);
        statusThread.quit();
        statusThread = null;
        statusBg = null;
    }

    private StatusRows.Status readStatus(Uri uri) {
        List<String[]> raw = new ArrayList<>();
        try (Cursor c = getContentResolver().query(uri, STATUS_COLUMNS, null, null, null)) {
            if (c == null) return StatusRows.EMPTY;
            int[] col = new int[STATUS_COLUMNS.length];
            for (int i = 0; i < col.length; i++) col[i] = c.getColumnIndex(STATUS_COLUMNS[i]);
            while (c.moveToNext() && raw.size() < 32) {
                String[] r = new String[col.length];
                for (int i = 0; i < col.length; i++) r[i] = col[i] < 0 || c.isNull(col[i]) ? "" : c.getString(col[i]);
                raw.add(r);
            }
        } catch (RuntimeException e) {
            return StatusRows.EMPTY;
        }
        return StatusRows.from(raw);
    }

    private void bindWeather(StatusRows.Status s) {
        View now = findViewById(R.id.railWeather);
        now.setVisibility(s.now != null ? View.VISIBLE : View.GONE);
        if (s.now != null) {
            ((ImageView) findViewById(R.id.railWxIcon)).setImageDrawable(new WeatherIcon(s.now.icon));
            ((TextView) findViewById(R.id.railWxTemp)).setText(s.now.value);
            ((TextView) findViewById(R.id.railWxText)).setText(s.now.title);
            ((TextView) findViewById(R.id.railWxHiLo)).setText(s.now.detail);
        }
        TextView alert = findViewById(R.id.railWxAlert);
        String badge = s.alert != null ? s.alert.title : s.badge != null ? s.badge.title : "";
        alert.setText(badge);
        alert.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);

        days.removeAllViews();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (StatusRows.Row d : s.days) {
            lo = Math.min(lo, Math.min(degrees(d.detail, degrees(d.value, 0)), degrees(d.value, 0)));
            hi = Math.max(hi, degrees(d.value, 0));
        }
        for (StatusRows.Row d : s.days) days.addView(dayRow(d, lo, hi));
    }

    private static int degrees(String s, int fallback) {
        String digits = s == null ? "" : s.replaceAll("[^0-9-]", "");
        try {
            return digits.isEmpty() ? fallback : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private View dayRow(StatusRows.Row d, int weekLo, int weekHi) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        String name = d.title.isEmpty() ? "" : d.title.charAt(0) + d.title.substring(1).toLowerCase(Locale.ROOT);
        r.addView(text(name, 10f, INK, R.font.outfit_medium), new LinearLayout.LayoutParams((int) (30 * dp),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout iconCol = new LinearLayout(this);
        iconCol.setOrientation(LinearLayout.VERTICAL);
        iconCol.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new WeatherIcon(d.icon));
        iconCol.addView(icon, new LinearLayout.LayoutParams((int) (13 * dp), (int) (13 * dp)));
        int chance = d.chancePercent();
        if (chance >= 10) iconCol.addView(text(d.chance, 6.5f, 0xFF78B4FF, R.font.outfit_medium));
        r.addView(iconCol, new LinearLayout.LayoutParams((int) (26 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView low = text(d.detail, 9f, SUB, R.font.outfit_regular);
        low.setGravity(Gravity.END);
        r.addView(low, new LinearLayout.LayoutParams((int) (24 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        RangeBar bar = new RangeBar(this);
        bar.set(degrees(d.detail, degrees(d.value, 0)), degrees(d.value, 0), weekLo, weekHi);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, (int) (8 * dp), 1);
        bl.leftMargin = bl.rightMargin = (int) (7 * dp);
        r.addView(bar, bl);
        r.addView(text(d.value, 9f, INK, R.font.outfit_medium), new LinearLayout.LayoutParams((int) (24 * dp),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (25 * dp)));
        return r;
    }

    // ---- keys ---------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && move(event.getKeyCode())) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * Focus between the three areas. Left on the first favourite or the grid's first column goes to the rail,
     * Right from the rail to the carousel, Down from the carousel into the grid and Up from its top row back.
     */
    private boolean move(int key) {
        View f = getCurrentFocus();
        if (f == null) return false;
        if (f == carousel) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && carousel.index() == 0) return focusRail();
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusGrid(0);
            return key == KeyEvent.KEYCODE_DPAD_UP;
        }
        int row = railRows.indexOf(f);
        if (row >= 0) {
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return carousel.requestFocus();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (key == KeyEvent.KEYCODE_DPAD_UP && row == 0) return true;
            return key == KeyEvent.KEYCODE_DPAD_DOWN && row == railRows.size() - 1;
        }
        View item = grid.findContainingItemView(f);
        if (item != null) {
            int pos = grid.getChildAdapterPosition(item);
            if (pos < 0) return false;
            int col = pos % COLUMNS;
            if (key == KeyEvent.KEYCODE_DPAD_UP && pos < COLUMNS) return carousel.requestFocus();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) return focusRail();
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == COLUMNS - 1 || pos == apps.size() - 1)) return true;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && pos + COLUMNS >= apps.size()
                    && pos / COLUMNS == (apps.size() - 1) / COLUMNS) return true;
        }
        return false;
    }

    private boolean focusRail() {
        return !railRows.isEmpty() && railRows.get(0).requestFocus();
    }

    private boolean focusGrid(int pos) {
        if (apps.isEmpty()) return true;
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
        if (h != null) return h.itemView.requestFocus();
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder later = grid.findViewHolderForAdapterPosition(pos);
            if (later != null) later.itemView.requestFocus();
        });
        return true;
    }

    // ---- grid ---------------------------------------------------------------------------------------------

    /** A grid tile: the banner with rounded corners, a ring in the accent colour while focused. */
    static final class Tile extends FrameLayout {
        static int accent = 0xFFFFFFFF;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        final ImageView image;
        final TextView label;
        boolean favourite;
        private final float d;

        Tile(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            setFocusable(true);
            setClipChildren(false);
            setWillNotDraw(false);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.5f * d);
            image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 4 * d);
                }
            });
            image.setClipToOutline(true);
            addView(image, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            label = new TextView(ctx);
            label.setTextColor(INK);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            label.setPadding(0, 0, 0, (int) (5 * d));
            addView(label, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            float s = gained ? 1.07f : 1f;
            animate().scaleX(s).scaleY(s).setDuration(140).start();
            setTranslationZ(gained ? 2 : 0);
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (favourite) {
                dot.setColor(isFocused() ? accent : 0xCCFFFFFF);
                c.drawCircle(getWidth() - 6 * d, 6 * d, 2.5f * d, dot);
            }
            if (isFocused()) {
                float p = 3.5f * d;
                ring.setColor(accent);
                box.set(-p, -p, getWidth() + p, getHeight() + p);
                c.drawRoundRect(box, 4 * d + p, 4 * d + p, ring);
            }
        }
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int cell = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / COLUMNS;
            if (cell <= 0) cell = (int) (110 * dp);
            int gap = (int) (10 * dp), w = cell - gap, h = Math.round(w * 9f / 16f);
            Tile t = new Tile(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
            lp.leftMargin = gap / 2;
            lp.rightMargin = gap / 2;
            lp.bottomMargin = (int) (12 * dp);
            t.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(t) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            App app = apps.get(position);
            Tile t = (Tile) h.itemView;
            t.favourite = favourites.contains(app);
            if (app.banner != null) {
                t.image.setImageDrawable(copy(app.banner));
                t.image.setBackgroundColor(0);
                t.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                t.label.setText("");
            } else {
                t.image.setImageDrawable(app.icon);
                t.image.setBackgroundColor(0xFF22252A);
                t.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                t.label.setText(app.label);
            }
            t.setContentDescription(app.label);
            t.setOnClickListener(v -> open(app));
            t.setOnFocusChangeListener((v, has) -> {
                if (has) setAccent(app.color);
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
