package com.gravekeeper.inference;

import android.graphics.Bitmap;

import java.util.Arrays;

/** Low-cost normalized visual fingerprint used only in memory for content boundaries. */
public final class ContentFingerprint {
    private static final int COLUMNS = 8;
    private static final int ROWS = 12;
    private final byte[] luma;

    private ContentFingerprint(byte[] luma) {
        this.luma = luma;
    }

    public static ContentFingerprint fromBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() < 2 || bitmap.getHeight() < 2) {
            throw new IllegalArgumentException("bitmap is empty");
        }
        int[] pixels = new int[COLUMNS * ROWS];
        int index = 0;
        for (int row = 0; row < ROWS; row++) {
            double yRatio = 0.08 + (row + 0.5) * 0.82 / ROWS;
            int y = Math.min(bitmap.getHeight() - 1, (int) (yRatio * bitmap.getHeight()));
            for (int column = 0; column < COLUMNS; column++) {
                double xRatio = (column + 0.5) / COLUMNS;
                int x = Math.min(bitmap.getWidth() - 1, (int) (xRatio * bitmap.getWidth()));
                pixels[index++] = bitmap.getPixel(x, y);
            }
        }
        return fromSampledArgb(pixels);
    }

    public static ContentFingerprint fromSampledArgb(int[] pixels) {
        byte[] values = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int red = (color >>> 16) & 0xff;
            int green = (color >>> 8) & 0xff;
            int blue = color & 0xff;
            values[i] = (byte) ((red * 54 + green * 183 + blue * 19) >>> 8);
        }
        return new ContentFingerprint(values);
    }

    public double distance(ContentFingerprint other) {
        if (other == null || other.luma.length != luma.length) return 1.0;
        long difference = 0;
        for (int i = 0; i < luma.length; i++) {
            difference += Math.abs((luma[i] & 0xff) - (other.luma[i] & 0xff));
        }
        return difference / (255.0 * luma.length);
    }

    public boolean isNearlyBlank() {
        int minimum = 255;
        int maximum = 0;
        long sum = 0;
        for (byte sample : luma) {
            int value = sample & 0xff;
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
            sum += value;
        }
        double mean = sum / (double) luma.length;
        return maximum - minimum <= 3 && (mean <= 3.0 || mean >= 252.0);
    }

    @Override public boolean equals(Object other) {
        return other instanceof ContentFingerprint
                && Arrays.equals(luma, ((ContentFingerprint) other).luma);
    }

    @Override public int hashCode() { return Arrays.hashCode(luma); }
}
