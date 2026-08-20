package com.gravekeeper.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GuardConfig {
    public enum Action { IGNORE, NOTIFY, SWIPE }
    public enum MediaKind { SHORT_VIDEO, LIVE, UNKNOWN }
    public enum WhitelistMatchMode { EXACT, PREFIX, CONTAINS }
    public enum EvidenceAggregation { MAX, AVERAGE, LATEST }

    public static final class NormalizedRegion {
        public final double left;
        public final double top;
        public final double right;
        public final double bottom;

        NormalizedRegion(JSONObject json, String label) throws JSONException {
            left = bounded(json.getDouble("left"), 0.0, 1.0, label + " left");
            top = bounded(json.getDouble("top"), 0.0, 1.0, label + " top");
            right = bounded(json.getDouble("right"), 0.0, 1.0, label + " right");
            bottom = bounded(json.getDouble("bottom"), 0.0, 1.0, label + " bottom");
            if (left >= right || top >= bottom) {
                throw new JSONException(label + " must have positive area");
            }
        }

        public NormalizedRegion(double left, double top, double right, double bottom) {
            if (left < 0.0 || top < 0.0 || right > 1.0 || bottom > 1.0
                    || left >= right || top >= bottom) {
                throw new IllegalArgumentException("invalid normalized region");
            }
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    public static final class AccountDetection {
        public final boolean enabled;
        public final boolean explicitPrefixAnywhere;
        public final boolean allowAtHandle;
        public final boolean allowFollowAnchoredHeader;
        public final boolean allowShortVideoHeaderName;
        public final boolean allowLiveHeaderName;
        public final boolean recoverConfiguredWhitelistFromAnchoredLine;
        public final boolean whitelistFuzzyRecoveryEnabled;
        public final int whitelistMaximumEditDistance;
        public final double whitelistMinimumCoverage;
        public final int whitelistFuzzyMinimumLength;
        public final int minimumLength;
        public final int maximumLength;
        public final double anchorMaximumDistance;
        public final double anchorMaximumVerticalDistance;
        public final double anchorMaximumOverlap;
        public final Pattern idAllowedPattern;
        public final List<String> followAnchorTerms;
        public final List<String> candidateExclusionTerms;
        public final List<NormalizedRegion> shortVideoRegions;
        public final List<NormalizedRegion> liveRegions;
        public final List<NormalizedRegion> unknownRegions;

        AccountDetection(JSONObject json) throws JSONException {
            enabled = json.getBoolean("enabled");
            explicitPrefixAnywhere = json.getBoolean("explicit_prefix_anywhere");
            allowAtHandle = json.getBoolean("allow_at_handle");
            allowFollowAnchoredHeader = json.getBoolean("allow_follow_anchored_header");
            allowShortVideoHeaderName = json.optBoolean("allow_short_video_header_name", false);
            allowLiveHeaderName = json.optBoolean("allow_live_header_name", false);
            recoverConfiguredWhitelistFromAnchoredLine = json.optBoolean(
                    "recover_configured_whitelist_from_anchored_line", true);
            whitelistFuzzyRecoveryEnabled = json.optBoolean(
                    "whitelist_fuzzy_recovery_enabled", true);
            whitelistMaximumEditDistance = (int) boundedLong(json.optInt(
                            "whitelist_maximum_edit_distance", 1),
                    0, 3, "account whitelist_maximum_edit_distance");
            whitelistMinimumCoverage = bounded(json.optDouble(
                            "whitelist_minimum_coverage", 0.75),
                    0.50, 1.0, "account whitelist_minimum_coverage");
            whitelistFuzzyMinimumLength = (int) boundedLong(json.optInt(
                            "whitelist_fuzzy_minimum_length", 4),
                    2, 16, "account whitelist_fuzzy_minimum_length");
            minimumLength = (int) boundedLong(json.getInt("minimum_length"),
                    1, 32, "account minimum_length");
            maximumLength = (int) boundedLong(json.getInt("maximum_length"),
                    minimumLength, 128, "account maximum_length");
            anchorMaximumDistance = bounded(json.getDouble("anchor_maximum_distance"),
                    0.01, 0.50, "account anchor_maximum_distance");
            anchorMaximumVerticalDistance = bounded(json.optDouble(
                            "anchor_maximum_vertical_distance", 0.025),
                    0.005, 0.20, "account anchor_maximum_vertical_distance");
            anchorMaximumOverlap = bounded(json.optDouble(
                            "anchor_maximum_overlap", 0.03),
                    0.0, 0.20, "account anchor_maximum_overlap");
            try {
                idAllowedPattern = Pattern.compile(
                        required(json, "id_allowed_regex"));
            } catch (PatternSyntaxException error) {
                throw new JSONException("account id_allowed_regex is invalid");
            }
            followAnchorTerms = immutableStrings(json.getJSONArray("follow_anchor_terms"));
            candidateExclusionTerms = immutableStrings(
                    json.getJSONArray("candidate_exclusion_terms"));
            shortVideoRegions = regions(json.getJSONArray("short_video_regions"),
                    "short video account region");
            liveRegions = regions(json.getJSONArray("live_regions"),
                    "live account region");
            unknownRegions = regions(json.getJSONArray("unknown_regions"),
                    "unknown account region");
        }

        public AccountDetection(boolean allowAtHandle, boolean allowFollowAnchoredHeader,
                int minimumLength, int maximumLength, double anchorMaximumDistance,
                List<String> followAnchorTerms, List<String> candidateExclusionTerms,
                List<NormalizedRegion> shortVideoRegions,
                List<NormalizedRegion> liveRegions,
                List<NormalizedRegion> unknownRegions) {
            this(allowAtHandle, allowFollowAnchoredHeader, false, false,
                    minimumLength, maximumLength, anchorMaximumDistance, followAnchorTerms,
                    candidateExclusionTerms, shortVideoRegions, liveRegions, unknownRegions);
        }

        public AccountDetection(boolean allowAtHandle, boolean allowFollowAnchoredHeader,
                boolean allowShortVideoHeaderName, boolean allowLiveHeaderName,
                int minimumLength, int maximumLength, double anchorMaximumDistance,
                List<String> followAnchorTerms, List<String> candidateExclusionTerms,
                List<NormalizedRegion> shortVideoRegions,
                List<NormalizedRegion> liveRegions,
                List<NormalizedRegion> unknownRegions) {
            enabled = true;
            explicitPrefixAnywhere = false;
            this.allowAtHandle = allowAtHandle;
            this.allowFollowAnchoredHeader = allowFollowAnchoredHeader;
            this.allowShortVideoHeaderName = allowShortVideoHeaderName;
            this.allowLiveHeaderName = allowLiveHeaderName;
            this.recoverConfiguredWhitelistFromAnchoredLine = true;
            this.whitelistFuzzyRecoveryEnabled = true;
            this.whitelistMaximumEditDistance = 1;
            this.whitelistMinimumCoverage = 0.75;
            this.whitelistFuzzyMinimumLength = 4;
            this.minimumLength = minimumLength;
            this.maximumLength = maximumLength;
            this.anchorMaximumDistance = anchorMaximumDistance;
            this.anchorMaximumVerticalDistance = Math.min(0.025, anchorMaximumDistance);
            this.anchorMaximumOverlap = 0.03;
            this.idAllowedPattern = Pattern.compile(
                    "(?i)[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*");
            this.followAnchorTerms = immutableCopy(followAnchorTerms);
            this.candidateExclusionTerms = immutableCopy(candidateExclusionTerms);
            this.shortVideoRegions = immutableCopy(shortVideoRegions);
            this.liveRegions = immutableCopy(liveRegions);
            this.unknownRegions = immutableCopy(unknownRegions);
        }

        public List<NormalizedRegion> regions(MediaKind kind) {
            if (kind == MediaKind.SHORT_VIDEO) return shortVideoRegions;
            if (kind == MediaKind.LIVE) return liveRegions;
            return unknownRegions;
        }

        private static List<NormalizedRegion> regions(JSONArray values, String label)
                throws JSONException {
            List<NormalizedRegion> result = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                result.add(new NormalizedRegion(values.getJSONObject(i), label));
            }
            if (result.isEmpty()) throw new JSONException(label + " is empty");
            return Collections.unmodifiableList(result);
        }
    }

    public static final class RiskBand {
        public final double threshold;
        public final Action action;

        RiskBand(JSONObject json, String label) throws JSONException {
            threshold = bounded(json.getDouble("threshold"), 0.01, 0.99,
                    label + " threshold");
            try {
                action = Action.valueOf(json.getString("action"));
            } catch (IllegalArgumentException error) {
                throw new JSONException(label + " action is invalid");
            }
        }

        public RiskBand(double threshold, Action action) {
            this.threshold = checked(threshold, 0.01, 0.99, "threshold");
            if (action == null) throw new IllegalArgumentException("action is null");
            this.action = action;
        }
    }

    public static final class MediaPolicy {
        public final boolean enabled;
        public final long captureIntervalMs;
        public final long ocrIntervalMs;
        public final int evidenceFrames;
        public final long evidenceResetGapMs;
        public final EvidenceAggregation evidenceAggregation;
        public final boolean whitelistEnabled;
        public final double riskBias;
        public final RiskBand low;
        public final RiskBand medium;
        public final RiskBand high;

        MediaPolicy(JSONObject json) throws JSONException {
            enabled = json.getBoolean("enabled");
            captureIntervalMs = boundedLong(json.getLong("capture_interval_ms"),
                    500, 30000, "media capture_interval_ms");
            ocrIntervalMs = boundedLong(json.getLong("ocr_interval_ms"),
                    500, 60000, "media ocr_interval_ms");
            evidenceFrames = (int) boundedLong(json.getInt("evidence_frames"),
                    1, 20, "evidence_frames");
            evidenceResetGapMs = boundedLong(json.getLong("evidence_reset_gap_ms"),
                    1000, 120000, "evidence_reset_gap_ms");
            try {
                evidenceAggregation = EvidenceAggregation.valueOf(
                        json.getString("evidence_aggregation"));
            } catch (IllegalArgumentException error) {
                throw new JSONException("evidence_aggregation is invalid");
            }
            whitelistEnabled = json.getBoolean("whitelist_enabled");
            riskBias = bounded(json.getDouble("risk_bias"), -1.0, 1.0, "risk_bias");
            low = new RiskBand(json.getJSONObject("low"), "low");
            medium = new RiskBand(json.getJSONObject("medium"), "medium");
            high = new RiskBand(json.getJSONObject("high"), "high");
            if (!(low.threshold <= medium.threshold && medium.threshold <= high.threshold)) {
                throw new JSONException("risk thresholds must be ordered low <= medium <= high");
            }
        }

        /** Compatibility constructor used by policy regression tests. */
        public MediaPolicy(Action action, double threshold, double riskBias) {
            enabled = true;
            captureIntervalMs = 1500;
            ocrIntervalMs = 3000;
            evidenceFrames = 4;
            evidenceResetGapMs = 8000;
            evidenceAggregation = EvidenceAggregation.MAX;
            whitelistEnabled = true;
            this.riskBias = checked(riskBias, -1.0, 1.0, "risk_bias");
            low = new RiskBand(threshold, action);
            medium = new RiskBand(threshold, action);
            high = new RiskBand(threshold, action);
        }

        public Action actionFor(double score) {
            if (!enabled) return Action.IGNORE;
            if (score >= high.threshold) return high.action;
            if (score >= medium.threshold) return medium.action;
            if (score >= low.threshold) return low.action;
            return Action.IGNORE;
        }

        public double minimumActionThreshold() {
            double result = 1.01;
            if (low.action != Action.IGNORE) result = Math.min(result, low.threshold);
            if (medium.action != Action.IGNORE) result = Math.min(result, medium.threshold);
            if (high.action != Action.IGNORE) result = Math.min(result, high.threshold);
            return result;
        }
    }

    public static final class Platform {
        public final String id;
        public final String name;
        public final boolean enabled;
        public final Set<String> packages;
        public final Set<String> whitelistIds;
        public final WhitelistMatchMode whitelistMatchMode;
        public final AccountDetection accountDetection;
        public final double riskBias;
        public final MediaPolicy shortVideo;
        public final MediaPolicy live;
        public final MediaPolicy unknown;

        Platform(JSONObject json) throws JSONException {
            id = required(json, "id");
            name = required(json, "name");
            enabled = json.getBoolean("enabled");
            packages = immutableSet(json.getJSONArray("packages"));
            if (packages.isEmpty()) throw new JSONException("platform packages empty");
            whitelistIds = immutableSet(json.getJSONArray("whitelist_ids"));
            try {
                whitelistMatchMode = WhitelistMatchMode.valueOf(
                        json.optString("whitelist_match_mode", "EXACT"));
            } catch (IllegalArgumentException error) {
                throw new JSONException("whitelist_match_mode is invalid");
            }
            JSONObject accountDetectionJson = json.optJSONObject("account_detection");
            accountDetection = accountDetectionJson == null ? null
                    : new AccountDetection(accountDetectionJson);
            riskBias = bounded(json.getDouble("risk_bias"), -1.0, 1.0,
                    "platform risk_bias");
            shortVideo = new MediaPolicy(json.getJSONObject("short_video"));
            live = new MediaPolicy(json.getJSONObject("live"));
            unknown = new MediaPolicy(json.getJSONObject("unknown"));
        }

        public Platform(String id, String name, boolean enabled, Set<String> packages,
                Set<String> whitelistIds, double riskBias, MediaPolicy shortVideo,
                MediaPolicy live, MediaPolicy unknown) {
            this(id, name, enabled, packages, whitelistIds, riskBias, shortVideo, live,
                    unknown, null);
        }

        public Platform(String id, String name, boolean enabled, Set<String> packages,
                Set<String> whitelistIds, double riskBias, MediaPolicy shortVideo,
                MediaPolicy live, MediaPolicy unknown, AccountDetection accountDetection) {
            this(id, name, enabled, packages, whitelistIds, riskBias,
                    shortVideo, live, unknown, accountDetection, WhitelistMatchMode.EXACT);
        }

        public Platform(String id, String name, boolean enabled, Set<String> packages,
                Set<String> whitelistIds, double riskBias, MediaPolicy shortVideo,
                MediaPolicy live, MediaPolicy unknown, AccountDetection accountDetection,
                WhitelistMatchMode whitelistMatchMode) {
            if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Platform id/name must not be empty");
            }
            this.id = id.trim();
            this.name = name.trim();
            this.enabled = enabled;
            this.packages = immutableCopy(packages);
            this.whitelistIds = immutableCopy(whitelistIds);
            this.whitelistMatchMode = whitelistMatchMode == null
                    ? WhitelistMatchMode.EXACT : whitelistMatchMode;
            this.accountDetection = accountDetection;
            this.riskBias = checked(riskBias, -1.0, 1.0, "platform risk_bias");
            this.shortVideo = shortVideo;
            this.live = live;
            this.unknown = unknown;
        }

        public MediaPolicy policy(MediaKind kind) {
            if (kind == MediaKind.LIVE) return live;
            if (kind == MediaKind.SHORT_VIDEO) return shortVideo;
            return unknown;
        }

        public boolean whitelistMatches(String candidate) {
            if (candidate == null || candidate.isEmpty()) return false;
            for (String configured : whitelistIds) {
                if (configured == null || configured.isEmpty()) continue;
                if (whitelistMatchMode == WhitelistMatchMode.EXACT
                        && configured.equals(candidate)) return true;
                if (whitelistMatchMode == WhitelistMatchMode.PREFIX
                        && candidate.startsWith(configured)) return true;
                if (whitelistMatchMode == WhitelistMatchMode.CONTAINS
                        && candidate.contains(configured)) return true;
            }
            return false;
        }
    }

    public static final class RuntimeRule {
        public final String id;
        public final boolean enabled;
        public final double riskBias;
        public final List<List<String>> requiredTermGroups;

        RuntimeRule(JSONObject json) throws JSONException {
            id = required(json, "id");
            enabled = json.getBoolean("enabled");
            riskBias = bounded(json.getDouble("risk_bias"), -1.0, 1.0,
                    "runtime rule risk_bias");
            JSONArray groups = json.getJSONArray("required_term_groups");
            List<List<String>> parsed = new ArrayList<>();
            for (int i = 0; i < groups.length(); i++) {
                List<String> group = strings(groups.getJSONArray(i));
                if (group.isEmpty()) throw new JSONException("runtime rule term group empty");
                parsed.add(Collections.unmodifiableList(group));
            }
            if (parsed.isEmpty()) throw new JSONException("runtime rule groups empty");
            requiredTermGroups = Collections.unmodifiableList(parsed);
        }

        public boolean matches(String text) {
            if (!enabled) return false;
            String value = text == null ? "" : text;
            for (List<String> group : requiredTermGroups) {
                boolean matched = false;
                for (String term : group) {
                    if (!term.isEmpty() && value.contains(term)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) return false;
            }
            return true;
        }
    }

    public final int version;
    public final boolean protectionEnabled;
    public final String activePerformanceProfile;
    public final boolean localTechnicalStatusEnabled;
    public final boolean statusNotificationEnabled;
    public final boolean vendorLiveActivityEnabled;
    public final boolean notificationQuickStop;
    public final boolean statusOverlayEnabled;
    public final boolean statusOverlayShowOutsideTargets;
    public final double statusOverlayOpacity;
    public final boolean contentIdentityEnabled;
    public final double contentVisualChangeThreshold;
    public final double contentCandidateSimilarityThreshold;
    public final int contentConfirmationFrames;
    public final long contentMinimumResetIntervalMs;
    public final boolean contentTargetScrollEnabled;
    public final boolean contentTargetScrollRequiresRecentTouch;
    public final long contentTargetScrollRecentTouchWindowMs;
    public final long contentTargetScrollDebounceMs;
    public final boolean contentTargetScrollRequiresMainPageEvidence;
    public final boolean contentTargetScrollAllowMainPageWithoutTouch;
    public final double contentTargetScrollMinimumSourceWidthRatio;
    public final double contentTargetScrollMinimumSourceHeightRatio;
    public final double contentTargetScrollMaximumHorizontalDeltaRatio;
    public final boolean contentTargetScrollAllowUnknownDirection;
    public final boolean contentTargetScrollAllowMissingSourceWhenHeld;
    public final boolean contentTargetScrollAllowMissingSourceUnknownDirectionWhenHeld;
    public final boolean contentReleaseHoldEnabled;
    public final boolean contentReleaseHoldAfterWhitelist;
    public final boolean contentReleaseHoldAfterDisabledMediaPolicy;
    public final boolean contentReleaseWakeOnTargetScroll;
    public final long captureIntervalMs;
    public final long ocrIntervalMs;
    public final long ocrTimeoutMs;
    public final long swipeCooldownMs;
    public final long swipeDurationMs;
    public final double swipeXRatio;
    public final double swipeStartYRatio;
    public final double swipeEndYRatio;
    public final boolean swipeVerificationEnabled;
    /** Automatic swipe is gated on a confirmed media kind when enabled. */
    public final boolean swipeRequiresKnownMedia;
    public final long swipeVerificationTimeoutMs;
    public final double swipeVerificationChangeThreshold;
    public final double swipeVerificationCandidateSimilarityThreshold;
    public final int swipeVerificationConfirmationFrames;
    public final int swipeVerificationMaximumRetries;
    public final long swipeVerificationFailureCircuitBreakerMs;
    public final long swipeAvoidUserTouchMs;
    public final int maxConsecutiveErrors;
    public final long errorPauseMs;
    public final boolean pauseOnBatteryLow;
    public final int minimumBatteryPercentWhileNotCharging;
    public final long foregroundEvidenceTtlMs;
    public final boolean usageStatsFallbackEnabled;
    public final long usageEventLookbackMs;
    public final long usageEventMaxAgeMs;
    public final Set<String> ignoredOverlayPackages;
    public final boolean fullscreenWindowEnabled;
    public final boolean splitScreenWindowEnabled;
    public final boolean pictureInPictureWindowEnabled;
    public final boolean floatingWindowEnabled;
    public final boolean unknownWindowModeEnabled;
    public final boolean requireTargetWindowFocused;
    public final double minimumFullscreenWidthRatio;
    public final double minimumFullscreenHeightRatio;
    public final double minimumSplitSpanRatio;
    public final double minimumSplitAreaRatio;
    public final double maximumSplitAreaRatio;
    public final double windowBoundsChangeToleranceRatio;
    public final double gestureEdgePaddingRatio;
    public final int performanceSampleCount;
    public final int performanceOcrSampleCount;
    public final long performanceSustainedDurationMs;
    public final long recommendedP95Ms;
    public final long degradedP95Ms;
    public final long recommendedOcrP95Ms;
    public final long degradedOcrP95Ms;
    public final long recommendedPeakPssMb;
    public final long degradedPeakPssMb;
    public final int severeThermalStatus;
    public final double degradedCaptureIntervalMultiplier;
    public final double degradedOcrIntervalMultiplier;
    public final int degradedEvidenceFramesDelta;
    public final boolean globalPurchaseStandaloneEnabled;
    public final double globalPurchaseBias;
    public final List<String> globalPurchaseTerms;
    public final double negativeContextBias;
    public final List<String> negativeContextTerms;
    public final List<String> liveTerms;
    public final List<String> shortVideoTerms;
    public final List<String> accountIdPrefixes;
    public final String rulePriceRegex;
    public final List<String> ruleHealthTerms;
    public final List<String> ruleSalesTerms;
    public final List<String> ruleElderlyTerms;
    public final List<String> ruleNegativeContextTerms;
    public final List<String> ruleShoppingCartTerms;
    public final List<String> ruleOrderPromptTerms;
    public final List<String> ruleCollectorOverlayTerms;
    public final List<String> ruleBlackOcclusionTerms;
    public final List<String> ruleLoadingOrBlankTerms;
    public final List<RuntimeRule> runtimeRules;
    public final List<Platform> platforms;

    public GuardConfig(JSONObject json) throws JSONException {
        if (!"gravekeeper_runtime_config".equals(json.getString("format"))) {
            throw new JSONException("Unsupported config format");
        }
        version = json.getInt("version");
        protectionEnabled = json.getBoolean("protection_enabled");
        activePerformanceProfile = json.getString("active_performance_profile");
        if (!("NORMAL".equals(activePerformanceProfile)
                || "DEGRADED".equals(activePerformanceProfile)
                || "CUSTOM".equals(activePerformanceProfile))) {
            throw new JSONException("active_performance_profile is invalid");
        }
        localTechnicalStatusEnabled = json.getBoolean("local_technical_status_enabled");
        statusNotificationEnabled = json.getBoolean("status_notification_enabled");
        vendorLiveActivityEnabled = json.getBoolean("vendor_live_activity_enabled");
        notificationQuickStop = json.getBoolean("notification_quick_stop");
        JSONObject statusOverlay = json.optJSONObject("status_overlay");
        statusOverlayEnabled = statusOverlay != null
                && statusOverlay.optBoolean("enabled", false);
        statusOverlayShowOutsideTargets = statusOverlay != null
                && statusOverlay.optBoolean("show_outside_targets", false);
        statusOverlayOpacity = bounded(statusOverlay == null ? 0.88
                        : statusOverlay.optDouble("opacity", 0.88),
                0.20, 1.0, "status overlay opacity");
        JSONObject contentIdentity = json.optJSONObject("content_identity");
        contentIdentityEnabled = contentIdentity == null
                || contentIdentity.optBoolean("enabled", true);
        contentVisualChangeThreshold = bounded(contentIdentity == null ? 0.24
                : contentIdentity.getDouble("visual_change_threshold"), 0.02, 0.80,
                "content visual_change_threshold");
        contentCandidateSimilarityThreshold = bounded(contentIdentity == null ? 0.12
                : contentIdentity.getDouble("candidate_similarity_threshold"), 0.01, 0.50,
                "content candidate_similarity_threshold");
        contentConfirmationFrames = (int) boundedLong(contentIdentity == null ? 2
                : contentIdentity.getInt("confirmation_frames"), 1, 5,
                "content confirmation_frames");
        contentMinimumResetIntervalMs = boundedLong(contentIdentity == null ? 1200
                : contentIdentity.getLong("minimum_reset_interval_ms"), 0, 30000,
                "content minimum_reset_interval_ms");
        JSONObject contentChangeEvents = json.optJSONObject("content_change_events");
        JSONObject targetScroll = contentChangeEvents == null ? null
                : contentChangeEvents.optJSONObject("target_scroll");
        contentTargetScrollEnabled = targetScroll == null
                || targetScroll.optBoolean("enabled", true);
        contentTargetScrollRequiresRecentTouch = targetScroll == null
                || targetScroll.optBoolean("requires_recent_touch", true);
        contentTargetScrollRecentTouchWindowMs = boundedLong(targetScroll == null ? 800
                        : targetScroll.optLong("recent_touch_window_ms", 800),
                0, 10000, "target scroll recent_touch_window_ms");
        contentTargetScrollDebounceMs = boundedLong(targetScroll == null ? 900
                        : targetScroll.optLong("debounce_ms", 900),
                0, 10000, "target scroll debounce_ms");
        contentTargetScrollRequiresMainPageEvidence = targetScroll == null
                || targetScroll.optBoolean("requires_main_page_evidence", true);
        contentTargetScrollAllowMainPageWithoutTouch = targetScroll == null
                || targetScroll.optBoolean("allow_main_page_without_touch", true);
        contentTargetScrollMinimumSourceWidthRatio = bounded(targetScroll == null ? 0.70
                        : targetScroll.optDouble("minimum_source_width_ratio", 0.70),
                0.20, 1.0, "target scroll minimum_source_width_ratio");
        contentTargetScrollMinimumSourceHeightRatio = bounded(targetScroll == null ? 0.80
                        : targetScroll.optDouble("minimum_source_height_ratio", 0.80),
                0.20, 1.0, "target scroll minimum_source_height_ratio");
        contentTargetScrollMaximumHorizontalDeltaRatio = bounded(targetScroll == null ? 0.75
                        : targetScroll.optDouble("maximum_horizontal_delta_ratio", 0.75),
                0.0, 1.0, "target scroll maximum_horizontal_delta_ratio");
        contentTargetScrollAllowUnknownDirection = targetScroll == null
                || targetScroll.optBoolean("allow_unknown_direction", true);
        contentTargetScrollAllowMissingSourceWhenHeld = targetScroll == null
                || targetScroll.optBoolean("allow_missing_source_when_held", true);
        contentTargetScrollAllowMissingSourceUnknownDirectionWhenHeld = targetScroll == null
                || targetScroll.optBoolean(
                        "allow_missing_source_unknown_direction_when_held", true);
        JSONObject contentReleaseHold = json.optJSONObject("content_release_hold");
        contentReleaseHoldEnabled = contentReleaseHold == null
                || contentReleaseHold.optBoolean("enabled", true);
        contentReleaseHoldAfterWhitelist = contentReleaseHold == null
                || contentReleaseHold.optBoolean("hold_after_whitelist", true);
        contentReleaseHoldAfterDisabledMediaPolicy = contentReleaseHold == null
                || contentReleaseHold.optBoolean(
                        "hold_after_disabled_media_policy", true);
        contentReleaseWakeOnTargetScroll = contentReleaseHold == null
                || contentReleaseHold.optBoolean("wake_on_target_scroll", true);
        if (notificationQuickStop
                && !statusNotificationEnabled && !vendorLiveActivityEnabled) {
            throw new JSONException("notification quick stop requires status notification");
        }
        captureIntervalMs = boundedLong(json.getLong("capture_interval_ms"),
                500, 30000, "capture_interval_ms");
        ocrIntervalMs = boundedLong(json.getLong("ocr_interval_ms"),
                500, 60000, "ocr_interval_ms");
        ocrTimeoutMs = boundedLong(json.getLong("ocr_timeout_ms"),
                300, 10000, "ocr_timeout_ms");
        swipeCooldownMs = boundedLong(json.getLong("swipe_cooldown_ms"),
                500, 30000, "swipe_cooldown_ms");
        swipeDurationMs = boundedLong(json.getLong("swipe_duration_ms"),
                100, 3000, "swipe_duration_ms");
        JSONObject swipeGesture = json.getJSONObject("swipe_gesture");
        swipeXRatio = bounded(swipeGesture.getDouble("x_ratio"), 0.05, 0.95,
                "swipe x_ratio");
        swipeStartYRatio = bounded(swipeGesture.getDouble("start_y_ratio"), 0.05, 0.95,
                "swipe start_y_ratio");
        swipeEndYRatio = bounded(swipeGesture.getDouble("end_y_ratio"), 0.05, 0.95,
                "swipe end_y_ratio");
        if (swipeStartYRatio <= swipeEndYRatio) {
            throw new JSONException("swipe gesture must move upward");
        }
        JSONObject swipeVerification = json.optJSONObject("swipe_verification");
        swipeVerificationEnabled = swipeVerification == null
                || swipeVerification.optBoolean("enabled", true);
        swipeRequiresKnownMedia = swipeVerification == null
                || swipeVerification.optBoolean("swipe_requires_known_media", true);
        swipeVerificationTimeoutMs = boundedLong(swipeVerification == null ? 4500
                : swipeVerification.getLong("timeout_ms"), 1000, 30000,
                "swipe verification timeout_ms");
        swipeVerificationChangeThreshold = bounded(swipeVerification == null ? 0.24
                : swipeVerification.getDouble("visual_change_threshold"), 0.02, 0.80,
                "swipe verification visual_change_threshold");
        swipeVerificationCandidateSimilarityThreshold = bounded(
                swipeVerification == null ? 0.12
                        : swipeVerification.getDouble("candidate_similarity_threshold"),
                0.01, 0.50, "swipe verification candidate_similarity_threshold");
        swipeVerificationConfirmationFrames = (int) boundedLong(
                swipeVerification == null ? 2
                        : swipeVerification.getInt("confirmation_frames"),
                1, 5, "swipe verification confirmation_frames");
        swipeVerificationMaximumRetries = (int) boundedLong(swipeVerification == null ? 1
                : swipeVerification.getInt("maximum_retries"), 0, 3,
                "swipe verification maximum_retries");
        swipeVerificationFailureCircuitBreakerMs = boundedLong(
                swipeVerification == null ? 30000
                        : swipeVerification.getLong("failure_circuit_breaker_ms"),
                1000, 600000, "swipe verification failure_circuit_breaker_ms");
        swipeAvoidUserTouchMs = boundedLong(swipeVerification == null ? 900
                : swipeVerification.getLong("avoid_user_touch_ms"), 0, 10000,
                "swipe verification avoid_user_touch_ms");
        JSONObject loadProtection = json.getJSONObject("load_protection");
        maxConsecutiveErrors = (int) boundedLong(loadProtection.getInt(
                "max_consecutive_errors"), 1, 20, "max_consecutive_errors");
        errorPauseMs = boundedLong(loadProtection.getLong("error_pause_ms"),
                1000, 600000, "error_pause_ms");
        pauseOnBatteryLow = loadProtection.getBoolean("pause_on_battery_low");
        minimumBatteryPercentWhileNotCharging = (int) boundedLong(
                loadProtection.getInt("minimum_battery_percent_while_not_charging"),
                1, 50, "minimum_battery_percent_while_not_charging");
        JSONObject foreground = json.getJSONObject("foreground_detection");
        foregroundEvidenceTtlMs = boundedLong(foreground.getLong(
                "recent_target_evidence_ttl_ms"), 500, 86400000,
                "recent_target_evidence_ttl_ms");
        usageStatsFallbackEnabled = foreground.getBoolean("usage_stats_fallback_enabled");
        usageEventLookbackMs = boundedLong(foreground.getLong("usage_event_lookback_ms"),
                60000, 604800000, "usage_event_lookback_ms");
        usageEventMaxAgeMs = boundedLong(foreground.getLong("usage_event_max_age_ms"),
                1000, 300000, "usage_event_max_age_ms");
        ignoredOverlayPackages = immutableSet(
                foreground.getJSONArray("ignored_overlay_packages"));

        JSONObject multiWindow = json.optJSONObject("multi_window");
        fullscreenWindowEnabled = multiWindow == null
                || multiWindow.optBoolean("fullscreen_enabled", true);
        splitScreenWindowEnabled = multiWindow == null
                || multiWindow.optBoolean("split_screen_enabled", true);
        pictureInPictureWindowEnabled = multiWindow != null
                && multiWindow.optBoolean("picture_in_picture_enabled", false);
        floatingWindowEnabled = multiWindow != null
                && multiWindow.optBoolean("floating_window_enabled", false);
        unknownWindowModeEnabled = multiWindow != null
                && multiWindow.optBoolean("unknown_mode_enabled", false);
        requireTargetWindowFocused = multiWindow == null
                || multiWindow.optBoolean("require_target_window_focused", true);
        minimumFullscreenWidthRatio = bounded(multiWindow == null ? 0.90
                        : multiWindow.getDouble("minimum_fullscreen_width_ratio"),
                0.50, 1.0, "minimum_fullscreen_width_ratio");
        minimumFullscreenHeightRatio = bounded(multiWindow == null ? 0.82
                        : multiWindow.getDouble("minimum_fullscreen_height_ratio"),
                0.50, 1.0, "minimum_fullscreen_height_ratio");
        minimumSplitSpanRatio = bounded(multiWindow == null ? 0.88
                        : multiWindow.getDouble("minimum_split_span_ratio"),
                0.50, 1.0, "minimum_split_span_ratio");
        minimumSplitAreaRatio = bounded(multiWindow == null ? 0.25
                        : multiWindow.getDouble("minimum_split_area_ratio"),
                0.05, 0.75, "minimum_split_area_ratio");
        maximumSplitAreaRatio = bounded(multiWindow == null ? 0.75
                        : multiWindow.getDouble("maximum_split_area_ratio"),
                minimumSplitAreaRatio, 0.95, "maximum_split_area_ratio");
        windowBoundsChangeToleranceRatio = bounded(multiWindow == null ? 0.03
                        : multiWindow.getDouble("bounds_change_tolerance_ratio"),
                0.001, 0.25, "bounds_change_tolerance_ratio");
        gestureEdgePaddingRatio = bounded(multiWindow == null ? 0.04
                        : multiWindow.getDouble("gesture_edge_padding_ratio"),
                0.0, 0.20, "gesture_edge_padding_ratio");

        JSONObject performance = json.getJSONObject("performance");
        performanceSampleCount = (int) boundedLong(performance.getInt("sample_count"),
                5, 200, "sample_count");
        performanceOcrSampleCount = (int) boundedLong(performance.getInt("ocr_sample_count"),
                1, 20, "ocr_sample_count");
        performanceSustainedDurationMs = boundedLong(performance.getLong(
                "sustained_duration_ms"), 2000, 120000, "sustained_duration_ms");
        recommendedP95Ms = boundedLong(performance.getLong("recommended_p95_ms"),
                100, 10000, "recommended_p95_ms");
        degradedP95Ms = boundedLong(performance.getLong("degraded_p95_ms"),
                recommendedP95Ms, 30000, "degraded_p95_ms");
        recommendedOcrP95Ms = boundedLong(performance.getLong("recommended_ocr_p95_ms"),
                100, 10000, "recommended_ocr_p95_ms");
        degradedOcrP95Ms = boundedLong(performance.getLong("degraded_ocr_p95_ms"),
                recommendedOcrP95Ms, 30000, "degraded_ocr_p95_ms");
        recommendedPeakPssMb = boundedLong(performance.getLong("recommended_peak_pss_mb"),
                64, 4096, "recommended_peak_pss_mb");
        degradedPeakPssMb = boundedLong(performance.getLong("degraded_peak_pss_mb"),
                recommendedPeakPssMb, 8192, "degraded_peak_pss_mb");
        severeThermalStatus = (int) boundedLong(performance.getInt(
                "severe_thermal_status"), 0, 6, "severe_thermal_status");
        JSONObject degradedAdvice = performance.getJSONObject("degraded_profile_advice");
        degradedCaptureIntervalMultiplier = bounded(degradedAdvice.getDouble(
                "capture_interval_multiplier"), 1.0, 10.0,
                "capture_interval_multiplier");
        degradedOcrIntervalMultiplier = bounded(degradedAdvice.getDouble(
                "ocr_interval_multiplier"), 1.0, 10.0,
                "ocr_interval_multiplier");
        degradedEvidenceFramesDelta = (int) boundedLong(degradedAdvice.getInt(
                "evidence_frames_delta"), -19, 19, "evidence_frames_delta");

        JSONObject signals = json.getJSONObject("signals");
        globalPurchaseStandaloneEnabled = signals.getBoolean(
                "global_purchase_standalone_enabled");
        globalPurchaseBias = bounded(signals.getDouble("global_purchase_bias"),
                -1.0, 1.0, "global_purchase_bias");
        globalPurchaseTerms = immutableStrings(signals.getJSONArray("global_purchase_terms"));
        negativeContextBias = bounded(signals.getDouble("negative_context_bias"),
                -1.0, 1.0, "negative_context_bias");
        negativeContextTerms = immutableStrings(
                signals.getJSONArray("negative_context_terms"));
        liveTerms = immutableStrings(signals.getJSONArray("live_terms"));
        shortVideoTerms = immutableStrings(signals.getJSONArray("short_video_terms"));
        accountIdPrefixes = immutableStrings(signals.getJSONArray("account_id_prefixes"));
        JSONObject ruleFeatures = signals.getJSONObject("fusion_rule_features");
        rulePriceRegex = required(ruleFeatures, "price_regex");
        try {
            Pattern.compile(rulePriceRegex);
        } catch (PatternSyntaxException error) {
            throw new JSONException("fusion rule price_regex is invalid");
        }
        ruleHealthTerms = immutableStrings(ruleFeatures.getJSONArray("health_terms"));
        ruleSalesTerms = immutableStrings(ruleFeatures.getJSONArray("sales_terms"));
        ruleElderlyTerms = immutableStrings(ruleFeatures.getJSONArray("elderly_terms"));
        ruleNegativeContextTerms = immutableStrings(
                ruleFeatures.getJSONArray("negative_context_terms"));
        ruleShoppingCartTerms = immutableStrings(
                ruleFeatures.getJSONArray("shopping_cart_terms"));
        ruleOrderPromptTerms = immutableStrings(
                ruleFeatures.getJSONArray("order_prompt_terms"));
        ruleCollectorOverlayTerms = immutableStrings(
                ruleFeatures.getJSONArray("collector_overlay_terms"));
        ruleBlackOcclusionTerms = immutableStrings(
                ruleFeatures.getJSONArray("black_occlusion_terms"));
        ruleLoadingOrBlankTerms = immutableStrings(
                ruleFeatures.getJSONArray("loading_or_blank_terms"));
        JSONArray rulesJson = signals.getJSONArray("runtime_rules");
        List<RuntimeRule> parsedRules = new ArrayList<>();
        for (int i = 0; i < rulesJson.length(); i++) {
            parsedRules.add(new RuntimeRule(rulesJson.getJSONObject(i)));
        }
        runtimeRules = Collections.unmodifiableList(parsedRules);

        JSONArray platformJson = json.getJSONArray("platforms");
        List<Platform> parsedPlatforms = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> packages = new HashSet<>();
        for (int i = 0; i < platformJson.length(); i++) {
            Platform platform = new Platform(platformJson.getJSONObject(i));
            if (!ids.add(platform.id)) throw new JSONException("duplicate platform id");
            for (String packageName : platform.packages) {
                if (!packages.add(packageName)) {
                    throw new JSONException("package assigned to multiple platforms: " + packageName);
                }
            }
            parsedPlatforms.add(platform);
        }
        if (parsedPlatforms.isEmpty()) throw new JSONException("At least one platform is required");
        platforms = Collections.unmodifiableList(parsedPlatforms);
    }

    /** Compact constructor retained for pure JVM policy tests. */
    public GuardConfig(double globalPurchaseBias, List<String> globalPurchaseTerms,
            double negativeContextBias, List<String> negativeContextTerms,
            List<String> liveTerms, List<String> shortVideoTerms,
            List<String> accountIdPrefixes, List<Platform> platforms) {
        version = 14;
        protectionEnabled = true;
        activePerformanceProfile = "NORMAL";
        localTechnicalStatusEnabled = true;
        statusNotificationEnabled = false;
        vendorLiveActivityEnabled = false;
        notificationQuickStop = false;
        statusOverlayEnabled = false;
        statusOverlayShowOutsideTargets = false;
        statusOverlayOpacity = 0.88;
        contentIdentityEnabled = true;
        contentVisualChangeThreshold = 0.24;
        contentCandidateSimilarityThreshold = 0.12;
        contentConfirmationFrames = 2;
        contentMinimumResetIntervalMs = 1200;
        contentTargetScrollEnabled = true;
        contentTargetScrollRequiresRecentTouch = true;
        contentTargetScrollRecentTouchWindowMs = 800;
        contentTargetScrollDebounceMs = 900;
        contentTargetScrollRequiresMainPageEvidence = true;
        contentTargetScrollAllowMainPageWithoutTouch = true;
        contentTargetScrollMinimumSourceWidthRatio = 0.70;
        contentTargetScrollMinimumSourceHeightRatio = 0.80;
        contentTargetScrollMaximumHorizontalDeltaRatio = 0.75;
        contentTargetScrollAllowUnknownDirection = true;
        contentTargetScrollAllowMissingSourceWhenHeld = true;
        contentTargetScrollAllowMissingSourceUnknownDirectionWhenHeld = true;
        contentReleaseHoldEnabled = true;
        contentReleaseHoldAfterWhitelist = true;
        contentReleaseHoldAfterDisabledMediaPolicy = true;
        contentReleaseWakeOnTargetScroll = true;
        captureIntervalMs = 1500;
        ocrIntervalMs = 3000;
        ocrTimeoutMs = 1800;
        swipeCooldownMs = 1800;
        swipeDurationMs = 350;
        swipeXRatio = 0.5;
        swipeStartYRatio = 0.75;
        swipeEndYRatio = 0.25;
        swipeVerificationEnabled = true;
        swipeRequiresKnownMedia = true;
        swipeVerificationTimeoutMs = 4500;
        swipeVerificationChangeThreshold = 0.24;
        swipeVerificationCandidateSimilarityThreshold = 0.12;
        swipeVerificationConfirmationFrames = 2;
        swipeVerificationMaximumRetries = 1;
        swipeVerificationFailureCircuitBreakerMs = 30000;
        swipeAvoidUserTouchMs = 900;
        maxConsecutiveErrors = 3;
        errorPauseMs = 30000;
        pauseOnBatteryLow = true;
        minimumBatteryPercentWhileNotCharging = 10;
        foregroundEvidenceTtlMs = 120000;
        usageStatsFallbackEnabled = true;
        usageEventLookbackMs = 86400000;
        usageEventMaxAgeMs = 15000;
        ignoredOverlayPackages = immutableCopy(Set.of("android", "com.android.systemui"));
        fullscreenWindowEnabled = true;
        splitScreenWindowEnabled = true;
        pictureInPictureWindowEnabled = false;
        floatingWindowEnabled = false;
        unknownWindowModeEnabled = false;
        requireTargetWindowFocused = true;
        minimumFullscreenWidthRatio = 0.90;
        minimumFullscreenHeightRatio = 0.82;
        minimumSplitSpanRatio = 0.88;
        minimumSplitAreaRatio = 0.25;
        maximumSplitAreaRatio = 0.75;
        windowBoundsChangeToleranceRatio = 0.03;
        gestureEdgePaddingRatio = 0.04;
        performanceSampleCount = 5;
        performanceOcrSampleCount = 1;
        performanceSustainedDurationMs = 2000;
        recommendedP95Ms = 900;
        degradedP95Ms = 1400;
        recommendedOcrP95Ms = 1800;
        degradedOcrP95Ms = 3200;
        recommendedPeakPssMb = 650;
        degradedPeakPssMb = 900;
        severeThermalStatus = 4;
        degradedCaptureIntervalMultiplier = 1.5;
        degradedOcrIntervalMultiplier = 2.0;
        degradedEvidenceFramesDelta = -1;
        globalPurchaseStandaloneEnabled = true;
        this.globalPurchaseBias = checked(globalPurchaseBias, -1.0, 1.0,
                "global_purchase_bias");
        this.globalPurchaseTerms = immutableCopy(globalPurchaseTerms);
        this.negativeContextBias = checked(negativeContextBias, -1.0, 1.0,
                "negative_context_bias");
        this.negativeContextTerms = immutableCopy(negativeContextTerms);
        this.liveTerms = immutableCopy(liveTerms);
        this.shortVideoTerms = immutableCopy(shortVideoTerms);
        this.accountIdPrefixes = immutableCopy(accountIdPrefixes);
        rulePriceRegex = "(?:¥|￥|\\b)\\s*\\d+(?:\\.\\d{1,2})?\\s*元?";
        ruleHealthTerms = List.of("保健品");
        ruleSalesTerms = List.of("立即购买", "下单");
        ruleElderlyTerms = List.of("老人");
        ruleNegativeContextTerms = List.of("科普");
        ruleShoppingCartTerms = List.of("购物车");
        ruleOrderPromptTerms = List.of("立即购买");
        ruleCollectorOverlayTerms = List.of("采集器");
        ruleBlackOcclusionTerms = List.of("黑屏");
        ruleLoadingOrBlankTerms = List.of("加载中");
        runtimeRules = Collections.emptyList();
        if (platforms == null || platforms.isEmpty()) {
            throw new IllegalArgumentException("platforms empty");
        }
        this.platforms = Collections.unmodifiableList(new ArrayList<>(platforms));
    }

    public Platform findPlatform(String packageName) {
        if (packageName == null) return null;
        for (Platform platform : platforms) {
            if (platform.enabled && platform.packages.contains(packageName)) return platform;
        }
        return null;
    }

    public Set<String> enabledPackages() {
        Set<String> result = new HashSet<>();
        for (Platform platform : platforms) {
            if (platform.enabled) result.addAll(platform.packages);
        }
        return Collections.unmodifiableSet(result);
    }

    private static String required(JSONObject json, String key) throws JSONException {
        String value = json.getString(key).trim();
        if (value.isEmpty()) throw new JSONException(key + " must not be empty");
        return value;
    }

    private static Set<String> immutableSet(JSONArray array) throws JSONException {
        return Collections.unmodifiableSet(new HashSet<>(strings(array)));
    }

    private static List<String> immutableStrings(JSONArray array) throws JSONException {
        return Collections.unmodifiableList(strings(array));
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.getString(i).trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static double bounded(double value, double min, double max, String label)
            throws JSONException {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new JSONException(label + " out of range");
        }
        return value;
    }

    private static long boundedLong(long value, long min, long max, String label)
            throws JSONException {
        if (value < min || value > max) throw new JSONException(label + " out of range");
        return value;
    }

    private static double checked(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(label + " out of range");
        }
        return value;
    }

    private static <T> Set<T> immutableCopy(Set<T> values) {
        return Collections.unmodifiableSet(new HashSet<>(values));
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
