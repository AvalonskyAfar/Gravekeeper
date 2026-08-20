package com.gravekeeper;

/** Accepts only configured user-touch or trusted main-page scroll signals. */
final class ContentScrollSignalFilter {
    private final boolean enabled;
    private final boolean requiresRecentTouch;
    private final long recentTouchWindowMs;
    private final long debounceMs;
    private final boolean requiresMainPageEvidence;
    private final boolean allowMainPageWithoutTouch;

    private long lastAcceptedScrollAt = Long.MIN_VALUE;
    private long lastTouchAt = Long.MIN_VALUE;
    private boolean touchActive;

    ContentScrollSignalFilter(boolean enabled, boolean requiresRecentTouch,
            long recentTouchWindowMs, long debounceMs) {
        this(enabled, requiresRecentTouch, recentTouchWindowMs, debounceMs,
                false, false);
    }

    ContentScrollSignalFilter(boolean enabled, boolean requiresRecentTouch,
            long recentTouchWindowMs, long debounceMs,
            boolean requiresMainPageEvidence, boolean allowMainPageWithoutTouch) {
        if (recentTouchWindowMs < 0 || debounceMs < 0) {
            throw new IllegalArgumentException("scroll timing must be non-negative");
        }
        this.enabled = enabled;
        this.requiresRecentTouch = requiresRecentTouch;
        this.recentTouchWindowMs = recentTouchWindowMs;
        this.debounceMs = debounceMs;
        this.requiresMainPageEvidence = requiresMainPageEvidence;
        this.allowMainPageWithoutTouch = allowMainPageWithoutTouch;
    }

    synchronized void onTouchStart(long now, boolean targetContextActive) {
        if (!enabled || !targetContextActive || now < 0) return;
        touchActive = true;
        lastTouchAt = now;
    }

    synchronized void onTouchEnd(long now, boolean targetContextActive) {
        if (!enabled || now < 0) return;
        if (touchActive || targetContextActive) lastTouchAt = now;
        touchActive = false;
    }

    synchronized boolean accept(long now) {
        return accept(now, false);
    }

    synchronized boolean accept(long now, boolean mainPageEvidence) {
        if (!enabled || now < 0) return false;
        if (requiresMainPageEvidence && !mainPageEvidence) return false;
        if (requiresRecentTouch && !hasTouchEvidence(now)
                && !(allowMainPageWithoutTouch && mainPageEvidence)) {
            return false;
        }
        if (lastAcceptedScrollAt != Long.MIN_VALUE
                && now >= lastAcceptedScrollAt
                && now - lastAcceptedScrollAt < debounceMs) {
            return false;
        }
        lastAcceptedScrollAt = now;
        return true;
    }

    private boolean hasTouchEvidence(long now) {
        if (lastTouchAt == Long.MIN_VALUE || now < lastTouchAt) return false;
        if (touchActive) return true;
        return now - lastTouchAt <= recentTouchWindowMs;
    }

    synchronized void reset() {
        lastAcceptedScrollAt = Long.MIN_VALUE;
        lastTouchAt = Long.MIN_VALUE;
        touchActive = false;
    }
}
