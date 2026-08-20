package com.gravekeeper.inference;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class TextFeatureHasherTest {
    @Test
    public void matchesFrozenSklearnVectorForHealthProduct() {
        TextFeatureHasher.SparseVector vector =
                new TextFeatureHasher().transform("保健品");
        assertArrayEquals(
                new int[] {24098, 51366, 56682, 79294, 90089, 95063},
                vector.indices);
        assertArrayEquals(
                new float[] {
                        -0.4082483f,
                        0.4082483f,
                        -0.4082483f,
                        0.4082483f,
                        0.4082483f,
                        -0.4082483f,
                },
                vector.values,
                1.0e-6f);
    }

    @Test
    public void emptyTextProducesEmptyVector() {
        TextFeatureHasher.SparseVector vector =
                new TextFeatureHasher().transform("");
        assertArrayEquals(new int[0], vector.indices);
        assertArrayEquals(new float[0], vector.values, 0.0f);
    }
}
