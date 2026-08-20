package com.gravekeeper;

import com.gravekeeper.inference.InferenceResult;

/** Holds expensive analysis after a result that safely releases the current content. */
final class ContentReleaseGate {
    private final boolean enabled;
    private final boolean holdAfterWhitelist;
    private final boolean holdAfterDisabledMediaPolicy;
    private final boolean wakeOnTargetScroll;

    private InferenceResult.ReleaseCause heldCause = InferenceResult.ReleaseCause.NONE;

    ContentReleaseGate(boolean enabled, boolean holdAfterWhitelist,
            boolean holdAfterDisabledMediaPolicy, boolean wakeOnTargetScroll) {
        this.enabled = enabled;
        this.holdAfterWhitelist = holdAfterWhitelist;
        this.holdAfterDisabledMediaPolicy = holdAfterDisabledMediaPolicy;
        this.wakeOnTargetScroll = wakeOnTargetScroll;
    }

    synchronized boolean holdFor(InferenceResult.ReleaseCause cause) {
        if (!enabled || cause == null || cause == InferenceResult.ReleaseCause.NONE) {
            return false;
        }
        if (cause == InferenceResult.ReleaseCause.WHITELIST && !holdAfterWhitelist) {
            return false;
        }
        if (cause == InferenceResult.ReleaseCause.DISABLED_MEDIA_POLICY
                && !holdAfterDisabledMediaPolicy) {
            return false;
        }
        heldCause = cause;
        return true;
    }

    synchronized boolean onTargetScroll() {
        if (heldCause == InferenceResult.ReleaseCause.NONE || !wakeOnTargetScroll) {
            return false;
        }
        heldCause = InferenceResult.ReleaseCause.NONE;
        return true;
    }

    synchronized void resetForContextChange() {
        heldCause = InferenceResult.ReleaseCause.NONE;
    }

    synchronized boolean shouldAnalyze() {
        return heldCause == InferenceResult.ReleaseCause.NONE;
    }

    synchronized boolean isHolding() {
        return heldCause != InferenceResult.ReleaseCause.NONE;
    }

    synchronized String statusText() {
        if (heldCause == InferenceResult.ReleaseCause.WHITELIST) {
            return "白名单已放行，等待内容切换";
        }
        if (heldCause == InferenceResult.ReleaseCause.DISABLED_MEDIA_POLICY) {
            return "当前媒体策略已关闭，等待内容切换";
        }
        return "持续分析";
    }
}
