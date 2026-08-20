package com.gravekeeper.inference;

import com.gravekeeper.config.GuardConfig;

/** Centralizes the safety gate for automatic page-turn gestures. */
public final class SwipeEligibility {
    private SwipeEligibility() {}

    public static boolean canAutomaticallySwipe(GuardConfig config,
            GuardConfig.MediaKind mediaKind) {
        if (config == null) return false;
        if (!config.swipeRequiresKnownMedia) return true;
        return mediaKind == GuardConfig.MediaKind.SHORT_VIDEO
                || mediaKind == GuardConfig.MediaKind.LIVE;
    }
}
