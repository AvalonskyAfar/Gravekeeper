package com.gravekeeper.inference;

import java.util.ArrayDeque;
import java.util.Deque;

import com.gravekeeper.config.GuardConfig;

public final class RollingEvidence {
    public static final class Decision {
        public final double currentScore;
        public final double windowScore;
        public final boolean positive;
        public final int frameCount;

        Decision(double currentScore, double windowScore, boolean positive, int frameCount) {
            this.currentScore = currentScore;
            this.windowScore = windowScore;
            this.positive = positive;
            this.frameCount = frameCount;
        }
    }

    private static final int DEFAULT_CAPACITY = 4;
    private static final long DEFAULT_RESET_GAP_MS = 8000L;
    private final Deque<Double> scores = new ArrayDeque<>();
    private long lastFrameAt;

    public synchronized Decision add(double score, long timestampMs, double threshold) {
        return add(score, timestampMs, threshold, DEFAULT_CAPACITY, DEFAULT_RESET_GAP_MS);
    }

    public synchronized Decision add(double score, long timestampMs, double threshold,
            int capacity, long resetGapMs) {
        return add(score, timestampMs, threshold, capacity, resetGapMs,
                GuardConfig.EvidenceAggregation.MAX);
    }

    public synchronized Decision add(double score, long timestampMs, double threshold,
            int capacity, long resetGapMs, GuardConfig.EvidenceAggregation aggregation) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        if (resetGapMs < 1) throw new IllegalArgumentException("resetGapMs must be positive");
        if (lastFrameAt != 0 && timestampMs - lastFrameAt > resetGapMs) scores.clear();
        lastFrameAt = timestampMs;
        scores.addLast(score);
        while (scores.size() > capacity) scores.removeFirst();

        double windowScore;
        if (aggregation == GuardConfig.EvidenceAggregation.LATEST) {
            windowScore = score;
        } else if (aggregation == GuardConfig.EvidenceAggregation.AVERAGE) {
            double total = 0.0;
            for (double value : scores) total += value;
            windowScore = total / scores.size();
        } else {
            windowScore = 0.0;
            for (double value : scores) windowScore = Math.max(windowScore, value);
        }
        return new Decision(score, windowScore,
                windowScore >= threshold, scores.size());
    }

    public synchronized void reset() {
        scores.clear();
        lastFrameAt = 0L;
    }
}
