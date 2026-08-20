package com.gravekeeper.performance;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PerformanceProbeTest {
    private static PerformanceProbe.Level classify(long visual, long ocr,
            long memory, int thermal) {
        return PerformanceProbe.classify(visual, ocr, memory, thermal,
                900, 1800, 520, 1400, 3200, 760, 4);
    }

    @Test public void recommendedStateRequiresEveryRecommendedLimit() {
        assertEquals(PerformanceProbe.Level.RECOMMENDED,
                classify(900, 1800, 520, 3));
    }

    @Test public void degradedStateCoversValuesAboveRecommendedLimits() {
        assertEquals(PerformanceProbe.Level.DEGRADED,
                classify(901, 1801, 521, 3));
    }

    @Test public void highRiskStateCoversLimitsAndSevereThermalStatus() {
        assertEquals(PerformanceProbe.Level.HIGH_RISK,
                classify(1401, 3200, 760, 3));
        assertEquals(PerformanceProbe.Level.HIGH_RISK,
                classify(100, 100, 100, 4));
    }

    @Test public void unknownStateRemainsReservedForProbeFailure() {
        assertEquals(PerformanceProbe.Level.UNKNOWN,
                PerformanceProbe.Level.valueOf("UNKNOWN"));
    }
}
