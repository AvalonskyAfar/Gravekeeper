package com.gravekeeper.inference;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class TextClassifier {
    private static final class Target {
        final float scale;
        final float intercept;
        final byte[] weights;

        Target(float scale, float intercept, byte[] weights) {
            this.scale = scale;
            this.intercept = intercept;
            this.weights = weights;
        }
    }

    private final TextFeatureHasher hasher = new TextFeatureHasher();
    private final Map<String, Target> targets;

    public TextClassifier(AssetManager assets, String path) throws IOException {
        this(readAll(assets.open(path)));
    }

    public TextClassifier(File file) throws IOException {
        this(readAll(new FileInputStream(file)));
    }

    public TextClassifier(InputStream input) throws IOException {
        this(readAll(input));
    }

    TextClassifier(byte[] binary) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 12
                || buffer.get() != 'H'
                || buffer.get() != 'T'
                || buffer.get() != 'C'
                || buffer.get() != '1') {
            throw new IOException("Invalid text model header");
        }
        int targetCount = buffer.getInt();
        int featureCount = buffer.getInt();
        if (targetCount != 3 || featureCount != TextFeatureHasher.FEATURE_COUNT) {
            throw new IOException("Unexpected text model dimensions");
        }

        targets = new HashMap<>();
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            int nameLength = buffer.getInt();
            if (nameLength <= 0 || nameLength > 64 || buffer.remaining() < nameLength) {
                throw new IOException("Invalid target name in text model");
            }
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            float scale = buffer.getFloat();
            float intercept = buffer.getFloat();
            buffer.getFloat(); // Per-target legacy threshold; fusion owns final threshold.
            byte[] weights = new byte[featureCount];
            buffer.get(weights);
            targets.put(name, new Target(scale, intercept, weights));
        }
        if (!targets.keySet().containsAll(
                java.util.Arrays.asList("sales", "health", "elderly"))) {
            throw new IOException("Text model is missing a required target");
        }
        if (buffer.hasRemaining()) {
            throw new IOException("Unexpected trailing bytes in text model");
        }
    }

    public TextScores predict(String text) {
        TextFeatureHasher.SparseVector vector = hasher.transform(text);
        return new TextScores(
                predictTarget(targets.get("sales"), vector),
                predictTarget(targets.get("health"), vector),
                predictTarget(targets.get("elderly"), vector));
    }

    private static double predictTarget(
            Target target,
            TextFeatureHasher.SparseVector vector) {
        double logit = target.intercept;
        for (int position = 0; position < vector.indices.length; position++) {
            int index = vector.indices[position];
            logit += target.weights[index]
                    * target.scale
                    * vector.values[position];
        }
        return sigmoid(logit);
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) return 1.0 / (1.0 + Math.exp(-value));
        double exponential = Math.exp(value);
        return exponential / (1.0 + exponential);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) output.write(chunk, 0, count);
            return output.toByteArray();
        }
    }
}
