package io.github.kingshmiley.tvweather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * content://io.github.kingshmiley.tvweather.art/app/<package>: one row, kind ("continue" or "preview"), title and
 * image (a content:// address of the cached picture, empty while it downloads; the app's URI is notified when it
 * lands). content://…/img/<hash> opens that picture. Guarded by a signature permission: the rows are what the
 * owner has been watching, so only the launcher, signed with the same key, may read them.
 */
public final class ArtProvider extends ContentProvider {

    private static final String[] COLUMNS = {"kind", "title", "image"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 1 && "continue".equals(seg.get(0))) return continueRows();
        MatrixCursor c = new MatrixCursor(COLUMNS);
        if (seg.size() != 2 || !"app".equals(seg.get(0)) || !seg.get(1).matches("[A-Za-z0-9_.]+")) return c;
        String pkg = seg.get(1);
        Art.Pick pick = Art.pick(getContext(), pkg);
        if (pick != null) {
            File f = Art.cached(getContext(), pick.url, pkg);
            String image = f == null ? "" : "content://" + Art.AUTHORITY + "/img/" + f.getName().replace(".img", "");
            c.addRow(new Object[]{pick.kind, pick.title, image});
        }
        c.setNotificationUri(getContext().getContentResolver(), Art.appUri(pkg));
        return c;
    }

    private static final String[] CONTINUE_COLUMNS = {"package", "title", "episode", "image", "position", "duration", "intent"};

    /** content://…/continue: the Continue Watching rows, newest first (docs/specs/art.md). */
    private Cursor continueRows() {
        MatrixCursor c = new MatrixCursor(CONTINUE_COLUMNS);
        for (Art.Resume r : Art.continueWatching(getContext())) {
            String image = "";
            if (r.url != null) {
                File f = Art.cached(getContext(), r.url, r.pkg);
                if (f != null) image = "content://" + Art.AUTHORITY + "/img/" + f.getName().replace(".img", "");
            }
            c.addRow(new Object[]{r.pkg, r.title, r.episode, image, r.position, r.duration, r.intent});
        }
        c.setNotificationUri(getContext().getContentResolver(), Art.CONTINUE);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (!"r".equals(mode) || seg.size() != 2 || !"img".equals(seg.get(0)) || !Art.validName(seg.get(1))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(new File(getContext().getCacheDir(), "art"), seg.get(1) + ".img");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
