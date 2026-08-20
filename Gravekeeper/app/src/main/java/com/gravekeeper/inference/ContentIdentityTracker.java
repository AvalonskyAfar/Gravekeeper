package com.gravekeeper.inference;

/** Confirms abrupt visual changes across frames and treats reliable account changes as boundaries. */
public final class ContentIdentityTracker {
    private final double changeThreshold;
    private final double candidateSimilarityThreshold;
    private final int confirmationFrames;
    private final long minimumResetIntervalMs;
    private ContentFingerprint baseline;
    private ContentFingerprint candidate;
    private int candidateFrames;
    private long lastResetAt;
    private String accountId = "";

    public ContentIdentityTracker(double changeThreshold, double candidateSimilarityThreshold,
            int confirmationFrames, long minimumResetIntervalMs) {
        this.changeThreshold = changeThreshold;
        this.candidateSimilarityThreshold = candidateSimilarityThreshold;
        this.confirmationFrames = confirmationFrames;
        this.minimumResetIntervalMs = minimumResetIntervalMs;
    }

    public synchronized boolean observeVisual(ContentFingerprint fingerprint, long nowMs) {
        if (fingerprint == null) return false;
        if (baseline == null) {
            baseline = fingerprint;
            return false;
        }
        if (baseline.distance(fingerprint) < changeThreshold) {
            baseline = fingerprint;
            candidate = null;
            candidateFrames = 0;
            return false;
        }
        if (candidate == null || candidate.distance(fingerprint) > candidateSimilarityThreshold) {
            candidate = fingerprint;
            candidateFrames = 1;
        } else {
            candidate = fingerprint;
            candidateFrames++;
        }
        if (candidateFrames < confirmationFrames
                || (lastResetAt != 0 && nowMs - lastResetAt < minimumResetIntervalMs)) {
            return false;
        }
        baseline = fingerprint;
        candidate = null;
        candidateFrames = 0;
        lastResetAt = nowMs;
        accountId = "";
        return true;
    }

    public synchronized boolean observeAccount(String reliableAccountId, long nowMs) {
        String value = reliableAccountId == null ? "" : reliableAccountId;
        if (value.isEmpty()) return false;
        if (accountId.isEmpty()) {
            accountId = value;
            return false;
        }
        if (accountId.equals(value)) return false;
        accountId = value;
        lastResetAt = nowMs;
        candidate = null;
        candidateFrames = 0;
        return true;
    }

    public synchronized void reset() {
        baseline = null;
        candidate = null;
        candidateFrames = 0;
        lastResetAt = 0L;
        accountId = "";
    }
}
