package com.gravekeeper.inference;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class RiskFusion {
    public static final int FEATURE_COUNT = 18;

    private final double[] mean;
    private final double[] scale;
    private final double[] coefficient;
    private final double intercept;
    private final double threshold;

    public RiskFusion(AssetManager assets, String path) throws IOException {
        this(readJson(assets, path));
    }

    public RiskFusion(File file) throws IOException {
        this(readJson(new FileInputStream(file), file.toString()));
    }

    public RiskFusion(InputStream input) throws IOException {
        this(readJson(input, "stream"));
    }

    RiskFusion(JSONObject model) throws IOException {
        try {
            if (!"identity".equals(model.getJSONObject("calibrator").getString("method"))) {
                throw new IOException("Only identity-calibrated fusion is supported");
            }
            validateFeatures(model.getJSONArray("feature_order"));
            JSONObject scaler = model.getJSONObject("standard_scaler");
            JSONObject classifier = model.getJSONObject("classifier");
            mean = doubles(scaler.getJSONArray("mean"));
            scale = doubles(scaler.getJSONArray("scale"));
            coefficient = doubles(classifier.getJSONArray("coefficient"));
            intercept = classifier.getDouble("intercept");
            threshold = model.getDouble("threshold");
            if (mean.length != FEATURE_COUNT || scale.length != FEATURE_COUNT
                    || coefficient.length != FEATURE_COUNT) {
                throw new IOException("Unexpected fusion dimensions");
            }
            for (double width : scale) if (width == 0.0) throw new IOException("Zero fusion scale");
        } catch (JSONException error) {
            throw new IOException("Invalid fusion JSON", error);
        }
    }

    RiskFusion(
            double[] mean,
            double[] scale,
            double[] coefficient,
            double intercept,
            double threshold) {
        if (mean.length != FEATURE_COUNT || scale.length != FEATURE_COUNT
                || coefficient.length != FEATURE_COUNT) {
            throw new IllegalArgumentException("Expected 18 fusion parameters");
        }
        this.mean = mean.clone();
        this.scale = scale.clone();
        this.coefficient = coefficient.clone();
        this.intercept = intercept;
        this.threshold = threshold;
    }

    public double predict(double visualScore, TextScores text, RuleFeatures rules) {
        double[] input = new double[FEATURE_COUNT];
        input[0] = visualScore;
        input[1] = text.sales;
        input[2] = text.health;
        input[3] = text.elderly;
        double[] ruleValues = rules.ordered();
        System.arraycopy(ruleValues, 0, input, 4, ruleValues.length);
        return predictOrdered(input);
    }

    public double predictOrdered(double[] input) {
        if (input.length != FEATURE_COUNT) throw new IllegalArgumentException("Expected 18 features");
        double logit = intercept;
        for (int index = 0; index < FEATURE_COUNT; index++) {
            logit += coefficient[index] * ((input[index] - mean[index]) / scale[index]);
        }
        if (logit >= 0.0) return 1.0 / (1.0 + Math.exp(-logit));
        double exponential = Math.exp(logit);
        return exponential / (1.0 + exponential);
    }

    public double threshold() {
        return threshold;
    }

    private static void validateFeatures(JSONArray names) throws JSONException, IOException {
        String[] expected = {
                "visual_score", "text_sales_score", "text_health_score", "text_elderly_score",
                "health_keyword_count", "sales_keyword_count", "elderly_keyword_count",
                "negative_context_count", "price_present", "shopping_cart_present",
                "order_prompt_present", "account_blacklist_hit", "account_whitelist_hit",
                "ocr_available", "collector_overlay", "black_occlusion", "loading_or_blank",
                "strong_positive_rule",
        };
        if (names.length() != expected.length) throw new IOException("Unexpected fusion feature count");
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals(names.getString(index))) {
                throw new IOException("Fusion feature order mismatch at " + index);
            }
        }
    }

    private static double[] doubles(JSONArray values) throws JSONException {
        double[] result = new double[values.length()];
        for (int index = 0; index < values.length(); index++) result[index] = values.getDouble(index);
        return result;
    }

    private static JSONObject readJson(AssetManager assets, String path) throws IOException {
        return readJson(assets.open(path), path);
    }

    private static JSONObject readJson(InputStream input, String label) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        try {
            return new JSONObject(text.toString());
        } catch (JSONException error) {
            throw new IOException("Invalid fusion JSON: " + label, error);
        }
    }
}
