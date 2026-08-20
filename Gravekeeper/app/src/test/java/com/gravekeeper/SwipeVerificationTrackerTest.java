package com.gravekeeper;

import static org.junit.Assert.assertEquals;

import com.gravekeeper.inference.ContentFingerprint;

import org.junit.Test;

public final class SwipeVerificationTrackerTest {
    private static ContentFingerprint fingerprint(int color) {
        int[] pixels = new int[96];
        java.util.Arrays.fill(pixels, color);
        return ContentFingerprint.fromSampledArgb(pixels);
    }

    @Test public void timeoutAllowsOnlyOneRetryThenFails() {
        SwipeVerificationTracker tracker = new SwipeVerificationTracker(1000, 0.20, 1);
        ContentFingerprint same = fingerprint(0xff303030);
        tracker.start(same, 1000);
        assertEquals(SwipeVerificationTracker.Outcome.WAITING,
                tracker.observe(same, false, 1500));
        assertEquals(SwipeVerificationTracker.Outcome.RETRY,
                tracker.observe(same, false, 2001));
        assertEquals(SwipeVerificationTracker.Outcome.WAITING,
                tracker.observe(same, false, 2002));
        tracker.retryDispatched(2100);
        assertEquals(SwipeVerificationTracker.Outcome.FAILED,
                tracker.observe(same, false, 3101));
    }

    @Test public void visualChangeOrBoundaryConfirmsTheSwipe() {
        SwipeVerificationTracker tracker = new SwipeVerificationTracker(1000, 0.20, 1);
        ContentFingerprint before = fingerprint(0xff202020);
        ContentFingerprint after = fingerprint(0xffe0e0e0);
        tracker.start(before, 1000);
        assertEquals(SwipeVerificationTracker.Outcome.WAITING,
                tracker.observe(after, false, 1100));
        assertEquals(SwipeVerificationTracker.Outcome.SUCCESS,
                tracker.observe(after, false, 1200));
        tracker.start(before, 2000);
        assertEquals(SwipeVerificationTracker.Outcome.SUCCESS,
                tracker.observe(before, true, 2100));
    }
}
