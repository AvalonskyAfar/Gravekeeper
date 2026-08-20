package com.gravekeeper.inference;

import com.gravekeeper.config.GuardConfig;

import java.text.Normalizer;
import java.util.List;

public final class ContentSignals {
    public final GuardConfig.MediaKind mediaKind;
    public final String accountId;
    public final boolean globalPurchase;
    public final boolean negativeContext;

    private ContentSignals(GuardConfig.MediaKind mediaKind, String accountId,
            boolean globalPurchase, boolean negativeContext) {
        this.mediaKind = mediaKind;
        this.accountId = accountId;
        this.globalPurchase = globalPurchase;
        this.negativeContext = negativeContext;
    }

    public static ContentSignals parse(String rawText, GuardConfig config) {
        return parse(rawText, "", config);
    }

    public static ContentSignals parse(
            String rawOcrText, String accessibilityText, GuardConfig config) {
        return parse(OcrDocument.fromText(rawOcrText), accessibilityText, config, null);
    }

    public static ContentSignals parse(OcrDocument document, String accessibilityText,
            GuardConfig config, GuardConfig.Platform platform) {
        String rawOcrText = document == null ? "" : document.text;
        String ocrText = rawOcrText == null ? "" : rawOcrText;
        String semanticText = accessibilityText == null ? "" : accessibilityText;
        String text = ocrText + "\n" + semanticText;
        boolean live = containsAny(text, config.liveTerms);
        boolean shortVideo = containsAny(text, config.shortVideoTerms);
        GuardConfig.MediaKind media = live ? GuardConfig.MediaKind.LIVE
                : shortVideo ? GuardConfig.MediaKind.SHORT_VIDEO
                : GuardConfig.MediaKind.UNKNOWN;
        return new ContentSignals(
                media,
                extractAccountId(document, config, platform, media),
                containsAny(text, config.globalPurchaseTerms),
                containsAny(text, config.negativeContextTerms));
    }

    static String extractAccountId(String text, GuardConfig config) {
        return extractAccountId(OcrDocument.fromText(text), config, null,
                GuardConfig.MediaKind.UNKNOWN);
    }

