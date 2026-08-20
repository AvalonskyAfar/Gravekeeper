package com.gravekeeper;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps a short-lived, fail-closed view of the foreground application.
 *
 * Android accessibility roots can temporarily be null while activities and
 * surfaces are switching. A target-app event is therefore retained briefly,
 * but any confident application-window observation replaces it immediately.
 */
public final class ForegroundAppTracker {
    public static final class Resolution {
        public final String packageName;
        public final String source;

        Resolution(String packageName, String source) {
            this.packageName = packageName == null ? "" : packageName;
            this.source = source;
        }

        public boolean isKnown() {
            return !packageName.isEmpty();
        }
    }

    private String lastTargetPackage = "";
    private long lastTargetAt;
    private long lastDecisiveAt;

    public synchronized void observeTargetEvent(
            String packageName, Set<String> targetPackages, long nowMs) {
        String normalized = normalize(packageName);
        if (targetPackages.contains(normalized)) {
            remember(normalized, nowMs);
        }
    }

    public synchronized void clear() {
        lastTargetPackage = "";
        lastTargetAt = 0L;
        lastDecisiveAt = 0L;
    }

    private void expireTargetEvidence() {
        lastTargetPackage = "";
        lastTargetAt = 0L;
    }

    public synchronized void observeOtherApp(long nowMs) {
        lastTargetPackage = "";
        lastTargetAt = 0L;
        lastDecisiveAt = nowMs;
    }

    public synchronized Resolution resolve(
            String activeRootPackage,
            List<String> activeApplicationWindowPackages,
            String usageStatsPackage,
            long usageStatsTimestampMs,
            Set<String> targetPackages,
            Set<String> ignoredOverlayPackages,
            long nowMs,
            long evidenceTtlMs) {
        return resolve(activeRootPackage, activeApplicationWindowPackages,
                usageStatsPackage, usageStatsTimestampMs, targetPackages,
                ignoredOverlayPackages, nowMs, evidenceTtlMs, evidenceTtlMs);
    }

    public synchronized Resolution resolve(
            String activeRootPackage,
            List<String> activeApplicationWindowPackages,
            String usageStatsPackage,
            long usageStatsTimestampMs,
            Set<String> targetPackages,
            Set<String> ignoredOverlayPackages,
            long nowMs,
            long evidenceTtlMs,
            long usageEventMaxAgeMs) {
        Set<String> ignored = ignoredOverlayPackages == null
                ? Collections.emptySet() : ignoredOverlayPackages;
        List<String> windows = activeApplicationWindowPackages == null
                ? Collections.emptyList() : activeApplicationWindowPackages;

        for (String candidate : windows) {
            String normalized = normalize(candidate);
            if (targetPackages.contains(normalized)) {
                remember(normalized, nowMs);
                return new Resolution(normalized, "active_application_window");
            }
        }

        String root = normalize(activeRootPackage);
        if (targetPackages.contains(root)) {
            remember(root, nowMs);
            return new Resolution(root, "active_root");
        }

        for (String candidate : windows) {
            String normalized = normalize(candidate);
            if (!normalized.isEmpty() && !ignored.contains(normalized)) {
                observeOtherApp(nowMs);
                return new Resolution("", "other_application_window:" + normalized);
            }
        }

        if (!root.isEmpty() && !ignored.contains(root)) {
            observeOtherApp(nowMs);
            return new Resolution("", "other_active_root:" + root);
        }

        String usage = normalize(usageStatsPackage);
        boolean usageIsFresh = usageStatsTimestampMs > 0L
                && nowMs - usageStatsTimestampMs >= 0L
                && nowMs - usageStatsTimestampMs <= usageEventMaxAgeMs;
        if (!usage.isEmpty() && usageIsFresh && usageStatsTimestampMs >= lastDecisiveAt
                && targetPackages.contains(usage)) {
            remember(usage, usageStatsTimestampMs);
            return new Resolution(usage, "usage_stats");
        }
        if (!usage.isEmpty() && usageIsFresh && usageStatsTimestampMs >= lastDecisiveAt
                && !ignored.contains(usage)) {
            lastTargetPackage = "";
            lastTargetAt = 0L;
            lastDecisiveAt = usageStatsTimestampMs;
            return new Resolution("", "other_usage_app:" + usage);
        }

        if (!lastTargetPackage.isEmpty()
                && nowMs - lastTargetAt >= 0
                && nowMs - lastTargetAt <= evidenceTtlMs) {
            return new Resolution(lastTargetPackage, "recent_target_event");
        }
        expireTargetEvidence();
        return new Resolution("", "unknown");
    }

    private void remember(String packageName, long nowMs) {
        lastTargetPackage = packageName;
        lastTargetAt = nowMs;
        lastDecisiveAt = nowMs;
    }

    public static Set<String> targetPackages(Iterable<String> packages) {
        Set<String> result = new HashSet<>();
        if (packages != null) {
            for (String value : packages) {
                String normalized = normalize(value);
                if (!normalized.isEmpty()) result.add(normalized);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
