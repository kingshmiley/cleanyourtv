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

import android.media.tv.TvContract;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
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
        /** Set for an HDMI input in the carousel; pkg is then "input:<slug>". */
        InputCards.Device input;

        App(String pkg, String label, ComponentName component, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** One Continue Watching entry from the art helper (docs/specs/art.md). */
    static final class Resume {
        final String pkg, title, episode, image, intent;
        final long position, duration;

        Resume(String pkg, String title, String episode, String image, String intent, long position, long duration) {
            this.pkg = pkg;
            this.title = title;
            this.episode = episode;
            this.image = image;
            this.intent = intent;
            this.position = position;
            this.duration = duration;
        }

        String key() {
            return "cw:" + pkg + ":" + title + ":" + episode;
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
    private final List<Resume> resumes = new ArrayList<>();
    private RecyclerView cwRow;
    private CwAdapter cwAdapter;
    private View cwLabel, allAppsLabel;
    private final ExecutorService cwWorker = Executors.newSingleThreadExecutor();
    private TvInputManager.TvInputCallback inputCallback;
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
            if (has) restoreCarouselHeader();
        });
        ((FrameLayout) findViewById(R.id.carouselHolder)).addView(carousel,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        grid = findViewById(R.id.railGrid);
        GridLayoutManager glm = new GridLayoutManager(this, COLUMNS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < items.size() && items.get(position).type == T_HEADER ? COLUMNS : 1;
            }
        });
        grid.setLayoutManager(glm);
        loadOrder();
        findViewById(R.id.allAppsLabel).setVisibility(View.GONE);   // the grid carries its own headers
        counter.setVisibility(View.GONE);
        grid.setItemAnimator(null);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);

        cwLabel = findViewById(R.id.cwLabel);
        allAppsLabel = findViewById(R.id.allAppsLabel);
        cwRow = findViewById(R.id.cwRow);
        cwRow.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        cwRow.setItemAnimator(null);
        cwAdapter = new CwAdapter();
        cwRow.setAdapter(cwAdapter);
        showContinueRow(false);

        buildRows();
        loadApps();
        setAccent(accent);

        // Plugging something in or unplugging it adds or removes its card.
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            inputCallback = new TvInputManager.TvInputCallback() {
                @Override
                public void onInputStateChanged(String inputId, int state) {
                    buildFavourites(false);
                    updateInputs();
                }

                @Override
                public void onInputAdded(String inputId) {
                    buildFavourites(false);
                }

                @Override
                public void onInputRemoved(String inputId) {
                    buildFavourites(false);
                }
            };
            tim.registerCallback(inputCallback, main);
        }

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
        // Home, also from inside an app (the window has no focus yet then): onResume rebuilds and focuses the
        // carousel; this covers Home pressed while Home is already showing.
        goHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGreeting();
        updateInputs();
        updateNetwork();
        main.postDelayed(minute, 60_000L);
        startStatus();
        refreshRecents(true);
        artObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                // A picture finished downloading: drop that entry and show it if it is in front.
                String last = uri == null ? null : uri.getLastPathSegment();
                if ("continue".equals(last)) {
                    loadContinue();
                    return;
                }
                if (last != null) heroes.remove(last);
                if (last != null && last.equals(heroPkg)) loadHero(last, false);
            }
        };
        try {
            getContentResolver().registerContentObserver(Uri.parse("content://" + ART_AUTHORITY + "/app"), true, artObserver);
            getContentResolver().registerContentObserver(Uri.parse("content://" + ART_AUTHORITY + "/continue"), false, artObserver);
        } catch (SecurityException e) {
            artObserver = null;
        }
        loadContinue();
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
        cwWorker.shutdownNow();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null && inputCallback != null) tim.unregisterCallback(inputCallback);
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
        // Alphabetical: the order All apps starts from; after that its saved order wins (loadOrder).
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        adapter.notifyDataSetChanged();
        buildFavourites(false);
    }

    /** At most this many cards in the carousel, and at least this many (topped up from dock_apps). */
    private static final int CAROUSEL_MAX = 8, CAROUSEL_MIN = 5;
    private static final long RECENT_WINDOW = 30L * 24 * 60 * 60 * 1000;
    /** Never carousel cards: places you pass through rather than go back to. */
    private static final Set<String> NOT_RECENT = new HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "io.github.kingshmiley.tvweather"));
    /** Package -> last time used, from Android's usage history (PACKAGE_USAGE_STATS, granted with appops). */
    private final Map<String, Long> recent = new HashMap<>();

    /** Off the main thread: reads the usage history, then rebuilds the carousel on the main thread. */
    private void refreshRecents(boolean toFirst) {
        cwWorker.execute(() -> {
            Map<String, Long> m = new HashMap<>();
            try {
                android.app.usage.UsageStatsManager um =
                        (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                List<android.app.usage.UsageStats> stats = um == null ? null
                        : um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - RECENT_WINDOW, now);
                if (stats != null) {
                    for (android.app.usage.UsageStats u : stats) {
                        if (u.getTotalTimeInForeground() > 0) m.merge(u.getPackageName(), u.getLastTimeUsed(), Math::max);
                    }
                }
            } catch (RuntimeException ignored) {   // no permission: the launcher's own record still orders
            }
            main.post(() -> {
                recent.clear();
                recent.putAll(m);
                buildFavourites(toFirst);
                if (toFirst) goHome();
            });
        });
    }

    /** When the app (or "input:<slug>") was last used: the later of Android's history and the launcher's record. */
    private long lastUsed(String key) {
        Long t = recent.get(key);
        return Math.max(t == null ? 0 : t, LastOpened.get(this, key));
    }

    /**
     * The carousel: recently used apps, newest first, with every connected HDMI input placed by when it was last
     * switched to (never: last). Topped up from dock_apps while it has fewer than CAROUSEL_MIN cards.
     */
    private void buildFavourites(boolean toFirst) {
        String keep = carousel.count() > 0 && carousel.index() < favourites.size()
                ? favourites.get(carousel.index()).pkg : null;
        List<App> inputs = new ArrayList<>();
        int w = Math.round(280 * dp * 2), h = Math.round(158 * dp * 2);
        for (InputCards.Device d : InputCards.connected(this)) {
            android.graphics.Bitmap card = InputCards.card(this, d, w, h, getDrawable(R.drawable.ic_input));
            App a = new App("input:" + d.slug + ":" + d.portId.hashCode(), d.name, null,
                    new BitmapDrawable(getResources(), card), null);
            a.input = d;
            a.color = InputCards.style(d.slug).accent;
            inputs.add(a);
        }
        List<App> used = new ArrayList<>();
        for (App a : apps) {
            if (!NOT_RECENT.contains(a.pkg) && !hiddenKeys.contains(a.pkg) && lastUsed(a.pkg) > 0) used.add(a);
        }
        used.sort((x, y) -> Long.compare(lastUsed(y.pkg), lastUsed(x.pkg)));
        int room = Math.max(0, CAROUSEL_MAX - inputs.size());
        if (used.size() > room) used = new ArrayList<>(used.subList(0, room));
        for (String pkg : cfg.dockApps) {
            if (used.size() + inputs.size() >= CAROUSEL_MIN) break;
            for (App a : apps) if (a.pkg.equals(pkg) && !used.contains(a)) used.add(a);
        }
        favourites.clear();
        favourites.addAll(used);
        favourites.addAll(inputs);
        // A stable sort keeps the top-up apps (time 0) in dock order, after everything used.
        favourites.sort((x, y) -> Long.compare(lastUsed(keyOf(y)), lastUsed(keyOf(x))));
        List<Drawable> banners = new ArrayList<>();
        for (App a : favourites) banners.add(copy(a.banner != null ? a.banner : a.icon));
        int index = 0;
        if (!toFirst) for (int i = 0; i < favourites.size(); i++) if (favourites.get(i).pkg.equals(keep)) index = i;
        carousel.select(index, false);
        carousel.setBanners(banners);
        buildDots();
        if (!favourites.isEmpty()) onCarouselFocus(Math.min(carousel.index(), favourites.size() - 1));
        currentInputs = inputs;
        buildGrid();
    }

    private static String keyOf(App a) {
        return a.input != null ? "input:" + a.input.slug : a.pkg;
    }

    /** Home: the first (most recent) card focused, both rows back at their start, the carousel shown. */
    private void goHome() {
        grid.scrollToPosition(0);
        cwRow.scrollToPosition(0);
        showCarousel(true);
        if (carousel.count() == 0) return;
        carousel.select(0, false);
        carousel.post(carousel::requestFocus);
    }

    /** The carousel fades out while Continue Watching has focus, so the show's art fills the top. */
    private void showCarousel(boolean on) {
        float alpha = on ? 1f : 0f;
        for (View v : new View[]{carousel, dots, counter}) {
            v.animate().cancel();
            if (animations) v.animate().alpha(alpha).setDuration(220).start();
            else v.setAlpha(alpha);
        }
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
        if (app.input != null) {
            openInput(app.input.portId);
            LastOpened.record(this, "input:" + app.input.slug);
            return;
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(app.component);
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            LastOpened.record(this, app.pkg);
        } catch (RuntimeException ignored) {
        }
    }

    /** Switches to an HDMI input through the Live TV app, as MainActivity's Inputs menu does. */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        startSafely(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    // ---- Continue Watching --------------------------------------------------------------------------------

    private void loadContinue() {
        cwWorker.execute(() -> {
            List<Resume> found = new ArrayList<>();
            try (Cursor c = getContentResolver().query(Uri.parse("content://" + ART_AUTHORITY + "/continue"),
                    new String[]{"package", "title", "episode", "image", "position", "duration", "intent"}, null, null, null)) {
                while (c != null && c.moveToNext() && found.size() < 10) {
                    String pkg = c.getString(0);
                    if (pkg == null || appByPackage(pkg) == null) continue;   // only apps that are installed and shown
                    found.add(new Resume(pkg, StatusRows.clean(c.getString(1), 40), StatusRows.clean(c.getString(2), 40),
                            c.getString(3), c.getString(6), c.getLong(4), c.getLong(5)));
                }
            } catch (RuntimeException ignored) {   // helper missing or no permission: no row
            }
            main.post(() -> {
                resumes.clear();
                resumes.addAll(found);
                cwAdapter.notifyDataSetChanged();
                showContinueRow(!resumes.isEmpty());
            });
        });
    }

    private App appByPackage(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** With no rows the label and row go and All apps moves up into their place. */
    private void showContinueRow(boolean show) {
        cwLabel.setVisibility(show ? View.VISIBLE : View.GONE);
        cwRow.setVisibility(show ? View.VISIBLE : View.GONE);
        ((ViewGroup.MarginLayoutParams) allAppsLabel.getLayoutParams()).topMargin = (int) ((show ? 423 : 296) * dp);
        ((ViewGroup.MarginLayoutParams) grid.getLayoutParams()).topMargin = (int) ((show ? 423 : 296) * dp);
        allAppsLabel.requestLayout();
        grid.requestLayout();
    }

    /**
     * Resumes the episode through the row's own intent. The intent must open the app that published the row:
     * one app cannot use the row to start another. Anything else opens the app instead.
     */
    private void resume(Resume r) {
        App app = appByPackage(r.pkg);
        try {
            Intent i = Intent.parseUri(r.intent, Intent.URI_INTENT_SCHEME);
            String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
            if (target == null) i.setPackage(r.pkg);
            else if (!target.equals(r.pkg)) throw new IllegalArgumentException("row for " + r.pkg + " opens " + target);
            i.setSelector(null);
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            LastOpened.record(this, r.pkg);
        } catch (Exception e) {
            if (app != null) open(app);
        }
    }

    private void onResumeFocused(Resume r) {
        App app = appByPackage(r.pkg);
        showCarousel(false);
        favName.setText(r.title);
        String on = app == null ? "" : app.label;
        favSub.setText(r.episode.isEmpty() ? on : on.isEmpty() ? r.episode : r.episode + " \u00B7 " + on);
        if (app != null) setAccent(app.color);
        heroPkg = r.key();
        Hero cached = heroes.get(r.key());
        if (cached != null) {
            hero.show(cached.bitmap, cached.dim, animations);
            return;
        }
        heroWorker.execute(() -> {
            android.graphics.Bitmap b = r.image == null || !r.image.startsWith("content://" + ART_AUTHORITY + "/img/")
                    ? null : decode(Uri.parse(r.image));
            Hero h = new Hero(b, "", 0xB4);
            main.post(() -> {
                if (b != null) heroes.put(r.key(), h);
                if (r.key().equals(heroPkg)) hero.show(b, 0xB4, animations);
            });
        });
    }

    /** Back on the carousel: its name, line and picture again. */
    private void restoreCarouselHeader() {
        showCarousel(true);
        if (carousel.count() > 0) onCarouselFocus(carousel.index());
    }

    // ---- carousel -----------------------------------------------------------------------------------------

    @Override
    public void onCarouselFocus(int index) {
        if (index < 0 || index >= favourites.size()) return;
        App app = favourites.get(index);
        favName.setText(app.label);
        favSub.setText(app.input != null ? getString(R.string.rail_connected, app.input.port) : lastOpenedLine(app.pkg));
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
        App app = appFor(pkg);
        if (app != null && app.input != null) return;   // its line is the port, set already
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
        if (app != null && app.input != null) {
            // heroes/input-<slug>.jpg, else the card's own colours as a field
            File f = InputCards.themeFile(this, "input", app.input.slug);
            Bitmap b = f == null ? null : decode(Uri.fromFile(f));
            if (b != null) return new Hero(b, "", 0x40);
            return field(app.banner, "");
        }
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
        return field(app == null ? null : app.banner, line);
    }

    /** No picture: the banner enlarged with filtering, so the few pixels left become a soft field of its colours. */
    private Hero field(Drawable d, String line) {
        if (d == null) return new Hero(null, line, 0);
        Bitmap small = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
        Drawable copy = copy(d);
        copy.setBounds(0, 0, 48, 27);
        copy.draw(new Canvas(small));
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
        View c = cwRow.getFocusedChild();
        if (c != null) {
            View[] v = (View[]) c.getTag();
            v[0].invalidate();
            v[1].invalidate();
        }
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

    /** "PlayStation 5 · HDMI 1": the first connected input by the name it gives itself, else its port name. */
    private void updateInputs() {
        List<InputCards.Device> on = InputCards.connected(this);
        String detail = "";
        if (!on.isEmpty()) {
            InputCards.Device d = on.get(0);
            detail = d.name.equals(d.port) ? d.port : d.name + " \u00B7 " + d.port;
            if (on.size() > 1) detail = d.name + " +" + (on.size() - 1);
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

    private boolean longFired;

    private static boolean isSelect(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (moving != null && key != KeyEvent.KEYCODE_HOME) return moveKey(event);
        // OK on a grid tile: a short press opens it, holding it opens the tile's menu.
        View f = getCurrentFocus();
        View item = f == null ? null : grid.findContainingItemView(f);
        if (item != null && isSelect(key)) {
            int pos = grid.getChildAdapterPosition(item);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) longFired = false;
                else if (!longFired) {
                    longFired = true;
                    showTileMenu(pos);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!longFired && !event.isCanceled()) item.performClick();
                longFired = false;
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && move(key)) return true;
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
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return resumes.isEmpty() ? focusGrid(firstTile()) : focusContinue(0);
            return key == KeyEvent.KEYCODE_DPAD_UP;
        }
        View cw = cwRow.findContainingItemView(f);
        if (cw != null) {
            int pos = cwRow.getChildAdapterPosition(cw);
            if (key == KeyEvent.KEYCODE_DPAD_UP) return carousel.requestFocus();
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusGrid(firstTile());
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) return focusRail();
            return key == KeyEvent.KEYCODE_DPAD_RIGHT && pos == resumes.size() - 1;
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
            int col = ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup().getSpanIndex(pos, COLUMNS);
            if (key == KeyEvent.KEYCODE_DPAD_UP && pos - firstTile() < COLUMNS && sectionOf(pos) == items.get(firstTile()).section) {
                return resumes.isEmpty() ? carousel.requestFocus() : focusContinue(0);
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) return focusRail();
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == COLUMNS - 1 || pos + 1 >= items.size()
                    || items.get(pos + 1).type == T_HEADER)) return true;
        }
        return false;
    }

    private boolean focusRail() {
        return !railRows.isEmpty() && railRows.get(0).requestFocus();
    }

    private boolean focusContinue(int pos) {
        RecyclerView.ViewHolder h = cwRow.findViewHolderForAdapterPosition(pos);
        if (h != null) return h.itemView.requestFocus();
        cwRow.scrollToPosition(pos);
        cwRow.post(() -> {
            RecyclerView.ViewHolder later = cwRow.findViewHolderForAdapterPosition(pos);
            if (later != null) later.itemView.requestFocus();
        });
        return true;
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
        private boolean ringed;
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
            showRing(gained);
        }

        /** Grows and rings the tile; a Continue Watching card calls it when its column takes focus. */
        void showRing(boolean on) {
            ringed = on;
            float s = on ? 1.07f : 1f;
            animate().scaleX(s).scaleY(s).setDuration(140).start();
            setTranslationZ(on ? 2 : 0);
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (favourite) {
                dot.setColor(ringed ? accent : 0xCCFFFFFF);
                c.drawCircle(getWidth() - 6 * d, 6 * d, 2.5f * d, dot);
            }
            if (ringed) {
                float p = 3.5f * d;
                ring.setColor(accent);
                box.set(-p, -p, getWidth() + p, getHeight() + p);
                c.drawRoundRect(box, 4 * d + p, 4 * d + p, ring);
            }
        }
    }

    /** A Continue Watching card: the show's picture, the app's banner as a badge, progress, title and episode. */
    private final class CwAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout col = new LinearLayout(parent.getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            Tile t = new Tile(parent.getContext());
            t.setFocusable(false);
            col.addView(t, new LinearLayout.LayoutParams((int) (150 * dp), (int) (84 * dp)));
            View track = new View(parent.getContext());
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (2.5f * dp),
                    Gravity.BOTTOM);
            tl.leftMargin = tl.rightMargin = (int) (6 * dp);
            tl.bottomMargin = (int) (5 * dp);
            t.addView(track, tl);
            ImageView badge = new ImageView(parent.getContext());
            badge.setScaleType(ImageView.ScaleType.CENTER_CROP);
            badge.setClipToOutline(true);
            badge.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 2 * dp);
                }
            });
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams((int) (23 * dp), (int) (13 * dp));
            bl.leftMargin = bl.topMargin = (int) (5 * dp);
            t.addView(badge, bl);
            TextView title = text("", 8.5f, INK, R.font.outfit_medium);
            LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams((int) (150 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
            ttl.topMargin = (int) (6 * dp);
            col.addView(title, ttl);
            TextView ep = text("", 7f, SUB, R.font.outfit_regular);
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams((int) (150 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
            el.topMargin = (int) (2 * dp);
            col.addView(ep, el);
            col.setFocusable(true);
            col.setTag(new View[]{t, track, badge, title, ep});
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (13 * dp);
            col.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(col) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Resume r = resumes.get(position);
            View[] v = (View[]) h.itemView.getTag();
            Tile t = (Tile) v[0];
            App app = appByPackage(r.pkg);
            t.image.setImageDrawable(null);
            t.image.setBackgroundColor(0xFF1E2126);
            t.label.setText(r.image == null || r.image.isEmpty() ? r.title : "");
            if (r.image != null && r.image.startsWith("content://" + ART_AUTHORITY + "/img/")) {
                heroWorker.execute(() -> {
                    Bitmap b = decodeThumb(Uri.parse(r.image));
                    main.post(() -> {
                        if (b != null && h.getBindingAdapterPosition() == position) t.image.setImageBitmap(b);
                    });
                });
            }
            float progress = r.duration > 0 ? Math.max(0.03f, Math.min(1f, r.position / (float) r.duration)) : 0f;
            v[1].setBackground(progress > 0 ? progressDrawable(progress) : null);
            ((ImageView) v[2]).setImageDrawable(app == null ? null : copy(app.banner != null ? app.banner : app.icon));
            ((TextView) v[3]).setText(r.title);
            ((TextView) v[4]).setText(r.episode);
            h.itemView.setOnClickListener(x -> resume(r));
            h.itemView.setOnFocusChangeListener((x, has) -> {
                t.setSelected(has);
                t.showRing(has);
                ((TextView) v[3]).setTextColor(has ? INK : 0xFFC8CCD0);
                if (has) onResumeFocused(r);
            });
        }

        @Override
        public int getItemCount() {
            return resumes.size();
        }
    }

    /** A thin bar: the watched part bright, the rest faint. */
    private Drawable progressDrawable(float fraction) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();

            @Override
            public void draw(@NonNull Canvas c) {
                android.graphics.Rect b = getBounds();
                float rad = b.height() / 2f;
                p.setColor(0x55FFFFFF);
                r.set(b);
                c.drawRoundRect(r, rad, rad, p);
                p.setColor(accent);
                r.set(b.left, b.top, b.left + b.width() * fraction, b.bottom);
                c.drawRoundRect(r, rad, rad, p);
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** A Continue Watching thumbnail, decoded small. */
    private Bitmap decodeThumb(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || !WallpaperSize.accepted(o.outWidth, o.outHeight)) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 150 * dp) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- Favorites and All apps (docs/rail-home.md) -------------------------------------------------------

    private static final int T_HEADER = 0, T_APP = 1, T_HIDDEN = 2;

    /** A grid row: a section header, an app or input tile, or the Hidden apps entry. section 0 Favorites, 1 All apps. */
    private static final class Item {
        final int type, section;
        final String text;
        final App app;

        Item(int type, int section, String text, App app) {
            this.type = type;
            this.section = section;
            this.text = text;
            this.app = app;
        }
    }

    private final List<Item> items = new ArrayList<>();
    /** Saved on the TV and never replaced by a theme push: the owner's choices win (docs/rail-home.md). */
    private final List<String> favKeys = new ArrayList<>(), allKeys = new ArrayList<>();
    private final Set<String> hiddenKeys = new HashSet<>();
    private List<App> currentInputs = new ArrayList<>();
    private android.content.SharedPreferences order;
    /** The key of the tile being moved, or null. */
    private String moving;

    private void loadOrder() {
        order = getSharedPreferences("rail_order", MODE_PRIVATE);
        favKeys.clear();
        allKeys.clear();
        hiddenKeys.clear();
        if (order.contains("fav")) favKeys.addAll(split(order.getString("fav", "")));
        else favKeys.addAll(cfg.dockApps);   // first run: the theme's dock_apps
        allKeys.addAll(split(order.getString("all", "")));
        hiddenKeys.addAll(split(order.getString("hidden", "")));
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String k : s.split("\n")) if (!k.isEmpty() && !out.contains(k)) out.add(k);
        return out;
    }

    private void saveOrder() {
        order.edit().putString("fav", String.join("\n", favKeys)).putString("all", String.join("\n", allKeys))
                .putString("hidden", String.join("\n", hiddenKeys)).apply();
    }

    private Map<String, App> byKey() {
        Map<String, App> m = new HashMap<>();
        for (App a : apps) m.put(a.pkg, a);
        for (App a : currentInputs) m.put(keyOf(a), a);
        return m;
    }

    /**
     * Favorites in their saved order (connected inputs included, each keeping its place), then All apps in theirs
     * without the favourites; new apps join the end of All apps. Hidden apps leave both and gather in the entry
     * at the end.
     */
    private void buildGrid() {
        Map<String, App> byKey = byKey();
        boolean changed = false;
        for (App in : currentInputs) {
            if (!favKeys.contains(keyOf(in))) {
                favKeys.add(keyOf(in));
                changed = true;
            }
        }
        for (App a : apps) {
            if (!allKeys.contains(a.pkg)) {
                allKeys.add(a.pkg);
                changed = true;
            }
        }
        if (changed) saveOrder();
        items.clear();
        List<App> fav = new ArrayList<>();
        for (String k : favKeys) {
            App a = byKey.get(k);
            if (a != null && !hiddenKeys.contains(k)) fav.add(a);
        }
        if (!fav.isEmpty()) {
            items.add(new Item(T_HEADER, 0, getString(R.string.rail_favorites), null));
            for (App a : fav) items.add(new Item(T_APP, 0, null, a));
        }
        items.add(new Item(T_HEADER, 1, getString(R.string.rail_all_apps), null));
        for (String k : allKeys) {
            App a = byKey.get(k);
            if (a == null || a.input != null || favKeys.contains(k) || hiddenKeys.contains(k)) continue;
            items.add(new Item(T_APP, 1, null, a));
        }
        int hidden = 0;
        for (String k : hiddenKeys) if (byKey.containsKey(k)) hidden++;
        if (hidden > 0) items.add(new Item(T_HIDDEN, 1, getString(R.string.rail_hidden_apps, hidden), null));
        adapter.notifyDataSetChanged();
    }

    private int firstTile() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).type != T_HEADER) return i;
        return 0;
    }

    private int sectionOf(int pos) {
        return pos >= 0 && pos < items.size() ? items.get(pos).section : -1;
    }

    private void focusKey(String key) {
        for (int i = 0; i < items.size(); i++) {
            App a = items.get(i).app;
            if (a != null && keyOf(a).equals(key)) {
                focusGrid(i);
                return;
            }
        }
    }

    /** The menu on a held OK: Add to / Remove from favorites, Move, Hide and App info; an input only moves. */
    private void showTileMenu(int pos) {
        if (pos < 0 || pos >= items.size() || items.get(pos).app == null) return;
        App app = items.get(pos).app;
        String key = keyOf(app);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (app.input == null) {
            boolean fav = favKeys.contains(key);
            labels.add(getString(fav ? R.string.rail_remove_fav : R.string.rail_add_fav));
            actions.add(() -> {
                if (fav) favKeys.remove(key);
                else favKeys.add(key);
                saveOrder();
                buildGrid();
                focusKey(key);
            });
        }
        labels.add(getString(R.string.rail_move));
        actions.add(() -> {
            moving = key;
            buildGrid();
            focusKey(key);
        });
        if (app.input == null) {
            labels.add(getString(R.string.rail_hide));
            actions.add(() -> {
                hiddenKeys.add(key);
                saveOrder();
                buildFavourites(false);   // out of the carousel too; rebuilds the grid
                focusGrid(Math.min(pos, items.size() - 1));
            });
            labels.add(getString(R.string.rail_app_info));
            actions.add(() -> startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.pkg, null))));
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Move mode: the arrows carry the tile within its section, OK or Back puts it down. */
    private boolean moveKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();
        if (isSelect(k) || k == KeyEvent.KEYCODE_BACK) {
            String done = moving;
            moving = null;
            buildGrid();
            focusKey(done);
            return true;
        }
        int step = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : k == KeyEvent.KEYCODE_DPAD_UP ? -COLUMNS : k == KeyEvent.KEYCODE_DPAD_DOWN ? COLUMNS : 0;
        if (step == 0) return true;
        List<String> list = favKeys.contains(moving) ? favKeys : allKeys;
        int section = list == favKeys ? 0 : 1;
        List<String> shown = new ArrayList<>();
        for (Item it : items) if (it.app != null && it.section == section) shown.add(keyOf(it.app));
        int i = shown.indexOf(moving), j = Math.max(0, Math.min(shown.size() - 1, i + step));
        if (i < 0 || j == i) return true;
        String other = shown.get(j);
        list.remove(moving);
        int to = list.indexOf(other);
        list.add(step > 0 ? to + 1 : to, moving);
        saveOrder();
        buildGrid();
        focusKey(moving);
        return true;
    }

    /** The Hidden apps entry: pick one to bring it back. */
    private void showHidden() {
        Map<String, App> byKey = byKey();
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String k : hiddenKeys) {
            App a = byKey.get(k);
            if (a != null) {
                keys.add(k);
                labels.add(a.label);
            }
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.rail_unhide_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    hiddenKeys.remove(keys.get(which));
                    saveOrder();
                    buildFavourites(false);
                    focusKey(keys.get(which));
                })
                .show();
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == T_HEADER) {
                TextView h = text("", 8f, SUB, R.font.outfit_medium);
                h.setLetterSpacing(0.25f);
                h.setPadding((int) (5 * dp), (int) (6 * dp), 0, (int) (8 * dp));
                h.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(h) {
                };
            }
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
            Item it = items.get(position);
            if (it.type == T_HEADER) {
                ((TextView) h.itemView).setText(it.text);
                return;
            }
            Tile t = (Tile) h.itemView;
            t.favourite = false;
            if (it.type == T_HIDDEN) {
                t.image.setImageDrawable(null);
                t.image.setBackgroundColor(0xFF22252A);
                t.label.setGravity(Gravity.CENTER);
                t.label.setText(it.text);
                t.setContentDescription(it.text);
                t.setAlpha(moving == null ? 1f : 0.45f);
                t.setOnClickListener(v -> showHidden());
                t.setOnFocusChangeListener(null);
                return;
            }
            App app = it.app;
            t.label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
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
            t.setAlpha(moving == null || moving.equals(keyOf(app)) ? 1f : 0.45f);
            t.setContentDescription(app.label);
            t.setOnClickListener(v -> open(app));
            t.setOnFocusChangeListener((v, has) -> {
                if (!has) return;
                if (carousel.getAlpha() < 1f) restoreCarouselHeader();   // down from Continue Watching
                setAccent(app.color);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
