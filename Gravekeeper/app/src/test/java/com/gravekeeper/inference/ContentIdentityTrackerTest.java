package com.gravekeeper.inference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class ContentIdentityTrackerTest {
    private static ContentFingerprint fingerprint(int color) {
        int[] pixels = new int[96];
        Arrays.fill(pixels, color);
        return ContentFingerprint.fromSampledArgb(pixels);
    }

    @Test public void visualBoundaryRequiresAStableChangedCandidate() {
        ContentIdentityTracker tracker = new ContentIdentityTracker(0.20, 0.10, 2, 1000);
        ContentFingerprint first = fingerprint(0xff202020);
        ContentFingerprint next = fingerprint(0xffd0d0d0);
        assertFalse(tracker.observeVisual(first, 1000));
        assertFalse(tracker.observeVisual(next, 2000));
        assertTrue(tracker.observeVisual(next, 2200));
    }

    @Test public void reliableAccountChangeIsAnImmediateBoundary() {
        ContentIdentityTracker tracker = new ContentIdentityTracker(0.20, 0.10, 2, 1000);
        assertFalse(tracker.observeAccount("account_a", 1000));
        assertFalse(tracker.observeAccount("account_a", 1100));
        assertTrue(tracker.observeAccount("account_b", 1200));
    }
}
