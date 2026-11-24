package io.github.kingshmiley.tvweather;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Artwork for the launcher's carousel (docs/specs/art.md): the newest Continue Watching row an app published,
 * else its first preview-channel programme. The TV provider holds only web addresses for the pictures, and the
 * launcher has no network permission, so this app downloads them into its cache and serves the files.
 */
final class Art {

    static final String AUTHORITY = "io.github.kingshmiley.tvweather.art";
    /** Pictures kept in the cache; the oldest go first. */
    private static final int KEEP = 40;
    private static final long MAX_BYTES = 6L << 20;
    private static final String TAG = "TvArt";
    private static final Set<String> pending = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** What the carousel shows for one app. */
    static final class Pick {
        final String kind, title, url;

        Pick(String kind, String title, String url) {
            this.kind = kind;
            this.title = title;
            this.url = url;
        }
    }

    private Art() {
    }

    static Uri appUri(String pkg) {
        return Uri.parse("content://" + AUTHORITY + "/app/" + pkg);
    }

    /**
     * The app's newest Continue Watching row, else its first preview programme with a picture; null if neither.
     * Selections are refused without ACCESS_ALL_EPG_DATA, so the whole table is read and filtered here.
     */
    static Pick pick(Context ctx, String pkg) {
        String[] cols = {TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS};
        Pick best = null;
        long newest = Long.MIN_VALUE;
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(c.getString(0))) continue;
                String url = picture(c.getString(3), c.getString(4));
                if (url == null) continue;
                long when = c.isNull(5) ? 0 : c.getLong(5);
                if (best == null || when > newest) {
                    newest = when;
                    best = new Pick("continue", title(c.getString(1), c.getString(2)), url);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next: " + e);
        }
        if (best != null) return best;
        String[] pcols = {TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI};
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, pcols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(c.getString(0))) continue;
                String url = picture(c.getString(2), c.getString(3));
                if (url != null) return new Pick("preview", title(c.getString(1), null), url);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "preview: " + e);
        }
        return null;
    }

    private static String picture(String poster, String thumb) {
        for (String s : new String[]{poster, thumb}) {
            if (s != null && s.startsWith("https://")) return s;
        }
        return null;
    }

    private static String title(String title, String episode) {
        String t = title == null ? "" : title.trim();
        String e = episode == null ? "" : episode.trim();
        return e.isEmpty() || t.contains(e) ? t : t + " · " + e;
    }

    /** The cached file for this address, or null while it is still to be fetched (then one fetch starts). */
    static File cached(Context ctx, String url, String pkg) {
        File f = file(ctx, url);
        if (f.isFile() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        if (pending.add(url)) {
            new Thread(() -> {
                try {
                    download(url, f);
                    trim(f.getParentFile());
                    ctx.getContentResolver().notifyChange(appUri(pkg), null);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "download failed: " + e);
                } finally {
                    pending.remove(url);
                }
            }, "art").start();
        }
        return null;
    }

    static File file(Context ctx, String url) {
        File dir = new File(ctx.getCacheDir(), "art");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, hash(url) + ".img");
    }

    /** "img/<hash>" names a cached file and nothing else. */
    static boolean validName(String name) {
        return name != null && name.matches("[0-9a-f]{40}");
    }

    private static void download(String url, File into) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        File tmp = new File(into.getPath() + ".part");
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("picture over 6 MB");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(into)) throw new IOException("rename failed");
        } finally {
            tmp.delete();
            c.disconnect();
        }
    }

    private static void trim(File dir) {
        File[] files = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(".img"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
