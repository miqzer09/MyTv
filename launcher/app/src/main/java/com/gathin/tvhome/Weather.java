package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Live weather for the home screen's glass card: the temperature and conditions now, the hours
 * ahead and the days ahead, from Open-Meteo (no key, no account). A TV has no GPS, so the place
 * comes once from the TV's public IP — or from a town named in the theme's {@code weather_city},
 * geocoded once — and is kept in preferences; {@code "weather": false} in config.json hides the
 * whole card. Cached for half an hour; runs on a background thread, and every failure just leaves
 * the last reading in place.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final long REFRESH_MS = 30 * 60 * 1000;
    /** Hours of the strip on the card; days in the row under it. */
    static final int HOURS = 6, DAYS = 4;
    /** The place, asked of these in turn until one answers; kept after the first success. */
    private static final String[] GEO = {
            "https://ipwho.is/",
            "https://ipapi.co/json/",
    };

    /** Screen effects (WeatherFxView): none, falling rain, drifting snow, a thunderstorm. */
    static final int FX_NONE = 0, FX_RAIN = 1, FX_SNOW = 2, FX_STORM = 3;

    /**
     * One reading, structured for the card: everything it draws, as it was fetched. Immutable;
     * {@link #toJson}/{@link #fromJson} round-trip it through preferences.
     */
    static final class Data {
        final String city;
        final double temp;
        final int code;                       // WMO weather code now
        final double hi, lo;                  // today's range
        final boolean fahrenheit;
        final String condition;               // localized words for code, may be empty
        final String[] hourLabels, hourTemps; // the hours strip, HOURS long
        final int[] hourCodes;
        final String[] dayLabels, dayRanges;  // the days row, DAYS long
        final int[] dayCodes;
        final int fx;                         // FX_* for the card's screen effects
        final long fetchedAt;

        Data(String city, double temp, int code, double hi, double lo, boolean fahrenheit,
             String condition, String[] hourLabels, String[] hourTemps, int[] hourCodes,
             String[] dayLabels, String[] dayRanges, int[] dayCodes, int fx, long fetchedAt) {
            this.city = city;
            this.temp = temp;
            this.code = code;
            this.hi = hi;
            this.lo = lo;
            this.fahrenheit = fahrenheit;
            this.condition = condition;
            this.hourLabels = hourLabels;
            this.hourTemps = hourTemps;
            this.hourCodes = hourCodes;
            this.dayLabels = dayLabels;
            this.dayRanges = dayRanges;
            this.dayCodes = dayCodes;
            this.fx = fx;
            this.fetchedAt = fetchedAt;
        }

        /** "32°" — one number, ready to draw. */
        String tempText() {
            return Math.round(temp) + "\u00B0";
        }
    }

    private Weather() {
    }

    /** The last reading already on screen, or null before the first fetch. Cheap: preferences only. */
    static Data load(Context ctx) {
        String blob = prefs(ctx).getString("data", null);
        if (blob == null || blob.isEmpty()) return null;
        try {
            return fromJson(new JSONObject(blob));
        } catch (Exception e) {
            Log.w(TAG, "cached reading unreadable: " + e);
            return null;
        }
    }

    /**
     * True when there is no reading yet, the last one is half an hour old, the place changed
     * (a different weather_city in the theme), or the cache predates the structured format (or
     * the country key, which picks the unit): fetched again, it is saved in the current format.
     *
     * @param city the theme's fixed place, empty for the public-IP lookup
     */
    static boolean due(Context ctx, String city) {
        SharedPreferences p = prefs(ctx);
        String want = city == null ? "" : city.trim();
        if (p.getString("data", null) == null) return true;
        if (p.getString("country", "").isEmpty()) return true;
        // The cached reading is for another place until this one has been resolved and fetched.
        if (!want.equals(p.getString("via", ""))) return true;
        return System.currentTimeMillis() - p.getLong("fetched_at", 0) >= REFRESH_MS;
    }

    /**
     * Blocking: the place (the theme's weather_city when set, otherwise once from the public IP,
     * then from preferences), then now, the hours and the days ahead, then saved for the next half
     * hour. Returns null when anything failed, keeping the previous reading. Call from a
     * background thread.
     *
     * @param city the theme's fixed place, empty for the public-IP lookup
     */
    static Data refresh(Context ctx, String city) {
        try {
            SharedPreferences p = prefs(ctx);
            String want = city == null ? "" : city.trim();
            String lat = p.getString("lat", null), lon = p.getString("lon", null);
            String country = p.getString("country", "");
            // The country picks the unit below, so a cache saved without one — or saved for a
            // different weather_city — is looked up again.
            if (lat == null || lon == null || country.isEmpty()
                    || !want.equals(p.getString("via", ""))) {
                String[] place = want.isEmpty() ? locate() : geocode(want);
                if (place == null) return null;
                lat = place[0];
                lon = place[1];
                country = place[3];
                // "via" is the query the place was resolved from ("" for the IP lookup), so a
                // changed weather_city is noticed and an old cache without the key stays valid.
                p.edit().putString("lat", lat).putString("lon", lon)
                        .putString("city", place[2]).putString("country", country)
                        .putString("via", want).apply();
            }
            boolean fahrenheit = usesFahrenheit(country);
            JSONObject w = json(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code"
                    + "&hourly=temperature_2m,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=5"
                    + (fahrenheit ? "&temperature_unit=fahrenheit" : ""), 256 * 1024));
            if (w == null) return null;

            JSONObject current = w.optJSONObject("current");
            JSONObject hc = w.optJSONObject("hourly");
            JSONObject daily = w.optJSONObject("daily");
            JSONArray times = daily != null ? daily.optJSONArray("time") : null;
            JSONArray highs = daily != null ? daily.optJSONArray("temperature_2m_max") : null;
            JSONArray lows = daily != null ? daily.optJSONArray("temperature_2m_min") : null;
            if (current == null || hc == null || times == null || highs == null || lows == null
                    || times.length() == 0) {
                Log.w(TAG, "forecast response incomplete");
                return null;
            }
            double temp = current.optDouble("temperature_2m", Double.NaN);
            if (Double.isNaN(temp)) return null;
            int code = current.optInt("weather_code", -1);

            // The hours strip: the model hour the reading belongs to, then the ones after it.
            JSONArray hTimes = hc.optJSONArray("time");
            JSONArray hTemps = hc.optJSONArray("temperature_2m");
            JSONArray hCodes = hc.optJSONArray("weather_code");
            String[] hourLabels = new String[HOURS];
            String[] hourTemps = new String[HOURS];
            int[] hourCodes = new int[HOURS];
            if (hTimes != null && hTemps != null && hCodes != null) {
                int at = 0;
                String now = current.optString("time", "");
                if (now.length() >= 13) {
                    for (int i = 0; i < hTimes.length(); i++) {
                        String t = hTimes.optString(i, "");
                        if (t.length() >= 13 && t.substring(0, 13).equals(now.substring(0, 13))) {
                            at = i;
                            break;
                        }
                    }
                }
                boolean h24 = DateFormat.is24HourFormat(ctx);
                for (int i = 0; i < HOURS; i++) {
                    int j = at + i;
                    if (j >= hTimes.length() || j >= hTemps.length() || j >= hCodes.length()) break;
                    // "2026-10-23T18:00" in the place's own timezone (timezone=auto): the hour is local.
                    String t = hTimes.optString(j, "");
                    if (t.length() >= 13) {
                        int hour = Integer.parseInt(t.substring(11, 13));
                        hourLabels[i] = h24 ? String.format(Locale.getDefault(), "%02d", hour)
                                : (hour % 12 == 0 ? 12 : hour % 12) + (hour < 12 ? "a" : "p");
                    }
                    hourTemps[i] = Math.round(hTemps.optDouble(j, Double.NaN)) + "\u00B0";
                    hourCodes[i] = hCodes.optInt(j, -1);
                }
            }

            // The days row: today's range on the card's big line, the next DAYS under it.
            String[] dayLabels = new String[DAYS];
            String[] dayRanges = new String[DAYS];
            int[] dayCodes = new int[DAYS];
            Locale loc = Locale.getDefault();
            for (int i = 0; i < DAYS && i + 1 < times.length(); i++) {
                int j = i + 1;
                if (j >= highs.length() || j >= lows.length()) break;
                dayLabels[i] = LocalDate.parse(times.getString(j))
                        .getDayOfWeek().getDisplayName(TextStyle.SHORT, loc).toUpperCase(loc);
                dayRanges[i] = Math.round(highs.getDouble(j)) + "\u00B0/"
                        + Math.round(lows.getDouble(j)) + "\u00B0";
                dayCodes[i] = daily.optJSONArray("weather_code") != null
                        ? daily.optJSONArray("weather_code").optInt(j, -1) : -1;
            }

            Data out = new Data(p.getString("city", ""), temp, code,
                    highs.length() > 0 ? highs.optDouble(0, temp) : temp,
                    lows.length() > 0 ? lows.optDouble(0, temp) : temp,
                    fahrenheit, condition(ctx, code), hourLabels, hourTemps, hourCodes,
                    dayLabels, dayRanges, dayCodes, effectOf(code, hourCodes),
                    System.currentTimeMillis());
            p.edit().putString("data", toJson(out).toString())
                    .putLong("fetched_at", out.fetchedAt).apply();
            return out;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed: " + e);
            return null;
        }
    }

    /**
     * The screen effect for a reading: what is falling on the card's own conditions, or on the
     * next couple of hours when rain or snow is on its way. {@link #FX_NONE} otherwise.
     */
    private static int effectOf(int code, int[] hourCodes) {
        int fx = fxFor(code);
        if (fx != FX_NONE) return fx;
        // Only the next three hours count as "on the way"; a shower tomorrow stays tomorrow's.
        for (int i = 0; i < hourCodes.length && i < 3; i++) {
            fx = fxFor(hourCodes[i]);
            if (fx != FX_NONE) return fx;
        }
        return FX_NONE;
    }

    /** One WMO code as an effect; drizzle counts as rain, a thunderstorm outranks its rain. */
    private static int fxFor(int code) {
        switch (code) {
            case 95: case 96: case 99: return FX_STORM;
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return FX_RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return FX_SNOW;
            default: return FX_NONE;
        }
    }

    /** Latitude, longitude, city and country from the TV's public IP, or null when none answered. */
    private static String[] locate() {
        for (String base : GEO) {
            try {
                JSONObject o = json(get(base, 16 * 1024));
                if (o == null) continue;
                if (o.has("success") && !o.optBoolean("success", true)) continue;
                if (o.optBoolean("error", false)) continue;
                double lat = o.optDouble("latitude", Double.NaN), lon = o.optDouble("longitude", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                // country_code on both ipwho.is and ipapi.co.
                return new String[]{Double.toString(lat), Double.toString(lon),
                        o.optString("city", "").trim(), o.optString("country_code", "").trim()};
            } catch (Exception e) {
                Log.i(TAG, "no place from " + base + ": " + e);
            }
        }
        return null;
    }

    /**
     * A named place from Open-Meteo's geocoding (also keyless), as locate()'s four strings, or
     * null when the name matches nothing.
     */
    private static String[] geocode(String city) {
        try {
            JSONObject o = json(get("https://geocoding-api.open-meteo.com/v1/search?count=1"
                    + "&language=en&format=json&name=" + URLEncoder.encode(city, "UTF-8"), 16 * 1024));
            if (o == null) return null;
            JSONArray r = o.optJSONArray("results");
            JSONObject hit = r != null ? r.optJSONObject(0) : null;
            if (hit == null) {
                Log.w(TAG, "no place named " + city);
                return null;
            }
            double lat = hit.optDouble("latitude", Double.NaN), lon = hit.optDouble("longitude", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
            return new String[]{Double.toString(lat), Double.toString(lon),
                    hit.optString("name", city).trim(), hit.optString("country_code", "").trim()};
        } catch (Exception e) {
            Log.w(TAG, "no place named " + city + ": " + e);
            return null;
        }
    }

    /** The WMO weather code as words; empty for a code the map does not name. */
    static String condition(Context ctx, int code) {
        int res;
        switch (code) {
            case 0: res = R.string.wx_clear; break;
            case 1: res = R.string.wx_mainly_clear; break;
            case 2: res = R.string.wx_partly_cloudy; break;
            case 3: res = R.string.wx_overcast; break;
            case 45: case 48: res = R.string.wx_fog; break;
            case 51: case 53: case 55: case 56: case 57: res = R.string.wx_drizzle; break;
            case 61: case 63: case 65: res = R.string.wx_rain; break;
            case 66: case 67: res = R.string.wx_freezing_rain; break;
            case 71: case 73: case 75: res = R.string.wx_snow; break;
            case 77: res = R.string.wx_snow_grains; break;
            case 80: case 81: case 82: res = R.string.wx_showers; break;
            case 85: case 86: res = R.string.wx_snow_showers; break;
            case 95: res = R.string.wx_thunder; break;
            case 96: case 99: res = R.string.wx_hail; break;
            default: return "";
        }
        return ctx.getString(res);
    }

    /**
     * Fahrenheit where it is the norm, decided by the country the weather is for rather than the
     * TV's UI language: an English-locale TV in Malaysia still reads Celsius. Falls back to the
     * device locale when the lookup returned no country.
     */
    private static boolean usesFahrenheit(String country) {
        if (country.isEmpty()) country = Locale.getDefault().getCountry();
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country);
    }

    private static JSONObject toJson(Data d) {
        try {
            JSONObject o = new JSONObject();
            o.put("city", d.city).put("temp", d.temp).put("code", d.code)
                    .put("hi", d.hi).put("lo", d.lo).put("f", d.fahrenheit)
                    .put("cond", d.condition).put("fx", d.fx).put("at", d.fetchedAt);
            JSONArray hl = new JSONArray(), ht = new JSONArray(), hc = new JSONArray();
            for (int i = 0; i < HOURS; i++) {
                hl.put(d.hourLabels[i] == null ? "" : d.hourLabels[i]);
                ht.put(d.hourTemps[i] == null ? "" : d.hourTemps[i]);
                hc.put(d.hourCodes[i]);
            }
            o.put("hl", hl).put("ht", ht).put("hc", hc);
            JSONArray dl = new JSONArray(), dr = new JSONArray(), dc = new JSONArray();
            for (int i = 0; i < DAYS; i++) {
                dl.put(d.dayLabels[i] == null ? "" : d.dayLabels[i]);
                dr.put(d.dayRanges[i] == null ? "" : d.dayRanges[i]);
                dc.put(d.dayCodes[i]);
            }
            o.put("dl", dl).put("dr", dr).put("dc", dc);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static Data fromJson(JSONObject o) throws Exception {
        JSONArray hl = o.getJSONArray("hl"), ht = o.getJSONArray("ht"), hc = o.getJSONArray("hc");
        JSONArray dl = o.getJSONArray("dl"), dr = o.getJSONArray("dr"), dc = o.getJSONArray("dc");
        return new Data(o.optString("city", ""), o.getDouble("temp"), o.optInt("code", -1),
                o.optDouble("hi", 0), o.optDouble("lo", 0), o.optBoolean("f", false),
                o.optString("cond", ""), strings(hl), strings(ht), ints(hc),
                strings(dl), strings(dr), ints(dc), o.optInt("fx", FX_NONE), o.optLong("at", 0));
    }

    private static String[] strings(JSONArray a) {
        String[] out = new String[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optString(i, "");
        return out;
    }

    private static int[] ints(JSONArray a) {
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optInt(i, -1);
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    private static JSONObject json(byte[] bytes) {
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "bad response: " + e.getMessage());
            return null;
        }
    }

    /** As PlaceWallpapers asks Bing: small timeouts, a size cap, and null only via an exception. */
    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
