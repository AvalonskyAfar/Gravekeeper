package com.gravekeeper.inference;

import com.gravekeeper.config.GuardConfig;

public final class RiskPolicyEngine {
    private final GuardConfig config;

    public RiskPolicyEngine(GuardConfig config) {
        this.config = config;
    }

    public PolicyDecision evaluate(double modelScore, String ocrText, GuardConfig.Platform platform) {
        return evaluate(modelScore, ocrText, "", platform);
    }

    public PolicyDecision evaluate(double modelScore, String ocrText,
            String accessibilityText, GuardConfig.Platform platform) {
        ContentSignals signals = ContentSignals.parse(ocrText, accessibilityText, config);
        return evaluate(modelScore, ocrText, accessibilityText, platform, signals);
    }

    PolicyDecision evaluate(double modelScore, String ocrText,
            String accessibilityText, GuardConfig.Platform platform, ContentSignals signals) {
        String policyText = (ocrText == null ? "" : ocrText) + "\n"
                + (accessibilityText == null ? "" : accessibilityText);
        GuardConfig.MediaPolicy policy = platform.policy(signals.mediaKind);
        boolean whitelisted = !signals.accountId.isEmpty()
                && policy.whitelistEnabled
                && platform.whitelistMatches(signals.accountId);
        double adjusted = modelScore + platform.riskBias + policy.riskBias;
        if (signals.globalPurchase && config.globalPurchaseStandaloneEnabled) {
            adjusted += config.globalPurchaseBias;
        }
        if (signals.negativeContext) adjusted += config.negativeContextBias;
        for (GuardConfig.RuntimeRule rule : config.runtimeRules) {
            if (rule.matches(policyText)) adjusted += rule.riskBias;
        }
        adjusted = clamp(adjusted);
        GuardConfig.Action action = whitelisted
                ? GuardConfig.Action.IGNORE : policy.actionFor(adjusted);
        boolean positive = action != GuardConfig.Action.IGNORE;
        StringBuilder reasons = new StringBuilder();
        if (signals.globalPurchase && config.globalPurchaseStandaloneEnabled) {
            appendReason(reasons, "全球购 " + formatSigned(config.globalPurchaseBias));
        }
        if (signals.negativeContext) appendReason(reasons, "负向语境 "
                + formatSigned(config.negativeContextBias));
        if (platform.riskBias != 0.0) appendReason(reasons, "平台 "
                + formatSigned(platform.riskBias));
        if (policy.riskBias != 0.0) appendReason(reasons, "媒体 "
                + formatSigned(policy.riskBias));
        for (GuardConfig.RuntimeRule rule : config.runtimeRules) {
            if (rule.matches(policyText)) appendReason(reasons, rule.id + " "
                    + formatSigned(rule.riskBias));
        }
        if (reasons.length() == 0) reasons.append("模型与基础规则");
        return new PolicyDecision(
                adjusted, policy.minimumActionThreshold(), positive, whitelisted,
                action,
                signals.mediaKind, signals.accountId, reasons.toString());
    }

    private static void appendReason(StringBuilder output, String value) {
        if (output.length() > 0) output.append("；");
        output.append(value);
    }

    private static String formatSigned(double value) {
        return String.format(java.util.Locale.ROOT, "%+.2f", value);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
