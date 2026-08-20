package com.gravekeeper.inference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RollingEvidenceTest {
    @Test
    public void recallFirstWindowKeepsRecentPositive() {
        RollingEvidence evidence = new RollingEvidence();
        assertFalse(evidence.add(0.2, 1000, 0.48).positive);
        assertTrue(evidence.add(0.7, 2000, 0.48).positive);
        assertTrue(evidence.add(0.1, 3000, 0.48).positive);
    }

    @Test
    public void longGapResetsTheWindow() {
        RollingEvidence evidence = new RollingEvidence();
        assertTrue(evidence.add(0.7, 1000, 0.48).positive);
        assertFalse(evidence.add(0.1, 10000, 0.48).positive);
    }
}
