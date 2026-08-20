package com.gravekeeper.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class ContentFingerprintTest {
    private static int[] pixels(int color) {
        int[] result = new int[96];
        Arrays.fill(result, color);
        return result;
    }

    @Test public void normalizedDistanceIsResolutionIndependentAfterSampling() {
        ContentFingerprint dark = ContentFingerprint.fromSampledArgb(pixels(0xff000000));
        ContentFingerprint bright = ContentFingerprint.fromSampledArgb(pixels(0xffffffff));
        assertEquals(0.0, dark.distance(dark), 0.0);
        assertTrue(dark.distance(bright) > 0.99);
    }

    @Test public void onlyUniformExtremesAreRejectedAsBlank() {
        assertTrue(ContentFingerprint.fromSampledArgb(pixels(0xff000000)).isNearlyBlank());
        assertTrue(ContentFingerprint.fromSampledArgb(pixels(0xffffffff)).isNearlyBlank());
        assertFalse(ContentFingerprint.fromSampledArgb(pixels(0xff606060)).isNearlyBlank());
    }
}
