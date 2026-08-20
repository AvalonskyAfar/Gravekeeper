package com.gravekeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Classifies target application windows using only accessibility window geometry. */
public final class WindowLayoutClassifier {
    public enum Mode {
        FULLSCREEN,
        SPLIT_TOP,
        SPLIT_BOTTOM,
        SPLIT_LEFT,
        SPLIT_RIGHT,
        PICTURE_IN_PICTURE,
        FLOATING_OR_UNKNOWN,
        NOT_FOCUSED,
        UNAVAILABLE
    }

    public static final class Bounds {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
        public long area() { return Math.max(0L, (long) width() * height()); }
        public boolean isPositive() { return width() > 0 && height() > 0; }
        public float xAt(double ratio) { return (float) (left + width() * ratio); }
        public float yAt(double ratio) { return (float) (top + height() * ratio); }
        public double leftRatio(int displayWidth) {
            return displayWidth <= 0 ? 0.0 : left / (double) displayWidth;
        }
        public double topRatio(int displayHeight) {
            return displayHeight <= 0 ? 0.0 : top / (double) displayHeight;
        }
        public double rightRatio(int displayWidth) {
            return displayWidth <= 0 ? 0.0 : right / (double) displayWidth;
        }
        public double bottomRatio(int displayHeight) {
            return displayHeight <= 0 ? 0.0 : bottom / (double) displayHeight;
        }

        public Bounds clamp(int displayWidth, int displayHeight) {
            return new Bounds(Math.max(0, Math.min(left, displayWidth)),
                    Math.max(0, Math.min(top, displayHeight)),
                    Math.max(0, Math.min(right, displayWidth)),
                    Math.max(0, Math.min(bottom, displayHeight)));
        }

        public double distanceRatio(Bounds other, int displayWidth, int displayHeight) {
            if (other == null || displayWidth <= 0 || displayHeight <= 0) return 1.0;
            return Math.max(Math.max(Math.abs(leftRatio(displayWidth) - other.leftRatio(displayWidth)),
                            Math.abs(topRatio(displayHeight) - other.topRatio(displayHeight))),
                    Math.max(Math.abs(rightRatio(displayWidth) - other.rightRatio(displayWidth)),
                            Math.abs(bottomRatio(displayHeight) - other.bottomRatio(displayHeight))));
        }
    }

    public static final class WindowRecord {
        public final String packageName;
        public final Bounds bounds;
        public final boolean active;
        public final boolean focused;
        public final boolean pictureInPicture;

        public WindowRecord(String packageName, Bounds bounds, boolean active,
                boolean focused, boolean pictureInPicture) {
            this.packageName = packageName == null ? "" : packageName.trim();
            this.bounds = bounds;
            this.active = active;
            this.focused = focused;
            this.pictureInPicture = pictureInPicture;
        }
    }

    public static final class Config {
        public final boolean fullscreenEnabled;
        public final boolean splitScreenEnabled;
        public final boolean pictureInPictureEnabled;
        public final boolean floatingWindowEnabled;
        public final boolean unknownModeEnabled;
        public final boolean requireTargetWindowFocused;
        public final double minimumFullscreenWidthRatio;
        public final double minimumFullscreenHeightRatio;
        public final double minimumSplitSpanRatio;
        public final double minimumSplitAreaRatio;
        public final double maximumSplitAreaRatio;
        public final double boundsChangeToleranceRatio;
        public final double gestureEdgePaddingRatio;

        public Config(boolean fullscreenEnabled, boolean splitScreenEnabled,
                boolean pictureInPictureEnabled, boolean floatingWindowEnabled,
                boolean unknownModeEnabled, boolean requireTargetWindowFocused,
                double minimumFullscreenWidthRatio, double minimumFullscreenHeightRatio,
                double minimumSplitSpanRatio, double minimumSplitAreaRatio,
                double maximumSplitAreaRatio, double boundsChangeToleranceRatio,
                double gestureEdgePaddingRatio) {
            this.fullscreenEnabled = fullscreenEnabled;
            this.splitScreenEnabled = splitScreenEnabled;
            this.pictureInPictureEnabled = pictureInPictureEnabled;
            this.floatingWindowEnabled = floatingWindowEnabled;
            this.unknownModeEnabled = unknownModeEnabled;
            this.requireTargetWindowFocused = requireTargetWindowFocused;
            this.minimumFullscreenWidthRatio = checkedRatio(minimumFullscreenWidthRatio);
            this.minimumFullscreenHeightRatio = checkedRatio(minimumFullscreenHeightRatio);
            this.minimumSplitSpanRatio = checkedRatio(minimumSplitSpanRatio);
            this.minimumSplitAreaRatio = checkedRatio(minimumSplitAreaRatio);
            this.maximumSplitAreaRatio = checkedRatio(maximumSplitAreaRatio);
            this.boundsChangeToleranceRatio = checkedRatio(boundsChangeToleranceRatio);
            this.gestureEdgePaddingRatio = checkedRatio(gestureEdgePaddingRatio);
            if (this.minimumSplitAreaRatio > this.maximumSplitAreaRatio) {
                throw new IllegalArgumentException("split area bounds are reversed");
            }
        }

