package com.gravekeeper.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

final class ConfigMigrator {
    private ConfigMigrator() {}

    static JSONObject migrate(JSONObject user, JSONObject defaults) throws JSONException {
        if (!"gravekeeper_runtime_config".equals(
                user.optString("format", ""))) {
            throw new JSONException("Unsupported config format");
        }
        JSONObject merged = copy(defaults);
        mergeObject(merged, user, false);
        if (user.optInt("version", 1) < 6) {
            // The old overlay was a temporary test-build default. Do not keep it
            // permanently enabled when upgrading into developer options.
            merged.getJSONObject("status_overlay").put("enabled", false);
            merged.getJSONObject("status_overlay").put("show_outside_targets", false);
        }
        if (user.has("platforms")) {
            merged.put("platforms", mergePlatforms(
                    defaults.getJSONArray("platforms"), user.getJSONArray("platforms"),
                    user.optInt("version", 1)));
        }
        if (user.optInt("version", 1) < 7) {
            // v7 establishes that automatic whitelist release requires an
            // explicit OCR account-ID label. Older experimental candidates
            // based on @ handles or live-header nicknames must not survive.
            JSONArray platforms = merged.getJSONArray("platforms");
            for (int index = 0; index < platforms.length(); index++) {
                JSONObject account = platforms.getJSONObject(index)
                        .getJSONObject("account_detection");
                account.put("explicit_prefix_anywhere", false);
                account.put("allow_at_handle", false);
                account.put("allow_follow_anchored_header", false);
                account.put("id_allowed_regex",
                        "(?i)[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*");
            }
        }
        merged.put("format", defaults.getString("format"));
        merged.put("version", defaults.getInt("version"));
        return merged;
    }

    private static JSONArray mergePlatforms(JSONArray defaults, JSONArray users,
            int sourceVersion)
            throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.getJSONObject(i);
            JSONObject base = findPlatform(defaults, user.optString("id", ""));
            if (base == null) base = copy(defaults.getJSONObject(0));
            JSONObject merged = copy(base);
            mergeObject(merged, user, true);
            if (sourceVersion < 3 && user.has("packages")) {
                JSONArray upgradedPackages = new JSONArray();
                java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
                JSONArray defaultPackages = base.getJSONArray("packages");
                JSONArray userPackages = user.getJSONArray("packages");
                for (int j = 0; j < defaultPackages.length(); j++) {
                    packages.add(defaultPackages.getString(j));
                }
                for (int j = 0; j < userPackages.length(); j++) {
                    packages.add(userPackages.getString(j));
                }
                for (String packageName : packages) upgradedPackages.put(packageName);
                merged.put("packages", upgradedPackages);
            }
            for (String key : new String[] {"short_video", "live", "unknown"}) {
                JSONObject defaultPolicy = base.getJSONObject(key);
                JSONObject userPolicy = user.optJSONObject(key);
                if (userPolicy != null) {
                    merged.put(key, mergeMediaPolicy(defaultPolicy, userPolicy));
                }
            }
            result.put(merged);
        }
        return result.length() == 0 ? new JSONArray(defaults.toString()) : result;
    }

    private static JSONObject mergeMediaPolicy(JSONObject defaults, JSONObject user)
            throws JSONException {
        JSONObject result = copy(defaults);
        mergeObject(result, user, false);
        if (user.has("action") && user.has("threshold")) {
            String action = user.getString("action");
            double threshold = user.getDouble("threshold");
            for (String band : new String[] {"low", "medium", "high"}) {
                result.put(band, new JSONObject()
                        .put("threshold", threshold)
                        .put("action", action));
            }
            result.remove("action");
            result.remove("threshold");
        }
        return result;
    }

    private static JSONObject findPlatform(JSONArray platforms, String id)
            throws JSONException {
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            if (id.equals(platform.optString("id"))) return platform;
        }
        return null;
    }

    private static void mergeObject(JSONObject destination, JSONObject source,
            boolean skipMediaPolicies) throws JSONException {
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("version".equals(key) || "format".equals(key)) continue;
            if (skipMediaPolicies && ("short_video".equals(key)
                    || "live".equals(key) || "unknown".equals(key))) continue;
            Object incoming = source.get(key);
            Object existing = destination.opt(key);
            if (incoming instanceof JSONObject && existing instanceof JSONObject) {
                JSONObject nested = copy((JSONObject) existing);
                mergeObject(nested, (JSONObject) incoming, false);
                destination.put(key, nested);
            } else {
                destination.put(key, deepCopy(incoming));
            }
        }
    }

    private static Object deepCopy(Object value) throws JSONException {
        if (value instanceof JSONObject) return copy((JSONObject) value);
        if (value instanceof JSONArray) return new JSONArray(value.toString());
        return value;
    }

    private static JSONObject copy(JSONObject value) throws JSONException {
        return new JSONObject(value.toString());
    }
}
