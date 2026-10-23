package io.github.kingshmiley.tvweather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns api.weather.gov responses into the launcher's status rows (docs/specs/status.md): one "now" line with
 * today's high and low, a rain badge, the most serious active warning, and the next five days. Plain Java and
 * org.json, so it is tested on the JVM against saved responses.
 */
final class Nws {

    /** Rows as the provider returns them: kind, icon, title, value, detail, chance (day rows: "40%"). */
    static final String[] COLUMNS = {"kind", "icon", "title", "value", "detail", "chance"};
    static final int DAYS = 5;
    /** How far ahead the rain badge looks. */
    static final int BADGE_HOURS = 12;
    static final int WET_PERCENT = 50;

    private Nws() {
    }

    /**
     * @param daily  /gridpoints/.../forecast
     * @param hourly /gridpoints/.../forecast/hourly
     * @param alerts /alerts/active?point=..., or null when that request failed (the rest still shows)
     * @param now    the current time in the forecast's time zone
     */
    static List<String[]> rows(JSONObject daily, JSONObject hourly, JSONObject alerts, ZonedDateTime now) {
        List<String[]> rows = new ArrayList<>();
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate();
        JSONArray days = periods(daily);
        JSONArray hours = periods(hourly);

        // The hour we're in: the first hourly period that hasn't ended.
        int current = -1;
        for (int i = 0; i < hours.length(); i++) {
            if (time(hours.optJSONObject(i), "endTime", zone).isAfter(now)) {
                current = i;
                break;
            }
        }
        if (current >= 0) {
            JSONObject h = hours.optJSONObject(current);
            String text = h.optString("shortForecast", "");
            rows.add(new String[]{"now", icon(text, h.optBoolean("isDaytime", true)), text,
                    degrees(h), highLow(days, today, zone), ""});
            String badge = badge(hours, current, zone);
            if (!badge.isEmpty()) rows.add(new String[]{"badge", "", badge, "", "", ""});
        }

        String alert = alert(alerts, now);
        if (!alert.isEmpty()) rows.add(new String[]{"alert", "", alert, "", "", ""});

        int added = 0;
        for (int i = 0; i < days.length() && added < DAYS; i++) {
            JSONObject d = days.optJSONObject(i);
            if (!d.optBoolean("isDaytime", false)) continue;
            LocalDate date = time(d, "startTime", zone).toLocalDate();
            if (!date.isAfter(today)) continue;
            JSONObject night = i + 1 < days.length() ? days.optJSONObject(i + 1) : null;
            boolean hasNight = night != null && !night.optBoolean("isDaytime", true);
            String low = hasNight ? degrees(night) : "";
            rows.add(new String[]{"day", icon(d.optString("shortForecast", ""), true),
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US).toUpperCase(Locale.US),
                    degrees(d), low, chance(d, hasNight ? night : null)});
            added++;
        }
        return rows;
    }

    /** "H 63°  L 47°" from today's daytime and tonight's periods; in the evening only the low is left. */
    static String highLow(JSONArray days, LocalDate today, ZoneId zone) {
        String high = "", low = "";
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (!time(d, "startTime", zone).toLocalDate().equals(today)) continue;
            if (d.optBoolean("isDaytime", false)) {
                if (high.isEmpty()) high = "H " + degrees(d);
            } else if (low.isEmpty()) {
                low = "L " + degrees(d);
            }
        }
        return high.isEmpty() ? low : low.isEmpty() ? high : high + "  " + low;
    }

    /**
     * "Rain from 7 PM" when the next {@link #BADGE_HOURS} hours turn wet, "Rain until 9 PM" while it is wet now,
     * "Snow", "Storms" the same way. Empty when the next hours are dry.
     */
    static String badge(JSONArray hours, int current, ZoneId zone) {
        int end = Math.min(hours.length(), current + BADGE_HOURS);
        int first = -1;
        for (int i = current; i < end; i++) {
            if (wet(hours.optJSONObject(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) return "";
        String what = kind(hours.optJSONObject(first).optString("shortForecast", ""));
        if (first > current) return what + " from " + hour(time(hours.optJSONObject(first), "startTime", zone));
        for (int i = current + 1; i < end; i++) {
            if (!wet(hours.optJSONObject(i))) return what + " until " + hour(time(hours.optJSONObject(i), "startTime", zone));
        }
        return what + " now";
    }

    static boolean wet(JSONObject hour) {
        JSONObject pop = hour.optJSONObject("probabilityOfPrecipitation");
        if (pop != null && pop.optInt("value", 0) >= WET_PERCENT) return true;
        String t = hour.optString("shortForecast", "").toLowerCase(Locale.US);
        if (t.contains("chance") || t.contains("slight")) return false;   // "Slight Chance Rain Showers" at 15%
        return t.matches(".*(rain|shower|drizzle|thunder|snow|sleet|flurr|freezing).*");
    }

    private static String kind(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("thunder")) return "Storms";
        if (t.matches(".*(snow|sleet|flurr|freezing|ice).*")) return "Snow";
        return "Rain";
    }

    /**
     * The most serious active warning, "Flood Warning until 9:45 PM". Only warnings and Severe or Extreme alerts:
     * advisories and statements would sit on Home for days.
     */
    static String alert(JSONObject alerts, ZonedDateTime now) {
        if (alerts == null) return "";
        JSONArray features = alerts.optJSONArray("features");
        if (features == null) return "";
        int bestRank = 0;
        String best = "";
        for (int i = 0; i < features.length(); i++) {
            JSONObject p = features.optJSONObject(i).optJSONObject("properties");
            if (p == null || !"Actual".equals(p.optString("status", "Actual"))) continue;
            String event = p.optString("event", "").trim();
            if (event.isEmpty()) continue;
            int rank = rank(p.optString("severity", ""));
            if (rank < 3 && !event.endsWith("Warning")) continue;
            if (event.endsWith("Warning")) rank += 1;   // a Moderate warning still beats nothing, never a Severe one
            String until = p.isNull("ends") ? p.optString("expires", "") : p.optString("ends", "");
            ZonedDateTime end = null;
            try {
                if (!until.isEmpty()) end = OffsetDateTime.parse(until).atZoneSameInstant(now.getZone());
            } catch (RuntimeException ignored) {
            }
            if (end != null && !end.isAfter(now)) continue;
            if (rank > bestRank) {
                bestRank = rank;
                best = end == null ? event : event + " until " + until(end, now);
            }
        }
        return best;
    }

    private static int rank(String severity) {
        switch (severity) {
            case "Extreme": return 5;
            case "Severe": return 4;
            case "Moderate": return 2;
            case "Minor": return 1;
            default: return 0;
        }
    }

    /** "9:45 PM" today, "Fri 6 AM" on another day. */
    static String until(ZonedDateTime end, ZonedDateTime now) {
        String time = end.getMinute() == 0 ? hour(end) : end.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
        if (end.toLocalDate().equals(now.toLocalDate())) return time;
        return end.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US) + " " + time;
    }

    private static String hour(ZonedDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("h a", Locale.US));
    }

    /**
     * The launcher's icon for a forecast phrase. Order matters: "Chance Showers And Thunderstorms" is a storm,
     * "Mostly Cloudy" a cloud before "Partly" or "Sunny" can match.
     */
    static String icon(String text, boolean day) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("thunder")) return "storm";
        if (t.matches(".*(snow|sleet|flurr|freezing|ice|blizzard).*")) return "snow";
        if (t.matches(".*(rain|shower|drizzle).*")) return "rain";
        // "Patchy Fog then Mostly Sunny": with nothing falling, the part after "then" is most of the period.
        int then = t.lastIndexOf(" then ");
        if (then >= 0) t = t.substring(then + 6);
        if (t.matches(".*(fog|haze|smoke|mist).*")) return "fog";
        if (t.contains("mostly cloudy") || t.contains("overcast") || t.equals("cloudy")) return "cloud";
        if (t.contains("partly") || t.contains("mostly sunny") || t.contains("mostly clear")) return day ? "partly" : "partly_night";
        if (t.contains("sunny") || t.contains("clear") || t.contains("fair")) return day ? "sun" : "moon";
        return "cloud";
    }

    /**
     * The day's chance of precipitation, "40%": the higher of the daytime and overnight periods, so a card does not
     * say 0% when the rain is due after dark. The API leaves the value null when it has none, which reads as 0%.
     */
    static String chance(JSONObject day, JSONObject night) {
        return Math.max(pop(day), night == null ? 0 : pop(night)) + "%";
    }

    private static int pop(JSONObject period) {
        JSONObject p = period.optJSONObject("probabilityOfPrecipitation");
        return p == null || p.isNull("value") ? 0 : Math.max(0, Math.min(100, p.optInt("value", 0)));
    }

    private static String degrees(JSONObject period) {
        return period.has("temperature") && !period.isNull("temperature") ? period.optInt("temperature") + "°" : "";
    }

    private static JSONArray periods(JSONObject forecast) {
        JSONObject p = forecast == null ? null : forecast.optJSONObject("properties");
        JSONArray a = p == null ? null : p.optJSONArray("periods");
        return a == null ? new JSONArray() : a;
    }

    private static ZonedDateTime time(JSONObject period, String key, ZoneId zone) {
        return OffsetDateTime.parse(period.optString(key, "")).atZoneSameInstant(zone);
    }
}