        private static double checkedRatio(double value) {
            if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
                throw new IllegalArgumentException("window ratio is invalid");
            }
            return value;
        }
    }

    public static final class Result {
        public final Mode mode;
        public final Bounds bounds;
        public final boolean focused;
        public final boolean detectionAllowed;
        public final String reason;

        Result(Mode mode, Bounds bounds, boolean focused, boolean detectionAllowed,
                String reason) {
            this.mode = mode;
            this.bounds = bounds;
            this.focused = focused;
            this.detectionAllowed = detectionAllowed;
            this.reason = reason;
        }

        public boolean isUsable() { return detectionAllowed && bounds != null; }
        public boolean sameContext(Result other, int displayWidth, int displayHeight,
                double tolerance) {
            return other != null && mode == other.mode && bounds != null
                    && bounds.distanceRatio(other.bounds, displayWidth, displayHeight) <= tolerance;
        }

        public String normalizedBounds(int displayWidth, int displayHeight) {
            if (bounds == null || displayWidth <= 0 || displayHeight <= 0) return "unknown";
            return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                    bounds.leftRatio(displayWidth), bounds.topRatio(displayHeight),
                    bounds.rightRatio(displayWidth), bounds.bottomRatio(displayHeight));
        }
    }

    private final Config config;

    public WindowLayoutClassifier(Config config) {
        if (config == null) throw new IllegalArgumentException("window config is null");
        this.config = config;
    }

    public Result classify(int displayWidth, int displayHeight, String targetPackage,
            List<WindowRecord> records) {
        if (displayWidth <= 0 || displayHeight <= 0 || targetPackage == null
                || targetPackage.trim().isEmpty() || records == null) {
            return unavailable("window geometry unavailable");
        }
        List<WindowRecord> targets = new ArrayList<>();
        List<WindowRecord> peers = new ArrayList<>();
        for (WindowRecord record : records) {
            if (record == null || record.bounds == null || !record.bounds.isPositive()) continue;
            Bounds clamped = record.bounds.clamp(displayWidth, displayHeight);
            if (!clamped.isPositive()) continue;
            WindowRecord normalized = new WindowRecord(record.packageName, clamped,
                    record.active, record.focused, record.pictureInPicture);
            if (targetPackage.equals(normalized.packageName)) targets.add(normalized);
            else if (!normalized.packageName.isEmpty()) peers.add(normalized);
        }
        if (targets.isEmpty()) return unavailable("target window not found");
        WindowRecord target = chooseTarget(targets);
        if (target.pictureInPicture) {
            return result(Mode.PICTURE_IN_PICTURE, target, config.pictureInPictureEnabled,
                    "picture_in_picture");
        }

        boolean targetHasFocusEvidence = target.focused || target.active;
        if (config.requireTargetWindowFocused && !targetHasFocusEvidence) {
            return result(Mode.NOT_FOCUSED, target, false, "target_window_not_focused");
        }

        double widthRatio = target.bounds.width() / (double) displayWidth;
        double heightRatio = target.bounds.height() / (double) displayHeight;
        if (widthRatio >= config.minimumFullscreenWidthRatio
                && heightRatio >= config.minimumFullscreenHeightRatio) {
            return result(Mode.FULLSCREEN, target, config.fullscreenEnabled,
                    "fullscreen_geometry");
        }

        Mode splitMode = splitMode(target, peers, displayWidth, displayHeight);
        if (splitMode != null) {
            return result(splitMode, target, config.splitScreenEnabled,
                    "standard_split_geometry");
        }
        boolean floating = target.bounds.area() < (long) (displayWidth * (double) displayHeight
                * config.maximumSplitAreaRatio);
        return result(Mode.FLOATING_OR_UNKNOWN, target,
                floating ? config.floatingWindowEnabled : config.unknownModeEnabled,
                floating ? "floating_window_geometry" : "unclassified_window_geometry");
    }

    private Mode splitMode(WindowRecord target, List<WindowRecord> peers,
            int displayWidth, int displayHeight) {
        double areaRatio = target.bounds.area() / (double) displayWidth / displayHeight;
        if (areaRatio < config.minimumSplitAreaRatio || areaRatio > config.maximumSplitAreaRatio) {
            return null;
        }
        double widthRatio = target.bounds.width() / (double) displayWidth;
        double heightRatio = target.bounds.height() / (double) displayHeight;
        boolean verticalSpan = widthRatio >= config.minimumSplitSpanRatio
                && heightRatio < config.minimumFullscreenHeightRatio;
        boolean horizontalSpan = heightRatio >= config.minimumSplitSpanRatio
                && widthRatio < config.minimumFullscreenWidthRatio;
        if (!verticalSpan && !horizontalSpan) return null;
        boolean hasComplement = false;
        for (WindowRecord peer : peers) {
            double peerArea = peer.bounds.area() / (double) displayWidth / displayHeight;
            if (peerArea < config.minimumSplitAreaRatio || peerArea > config.maximumSplitAreaRatio) continue;
            if (verticalSpan && peer.bounds.width() / (double) displayWidth >= config.minimumSplitSpanRatio
                    && overlapsX(target.bounds, peer.bounds, displayWidth)
                    && adjacentVertically(target.bounds, peer.bounds, displayHeight)) {
                hasComplement = true;
                break;
            }
            if (horizontalSpan && peer.bounds.height() / (double) displayHeight >= config.minimumSplitSpanRatio
                    && overlapsY(target.bounds, peer.bounds, displayHeight)
                    && adjacentHorizontally(target.bounds, peer.bounds, displayWidth)) {
                hasComplement = true;
                break;
            }
        }
        if (!hasComplement) return null;
        if (verticalSpan) {
            return target.bounds.topRatio(displayHeight) < 0.5
                    ? Mode.SPLIT_TOP : Mode.SPLIT_BOTTOM;
        }
        return target.bounds.leftRatio(displayWidth) < 0.5
                ? Mode.SPLIT_LEFT : Mode.SPLIT_RIGHT;
    }

    private boolean overlapsX(Bounds left, Bounds right, int displayWidth) {
        return Math.min(left.right, right.right) - Math.max(left.left, right.left)
                >= displayWidth * config.minimumSplitSpanRatio;
    }

    private boolean overlapsY(Bounds first, Bounds second, int displayHeight) {
        return Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top)
                >= displayHeight * config.minimumSplitSpanRatio;
    }

    private boolean adjacentVertically(Bounds first, Bounds second, int displayHeight) {
        int overlap = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top);
        int gap = Math.max(first.top, second.top) - Math.min(first.bottom, second.bottom);
        int tolerance = (int) Math.ceil(displayHeight * (1.0 - config.minimumSplitSpanRatio));
        int union = Math.max(first.bottom, second.bottom) - Math.min(first.top, second.top);
        return overlap <= tolerance && gap <= tolerance
                && union >= displayHeight * config.minimumSplitSpanRatio;
    }

    private boolean adjacentHorizontally(Bounds first, Bounds second, int displayWidth) {
        int overlap = Math.min(first.right, second.right) - Math.max(first.left, second.left);
        int gap = Math.max(first.left, second.left) - Math.min(first.right, second.right);
        int tolerance = (int) Math.ceil(displayWidth * (1.0 - config.minimumSplitSpanRatio));
        int union = Math.max(first.right, second.right) - Math.min(first.left, second.left);
        return overlap <= tolerance && gap <= tolerance
                && union >= displayWidth * config.minimumSplitSpanRatio;
    }

    private static WindowRecord chooseTarget(List<WindowRecord> candidates) {
        Collections.sort(candidates, Comparator.comparing((WindowRecord value) -> value.focused)
                .thenComparing(value -> value.active)
                .thenComparingLong(value -> value.bounds.area()).reversed());
        return candidates.get(0);
    }

    private static Result unavailable(String reason) {
        return new Result(Mode.UNAVAILABLE, null, false, false, reason);
    }

    private static Result result(Mode mode, WindowRecord record, boolean allowed, String reason) {
        return new Result(mode, record.bounds, record.focused || record.active, allowed, reason);
    }
}
