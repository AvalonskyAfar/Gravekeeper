package com.gravekeeper;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.gravekeeper.config.BundleValidator;
import com.gravekeeper.config.ConfigStore;
import com.gravekeeper.config.GuardConfig;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds a user-initiated, privacy-limited local support report. */
final class LocalDiagnosticReport {
    private LocalDiagnosticReport() {}

    static String build(Context context) {
        StringBuilder report = new StringBuilder();
        report.append(BrandIdentity.ENGLISH_NAME).append(" local diagnostic report\n");
        report.append("generated_at=").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date())).append('\n');
        report.append("app_version=").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        report.append("android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("abis=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');

        try {
            GuardConfig config = new ConfigStore(context).load();
            report.append("runtime_config_version=").append(config.version).append('\n');
            report.append("performance_profile=")
                    .append(config.activePerformanceProfile).append('\n');
            report.append("protection_enabled=")
                    .append(config.protectionEnabled).append('\n');
            report.append("content_release_hold_enabled=")
                    .append(config.contentReleaseHoldEnabled).append('\n');
            report.append("content_target_scroll_enabled=")
                    .append(config.contentTargetScrollEnabled).append('\n');
            report.append("content_scroll_requires_recent_touch=")
                    .append(config.contentTargetScrollRequiresRecentTouch).append('\n');
            report.append("content_scroll_touch_window_ms=")
                    .append(config.contentTargetScrollRecentTouchWindowMs).append('\n');
            report.append("content_scroll_debounce_ms=")
                    .append(config.contentTargetScrollDebounceMs).append('\n');
            report.append("content_scroll_requires_main_page_evidence=")
                    .append(config.contentTargetScrollRequiresMainPageEvidence).append('\n');
            report.append("content_scroll_allow_main_page_without_touch=")
                    .append(config.contentTargetScrollAllowMainPageWithoutTouch).append('\n');
            report.append("content_scroll_minimum_source_width_ratio=")
                    .append(config.contentTargetScrollMinimumSourceWidthRatio).append('\n');
            report.append("content_scroll_minimum_source_height_ratio=")
                    .append(config.contentTargetScrollMinimumSourceHeightRatio).append('\n');
            report.append("vendor_live_activity_enabled=")
                    .append(config.vendorLiveActivityEnabled).append('\n');
            report.append("live_activity_capability=")
                    .append(LiveStatusNotificationCompat.capabilitySummary(
                            context, config.vendorLiveActivityEnabled)).append('\n');
            report.append("developer_overlay_enabled=")
                    .append(config.statusOverlayEnabled).append('\n');
            report.append("split_screen_enabled=")
                    .append(config.splitScreenWindowEnabled).append('\n');
            report.append("floating_window_enabled=")
                    .append(config.floatingWindowEnabled).append('\n');
            report.append("picture_in_picture_enabled=")
                    .append(config.pictureInPictureWindowEnabled).append('\n');
        } catch (IOException error) {
            report.append("runtime_config=unavailable\n");
        }

        try {
            BundleValidator.ResourceBundle bundle = BundleValidator.active(context);
            report.append("bundle_version=").append(bundle.version).append('\n');
            report.append("bundle_slot=").append(bundle.slot).append('\n');
            report.append("bundle_fallback=").append(bundle.fallback).append('\n');
            report.append("candidate_id=").append(bundle.candidateId).append('\n');
            report.append("models_version=").append(bundle.modelsVersion).append('\n');
            report.append("rules_version=").append(bundle.rulesVersion).append('\n');
            report.append("bundle_runtime_config_version=")
                    .append(bundle.runtimeConfigVersion).append('\n');
            report.append("bundle_manifest_sha256=")
                    .append(bundle.manifestSha256).append('\n');
        } catch (IOException error) {
            report.append("bundle=unavailable\n");
        }

        report.append("accessibility_enabled=")
                .append(AccessibilityCapability.isEnabled(context)).append('\n');
        report.append("usage_access_enabled=")
                .append(UsageStatsForegroundResolver.hasAccess(context)).append('\n');
        boolean notificationGranted = Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        report.append("notification_permission=")
                .append(notificationGranted).append('\n');

        SharedPreferences performance = context.getSharedPreferences(
                "guard_performance", Context.MODE_PRIVATE);
        report.append("performance_level=")
                .append(performance.getString("level", "UNKNOWN")).append('\n');
        appendLong(report, "visual_p50_ms", performance, "p50_ms");
        appendLong(report, "visual_p95_ms", performance, "p95_ms");
        appendLong(report, "ocr_p50_ms", performance, "ocr_p50_ms");
        appendLong(report, "ocr_p95_ms", performance, "ocr_p95_ms");
        appendLong(report, "peak_pss_mb", performance, "peak_pss_mb");
        appendLong(report, "performance_measured_at", performance, "measured_at");
        report.append("runtime_screenshot_p95_ms=")
                .append(PerformanceTelemetry.p95(context, "screenshot_samples"))
                .append('\n');
        report.append("runtime_end_to_end_p95_ms=")
                .append(PerformanceTelemetry.p95(context, "end_to_end_samples"))
                .append('\n');

        SharedPreferences state = context.getSharedPreferences(
                "guard_state", Context.MODE_PRIVATE);
        report.append("processing_error_count=")
                .append(state.getLong("processing_error_count", 0L)).append('\n');
        report.append("swipe_verification_retry_count=")
                .append(state.getLong("swipe_verification_retry_count", 0L)).append('\n');
        report.append("swipe_verification_failure_count=")
                .append(state.getLong("swipe_verification_failure_count", 0L)).append('\n');
        report.append("window_mode=")
                .append(state.getString("window_mode", "UNAVAILABLE")).append('\n');
        report.append("window_bounds_normalized=")
                .append(state.getString("window_bounds_normalized", "unknown")).append('\n');
        report.append("window_detection_allowed=")
                .append(state.getBoolean("window_detection_allowed", false)).append('\n');
        report.append("privacy_exclusions=screenshots, OCR text, account IDs, video content, "
                + "decision history, other-app package names\n");
        return report.toString();
    }

    private static void appendLong(StringBuilder destination, String label,
            SharedPreferences preferences, String key) {
        destination.append(label).append('=')
                .append(preferences.getLong(key, 0L)).append('\n');
    }

}
