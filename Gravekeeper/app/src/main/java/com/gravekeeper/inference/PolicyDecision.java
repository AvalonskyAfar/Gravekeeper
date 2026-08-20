package com.gravekeeper.inference;

import com.gravekeeper.config.GuardConfig;

public final class PolicyDecision {
    public final double adjustedScore;
    public final double threshold;
    public final boolean positive;
    public final boolean whitelisted;
    public final GuardConfig.Action action;
    public final GuardConfig.MediaKind mediaKind;
    public final String accountId;
    public final String reasonSummary;

    PolicyDecision(double adjustedScore, double threshold, boolean positive,
            boolean whitelisted, GuardConfig.Action action,
            GuardConfig.MediaKind mediaKind, String accountId, String reasonSummary) {
        this.adjustedScore = adjustedScore;
        this.threshold = threshold;
        this.positive = positive;
        this.whitelisted = whitelisted;
        this.action = action;
        this.mediaKind = mediaKind;
        this.accountId = accountId;
        this.reasonSummary = reasonSummary == null ? "" : reasonSummary;
    }

    public String reasonSummary() { return reasonSummary; }
}
