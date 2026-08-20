package com.gravekeeper.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ConfigStore {
    private static final String ASSET_PATH = "config/guard_runtime_config.json";
    public static final String PREFERENCES_NAME = "guard_config";
    private static final String KEY_USER_JSON = "user_json";
    private static final String KEY_LAST_GOOD_JSON = "last_good_json";

    private final Context context;
    private final SharedPreferences preferences;

    // Parsed-config cache. Page renderers (MAIN/SETTINGS/ADVANCED/MORE) call
    // load()/loadJson()/defaultJson() on every non-cached build; without caching,
    // a single page build re-reads the bundled asset twice and re-parses both the
    // defaults and the effective config. That synchronous work runs on the UI
    // thread inside the first drag frame and is a direct cause of scroll jank on
    // fresh entries. The caches are instance-scoped (never static) so no state
    // leaks across Activities or JVM tests.
    private String assetTextCache;
    private JSONObject defaultsCache;
    private JSONObject effectiveJsonCache;
    private GuardConfig effectiveConfigCache;

    public ConfigStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        // Separate screens (DeveloperOptions / ConfigEditor) write the same prefs
        // through their own ConfigStore instances. Watch for any such write so this
        // instance's effective-config cache never goes stale when control returns here.
        // The write already runs below on the committing thread, so this listener is
        // concurrency-safe with the synchronized read/write methods.
        preferences.registerOnSharedPreferenceChangeListener(
                (prefs, key) -> {
                    if (KEY_USER_JSON.equals(key) || KEY_LAST_GOOD_JSON.equals(key)) {
                        clearEffectiveCache();
                    }
                });
    }

    public synchronized GuardConfig load() throws IOException {
        GuardConfig cached = effectiveConfigCache;
        if (cached != null) return cached;
        JSONObject json = loadJson();
        try {
            GuardConfig configured = new GuardConfig(json);
            effectiveConfigCache = configured;
            return configured;
        } catch (JSONException error) {
            throw new IOException("Invalid effective guard config", error);
        }
    }

    public synchronized JSONObject loadJson() throws IOException {
        JSONObject cached = effectiveJsonCache;
        if (cached != null) return cached;
        JSONObject defaults = defaultJson();
        String user = preferences.getString(KEY_USER_JSON, null);
        if (user != null) {
            try {
                JSONObject migrated = ConfigMigrator.migrate(new JSONObject(user), defaults);
                new GuardConfig(migrated);
                if (!migrated.toString().equals(user)) {
                    preferences.edit().putString(KEY_USER_JSON, migrated.toString()).apply();
                }
                effectiveJsonCache = migrated;
                return migrated;
            } catch (JSONException invalidUserConfig) {
                JSONObject lastGood = loadLastGood(defaults);
                if (lastGood != null) {
                    preferences.edit().putString(KEY_USER_JSON, lastGood.toString()).apply();
                    effectiveJsonCache = lastGood;
                    return lastGood;
                }
                preferences.edit().remove(KEY_USER_JSON).apply();
            }
        }
        try {
            new GuardConfig(defaults);
            // Never hand out the shared cached defaults to a caller: callers such as
            // setProtectionEnabled/applyDegradedPerformanceProfile mutate the returned
            // JSON before saving, and mutating the shared defaults would poison the
            // defaultJson() cache. A defensive copy keeps the defaults pristine while
            // still avoiding the asset read + parse on the fresh-install path.
            JSONObject independent = new JSONObject(defaults.toString());
            effectiveJsonCache = independent;
            return independent;
        } catch (JSONException error) {
            throw new IOException("Invalid bundled guard config", error);
        }
    }

    public synchronized JSONObject validateAndMigrate(JSONObject json) throws IOException {
        try {
            JSONObject migrated = ConfigMigrator.migrate(json, defaultJson());
            new GuardConfig(migrated);
            return migrated;
        } catch (JSONException error) {
            throw new IOException("Invalid guard config", error);
        }
    }

    public synchronized void save(JSONObject json) throws IOException {
        JSONObject migrated = validateAndMigrate(json);
        JSONObject current = loadJson();
        preferences.edit()
                .putString(KEY_LAST_GOOD_JSON, current.toString())
                .putString(KEY_USER_JSON, migrated.toString())
                .apply();
        // The committed (migrated) object is the new effective config. Rebuild the
        // caches from it so this session's page renderers do not re-read prefs and
        // re-parse the bundles on the next load.
        adoptEffective(migrated);
    }

    /** Point both effective-config caches at a freshly committed JSON, parsing once. */
    private void adoptEffective(JSONObject effective) throws IOException {
        try {
            GuardConfig parsed = new GuardConfig(effective);
            effectiveJsonCache = effective;
            effectiveConfigCache = parsed;
        } catch (JSONException error) {
            throw new IOException("Invalid effective guard config", error);
        }
    }

    public synchronized void setProtectionEnabled(boolean enabled) throws IOException {
        JSONObject json = loadJson();
        try {
            json.put("protection_enabled", enabled);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        save(json);
    }

    public synchronized void resetSectionToDefault(String section) throws IOException {
        JSONObject current = loadJson();
        JSONObject defaults = defaultJson();
        try {
            if (!defaults.has(section)) throw new JSONException("Unknown default section");
            current.put(section, new JSONObject().put("value", defaults.get(section)).get("value"));
        } catch (JSONException error) {
            throw new IOException("Cannot restore default section", error);
        }
        save(current);
    }

    public synchronized void resetPathToDefault(String path) throws IOException {
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty()) throw new IOException("配置路径不能为空");
        JSONObject current = loadJson();
        JSONObject defaults = defaultJson();
        String[] parts = normalized.split("\\.");
        try {
            Object defaultValue = valueAt(defaults, parts);
            setValueAt(current, parts, defaultValue);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("找不到默认配置项：" + normalized, error);
        }
        save(current);
    }

    public synchronized boolean hasLastGood() {
        return preferences.contains(KEY_LAST_GOOD_JSON);
    }

    public synchronized boolean applyDegradedPerformanceProfile() throws IOException {
        JSONObject json = loadJson();
        if ("DEGRADED".equals(json.optString("active_performance_profile"))) return false;
        try {
            JSONObject advice = json.getJSONObject("performance")
                    .getJSONObject("degraded_profile_advice");
            double captureMultiplier = advice.getDouble("capture_interval_multiplier");
            double ocrMultiplier = advice.getDouble("ocr_interval_multiplier");
            int evidenceDelta = advice.getInt("evidence_frames_delta");
            org.json.JSONArray platforms = json.getJSONArray("platforms");
            for (int i = 0; i < platforms.length(); i++) {
                JSONObject platform = platforms.getJSONObject(i);
                for (String kind : new String[] {"short_video", "live", "unknown"}) {
                    JSONObject media = platform.getJSONObject(kind);
                    media.put("capture_interval_ms", clampLong(Math.round(
                            media.getLong("capture_interval_ms") * captureMultiplier),
                            500L, 30000L));
                    media.put("ocr_interval_ms", clampLong(Math.round(
                            media.getLong("ocr_interval_ms") * ocrMultiplier),
                            500L, 60000L));
                    media.put("evidence_frames", clampLong(
                            media.getInt("evidence_frames") + evidenceDelta, 1L, 20L));
                }
            }
            json.put("active_performance_profile", "DEGRADED");
        } catch (JSONException error) {
            throw new IOException("Cannot apply degraded performance profile", error);
        }
        save(json);
        return true;
    }

    public synchronized boolean restoreLastGood() throws IOException {
        String previous = preferences.getString(KEY_LAST_GOOD_JSON, null);
        if (previous == null) return false;
        try {
            JSONObject migrated = validateAndMigrate(new JSONObject(previous));
            String current = preferences.getString(KEY_USER_JSON, null);
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(KEY_USER_JSON, migrated.toString());
            if (current != null) editor.putString(KEY_LAST_GOOD_JSON, current);
            editor.apply();
            adoptEffective(migrated);
            return true;
        } catch (JSONException error) {
            throw new IOException("Previous config is invalid", error);
        }
    }

    public synchronized void resetToDefaults() {
        String current = preferences.getString(KEY_USER_JSON, null);
        SharedPreferences.Editor editor = preferences.edit().remove(KEY_USER_JSON);
        if (current != null) editor.putString(KEY_LAST_GOOD_JSON, current);
        editor.apply();
        // Removing the effective user config means the next read must fall back to
        // the bundled defaults; drop the cached effective value so it is rebuilt.
        clearEffectiveCache();
    }

    private void clearEffectiveCache() {
        effectiveJsonCache = null;
        effectiveConfigCache = null;
    }

    public synchronized JSONObject defaultJson() throws IOException {
        JSONObject cached = defaultsCache;
        if (cached != null) return cached;
        try {
            JSONObject parsed = new JSONObject(readAssetCached());
            defaultsCache = parsed;
            return parsed;
        } catch (JSONException error) {
            throw new IOException("Invalid bundled guard config", error);
        }
    }

    /**
     * Thread the bundled asset once and reuse it. The bundled defaults never
     * change at runtime, so caching the string makes every later
     * loadJson()/defaultJson() skip asset I/O entirely.
     */
    private String readAssetCached() throws IOException {
        String cached = assetTextCache;
        if (cached != null) return cached;
        String text = readAsset();
        assetTextCache = text;
        return text;
    }

    private JSONObject loadLastGood(JSONObject defaults) {
        String previous = preferences.getString(KEY_LAST_GOOD_JSON, null);
        if (previous == null) return null;
        try {
            JSONObject migrated = ConfigMigrator.migrate(new JSONObject(previous), defaults);
            new GuardConfig(migrated);
            return migrated;
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static Object valueAt(Object root, String[] parts) throws JSONException {
        Object current = root;
        for (String part : parts) {
            if (current instanceof JSONObject) current = ((JSONObject) current).get(part);
            else if (current instanceof org.json.JSONArray) {
                current = ((org.json.JSONArray) current).get(Integer.parseInt(part));
            } else throw new JSONException("Path crosses a scalar value");
        }
        return deepCopy(current);
    }

    private static void setValueAt(Object root, String[] parts, Object value)
            throws JSONException {
        Object current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (current instanceof JSONObject) current = ((JSONObject) current).get(part);
            else if (current instanceof org.json.JSONArray) {
                current = ((org.json.JSONArray) current).get(Integer.parseInt(part));
            } else throw new JSONException("Path crosses a scalar value");
        }
        String leaf = parts[parts.length - 1];
        if (current instanceof JSONObject) ((JSONObject) current).put(leaf, value);
        else if (current instanceof org.json.JSONArray) {
            ((org.json.JSONArray) current).put(Integer.parseInt(leaf), value);
        } else throw new JSONException("Path ends at a scalar value");
    }

    private static Object deepCopy(Object value) throws JSONException {
        if (value instanceof JSONObject) return new JSONObject(value.toString());
        if (value instanceof org.json.JSONArray) return new org.json.JSONArray(value.toString());
        return value;
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private String readAsset() throws IOException {
        StringBuilder text = new StringBuilder();
        BundleValidator.ResourceBundle bundle = BundleValidator.active(context);
        try (InputStream input = bundle.open(ASSET_PATH);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }
}