    static String extractAccountId(OcrDocument document, GuardConfig config,
            GuardConfig.Platform platform, GuardConfig.MediaKind media) {
        if (document == null) return "";
        GuardConfig.AccountDetection profile = platform == null
                ? null : platform.accountDetection;
        if (profile != null && !profile.enabled) return "";
        List<GuardConfig.NormalizedRegion> regions = profile == null
                ? java.util.Collections.emptyList() : profile.regions(media);
        if (profile == null || profile.explicitPrefixAnywhere || !regions.isEmpty()) {
            String adjacent = "";
            double adjacentScore = Double.POSITIVE_INFINITY;
            for (OcrDocument.Line line : document.lines) {
                if (profile != null && !profile.explicitPrefixAnywhere
                        && !inRegions(line, regions)) continue;
                String trimmed = line.text.trim();
                for (String prefix : config.accountIdPrefixes) {
                    int found = trimmed.indexOf(prefix);
                    if (found < 0) continue;
                    String candidate = normalizeAccountId(
                            trimmed.substring(found + prefix.length()));
                    if (validExplicitCandidate(candidate, trimmed, profile)) {
                        return candidate;
                    }
                    if (profile == null || !line.hasGeometry()) continue;
                    for (OcrDocument.Line valueLine : document.lines) {
                        if (valueLine == line || !valueLine.hasGeometry()
                                || !inRegions(valueLine, regions)) continue;
                        candidate = normalizeAccountId(valueLine.text);
                        if (!validCandidate(candidate, valueLine.text, profile,
                                config.accountIdPrefixes)) continue;
                        double score = explicitAnchorScore(line, valueLine,
                                profile.anchorMaximumDistance);
                        if (score < adjacentScore) {
                            adjacentScore = score;
                            adjacent = candidate;
                        }
                    }
                }
            }
            if (!adjacent.isEmpty()) return adjacent;
        }
        if (profile == null) return "";
        if (profile.allowAtHandle) {
            for (OcrDocument.Line line : document.lines) {
                String trimmed = line.text.trim();
                if (!trimmed.startsWith("@") || !inRegions(line, regions)) continue;
                String candidate = normalizeAccountId(trimmed);
                if (validCandidate(candidate, line.text, profile,
                        config.accountIdPrefixes)) return candidate;
            }
        }
        boolean allowAnchoredName = profile.allowFollowAnchoredHeader
                || (media == GuardConfig.MediaKind.SHORT_VIDEO
                    && profile.allowShortVideoHeaderName)
                || (media == GuardConfig.MediaKind.LIVE && profile.allowLiveHeaderName);
        if (allowAnchoredName) {
            String best = "";
            double bestScore = Double.POSITIVE_INFINITY;
            for (OcrDocument.Line line : document.lines) {
                if (!inRegions(line, regions)) continue;
                String segment = textBeforeAnchor(line.text, profile.followAnchorTerms);
                String candidate = anchoredNameCandidate(segment, platform, profile);
                if (validCandidate(candidate, segment, profile,
                        config.accountIdPrefixes)) {
                    if (platform.whitelistMatches(candidate)) return candidate;
                    if (bestScore > 0.0) {
                        bestScore = 0.0;
                        best = candidate;
                    }
                }
                for (OcrDocument.Line anchor : document.lines) {
                    if (!containsAny(anchor.text, profile.followAnchorTerms)
                            || !anchor.hasGeometry() || !inRegions(anchor, regions)) continue;
                    double score = followAnchorScore(line, anchor, profile);
                    if (score >= bestScore) continue;
                    candidate = anchoredNameCandidate(line.text, platform, profile);
                    if (validCandidate(candidate, line.text, profile,
                            config.accountIdPrefixes)) {
                        if (platform.whitelistMatches(candidate)) return candidate;
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
            if (!best.isEmpty()) return best;
        }
        return "";
    }

    public static String normalizeAccountId(String raw) {
        String value = Normalizer.normalize(raw == null ? "" : raw,
                Normalizer.Form.NFKC).trim();
        value = value.replaceFirst("^[\\s:：#@]+", "");
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)
                    || codePoint == '_' || codePoint == '.' || codePoint == '-') {
                result.appendCodePoint(codePoint);
            } else if (result.length() > 0) break;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static boolean validLength(String value, GuardConfig.AccountDetection profile) {
        int minimum = profile == null ? 3 : profile.minimumLength;
        int maximum = profile == null ? 64 : profile.maximumLength;
        return value.length() >= minimum && value.length() <= maximum;
    }

    private static boolean validCandidate(String candidate, String raw,
            GuardConfig.AccountDetection profile, Iterable<String> prefixes) {
        if (profile == null) return validLength(candidate, null)
                && !containsAny(raw == null ? "" : raw, prefixes);
        return validLength(candidate, profile)
                && profile.idAllowedPattern.matcher(candidate).matches()
                && !containsAny(raw == null ? "" : raw, prefixes)
                && !containsAny(raw == null ? "" : raw, profile.followAnchorTerms)
                && !containsAny(raw == null ? "" : raw, profile.candidateExclusionTerms);
    }

    private static boolean validExplicitCandidate(String candidate, String raw,
            GuardConfig.AccountDetection profile) {
        if (!validLength(candidate, profile)) return false;
        if (profile == null) return true;
        return profile.idAllowedPattern.matcher(candidate).matches()
                && !containsAny(raw == null ? "" : raw,
                        profile.candidateExclusionTerms);
    }

    /**
     * Returns a normalized layout score for an ID value immediately to the right of,
     * or just below, an explicit account-ID label. Coordinates are already in 0..1,
     * so this is independent of physical pixels, density, and resolution.
     */
    private static double explicitAnchorScore(OcrDocument.Line label,
            OcrDocument.Line value, double maximumDistance) {
        double dx = value.centerX() - label.centerX();
        double dy = value.centerY() - label.centerY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance > maximumDistance) return Double.POSITIVE_INFINITY;
        double labelHeight = Math.max(0.005, label.bottom - label.top);
        double valueHeight = Math.max(0.005, value.bottom - value.top);
        double rowTolerance = Math.max(0.025, 1.5 * Math.max(labelHeight, valueHeight));
        boolean sameRowToRight = value.centerX() >= label.centerX()
                && Math.abs(dy) <= rowTolerance;
        double verticalGap = value.top - label.bottom;
        boolean immediatelyBelow = verticalGap >= -0.01
                && verticalGap <= Math.min(0.08, maximumDistance)
                && Math.abs(value.left - label.left) <= 0.20;
        if (!sameRowToRight && !immediatelyBelow) return Double.POSITIVE_INFINITY;
        return (sameRowToRight ? 0.0 : 0.05) + Math.abs(dy) * 2.0 + Math.abs(dx);
    }

    private static boolean inRegions(OcrDocument.Line line,
            List<GuardConfig.NormalizedRegion> regions) {
        if (!line.hasGeometry()) return false;
        for (GuardConfig.NormalizedRegion region : regions) {
            if (region.contains(line.centerX(), line.centerY())) return true;
        }
        return false;
    }

    private static String textBeforeAnchor(String text, Iterable<String> anchors) {
        String value = text == null ? "" : text.trim();
        int earliest = value.length();
        for (String anchor : anchors) {
            int found = value.indexOf(anchor);
            if (found >= 0) earliest = Math.min(earliest, found);
        }
        if (earliest == value.length()) return "";
        return value.substring(0, earliest).trim();
    }

    private static String anchoredNameCandidate(String text, GuardConfig.Platform platform,
            GuardConfig.AccountDetection profile) {
        String value = Normalizer.normalize(text == null ? "" : text,
                Normalizer.Form.NFKC).trim();
        if (value.isEmpty()) return "";
        if (profile.recoverConfiguredWhitelistFromAnchoredLine && platform != null) {
            String exactBest = "";
            String fuzzyBest = "";
            int bestOffset = Integer.MAX_VALUE;
            String compactValue = compactForAnchor(value);
            for (String configured : platform.whitelistIds) {
                String normalized = Normalizer.normalize(configured == null ? "" : configured,
                        Normalizer.Form.NFKC).trim();
                if (normalized.isEmpty()) continue;
                String compactConfigured = compactForAnchor(normalized);
                int offset = value.indexOf(normalized);
                if (offset >= 0 && (offset < bestOffset
                        || (offset == bestOffset
                        && normalized.length() > exactBest.length()))) {
                    bestOffset = offset;
                    exactBest = normalizeAccountId(normalized);
                } else if (offset < 0 && !compactConfigured.isEmpty()
                        && compactValue.contains(compactConfigured)) {
                    bestOffset = 0;
                    exactBest = normalizeAccountId(normalized);
                } else if (offset < 0 && exactBest.isEmpty()
                        && fuzzyAnchorMatch(compactValue, compactConfigured, profile)
                        && normalized.length() > fuzzyBest.length()) {
                    fuzzyBest = normalizeAccountId(normalized);
                }
            }
            if (!exactBest.isEmpty()) return exactBest;
            if (!fuzzyBest.isEmpty()) return fuzzyBest;
        }
        return normalizeAccountId(value);
    }

    private static String compactForAnchor(String value) {
        StringBuilder compact = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) compact.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return compact.toString();
    }

