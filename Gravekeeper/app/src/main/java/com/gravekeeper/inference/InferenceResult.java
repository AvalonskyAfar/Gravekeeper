package com.gravekeeper.inference;

import com.gravekeeper.config.GuardConfig;

public final class InferenceResult {
    public enum ReleaseCause { NONE, WHITELIST, DISABLED_MEDIA_POLICY }

    public final double visualScore;
    public final double fusionScore;
    public final double windowScore;
    public final boolean positive;
    public final GuardConfig.Action action;
    public final GuardConfig.MediaKind mediaKind;
    public final boolean whitelisted;
    public final String accountId;
    public final String platformId;
    public final boolean ocrAvailable;
    public final long visualLatencyMs;
    public final long totalLatencyMs;
    public final ContentFingerprint fingerprint;
    public final String reasonSummary;
    public final boolean contentChanged;
    public final ReleaseCause releaseCause;

    public InferenceResult(
            double visualScore,
            double fusionScore,
            double windowScore,
            boolean positive,
            GuardConfig.Action action,
            GuardConfig.MediaKind mediaKind,
            boolean whitelisted,
            String accountId,
            String platformId,
            boolean ocrAvailable,
            long visualLatencyMs,
            long totalLatencyMs,
            ContentFingerprint fingerprint,
            String reasonSummary) {
        this(visualScore, fusionScore, windowScore, positive, action, mediaKind,
                whitelisted, accountId, platformId, ocrAvailable, visualLatencyMs,
                totalLatencyMs, fingerprint, reasonSummary, ReleaseCause.NONE);
    }

    public InferenceResult(
            double visualScore,
            double fusionScore,
            double windowScore,
            boolean positive,
            GuardConfig.Action action,
            GuardConfig.MediaKind mediaKind,
            boolean whitelisted,
            String accountId,
            String platformId,
            boolean ocrAvailable,
            long visualLatencyMs,
            long totalLatencyMs,
            ContentFingerprint fingerprint,
            String reasonSummary,
            ReleaseCause releaseCause) {
        this.visualScore = visualScore;
        this.fusionScore = fusionScore;
        this.windowScore = windowScore;
        this.positive = positive;
        this.action = action;
        this.mediaKind = mediaKind;
        this.whitelisted = whitelisted;
        this.accountId = accountId;
        this.platformId = platformId;
        this.ocrAvailable = ocrAvailable;
        this.visualLatencyMs = visualLatencyMs;
        this.totalLatencyMs = totalLatencyMs;
        this.fingerprint = fingerprint;
        this.reasonSummary = reasonSummary == null ? "" : reasonSummary;
        this.contentChanged = false;
        this.releaseCause = releaseCause == null ? ReleaseCause.NONE : releaseCause;
    }

    private InferenceResult(String platformId, ContentFingerprint fingerprint,
            String summary, boolean contentChanged) {
        visualScore = 0.0;
        fusionScore = 0.0;
        windowScore = 0.0;
        positive = false;
        action = GuardConfig.Action.IGNORE;
        mediaKind = GuardConfig.MediaKind.UNKNOWN;
        whitelisted = false;
        accountId = "";
        this.platformId = platformId == null ? "" : platformId;
        ocrAvailable = false;
        visualLatencyMs = 0L;
        totalLatencyMs = 0L;
        this.fingerprint = fingerprint;
        reasonSummary = summary == null ? "" : summary;
        this.contentChanged = contentChanged;
        releaseCause = ReleaseCause.NONE;
    }

    public static InferenceResult contentChanged(String platformId,
            ContentFingerprint fingerprint) {
        return new InferenceResult(platformId, fingerprint, "已确认内容切换，证据已重置", true);
    }

    public static InferenceResult skipped(String platformId, String summary) {
        return new InferenceResult(platformId, null, summary, false);
    }
}
