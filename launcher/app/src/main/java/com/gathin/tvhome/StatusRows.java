package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Rows from a status provider (docs/specs/status.md): another app on the TV, named by the theme's
 * {@code status_provider}, that hands Home a weather line, a badge, an alert and day cards. Home only reads
 * them: the launcher itself never goes online. Every value is another app's text, so it is checked and cut
 * to size here before it reaches a view. No Android types, so it is tested on the JVM.
 */
final class StatusRows {

    /** Kinds of row; anything else is ignored. */
    static final String NOW = "now", BADGE = "badge", ALERT = "alert", DAY = "day";
    /** Icons the launcher draws (WeatherIcon); an unknown name draws nothing. */
    static final List<String> ICONS = Collections.unmodifiableList(java.util.Arrays.asList(
            "sun", "moon", "partly", "partly_night", "cloud", "rain", "storm", "snow", "fog"));
    /** Day cards shown at most; the row is laid out for this many. */
    static final int MAX_DAYS = 5;
    static final int MAX_TEXT = 40, MAX_ALERT = 72, MAX_VALUE = 8;

    static final class Row {
        final String kind, icon, title, value, detail;
        /** Day rows: chance of precipitation, "40%", or empty. */
        final String chance;

        Row(String kind, String icon, String title, String value, String detail, String chance) {
            this.kind = kind;
            this.icon = icon;
            this.title = title;
            this.value = value;
            this.detail = detail;
            this.chance = chance;
        }

        /** The chance as a number, or -1 when there is none. */
        int chancePercent() {
            return chance.isEmpty() ? -1 : Integer.parseInt(chance.substring(0, chance.length() - 1));
        }
    }

    /** What Home shows; every part may be missing. */
    static final class Status {
        final Row now, badge, alert;
        final List<Row> days;

        Status(Row now, Row badge, Row alert, List<Row> days) {
            this.now = now;
            this.badge = badge;
            this.alert = alert;
            this.days = Collections.unmodifiableList(days);
        }

        boolean isEmpty() {
            return now == null && alert == null && days.isEmpty();
        }
    }

    static final Status EMPTY = new Status(null, null, null, Collections.emptyList());

    private StatusRows() {
    }

    /** An authority Android would accept and a theme can't use to smuggle a path or query into the URI. */
    static boolean validAuthority(String s) {
        return s != null && s.length() <= 128 && s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    /** "0%" to "100%", or empty: anything else from the provider is dropped. */
    static String chance(String s) {
        String c = clean(s, 4);
        if (!c.matches("\\d{1,3}%")) return "";
        return Integer.parseInt(c.substring(0, c.length() - 1)) > 100 ? "" : c;
    }

    /**
     * One Row per cursor row, in order, cleaned: control characters dropped, whitespace collapsed, text cut to
     * size, unknown kinds and icons blanked. The first now, badge and alert count; days stop at {@link #MAX_DAYS}.
     * A badge without a now row is dropped, since it only makes sense beside it.
     */
    static Status from(List<String[]> raw) {
        Row now = null, badge = null, alert = null;
        List<Row> days = new ArrayList<>();
        for (String[] r : raw) {
            if (r == null || r.length < 5) continue;
            String kind = clean(r[0], 8).toLowerCase(Locale.ROOT);
            String icon = clean(r[1], 16).toLowerCase(Locale.ROOT);
            if (!ICONS.contains(icon)) icon = "";
            String chance = r.length > 5 ? chance(r[5]) : "";
            switch (kind) {
                case NOW:
                    if (now == null) now = new Row(kind, icon, clean(r[2], MAX_TEXT), clean(r[3], MAX_VALUE), clean(r[4], MAX_TEXT), "");
                    break;
                case BADGE:
                    if (badge == null) badge = new Row(kind, icon, clean(r[2], MAX_TEXT), "", "", "");
                    break;
                case ALERT:
                    if (alert == null) alert = new Row(kind, icon, clean(r[2], MAX_ALERT), "", "", "");
                    break;
                case DAY:
                    if (days.size() < MAX_DAYS) days.add(new Row(kind, icon, clean(r[2], 4), clean(r[3], MAX_VALUE), clean(r[4], MAX_VALUE), chance));
                    break;
                default:
            }
        }
        if (now != null && now.value.isEmpty() && now.title.isEmpty()) now = null;
        if (now == null || (badge != null && badge.title.isEmpty())) badge = null;
        if (alert != null && alert.title.isEmpty()) alert = null;
        days.removeIf(d -> d.title.isEmpty() || d.value.isEmpty());
        return new Status(now, badge, alert, days);
    }

    /** Printable text on one line, at most {@code max} characters, an ellipsis where it was cut. */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max + 1));
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() <= max; ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = b.length() > 0;
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE || type == Character.UNASSIGNED) continue;
            if (space) b.append(' ');
            space = false;
            b.appendCodePoint(c);
        }
        if (b.length() > max) {
            b.setLength(max - 1);
            return b.toString().trim() + "…";
        }
        return b.toString();
    }
}
