package io.github.kingshmiley.tvweather;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The Weather tile: the National Weather Service's detailed forecast for the next week, from the last refresh.
 * Up and Down scroll; Back returns to Home. No fetching here: Home's status line keeps the data fresh.
 */
public final class ForecastActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float dp = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setBackgroundColor(0xFF000000);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(56 * dp);
        list.setPadding(pad, Math.round(32 * dp), pad, Math.round(32 * dp));
        scroll.addView(list);

        list.addView(text("Forecast", 30, 0xFFFFFFFF, Typeface.create("sans-serif-light", Typeface.NORMAL), 0));
        long at = Weather.fetchedAt(this);
        String place = Weather.lastPlace(this);
        String meta = at == 0 ? "No forecast yet. Open Home and it will load within a minute."
                : "National Weather Service  ·  " + place + "  ·  updated "
                + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        list.addView(text(meta, 13, 0x99FFFFFF, Typeface.DEFAULT, Math.round(6 * dp)));

        List<String[]> periods = Weather.periods(this);
        for (String[] p : periods) {
            if (p.length < 2) continue;
            list.addView(text(p[0], 16, 0xFFFFFFFF, Typeface.DEFAULT_BOLD, Math.round(22 * dp)));
            list.addView(text(p[1], 15, 0xCCFFFFFF, Typeface.DEFAULT, Math.round(4 * dp)));
        }
        setContentView(scroll);
        scroll.requestFocus();
    }

    private TextView text(String s, int sp, int color, Typeface face, int top) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        t.setLayoutParams(lp);
        return t;
    }
}