    private static boolean fuzzyAnchorMatch(String observed, String configured,
            GuardConfig.AccountDetection profile) {
        if (!profile.whitelistFuzzyRecoveryEnabled
                || profile.whitelistMaximumEditDistance <= 0) return false;
        int[] source = observed.codePoints().toArray();
        int[] target = configured.codePoints().toArray();
        if (target.length < profile.whitelistFuzzyMinimumLength) return false;
        int minimumLength = Math.max(
                (int) Math.ceil(target.length * profile.whitelistMinimumCoverage),
                target.length - profile.whitelistMaximumEditDistance);
        int maximumLength = Math.min(source.length,
                target.length + profile.whitelistMaximumEditDistance);
        for (int start = 0; start + minimumLength <= source.length; start++) {
            int available = Math.min(maximumLength, source.length - start);
            for (int length = minimumLength; length <= available; length++) {
                if (levenshteinWithin(source, start, length, target,
                        profile.whitelistMaximumEditDistance)) return true;
            }
        }
        return false;
    }

    private static boolean levenshteinWithin(int[] source, int start, int length,
            int[] target, int maximumDistance) {
        if (Math.abs(length - target.length) > maximumDistance) return false;
        int[] previous = new int[target.length + 1];
        int[] current = new int[target.length + 1];
        for (int column = 0; column <= target.length; column++) previous[column] = column;
        for (int row = 1; row <= length; row++) {
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= target.length; column++) {
                int substitution = previous[column - 1]
                        + (source[start + row - 1] == target[column - 1] ? 0 : 1);
                current[column] = Math.min(substitution,
                        Math.min(previous[column] + 1, current[column - 1] + 1));
                rowMinimum = Math.min(rowMinimum, current[column]);
            }
            if (rowMinimum > maximumDistance) return false;
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[target.length] <= maximumDistance;
    }

    private static double followAnchorScore(OcrDocument.Line value,
            OcrDocument.Line anchor, GuardConfig.AccountDetection profile) {
        if (value == anchor || !value.hasGeometry() || !anchor.hasGeometry()) {
            return Double.POSITIVE_INFINITY;
        }
        double verticalDistance = Math.abs(value.centerY() - anchor.centerY());
        if (verticalDistance > profile.anchorMaximumVerticalDistance) {
            return Double.POSITIVE_INFINITY;
        }
        double horizontalGap = anchor.left - value.right;
        if (horizontalGap < -profile.anchorMaximumOverlap
                || horizontalGap > profile.anchorMaximumDistance
                || value.centerX() >= anchor.centerX()) {
            return Double.POSITIVE_INFINITY;
        }
        return verticalDistance * 3.0 + Math.max(0.0, horizontalGap);
    }

    private static boolean containsAny(String text, Iterable<String> terms) {
        for (String term : terms) if (!term.isEmpty() && text.contains(term)) return true;
        return false;
    }
}
