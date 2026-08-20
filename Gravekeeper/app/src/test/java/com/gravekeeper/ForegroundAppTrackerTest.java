package com.gravekeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class ForegroundAppTrackerTest {
    private static final Set<String> TARGETS = Set.of(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live",
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
            "com.kwai.thanos");
    private static final Set<String> IGNORED = Set.of("com.android.systemui");

    @Test public void recentTargetEventBridgesTemporaryNullRoot() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.ss.android.ugc.aweme", TARGETS, 1000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "", 0L, TARGETS, IGNORED, 3500L, 5000L);
        assertEquals("com.ss.android.ugc.aweme", resolution.packageName);
        assertEquals("recent_target_event", resolution.source);
    }

    @Test public void activeVariantWindowIsAcceptedImmediately() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of("com.ss.android.ugc.live"), "", 0L, TARGETS, IGNORED, 1000L, 5000L);
        assertEquals("com.ss.android.ugc.live", resolution.packageName);
        assertEquals("active_application_window", resolution.source);
    }

    @Test public void kuaishouConceptVariantIsAcceptedImmediately() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "com.kwai.thanos", List.of(), "", 0L,
                TARGETS, IGNORED, 1000L, 5000L);
        assertEquals("com.kwai.thanos", resolution.packageName);
        assertEquals("active_root", resolution.source);
    }

    @Test public void confidentOtherAppClearsRecentTarget() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.kuaishou.nebula", TARGETS, 1000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "com.example.reader", List.of("com.example.reader"), "", 0L,
                TARGETS, IGNORED, 1200L, 5000L);
        assertFalse(resolution.isKnown());
        ForegroundAppTracker.Resolution later = tracker.resolve(
                "", List.of(), "", 0L, TARGETS, IGNORED, 1300L, 5000L);
        assertFalse(later.isKnown());
    }

    @Test public void ignoredSystemOverlayDoesNotDisplaceRecentTarget() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.smile.gifmaker", TARGETS, 1000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "com.android.systemui", List.of(), "", 0L, TARGETS, IGNORED, 1500L, 5000L);
        assertEquals("com.smile.gifmaker", resolution.packageName);
    }

    @Test public void ownAccessibilityOverlayDoesNotDisplaceRecentTarget() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.ss.android.ugc.aweme", TARGETS, 1000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "", 0L,
                TARGETS, IGNORED, 1500L, 5000L);
        assertEquals("com.ss.android.ugc.aweme", resolution.packageName);
        assertEquals("recent_target_event", resolution.source);
    }

    @Test public void staleEventFailsClosed() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.ss.android.ugc.aweme.lite", TARGETS, 1000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "", 0L, TARGETS, IGNORED, 7001L, 5000L);
        assertFalse(resolution.isKnown());
    }

    @Test public void usageStatsFillsAccessibilityBlindSpot() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "com.kuaishou.nebula", 1000L,
                TARGETS, IGNORED, 1000L, 5000L);
        assertEquals("com.kuaishou.nebula", resolution.packageName);
        assertEquals("usage_stats", resolution.source);
    }

    @Test public void staleUsageEventCannotOverrideNewTargetEvent() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.ss.android.ugc.aweme", TARGETS, 2000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "com.example.reader", 1500L,
                TARGETS, IGNORED, 2100L, 5000L);
        assertEquals("com.ss.android.ugc.aweme", resolution.packageName);
        assertEquals("recent_target_event", resolution.source);
    }

    @Test public void staleUsageTargetCannotOverrideNewOtherAppEvent() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        tracker.observeTargetEvent("com.ss.android.ugc.aweme", TARGETS, 1000L);
        tracker.observeOtherApp(2000L);
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "com.ss.android.ugc.aweme", 1500L,
                TARGETS, IGNORED, 2100L, 5000L);
        assertFalse(resolution.isKnown());
        ForegroundAppTracker.Resolution repeated = tracker.resolve(
                "", List.of(), "com.ss.android.ugc.aweme", 1500L,
                TARGETS, IGNORED, 2200L, 5000L);
        assertFalse(repeated.isKnown());
    }

    @Test public void oldUsageEventCannotCreateForegroundTarget() {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        ForegroundAppTracker.Resolution resolution = tracker.resolve(
                "", List.of(), "com.ss.android.ugc.aweme", 1000L,
                TARGETS, IGNORED, 20000L, 43200000L, 15000L);
        assertFalse(resolution.isKnown());
        assertEquals("unknown", resolution.source);
    }
}
