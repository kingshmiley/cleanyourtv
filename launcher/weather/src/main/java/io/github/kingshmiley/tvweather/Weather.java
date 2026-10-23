package io.github.kingshmiley.tvweather;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches and caches the forecast for one place. Nothing runs in the background on its own: the launcher asks
 * (StatusProvider) while Home is on screen, gets the cache at once, and a refresh starts when the cache is older
 * than {@link #FRESH_MS}. When the refresh lands, the provider's URI is notified and the launcher reads again.
 */
final class Weather {

    static final String AUTHORITY = "io.github.kingshmiley.tvweather.status";
    static final Uri STATUS = Uri.parse("content://" + AUTHORITY + "/status");
    /** Refresh once the data is this old... */
    static final long FRESH_MS = 30 * 60_000L;
    /** ...and show nothing once it is this old: no weather beats old weather. */
    static final long MAX_AGE_MS = 2 * 60 * 60_000L;
    /** After a failed refresh, wait this long before trying again. */
    static final long RETRY_MS = 5 * 60_000L;
    /** api.weather.gov asks every client to identify itself. */
    private static final String USER_AGENT = "(cleanyourtv-weather, github.com/kingshmiley/cleanyourtv)";
    private static final String TAG = "TvWeather";

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile long lastFailure;

    private Weather() {
    }

    /** "40.4406,-79.9959" for the query's lat and lon, or null if they are missing or out of range. */
    static String place(Uri uri) {
        try {
            double lat = Double.parseDouble(uri.getQueryParameter("lat"));
            double lon = Double.parseDouble(uri.getQueryParameter("lon"));
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            // api.weather.gov redirects coordinates with more than four decimals.
            return String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The cached rows for this place, if fresh enough; starts a refresh when they are getting old. */
    static List<String[]> rows(Context ctx, String place) {
        SharedPreferences prefs = prefs(ctx);
        long age = System.currentTimeMillis() - prefs.getLong("at", 0);
        boolean same = place.equals(prefs.getString("place", ""));
        if (!same || age >= FRESH_MS) refresh(ctx.getApplicationContext(), place);
        if (!same || age >= MAX_AGE_MS || age < 0) return Collections.emptyList();
        return decode(prefs.getString("rows", "[]"));
    }

    /** The NWS detailed forecast saved with the last refresh, for ForecastActivity: name and text per period. */
    static List<String[]> periods(Context ctx) {
        return decode(prefs(ctx).getString("periods", "[]"));
    }

    static String lastPlace(Context ctx) {
        return prefs(ctx).getString("place", "");
    }

    static long fetchedAt(Context ctx) {
        return prefs(ctx).getLong("at", 0);
    }

    private static void refresh(Context ctx, String place) {
        if (System.currentTimeMillis() - lastFailure < RETRY_MS) return;
        if (!busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                fetch(ctx, place);
                ctx.getContentResolver().notifyChange(STATUS, null);
            } catch (Exception e) {
                lastFailure = System.currentTimeMillis();
                Log.w(TAG, "refresh failed: " + e);
            } finally {
                busy.set(false);
            }
        }, "weather").start();
    }

    private static void fetch(Context ctx, String place) throws IOException, org.json.JSONException {
        SharedPreferences prefs = prefs(ctx);
        // The grid point for a place never changes; look it up once.
        String daily = prefs.getString("daily:" + place, "");
        String hourly = prefs.getString("hourly:" + place, "");
        String zone = prefs.getString("zone:" + place, "");
        if (daily.isEmpty() || hourly.isEmpty()) {
            JSONObject p = get("https://api.weather.gov/points/" + place).getJSONObject("properties");
            daily = p.getString("forecast");
            hourly = p.getString("forecastHourly");
            zone = p.optString("timeZone", "");
            prefs.edit().putString("daily:" + place, daily).putString("hourly:" + place, hourly)
                    .putString("zone:" + place, zone).apply();
        }
        JSONObject d = get(daily), h = get(hourly);
        JSONObject alerts = null;
        try {
            alerts = get("https://api.weather.gov/alerts/active?point=" + place);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "alerts failed, showing the forecast without them: " + e);
        }
        ZoneId z;
        try {
            z = zone.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(zone);
        } catch (RuntimeException e) {
            z = ZoneId.systemDefault();
        }
        List<String[]> rows = Nws.rows(d, h, alerts, ZonedDateTime.now(z));

        List<String[]> periods = new ArrayList<>();
        JSONArray list = d.getJSONObject("properties").getJSONArray("periods");
        for (int i = 0; i < list.length() && i < 14; i++) {
            JSONObject p = list.getJSONObject(i);
            periods.add(new String[]{p.optString("name", ""), p.optString("detailedForecast", "")});
        }
        prefs.edit().putString("place", place).putLong("at", System.currentTimeMillis())
                .putString("rows", encode(rows)).putString("periods", encode(periods)).apply();
    }

    private static JSONObject get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/geo+json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 << 20) throw new IOException("response over 4 MB: " + url);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad JSON from " + url, e);
        } finally {
            c.disconnect();
        }
    }

    private static String encode(List<String[]> rows) {
        JSONArray a = new JSONArray();
        for (String[] r : rows) {
            JSONArray row = new JSONArray();
            for (String v : r) row.put(v);
            a.put(row);
        }
        return a.toString();
    }

    private static List<String[]> decode(String json) {
        List<String[]> rows = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray row = a.getJSONArray(i);
                String[] r = new String[row.length()];
                for (int j = 0; j < r.length; j++) r[j] = row.optString(j, "");
                rows.add(r);
            }
        } catch (org.json.JSONException ignored) {
        }
        return rows;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
