package io.github.kingshmiley.tvweather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class NwsTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    /** pbz-*.json: a grid point of the Pittsburgh NWS office (PBZ), fetched 22 October 2026 a little before 10 AM. */
    private static final ZonedDateTime MORNING = ZonedDateTime.of(2026, 10, 22, 9, 40, 0, 0, NY);

    private static JSONObject fixture(String name) throws Exception {
        try (InputStream in = NwsTest.class.getClassLoader().getResourceAsStream(name)) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String[] find(List<String[]> rows, String kind) {
        for (String[] r : rows) if (r[0].equals(kind)) return r;
        return null;
    }

    @Test
    public void aDryOctoberMorning() throws Exception {
        List<String[]> rows = Nws.rows(fixture("pbz-daily.json"), fixture("pbz-hourly.json"), null, MORNING);
        String[] now = find(rows, "now");
        assertEquals("sun", now[1]);
        assertEquals("Sunny", now[2]);
        assertEquals("44°", now[3]);
        assertEquals("H 65°  L 40°", now[4]);
        assertEquals("no rain in the next 12 hours, no badge", null, find(rows, "badge"));
        assertEquals(null, find(rows, "alert"));

        long days = rows.stream().filter(r -> r[0].equals("day")).count();
        assertEquals(5, days);
        String[] fri = rows.get(1);
        assertEquals("day", fri[0]);
        assertEquals("FRI", fri[2]);
        assertEquals("65°", fri[3]);
        assertEquals("34°", fri[4]);
        assertEquals("partly", fri[1]);
        assertEquals("0%", fri[5]);
        assertEquals("TUE", rows.get(5)[2]);
        assertEquals("2%", rows.get(5)[5]);
        for (String[] r : rows) assertEquals("every row has every column", Nws.COLUMNS.length, r.length);
    }

    @Test
    public void aDaysChanceIsTheWetterOfDayAndNight() throws Exception {
        JSONObject day = new JSONObject().put("probabilityOfPrecipitation", new JSONObject().put("value", 20));
        JSONObject night = new JSONObject().put("probabilityOfPrecipitation", new JSONObject().put("value", 60));
        assertEquals("60%", Nws.chance(day, night));
        assertEquals("20%", Nws.chance(day, null));
        JSONObject none = new JSONObject().put("probabilityOfPrecipitation", new JSONObject().put("value", JSONObject.NULL));
        assertEquals("a null chance reads as 0%", "0%", Nws.chance(none, new JSONObject()));
    }

    @Test
    public void inTheEveningOnlyTonightsLowIsLeft() throws Exception {
        JSONObject daily = fixture("pbz-daily.json");
        JSONArray periods = daily.getJSONObject("properties").getJSONArray("periods");
        periods.remove(0);   // the API drops "Today" once it is evening
        assertEquals("L 40°", Nws.highLow(periods, MORNING.toLocalDate(), NY));
    }

    private static JSONArray hours(int startHour, int[] pops, String[] texts) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < pops.length; i++) {
            ZonedDateTime s = MORNING.withHour(0).withMinute(0).plusHours(startHour + i);
            a.put(new JSONObject()
                    .put("startTime", s.toOffsetDateTime().toString())
                    .put("endTime", s.plusHours(1).toOffsetDateTime().toString())
                    .put("isDaytime", true).put("temperature", 50)
                    .put("probabilityOfPrecipitation", new JSONObject().put("value", pops[i]))
                    .put("shortForecast", texts[i]));
        }
        return a;
    }

    @Test
    public void rainLaterTodayGetsABadgeWithTheHour() throws Exception {
        JSONArray h = hours(16, new int[]{10, 20, 30, 70, 80}, new String[]{"Cloudy", "Cloudy", "Chance Rain Showers", "Rain Showers", "Rain"});
        assertEquals("Rain from 7 PM", Nws.badge(h, 0, NY));
    }

    @Test
    public void rainNowSaysWhenItStops() throws Exception {
        JSONArray h = hours(16, new int[]{90, 80, 20}, new String[]{"Rain", "Rain", "Cloudy"});
        assertEquals("Rain until 6 PM", Nws.badge(h, 0, NY));
        JSONArray all = hours(16, new int[]{90, 90}, new String[]{"Rain", "Rain"});
        assertEquals("Rain now", Nws.badge(all, 0, NY));
    }

    @Test
    public void stormsAndSnowAreNamedAndSlightChancesIgnored() throws Exception {
        assertEquals("Storms from 5 PM", Nws.badge(hours(16, new int[]{0, 60}, new String[]{"Sunny", "Showers And Thunderstorms"}), 0, NY));
        assertEquals("Snow from 5 PM", Nws.badge(hours(16, new int[]{0, 55}, new String[]{"Cloudy", "Snow Showers"}), 0, NY));
        assertEquals("", Nws.badge(hours(16, new int[]{15, 20}, new String[]{"Slight Chance Rain Showers", "Chance Rain Showers"}), 0, NY));
    }

    private static JSONObject alerts(JSONObject... props) throws Exception {
        JSONArray f = new JSONArray();
        for (JSONObject p : props) f.put(new JSONObject().put("properties", p));
        return new JSONObject().put("features", f);
    }

    @Test
    public void theMostSeriousWarningWinsAndAdvisoriesStayOff() throws Exception {
        ZonedDateTime now = MORNING.withHour(18);
        JSONObject a = alerts(
                new JSONObject().put("event", "Wind Advisory").put("severity", "Moderate").put("status", "Actual")
                        .put("ends", "2026-10-22T23:00:00-04:00"),
                new JSONObject().put("event", "Severe Thunderstorm Warning").put("severity", "Severe").put("status", "Actual")
                        .put("ends", "2026-10-22T21:45:00-04:00"),
                new JSONObject().put("event", "Flood Watch").put("severity", "Moderate").put("status", "Actual")
                        .put("ends", JSONObject.NULL).put("expires", "2026-10-23T06:00:00-04:00"));
        assertEquals("Severe Thunderstorm Warning until 9:45 PM", Nws.alert(a, now));

        JSONObject onlyAdvisory = alerts(new JSONObject().put("event", "Wind Advisory").put("severity", "Moderate")
                .put("status", "Actual").put("ends", "2026-10-22T23:00:00-04:00"));
        assertEquals("", Nws.alert(onlyAdvisory, now));
    }

    @Test
    public void expiredTestAndOvernightAlerts() throws Exception {
        ZonedDateTime now = MORNING.withHour(18);
        assertEquals("", Nws.alert(alerts(new JSONObject().put("event", "Tornado Warning").put("severity", "Extreme")
                .put("status", "Actual").put("ends", "2026-10-22T17:00:00-04:00")), now));
        assertEquals("", Nws.alert(alerts(new JSONObject().put("event", "Tornado Warning").put("severity", "Extreme")
                .put("status", "Test").put("ends", "2026-10-22T23:00:00-04:00")), now));
        assertEquals("Winter Storm Warning until Fri 6 AM", Nws.alert(alerts(new JSONObject().put("event", "Winter Storm Warning")
                .put("severity", "Severe").put("status", "Actual").put("ends", "2026-10-23T06:00:00-04:00")), now));
        assertEquals("", Nws.alert(null, now));
    }

    @Test
    public void iconsFollowThePhrase() throws Exception {
        assertEquals("storm", Nws.icon("Chance Showers And Thunderstorms", true));
        assertEquals("rain", Nws.icon("Light Rain Likely", true));
        assertEquals("snow", Nws.icon("Rain And Snow Showers", true));
        assertEquals("cloud", Nws.icon("Mostly Cloudy", true));
        assertEquals("partly", Nws.icon("Partly Sunny", true));
        assertEquals("partly_night", Nws.icon("Mostly Clear", false));
        assertEquals("moon", Nws.icon("Clear", false));
        assertEquals("sun", Nws.icon("Sunny", true));
        assertEquals("fog", Nws.icon("Patchy Fog", true));
        assertEquals("morning fog, sunny day", "partly", Nws.icon("Patchy Fog then Mostly Sunny", true));
        assertEquals("sun", Nws.icon("Patchy Fog then Sunny", true));
        assertEquals("fog", Nws.icon("Mostly Clear then Patchy Fog", false));
        assertEquals("rain anywhere wins", "rain", Nws.icon("Sunny then Chance Rain Showers", true));
        assertTrue(Nws.wet(new JSONObject().put("probabilityOfPrecipitation", new JSONObject().put("value", 60)).put("shortForecast", "Cloudy")));
    }
}
