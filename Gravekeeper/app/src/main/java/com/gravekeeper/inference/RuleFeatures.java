package com.gravekeeper.inference;

public final class RuleFeatures {
    public static final int COUNT = 14;

    private final double[] ordered;

    RuleFeatures(double[] ordered) {
        if (ordered.length != COUNT) throw new IllegalArgumentException("Expected 14 rule features");
        this.ordered = ordered.clone();
    }

    public double[] ordered() {
        return ordered.clone();
    }

    public boolean strongPositive() {
        return ordered[13] > 0.5;
    }
}
