package com.gravekeeper;

/** Central motion contract. It describes timing only; page visuals remain owned by the UI kit. */
public final class MotionSpec {
    private MotionSpec() {}

    public static long settleDuration(UiKit ui, float remaining) {
        if (ui.prefs.reduceMotion()) return 120L;
        float bounded = Math.max(0f, Math.min(1f, remaining));
        // Preserve a launcher-like readable settle even when the finger releases
        // close to the destination. The old 150 ms floor made the final movement
        // disappear abruptly and felt disconnected from the drag phase.
        return Math.max(220L, Math.min(420L, Math.round(210f + bounded * 210f)));
    }

    public static long controlDuration(UiKit ui) {
        return ui.prefs.reduceMotion() ? 120L : 260L;
    }
}
