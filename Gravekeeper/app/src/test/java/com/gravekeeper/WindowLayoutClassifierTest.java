package com.gravekeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class WindowLayoutClassifierTest {
    private static final String TARGET = "com.ss.android.ugc.aweme";
    private static final String PEER = "com.example.reader";

    private static WindowLayoutClassifier classifier() {
        return new WindowLayoutClassifier(new WindowLayoutClassifier.Config(
                true, true, false, false, false, true,
                0.90, 0.82, 0.88, 0.25, 0.75, 0.03, 0.04));
    }

    private static WindowLayoutClassifier.WindowRecord record(String packageName,
            int left, int top, int right, int bottom, boolean active, boolean focused) {
        return new WindowLayoutClassifier.WindowRecord(packageName,
                new WindowLayoutClassifier.Bounds(left, top, right, bottom),
                active, focused, false);
    }

    @Test public void fullscreenWindowIsAllowed() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 80, 1080, 2320, true, true)));
        assertEquals(WindowLayoutClassifier.Mode.FULLSCREEN, result.mode);
        assertTrue(result.detectionAllowed);
    }

    @Test public void upperSplitWindowIsLocated() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 80, 1080, 1180, true, true),
                        record(PEER, 0, 1220, 1080, 2320, false, false)));
        assertEquals(WindowLayoutClassifier.Mode.SPLIT_TOP, result.mode);
        assertTrue(result.detectionAllowed);
    }

    @Test public void lowerSplitWindowIsLocated() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(PEER, 0, 80, 1080, 1170, false, false),
                        record(TARGET, 0, 1210, 1080, 2320, true, true)));
        assertEquals(WindowLayoutClassifier.Mode.SPLIT_BOTTOM, result.mode);
    }

    @Test public void leftSplitWindowIsLocated() {
        WindowLayoutClassifier.Result result = classifier().classify(2400, 1080, TARGET,
                List.of(record(TARGET, 0, 0, 1170, 1080, true, true),
                        record(PEER, 1220, 0, 2400, 1080, false, false)));
        assertEquals(WindowLayoutClassifier.Mode.SPLIT_LEFT, result.mode);
    }

    @Test public void rightSplitWindowIsLocated() {
        WindowLayoutClassifier.Result result = classifier().classify(2400, 1080, TARGET,
                List.of(record(PEER, 0, 0, 1170, 1080, false, false),
                        record(TARGET, 1220, 0, 2400, 1080, true, true)));
        assertEquals(WindowLayoutClassifier.Mode.SPLIT_RIGHT, result.mode);
    }

    @Test public void pictureInPictureDefaultsToStopped() {
        WindowLayoutClassifier.WindowRecord pip = new WindowLayoutClassifier.WindowRecord(
                TARGET, new WindowLayoutClassifier.Bounds(700, 1500, 1050, 2100),
                true, true, true);
        WindowLayoutClassifier.Result result = classifier().classify(
                1080, 2400, TARGET, List.of(pip));
        assertEquals(WindowLayoutClassifier.Mode.PICTURE_IN_PICTURE, result.mode);
        assertFalse(result.detectionAllowed);
    }

    @Test public void centeredFloatingWindowDefaultsToStopped() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 170, 400, 910, 1800, true, true)));
        assertEquals(WindowLayoutClassifier.Mode.FLOATING_OR_UNKNOWN, result.mode);
        assertFalse(result.detectionAllowed);
    }

    @Test public void overlappingFreeformWindowsCannotMasqueradeAsSplitScreen() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 180, 1080, 1320, true, true),
                        record(PEER, 0, 300, 1080, 1440, false, false)));
        assertEquals(WindowLayoutClassifier.Mode.FLOATING_OR_UNKNOWN, result.mode);
        assertFalse(result.detectionAllowed);
    }

    @Test public void visibleButUnfocusedTargetDefaultsToStopped() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 80, 1080, 1180, false, false),
                        record(PEER, 0, 1220, 1080, 2320, true, true)));
        assertEquals(WindowLayoutClassifier.Mode.NOT_FOCUSED, result.mode);
        assertFalse(result.detectionAllowed);
    }

    @Test public void invalidOrMissingBoundsFailClosed() {
        WindowLayoutClassifier.Result result = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, -20, -30, -1, -1, true, true)));
        assertEquals(WindowLayoutClassifier.Mode.UNAVAILABLE, result.mode);
        assertFalse(result.detectionAllowed);
    }

    @Test public void meaningfulBoundsChangeBreaksContext() {
        WindowLayoutClassifier.Result first = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 80, 1080, 1180, true, true),
                        record(PEER, 0, 1220, 1080, 2320, false, false)));
        WindowLayoutClassifier.Result moved = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 80, 1080, 1000, true, true),
                        record(PEER, 0, 1040, 1080, 2320, false, false)));
        assertFalse(first.sameContext(moved, 1080, 2400, 0.03));
    }

    @Test public void tinyBoundsJitterKeepsContext() {
        WindowLayoutClassifier.Result first = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 80, 1080, 1180, true, true),
                        record(PEER, 0, 1220, 1080, 2320, false, false)));
        WindowLayoutClassifier.Result jitter = classifier().classify(1080, 2400, TARGET,
                List.of(record(TARGET, 0, 84, 1080, 1184, true, true),
                        record(PEER, 0, 1224, 1080, 2320, false, false)));
        assertTrue(first.sameContext(jitter, 1080, 2400, 0.03));
    }

    @Test public void gestureCoordinatesStayInsideUpperAndLowerSplitWindows() {
        WindowLayoutClassifier.Bounds upper =
                new WindowLayoutClassifier.Bounds(0, 80, 1080, 1180);
        WindowLayoutClassifier.Bounds lower =
                new WindowLayoutClassifier.Bounds(0, 1210, 1080, 2320);
        assertEquals(540.0, upper.xAt(0.50), 0.01);
        assertEquals(905.0, upper.yAt(0.75), 0.01);
        assertEquals(355.0, upper.yAt(0.25), 0.01);
        assertEquals(2042.5, lower.yAt(0.75), 0.01);
        assertEquals(1487.5, lower.yAt(0.25), 0.01);
        assertTrue(upper.yAt(0.75) < upper.bottom);
        assertTrue(lower.yAt(0.25) > lower.top);
    }
}
