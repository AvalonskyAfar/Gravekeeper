package com.gravekeeper.inference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TextFeatureHasher {
    public static final int FEATURE_COUNT = 1 << 18;

    public static final class SparseVector {
        public final int[] indices;
        public final float[] values;

        SparseVector(int[] indices, float[] values) {
            this.indices = indices;
            this.values = values;
        }
    }

    public SparseVector transform(String text) {
        int[] codePoints = (text == null ? "" : text).codePoints().toArray();
        Map<Integer, Integer> counts = new HashMap<>();

        for (int length = 1; length <= 4; length++) {
            for (int start = 0; start + length <= codePoints.length; start++) {
                String ngram = new String(codePoints, start, length);
                int hash = MurmurHash3.hash32(
                        ngram.getBytes(StandardCharsets.UTF_8), 0);
                int index = featureIndex(hash);
                int signedCount = counts.getOrDefault(index, 0)
                        + (hash >= 0 ? 1 : -1);
                if (signedCount == 0) counts.remove(index);
                else counts.put(index, signedCount);
            }
        }

        List<Integer> sorted = new ArrayList<>(counts.keySet());
        Collections.sort(sorted);
        double squaredNorm = 0.0;
        for (int index : sorted) {
            int value = counts.get(index);
            squaredNorm += (double) value * value;
        }
        double norm = Math.sqrt(squaredNorm);

        int[] indices = new int[sorted.size()];
        float[] values = new float[sorted.size()];
        for (int position = 0; position < sorted.size(); position++) {
            int index = sorted.get(position);
            indices[position] = index;
            values[position] = norm == 0.0
                    ? 0.0f
                    : (float) (counts.get(index) / norm);
        }
        return new SparseVector(indices, values);
    }

    static int featureIndex(int hash) {
        if (hash == Integer.MIN_VALUE) {
            return (Integer.MAX_VALUE - (FEATURE_COUNT - 1)) % FEATURE_COUNT;
        }
        return Math.abs(hash) % FEATURE_COUNT;
    }
}
