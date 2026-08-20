package com.gravekeeper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gravekeeper.inference.InferenceResult;

import org.junit.Test;

public final class ContentReleaseGateTest {
    @Test public void whitelistHoldsUntilTargetScroll() {
        ContentReleaseGate gate = enabledGate();

        assertTrue(gate.holdFor(InferenceResult.ReleaseCause.WHITELIST));
        assertFalse(gate.shouldAnalyze());
        assertTrue(gate.onTargetScroll());
        assertTrue(gate.shouldAnalyze());
    }

    @Test public void disabledMediaPolicyCanHold() {
        ContentReleaseGate gate = enabledGate();

        assertTrue(gate.holdFor(InferenceResult.ReleaseCause.DISABLED_MEDIA_POLICY));
        assertFalse(gate.shouldAnalyze());
    }

    @Test public void ordinaryLowRiskResultDoesNotHold() {
        ContentReleaseGate gate = enabledGate();

        assertFalse(gate.holdFor(InferenceResult.ReleaseCause.NONE));
        assertTrue(gate.shouldAnalyze());
    }

    @Test public void unrelatedEventsDoNotWakeGate() {
        ContentReleaseGate gate = enabledGate();
        gate.holdFor(InferenceResult.ReleaseCause.WHITELIST);

        assertFalse(gate.shouldAnalyze());
    }

    @Test public void contextChangeAlwaysWakesGate() {
        ContentReleaseGate gate = enabledGate();
        gate.holdFor(InferenceResult.ReleaseCause.WHITELIST);

        gate.resetForContextChange();

        assertTrue(gate.shouldAnalyze());
    }

    @Test public void disabledGateNeverHolds() {
        ContentReleaseGate gate = new ContentReleaseGate(false, true, true, true);

        assertFalse(gate.holdFor(InferenceResult.ReleaseCause.WHITELIST));
        assertTrue(gate.shouldAnalyze());
    }

    @Test public void wakeOnScrollCanBeCustomized() {
        ContentReleaseGate gate = new ContentReleaseGate(true, true, true, false);
        gate.holdFor(InferenceResult.ReleaseCause.WHITELIST);

        assertFalse(gate.onTargetScroll());
        assertFalse(gate.shouldAnalyze());
    }

    @Test public void eachHoldCauseCanBeCustomized() {
        ContentReleaseGate gate = new ContentReleaseGate(true, false, true, true);

        assertFalse(gate.holdFor(InferenceResult.ReleaseCause.WHITELIST));
        assertTrue(gate.holdFor(
                InferenceResult.ReleaseCause.DISABLED_MEDIA_POLICY));
    }

    @Test public void holdingStateReflectsWhetherWakeIsNeeded() {
        ContentReleaseGate gate = enabledGate();

        assertFalse(gate.isHolding());
        gate.holdFor(InferenceResult.ReleaseCause.WHITELIST);
        assertTrue(gate.isHolding());
        gate.onTargetScroll();
        assertFalse(gate.isHolding());
    }

    private static ContentReleaseGate enabledGate() {
        return new ContentReleaseGate(true, true, true, true);
    }
}
