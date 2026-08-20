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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.gravekeeper.config.GuardConfig;

public final class RuleEngine {
    private final List<String> health;
    private final List<String> sales;
    private final List<String> elderly;
    private final List<String> negative;
    private final List<String> shoppingCart;
    private final List<String> orderPrompt;
    private final List<String> collectorOverlay;
    private final List<String> blackOcclusion;
    private final List<String> loadingOrBlank;
    private final Pattern price;
    private final Set<String> blacklist;
    private final Set<String> whitelist;

    public RuleEngine(AssetManager assets, String path) throws IOException {
        this(readJson(assets, path), Collections.emptySet(), Collections.emptySet());
    }

    public RuleEngine(File file) throws IOException {
        this(readJson(new FileInputStream(file), file.toString()),
                Collections.emptySet(), Collections.emptySet());
    }

    public RuleEngine(File file, GuardConfig config) throws IOException {
        this(readJson(new FileInputStream(file), file.toString()), config,
                Collections.emptySet(), Collections.emptySet());
    }

    public RuleEngine(InputStream input) throws IOException {
        this(readJson(input, "stream"), Collections.emptySet(), Collections.emptySet());
    }

    RuleEngine(GuardConfig config) {
        health = config.ruleHealthTerms;
        sales = config.ruleSalesTerms;
        elderly = config.ruleElderlyTerms;
        negative = config.ruleNegativeContextTerms;
        shoppingCart = config.ruleShoppingCartTerms;
        orderPrompt = config.ruleOrderPromptTerms;
        collectorOverlay = config.ruleCollectorOverlayTerms;
        blackOcclusion = config.ruleBlackOcclusionTerms;
        loadingOrBlank = config.ruleLoadingOrBlankTerms;
        price = Pattern.compile(config.rulePriceRegex);
        blacklist = Collections.emptySet();
        whitelist = Collections.emptySet();
    }

    RuleEngine(JSONObject schema, Set<String> blacklist, Set<String> whitelist)
            throws IOException {
        this(schema, null, blacklist, whitelist);
    }

    RuleEngine(JSONObject schema, GuardConfig config,
            Set<String> blacklist, Set<String> whitelist) throws IOException {
        try {
            JSONObject keywords = schema.getJSONObject("keywords");
            health = config == null ? strings(keywords.getJSONArray("health"))
                    : config.ruleHealthTerms;
            sales = config == null ? strings(keywords.getJSONArray("sales"))
                    : config.ruleSalesTerms;
            elderly = config == null ? strings(keywords.getJSONArray("elderly"))
                    : config.ruleElderlyTerms;
            negative = config == null ? strings(keywords.getJSONArray("negative_context"))
                    : config.ruleNegativeContextTerms;
            shoppingCart = config == null ? strings(keywords.getJSONArray("shopping_cart"))
                    : config.ruleShoppingCartTerms;
            orderPrompt = config == null ? strings(keywords.getJSONArray("order_prompt"))
                    : config.ruleOrderPromptTerms;
            collectorOverlay = config == null ? strings(keywords.getJSONArray("collector_overlay"))
                    : config.ruleCollectorOverlayTerms;
            blackOcclusion = config == null ? strings(keywords.getJSONArray("black_occlusion"))
                    : config.ruleBlackOcclusionTerms;
            loadingOrBlank = config == null ? strings(keywords.getJSONArray("loading_or_blank"))
                    : config.ruleLoadingOrBlankTerms;
            price = Pattern.compile(config == null ? schema.getString("price_regex")
                    : config.rulePriceRegex);
        } catch (JSONException error) {
            throw new IOException("Invalid rule schema", error);
        }
        this.blacklist = new HashSet<>(blacklist);
        this.whitelist = new HashSet<>(whitelist);
    }

    public RuleFeatures evaluate(String rawText, String accountId, boolean ocrAvailable) {
        String text = rawText == null ? "" : rawText.trim();
        String account = accountId == null ? "" : accountId.trim();
        double healthCount = count(text, health);
        double salesCount = count(text, sales);
        double elderlyCount = count(text, elderly);
        double negativeCount = count(text, negative);
        double pricePresent = price.matcher(text).find() ? 1.0 : 0.0;
        double cartPresent = containsAny(text, shoppingCart) ? 1.0 : 0.0;
        double orderPresent = containsAny(text, orderPrompt) ? 1.0 : 0.0;
        double blacklistHit = !account.isEmpty() && blacklist.contains(account) ? 1.0 : 0.0;
        double whitelistHit = !account.isEmpty() && whitelist.contains(account) ? 1.0 : 0.0;
        double overlay = containsAny(text, collectorOverlay) ? 1.0 : 0.0;
        double occlusion = containsAny(text, blackOcclusion) ? 1.0 : 0.0;
        double loading = containsAny(text, loadingOrBlank) ? 1.0 : 0.0;
        double strong = healthCount > 0
                && (salesCount > 0 || pricePresent > 0 || cartPresent > 0
                    || orderPresent > 0 || blacklistHit > 0)
                && negativeCount < 2
                && whitelistHit == 0
                && overlay == 0
                && loading == 0 ? 1.0 : 0.0;

        return new RuleFeatures(new double[] {
                healthCount, salesCount, elderlyCount, negativeCount,
                pricePresent, cartPresent, orderPresent,
                blacklistHit, whitelistHit, ocrAvailable ? 1.0 : 0.0,
                overlay, occlusion, loading, strong,
        });
    }

    private static int count(String text, List<String> terms) {
        int total = 0;
        for (String term : terms) {
            int from = 0;
            while (!term.isEmpty()) {
                int found = text.indexOf(term, from);
                if (found < 0) break;
                total++;
                from = found + term.length();
            }
        }
        return total;
    }

    private static boolean containsAny(String text, List<String> terms) {
        for (String term : terms) if (!term.isEmpty() && text.contains(term)) return true;
        return false;
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
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
            throw new IOException("Invalid rule JSON: " + label, error);
        }
    }
}
