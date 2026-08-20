package com.gravekeeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PerformanceTelemetry {
    private static final int MAX_SAMPLES = 30;
    private static final String PREFS = "guard_runtime_performance";

    private PerformanceTelemetry() {}

    public static void recordScreenshot(Context context, long latencyMs, boolean enabled) {
        if (enabled) record(context, "screenshot_samples", latencyMs);
    }

    public static void recordEndToEnd(Context context, long latencyMs, boolean enabled) {
        if (enabled) record(context, "end_to_end_samples", latencyMs);
    }

    public static long p95(Context context, String key) {
        List<Long> values = parse(context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).getString(key, ""));
        if (values.isEmpty()) return 0L;
        Collections.sort(values);
        int index = Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(index);
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static synchronized void record(Context context, String key, long value) {
        if (value < 0) return;
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        List<Long> values = parse(preferences.getString(key, ""));
        values.add(value);
        while (values.size() > MAX_SAMPLES) values.remove(0);
        StringBuilder encoded = new StringBuilder();
        for (long sample : values) {
            if (encoded.length() > 0) encoded.append(',');
            encoded.append(sample);
        }
        preferences.edit().putString(key, encoded.toString()).apply();
    }

    private static List<Long> parse(String encoded) {
        List<Long> values = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return values;
        for (String part : encoded.split(",")) {
            try { values.add(Long.parseLong(part)); }
            catch (NumberFormatException ignored) { }
        }
        return values;
    }
}
