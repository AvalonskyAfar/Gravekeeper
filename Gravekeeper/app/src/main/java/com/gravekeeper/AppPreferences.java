package com.gravekeeper;

import android.content.Context;
import android.content.SharedPreferences;

/** UI-only preferences. Runtime protection settings remain in ConfigStore. */
public final class AppPreferences {
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private static final String NAME = "guard_ui";
    private static final String CONSENT_NAME = "guard_consent";
    private static final String CONSENT_KEY = "screen_analysis_consent";
    private final SharedPreferences values;
    private final SharedPreferences consent;

    public AppPreferences(Context context) {
        Context app = context.getApplicationContext();
        values = app.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        consent = app.getSharedPreferences(CONSENT_NAME, Context.MODE_PRIVATE);
    }

    public int theme() { return values.getInt("theme", THEME_SYSTEM); }
    public void setTheme(int value) { values.edit().putInt("theme", value).apply(); }
    public boolean largeText() { return values.getBoolean("large_text", false); }
    public void setLargeText(boolean value) { values.edit().putBoolean("large_text", value).apply(); }
    public boolean highContrast() { return values.getBoolean("high_contrast", false); }
    public void setHighContrast(boolean value) { values.edit().putBoolean("high_contrast", value).apply(); }
    public boolean reduceMotion() { return values.getBoolean("reduce_motion", false); }
    public void setReduceMotion(boolean value) { values.edit().putBoolean("reduce_motion", value).apply(); }
    public boolean haptics() { return values.getBoolean("haptics", true); }
    public void setHaptics(boolean value) { values.edit().putBoolean("haptics", value).apply(); }
    public boolean hideLauncher() { return values.getBoolean("hide_launcher", false); }
    public void setHideLauncher(boolean value) { values.edit().putBoolean("hide_launcher", value).apply(); }
    public boolean hideRecents() { return values.getBoolean("hide_recents", false); }
    public void setHideRecents(boolean value) { values.edit().putBoolean("hide_recents", value).apply(); }
    public boolean consented() { return consent.getBoolean(CONSENT_KEY, false); }
    public void setConsented(boolean value) { consent.edit().putBoolean(CONSENT_KEY, value).apply(); }

    public boolean resolveDark(Context context) {
        if (theme() == THEME_DARK) return true;
        if (theme() == THEME_LIGHT) return false;
        int mask = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
