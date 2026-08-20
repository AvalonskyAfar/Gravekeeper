package com.gravekeeper;

import com.gravekeeper.inference.ContentFingerprint;

/** State machine proving that an accepted gesture actually changed the visible content. */
final class SwipeVerificationTracker {
    enum Outcome { INACTIVE, WAITING, SUCCESS, RETRY, FAILED }

    private final long timeoutMs;
    private final double changeThreshold;
    private final double candidateSimilarityThreshold;
    private final int confirmationFrames;
    private final int maximumRetries;
    private ContentFingerprint before;
    private ContentFingerprint candidate;
    private int candidateFrames;
    private long deadlineAt;
    private int attempts;

    SwipeVerificationTracker(long timeoutMs, double changeThreshold, int maximumRetries) {
        this(timeoutMs, changeThreshold, 0.12, 2, maximumRetries);
    }

    SwipeVerificationTracker(long timeoutMs, double changeThreshold,
            double candidateSimilarityThreshold, int confirmationFrames,
            int maximumRetries) {
        this.timeoutMs = timeoutMs;
        this.changeThreshold = changeThreshold;
        this.candidateSimilarityThreshold = candidateSimilarityThreshold;
        this.confirmationFrames = confirmationFrames;
        this.maximumRetries = maximumRetries;
    }

    synchronized void start(ContentFingerprint fingerprint, long nowMs) {
        before = fingerprint;
        candidate = null;
        candidateFrames = 0;
        deadlineAt = nowMs + timeoutMs;
        attempts = 1;
    }

    synchronized Outcome observe(ContentFingerprint current, boolean boundary, long nowMs) {
        if (before == null) return Outcome.INACTIVE;
        if (boundary) {
            clear();
            return Outcome.SUCCESS;
        }
        if (current != null && before.distance(current) >= changeThreshold) {
            if (candidate == null
                    || candidate.distance(current) > candidateSimilarityThreshold) {
                candidate = current;
                candidateFrames = 1;
            } else {
                candidate = current;
                candidateFrames++;
            }
            if (candidateFrames >= confirmationFrames) {
                clear();
                return Outcome.SUCCESS;
            }
        } else {
            candidate = null;
            candidateFrames = 0;
        }
        if (nowMs < deadlineAt) return Outcome.WAITING;
        if (attempts <= maximumRetries) {
            // Claim the only retry atomically. Further frames wait for the
            // retry deadline instead of dispatching duplicate gestures.
            attempts++;
            candidate = null;
            candidateFrames = 0;
            deadlineAt = nowMs + timeoutMs;
            return Outcome.RETRY;
        }
        clear();
        return Outcome.FAILED;
    }

    synchronized void retryDispatched(long nowMs) {
        candidate = null;
        candidateFrames = 0;
        deadlineAt = nowMs + timeoutMs;
    }

    synchronized boolean isActive() { return before != null; }

    synchronized void clear() {
        before = null;
        candidate = null;
        candidateFrames = 0;
        deadlineAt = 0L;
        attempts = 0;
    }
}
