package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class StatusRowsTest {

    private static String[] row(String kind, String icon, String title, String value, String detail) {
        return new String[]{kind, icon, title, value, detail};
    }

    @Test
    public void aWeatherDayReadsAsOneLineABadgeAndFiveCards() {
        List<String[]> raw = new ArrayList<>();
        raw.add(row("now", "partly", "Mostly Cloudy", "58°", "H 63°  L 47°"));
        raw.add(row("badge", "", "Rain from 7 PM", "", ""));
        for (String d : Arrays.asList("THU", "FRI", "SAT", "SUN", "MON", "TUE")) raw.add(row("day", "rain", d, "57°", "45°"));
        StatusRows.Status s = StatusRows.from(raw);
        assertEquals("58°", s.now.value);
        assertEquals("partly", s.now.icon);
        assertEquals("Rain from 7 PM", s.badge.title);
        assertNull(s.alert);
        assertEquals("five cards at most: the row is laid out for five", 5, s.days.size());
        assertEquals("MON", s.days.get(4).title);
    }

    @Test
    public void nothingFromTheProviderShowsNothing() {
        StatusRows.Status s = StatusRows.from(Collections.emptyList());
        assertTrue(s.isEmpty());
        assertNull(s.badge);
    }

    @Test
    public void onlyTheFirstOfEachKindCountsAndUnknownKindsAreIgnored() {
        StatusRows.Status s = StatusRows.from(Arrays.asList(
                row("now", "sun", "Sunny", "65°", ""),
                row("now", "rain", "Rain", "40°", ""),
                row("alert", "", "Flood Warning until 9 PM", "", ""),
                row("alert", "", "Wind Advisory", "", ""),
                row("ticker", "", "Buy now", "", "")));
        assertEquals("Sunny", s.now.title);
        assertEquals("Flood Warning until 9 PM", s.alert.title);
    }

    @Test
    public void aBadgeWithoutAWeatherLineIsDropped() {
        StatusRows.Status s = StatusRows.from(Collections.singletonList(row("badge", "", "Rain from 7 PM", "", "")));
        assertNull(s.badge);
        assertTrue(s.isEmpty());
    }

    @Test
    public void unknownIconsAreBlankedAndRowsWithoutTextDropped() {
        StatusRows.Status s = StatusRows.from(Arrays.asList(
                row("now", "../../etc/passwd", "Sunny", "65°", ""),
                row("day", "sun", "", "60°", "40°"),
                row("day", "sun", "FRI", "", "40°"),
                row("alert", "", "   ", "", ""),
                new String[]{"day", "sun"}));
        assertEquals("", s.now.icon);
        assertTrue(s.days.isEmpty());
        assertNull(s.alert);
    }

    @Test
    public void textIsFlattenedAndCutToSize() {
        assertEquals("Rain from 7 PM", StatusRows.clean("  Rain\nfrom\t7\u0000 PM ‮ ", 40));
        String cut = StatusRows.clean("Severe Thunderstorm Warning until 9:45 PM", 20);
        assertEquals(20, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("", StatusRows.clean(null, 10));
    }

    @Test
    public void dayCardsCarryTheChanceOfRainWhenItIsAPercentage() {
        StatusRows.Status s = StatusRows.from(Arrays.asList(
                new String[]{"day", "rain", "FRI", "58°", "41°", "80%"},
                new String[]{"day", "sun", "SAT", "61°", "43°", "0%"},
                new String[]{"day", "sun", "SUN", "64°", "45°", "lots"},
                new String[]{"day", "sun", "MON", "66°", "47°", "250%"},
                row("day", "sun", "TUE", "60°", "44°")));
        assertEquals("80%", s.days.get(0).chance);
        assertEquals(80, s.days.get(0).chancePercent());
        assertEquals(0, s.days.get(1).chancePercent());
        assertEquals("not a percentage: dropped, the card shows no line", "", s.days.get(2).chance);
        assertEquals(-1, s.days.get(3).chancePercent());
        assertEquals("a provider without the column still works", -1, s.days.get(4).chancePercent());
    }

    @Test
    public void authoritiesMustBePlainNames() {
        assertTrue(StatusRows.validAuthority("io.github.kingshmiley.tvweather.status"));
        assertFalse(StatusRows.validAuthority(""));
        assertFalse(StatusRows.validAuthority("weather"));
        assertFalse(StatusRows.validAuthority("evil.app/../other"));
        assertFalse(StatusRows.validAuthority("a.b?x=1"));
        assertFalse(StatusRows.validAuthority(null));
    }
}
