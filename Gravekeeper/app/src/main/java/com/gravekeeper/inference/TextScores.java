package com.gravekeeper.inference;

public final class TextScores {
    public final double sales;
    public final double health;
    public final double elderly;

    public TextScores(double sales, double health, double elderly) {
        this.sales = sales;
        this.health = health;
        this.elderly = elderly;
    }
}
