package io.github.kingshmiley.tvweather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * content://io.github.kingshmiley.tvweather.status/status?lat=40.4406&lon=-79.9959, read by the launcher
 * (docs/specs/status.md). Answers from the cache at once; a stale cache starts a refresh, and the launcher is
 * told through the URI's notification when the new rows are in. Read-only.
 */
public final class StatusProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(Nws.COLUMNS);
        if (!"status".equals(uri.getLastPathSegment())) return c;
        String place = Weather.place(uri);
        if (place != null) {
            for (String[] row : Weather.rows(getContext(), place)) {
                // Rows cached by an older version have fewer columns; MatrixCursor wants exactly one per column.
                c.addRow(java.util.Arrays.copyOf(row, Nws.COLUMNS.length));
            }
        }
        c.setNotificationUri(getContext().getContentResolver(), Weather.STATUS);
        return c;
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
