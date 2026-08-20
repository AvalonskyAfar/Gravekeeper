package com.gravekeeper;

/** Identifies vertical paging of the main target-app surface, excluding local lists. */
final class TargetPageScrollClassifier {
    static final class Evidence {
        final WindowLayoutClassifier.Bounds targetBounds;
        final WindowLayoutClassifier.Bounds sourceBounds;
        final int scrollDeltaX;
        final int scrollDeltaY;
        final int fromIndex;
        final int toIndex;

        Evidence(WindowLayoutClassifier.Bounds targetBounds,
                WindowLayoutClassifier.Bounds sourceBounds,
                int scrollDeltaX, int scrollDeltaY, int fromIndex, int toIndex) {
            this.targetBounds = targetBounds;
            this.sourceBounds = sourceBounds;
            this.scrollDeltaX = scrollDeltaX;
            this.scrollDeltaY = scrollDeltaY;
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }
    }

    private final double minimumSourceWidthRatio;
    private final double minimumSourceHeightRatio;
    private final double maximumHorizontalDeltaRatio;
    private final boolean allowUnknownDirection;

    TargetPageScrollClassifier(double minimumSourceWidthRatio,
            double minimumSourceHeightRatio, double maximumHorizontalDeltaRatio,
            boolean allowUnknownDirection) {
        this.minimumSourceWidthRatio = checkedRatio(minimumSourceWidthRatio);
        this.minimumSourceHeightRatio = checkedRatio(minimumSourceHeightRatio);
        this.maximumHorizontalDeltaRatio = checkedRatio(maximumHorizontalDeltaRatio);
        this.allowUnknownDirection = allowUnknownDirection;
    }

    boolean isMainPageScroll(Evidence evidence) {
        if (evidence == null || evidence.targetBounds == null
                || evidence.sourceBounds == null
                || !evidence.targetBounds.isPositive()
                || !evidence.sourceBounds.isPositive()) return false;
        WindowLayoutClassifier.Bounds target = evidence.targetBounds;
        WindowLayoutClassifier.Bounds source = evidence.sourceBounds;
        int overlapWidth = Math.max(0, Math.min(target.right, source.right)
                - Math.max(target.left, source.left));
        int overlapHeight = Math.max(0, Math.min(target.bottom, source.bottom)
                - Math.max(target.top, source.top));
        if (overlapWidth / (double) target.width() < minimumSourceWidthRatio
                || overlapHeight / (double) target.height() < minimumSourceHeightRatio) {
            return false;
        }

        long horizontal = Math.abs((long) evidence.scrollDeltaX);
        long vertical = Math.abs((long) evidence.scrollDeltaY);
        if (horizontal != 0L || vertical != 0L) {
            return vertical > 0L
                    && horizontal <= vertical * maximumHorizontalDeltaRatio;
        }
        if (evidence.fromIndex >= 0 && evidence.toIndex >= 0
                && evidence.fromIndex != evidence.toIndex) return true;
        // Some OEMs omit both deltas and indices. TYPE_VIEW_SCROLLED from a
        // source covering almost the entire target window remains useful, and
        // the configurable coverage thresholds still exclude comment lists.
        return allowUnknownDirection;
    }

    boolean isStrongVerticalPagingSignal(int scrollDeltaX, int scrollDeltaY,
            int fromIndex, int toIndex, boolean allowUnknownDirection) {
        long horizontal = Math.abs((long) scrollDeltaX);
        long vertical = Math.abs((long) scrollDeltaY);
        if (horizontal != 0L || vertical != 0L) {
            return vertical > 0L
                    && horizontal <= vertical * maximumHorizontalDeltaRatio;
        }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) return true;
        return allowUnknownDirection;
    }

    private static double checkedRatio(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("page scroll ratio is invalid");
        }
        return value;
    }
}
