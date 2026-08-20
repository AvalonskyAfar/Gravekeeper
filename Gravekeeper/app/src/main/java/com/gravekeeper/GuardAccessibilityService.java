package com.gravekeeper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.BatteryManager;
import android.content.IntentFilter;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.gravekeeper.config.ConfigStore;
import com.gravekeeper.config.BundleValidator;
import com.gravekeeper.config.GuardConfig;
import com.gravekeeper.inference.InferencePipeline;
import com.gravekeeper.inference.InferenceResult;
import com.gravekeeper.inference.ContentFingerprint;

import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardAccessibilityService extends AccessibilityService {
    private static final String CHANNEL_ID = "guard_accessibility_status";
    private static final int NOTIFICATION_ID = 5210;
    private static final int ALERT_NOTIFICATION_ID = 5211;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean screenshotInFlight = new AtomicBoolean(false);
    private final Executor callbackExecutor = command -> main.post(command);
    private final ForegroundAppTracker foregroundTracker = new ForegroundAppTracker();
    private ConfigStore configStore;
    private GuardConfig config;
    private WindowLayoutClassifier windowLayoutClassifier;
    private ContentReleaseGate contentReleaseGate;
    private ContentScrollSignalFilter contentScrollSignalFilter;
    private TargetPageScrollClassifier targetPageScrollClassifier;
    private GuardConfig.Platform foregroundPlatform;
    private InferencePipeline pipeline;
    private StatusOverlayController statusOverlay;
    private BundleValidator.ResourceBundle activeBundle;
    private String foregroundPackage = "";
    private String foregroundEvidenceSource = "unknown";
    private TargetWindowSnapshot targetWindowState;
    private String latestWindowStatus = "窗口尚未确认";
    private String latestTechnicalStatus = "正在启动";
    private String latestMediaStatus = "尚未分析";
    private String latestWhitelistStatus = "尚未检测";
    private String latestAnalysisStatus = "等待目标内容";
    private long lastSwipeAt;
    private long swipeCircuitBreakerUntil;
    private long lastUserInteractionAt;
    private ContentFingerprint latestContentFingerprint;
    private SwipeVerificationTracker swipeVerification;
    private long captureStartedAt;
    private long loadPausedUntil;
    private int consecutiveErrors;
    private boolean destroyed;
    private SharedPreferences configPreferences;

    private static final class DisplaySize {
        final int width;
        final int height;

        DisplaySize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class WindowScan {
        final int displayWidth;
        final int displayHeight;
        final List<WindowLayoutClassifier.WindowRecord> records;
        final List<String> activePackages;

        WindowScan(int displayWidth, int displayHeight,
                List<WindowLayoutClassifier.WindowRecord> records,
                List<String> activePackages) {
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.records = records;
            this.activePackages = activePackages;
        }
    }

    private static final class TargetWindowSnapshot {
        final String packageName;
        final int displayWidth;
        final int displayHeight;
        final WindowLayoutClassifier.Result layout;

        TargetWindowSnapshot(String packageName, int displayWidth, int displayHeight,
                WindowLayoutClassifier.Result layout) {
            this.packageName = packageName;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.layout = layout;
        }

        boolean isUsable() { return layout != null && layout.isUsable(); }
    }

    private static final class ForegroundContext {
        final GuardConfig.Platform platform;
        final String packageName;
        final String source;
        final TargetWindowSnapshot window;

        ForegroundContext(GuardConfig.Platform platform, String packageName,
                String source, TargetWindowSnapshot window) {
            this.platform = platform;
            this.packageName = packageName == null ? "" : packageName;
            this.source = source == null ? "unknown" : source;
            this.window = window;
        }
    }
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                foregroundTracker.observeOtherApp(System.currentTimeMillis());
                updateForegroundPlatform(null, "", "screen_off");
                if (statusOverlay != null) statusOverlay.setScreenActive(false);
            } else if (Intent.ACTION_USER_PRESENT.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)) {
                if (statusOverlay != null) statusOverlay.setScreenActive(screenReadyForAnalysis());
            }
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener configListener =
            (preferences, key) -> reloadConfig();

    private final Runnable captureLoop = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!screenReadyForAnalysis()) {
                foregroundTracker.observeOtherApp(System.currentTimeMillis());
                updateForegroundPlatform(null, "", "screen_off_or_locked");
                if (statusOverlay != null) statusOverlay.setScreenActive(false);
            } else {
                if (statusOverlay != null) statusOverlay.setScreenActive(true);
                reconcileActiveWindow();
            }
            if (config != null && config.protectionEnabled && foregroundPlatform != null
                    && analysisAllowed()) {
                captureOnce();
            }
            InferencePipeline activePipeline = pipeline;
            long delay = config == null ? 2000L
                    : activePipeline == null ? config.captureIntervalMs
                    : activePipeline.captureIntervalMsFor(foregroundPlatform);
            main.postDelayed(this, delay);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        configStore = new ConfigStore(this);
        statusOverlay = new StatusOverlayController(this);
        configPreferences = getSharedPreferences(
                ConfigStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        configPreferences.registerOnSharedPreferenceChangeListener(configListener);
        IntentFilter screenState = new IntentFilter();
        screenState.addAction(Intent.ACTION_SCREEN_OFF);
        screenState.addAction(Intent.ACTION_SCREEN_ON);
        screenState.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenStateReceiver, screenState, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, screenState);
        }
        reloadConfig();
        createNotificationChannel();
        main.removeCallbacks(captureLoop);
        main.post(captureLoop);
        writeStatus(config != null && config.protectionEnabled ? "等待目标 App" : "保护已关闭");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        reloadConfig();
        return START_STICKY;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        String packageName = event.getPackageName() == null
                ? "" : event.getPackageName().toString();
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean targetContextActive = isUsableTargetContext();
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                && !targetContextActive && screenReadyForAnalysis()) {
            // Window callbacks can lag behind the first touch event. Refresh the
            // context before recording the gesture so a package-less touch is
            // still associated with the visible target app.
            reconcileForegroundApp();
            targetContextActive = isUsableTargetContext();
        }
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastUserInteractionAt = nowElapsed;
        }
        if (contentScrollSignalFilter != null) {
            if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
                contentScrollSignalFilter.onTouchStart(
                        nowElapsed, targetContextActive);
            } else if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
                contentScrollSignalFilter.onTouchEnd(
                        nowElapsed, targetContextActive);
            }
        }
        GuardConfig snapshot = config;
        if (snapshot != null && !packageName.isEmpty()) {
            SharedPreferences.Editor observed = snapshot.localTechnicalStatusEnabled
                    ? getSharedPreferences("guard_state", MODE_PRIVATE).edit()
                            .putString("observed_package", packageName)
                    : null;
            Set<String> targets = snapshot.enabledPackages();
            if (!targets.contains(packageName)
                    && !snapshot.ignoredOverlayPackages.contains(packageName)
                    && !getPackageName().equals(packageName)) {
                if (observed != null) observed.putString(
                        "last_unconfigured_package", packageName);
            }
            if (observed != null) observed.apply();
            foregroundTracker.observeTargetEvent(
                    packageName, targets, System.currentTimeMillis());
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && !targets.contains(packageName)
                    && !snapshot.ignoredOverlayPackages.contains(packageName)
                    && !getPackageName().equals(packageName)) {
                foregroundTracker.observeOtherApp(System.currentTimeMillis());
                updateForegroundPlatform(null, "", "other_app_event:" + packageName);
            }
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!isUsableTargetContext() && screenReadyForAnalysis()) {
                // The first feed scroll can arrive before the periodic window
                // reconciliation has populated the target bounds.
                reconcileForegroundApp();
            }
            GuardConfig.Platform eventPlatform = config == null
                    ? null : config.findPlatform(packageName);
            GuardConfig.Platform foregroundEventPlatform = config == null
                    || foregroundPlatform == null ? null
                    : config.findPlatform(foregroundPackage);
            boolean packageMatchesForegroundTarget = eventPlatform != null
                    && eventPlatform == foregroundPlatform
                    && packageName.equals(foregroundPackage)
                    && isUsableTargetContext();
            boolean packageOmittedForTarget = packageName.isEmpty()
                    && foregroundPlatform != null
                    && foregroundEventPlatform == foregroundPlatform
                    && isUsableTargetContext();
            if (pipeline != null && config != null
                    && (packageMatchesForegroundTarget || packageOmittedForTarget)) {
                boolean mainPageEvidence = targetPageScrollClassifier != null
                        && targetPageScrollClassifier.isMainPageScroll(
                                targetPageScrollEvidence(event));
                if (!mainPageEvidence && canUseMissingSourceHeldFallback(event)) {
                    mainPageEvidence = true;
                }
                if (contentScrollSignalFilter != null
                        && !contentScrollSignalFilter.accept(
                                nowElapsed, mainPageEvidence)) {
                    return;
                }
                boolean wokeRelease = contentReleaseGate != null
                        && contentReleaseGate.onTargetScroll();
                pipeline.resetContentContext();
                latestContentFingerprint = null;
                updateWhitelistStatus(null);
                if (wokeRelease || analysisAllowed()) {
                    latestMediaStatus = "检测到用户滑动，等待分析";
                    updateAnalysisStatus("用户滑动后恢复分析");
                } else {
                    latestMediaStatus = contentReleaseGate.statusText();
                    updateAnalysisStatus(contentReleaseGate.statusText());
                }
            }
            return;
        }
        reconcileForegroundApp();
    }

    private boolean isUsableTargetContext() {
        return foregroundPlatform != null
                && targetWindowState != null && targetWindowState.isUsable()
                && screenReadyForAnalysis();
    }

    @Override public void onInterrupt() {
        writeErrorStatus("无障碍服务已中断，请重新启用授权");
    }

    private void reloadConfig() {
        try {
            if (configStore == null) configStore = new ConfigStore(this);
            activeBundle = BundleValidator.active(this);
            GuardConfig loaded = configStore.load();
            config = loaded;
            windowLayoutClassifier = createWindowLayoutClassifier(loaded);
            contentReleaseGate = new ContentReleaseGate(
                    loaded.contentReleaseHoldEnabled,
                    loaded.contentReleaseHoldAfterWhitelist,
                    loaded.contentReleaseHoldAfterDisabledMediaPolicy,
                    loaded.contentReleaseWakeOnTargetScroll);
            contentScrollSignalFilter = createContentScrollSignalFilter(loaded);
            targetPageScrollClassifier = createTargetPageScrollClassifier(loaded);
            if (!loaded.localTechnicalStatusEnabled) {
                getSharedPreferences("guard_state", MODE_PRIVATE).edit().clear().apply();
                getSharedPreferences("guard_performance", MODE_PRIVATE).edit().clear().apply();
                PerformanceTelemetry.clear(this);
            }
            if (statusOverlay != null) {
                statusOverlay.configure(loaded.statusOverlayEnabled,
                        loaded.statusOverlayOpacity);
            }
            swipeVerification = new SwipeVerificationTracker(
                    loaded.swipeVerificationTimeoutMs,
                    loaded.swipeVerificationChangeThreshold,
                    loaded.swipeVerificationCandidateSimilarityThreshold,
                    loaded.swipeVerificationConfirmationFrames,
                    loaded.swipeVerificationMaximumRetries);
            foregroundTracker.clear();
            foregroundPackage = "";
            foregroundPlatform = null;
            foregroundEvidenceSource = "unknown";
            targetWindowState = null;
            latestWindowStatus = "窗口尚未确认";
            latestMediaStatus = "尚未分析";
            latestAnalysisStatus = loaded.protectionEnabled
                    ? "等待目标内容" : "保护已关闭";
            if (!loaded.protectionEnabled) {
                closePipeline();
                screenshotInFlight.set(false);
                cancelNotification();
                writeStatus("保护已关闭");
            } else {
                closePipeline();
                try {
                    pipeline = new InferencePipeline(this, loaded, activeBundle);
                } catch (IOException | RuntimeException currentFailure) {
                    BundleValidator.ResourceBundle fallback =
                            BundleValidator.fallbackToPrevious(
                                    this, activeBundle, currentFailure);
                    if (fallback == null) throw currentFailure;
                    activeBundle = fallback;
                    loaded = configStore.load();
                    config = loaded;
                    windowLayoutClassifier = createWindowLayoutClassifier(loaded);
                    contentReleaseGate = new ContentReleaseGate(
                            loaded.contentReleaseHoldEnabled,
                            loaded.contentReleaseHoldAfterWhitelist,
                            loaded.contentReleaseHoldAfterDisabledMediaPolicy,
                            loaded.contentReleaseWakeOnTargetScroll);
                    contentScrollSignalFilter = createContentScrollSignalFilter(loaded);
                    targetPageScrollClassifier = createTargetPageScrollClassifier(loaded);
                    pipeline = new InferencePipeline(this, loaded, fallback);
                }
                if (loaded.statusNotificationEnabled
                        || loaded.vendorLiveActivityEnabled) {
                    updateNotification("保护运行中");
                }
                else cancelNotification();
            }
            persistAnalysisStatus();
            updateOverlay();
        } catch (IOException | RuntimeException error) {
            closePipeline();
            writeErrorStatus("配置或模型加载失败：" + safeMessage(error));
        }
    }

    private void captureOnce() {
        if (Build.VERSION.SDK_INT < 30 || pipeline == null
                || foregroundPlatform == null || !screenReadyForAnalysis()
                || !analysisAllowed()) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed < loadPausedUntil) return;
        ForegroundContext beforeCapture = resolveForegroundContext();
        applyForegroundContext(beforeCapture);
        if (beforeCapture.platform == null || beforeCapture.platform != foregroundPlatform
                || beforeCapture.window == null || !beforeCapture.window.isUsable()) {
            return;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 29 && power.getCurrentThermalStatus() >= config.severeThermalStatus) {
            writeStatus("设备温度较高，已暂停高负载检测");
            return;
        }
        if (batteryTooLow()) {
            writeStatus("电量较低且未充电，已按配置暂停高负载检测");
            return;
        }
        if (!screenshotInFlight.compareAndSet(false, true)) return;
        captureStartedAt = SystemClock.elapsedRealtime();
        if (statusOverlay != null) statusOverlay.hideForCapture();
        TargetWindowSnapshot expectedWindow = beforeCapture.window;
        main.postDelayed(() -> takeProtectedScreenshot(expectedWindow), 80L);
    }

    private void takeProtectedScreenshot(TargetWindowSnapshot expectedWindow) {
        if (destroyed || pipeline == null || foregroundPlatform == null
                || !screenReadyForAnalysis() || !analysisAllowed()) {
            screenshotInFlight.set(false);
            if (statusOverlay != null) statusOverlay.restoreAfterCapture();
            return;
        }
        ForegroundContext immediatelyBefore = resolveForegroundContext();
        applyForegroundContext(immediatelyBefore);
        if (immediatelyBefore.platform == null
                || immediatelyBefore.platform != foregroundPlatform
                || !sameWindowContext(expectedWindow, immediatelyBefore.window)) {
            screenshotInFlight.set(false);
            if (statusOverlay != null) statusOverlay.restoreAfterCapture();
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap copy = null;
                Bitmap pipelineBitmap = null;
                try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("无法读取无障碍截图");
                    copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    PerformanceTelemetry.recordScreenshot(
                            GuardAccessibilityService.this,
                            SystemClock.elapsedRealtime() - captureStartedAt,
                            config != null && config.localTechnicalStatusEnabled);
                    consecutiveErrors = 0;
                    hardware.recycle();
                    ForegroundContext afterCapture = resolveForegroundContext();
                    applyForegroundContext(afterCapture);
                    GuardConfig.Platform platform = afterCapture.platform;
                    if (copy == null || platform == null
                            || platform != foregroundPlatform
                            || !sameWindowContext(expectedWindow, afterCapture.window)) {
                        if (copy != null) copy.recycle();
                        return;
                    }
                    InferencePipeline activePipeline = pipeline;
                    if (activePipeline == null || !analysisAllowed()) {
                        copy.recycle();
                        return;
                    }
                    pipelineBitmap = cropToTargetWindow(copy, afterCapture.window);
                    if (pipelineBitmap != copy) copy.recycle();
                    copy = null;
                    Bitmap submitted = pipelineBitmap;
                    pipelineBitmap = null;
                    activePipeline.submit(submitted, platform,
                            collectAccessibilitySemanticText(afterCapture.packageName),
                            inferenceCallbackFor(expectedWindow));
                } catch (Throwable error) {
                    if (copy != null && !copy.isRecycled()) copy.recycle();
                    if (pipelineBitmap != null && !pipelineBitmap.isRecycled()) {
                        pipelineBitmap.recycle();
                    }
                    recordProcessingError();
                    writeErrorStatus("截图失败：" + safeMessage(error));
                } finally {
                    screenshotInFlight.set(false);
                    if (statusOverlay != null) statusOverlay.restoreAfterCapture();
                    updateOverlay();
                }
            }

            @Override public void onFailure(int errorCode) {
                screenshotInFlight.set(false);
                if (statusOverlay != null) statusOverlay.restoreAfterCapture();
                recordProcessingError();
                writeErrorStatus("无障碍截图失败（" + errorCode + "）");
            }
        });
    }

    private String activeRootPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        String packageName = root.getPackageName().toString();
        // TYPE_ACCESSIBILITY_OVERLAY may momentarily become the active root.
        // That is not an application switch; actual MainActivity windows are
        // still caught by application-window and UsageStats evidence.
        return getPackageName().equals(packageName) ? "" : packageName;
    }

    private String collectAccessibilitySemanticText(String targetPackage) {
        AccessibilityNodeInfo root = targetWindowRoot(targetPackage);
        if (root == null) return "";
        StringBuilder text = new StringBuilder();
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty() && visited < 300 && text.length() < 8000) {
            AccessibilityNodeInfo node = pending.removeFirst();
            visited++;
            appendSemanticText(text, node.getText());
            appendSemanticText(text, node.getContentDescription());
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.addLast(child);
            }
        }
        return text.toString();
    }

    private AccessibilityNodeInfo targetWindowRoot(String targetPackage) {
        if (targetPackage == null || targetPackage.isEmpty()) return null;
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        AccessibilityNodeInfo best = null;
        boolean bestFocused = false;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null
                    || !targetPackage.contentEquals(root.getPackageName())) continue;
            boolean focused = window.isFocused() || window.isActive();
            if (best == null || (focused && !bestFocused)) {
                best = root;
                bestFocused = focused;
            }
        }
        return best;
    }

    private static void appendSemanticText(StringBuilder destination, CharSequence value) {
        if (value == null || value.length() == 0 || destination.length() >= 8000) return;
        if (destination.length() > 0) destination.append('\n');
        int remaining = 8000 - destination.length();
        destination.append(value, 0, Math.min(value.length(), remaining));
    }

    private WindowScan scanApplicationWindows() {
        DisplaySize display = displaySize();
        List<WindowLayoutClassifier.WindowRecord> records = new ArrayList<>();
        List<String> activePackages = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return new WindowScan(
                display.width, display.height, records, activePackages);
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && root.getPackageName() != null) {
                String packageName = root.getPackageName().toString();
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                boolean pictureInPicture = window.isInPictureInPictureMode();
                records.add(new WindowLayoutClassifier.WindowRecord(packageName,
                        new WindowLayoutClassifier.Bounds(
                                bounds.left, bounds.top, bounds.right, bounds.bottom),
                        window.isActive(), window.isFocused(), pictureInPicture));
                if (window.isActive() || window.isFocused()) activePackages.add(packageName);
            }
        }
        return new WindowScan(display.width, display.height, records, activePackages);
    }

    private ForegroundContext resolveForegroundContext() {
        GuardConfig snapshot = config;
        if (snapshot == null) return new ForegroundContext(null, "", "no_config", null);
        WindowScan scan = scanApplicationWindows();
        UsageStatsForegroundResolver.Result usage = snapshot.usageStatsFallbackEnabled
                ? UsageStatsForegroundResolver.resolve(this, snapshot.usageEventLookbackMs)
                : new UsageStatsForegroundResolver.Result("", 0L);
        ForegroundAppTracker.Resolution resolution = foregroundTracker.resolve(
                activeRootPackage(), scan.activePackages,
                usage.packageName, usage.timestampMs,
                snapshot.enabledPackages(), snapshot.ignoredOverlayPackages,
                System.currentTimeMillis(), snapshot.foregroundEvidenceTtlMs,
                snapshot.usageEventMaxAgeMs);
        String targetPackage = resolution.packageName;
        String source = resolution.source;
        if (targetPackage.isEmpty()) {
            Set<String> visibleTargets = new HashSet<>();
            for (WindowLayoutClassifier.WindowRecord record : scan.records) {
                if (snapshot.enabledPackages().contains(record.packageName)) {
                    visibleTargets.add(record.packageName);
                }
            }
            if (visibleTargets.size() == 1) {
                targetPackage = visibleTargets.iterator().next();
                source = "visible_target_window";
            }
        }
        GuardConfig.Platform platform = snapshot.findPlatform(targetPackage);
        TargetWindowSnapshot window = null;
        if (platform != null && windowLayoutClassifier != null) {
            WindowLayoutClassifier.Result layout = windowLayoutClassifier.classify(
                    scan.displayWidth, scan.displayHeight, targetPackage, scan.records);
            window = new TargetWindowSnapshot(targetPackage, scan.displayWidth,
                    scan.displayHeight, layout);
        }
        return new ForegroundContext(platform, targetPackage, source, window);
    }

    private void reconcileForegroundApp() {
        applyForegroundContext(resolveForegroundContext());
    }

    private void reconcileActiveWindow() {
        reconcileForegroundApp();
    }

    private void updateForegroundPlatform(
            GuardConfig.Platform next, String packageName, String evidenceSource) {
        applyForegroundContext(new ForegroundContext(
                next, packageName, evidenceSource, null));
    }

    private void applyForegroundContext(ForegroundContext context) {
        GuardConfig.Platform next = context == null ? null : context.platform;
        String packageName = context == null ? "" : context.packageName;
        String evidenceSource = context == null ? "unknown" : context.source;
        TargetWindowSnapshot nextWindow = context == null ? null : context.window;
        String normalizedPackage = packageName == null ? "" : packageName;
        boolean changed = foregroundPlatform != next
                || !foregroundPackage.equals(normalizedPackage)
                || !sameWindowContext(targetWindowState, nextWindow);
        foregroundPackage = packageName == null ? "" : packageName;
        foregroundEvidenceSource = evidenceSource == null ? "unknown" : evidenceSource;
        if (changed && contentReleaseGate != null) {
            contentReleaseGate.resetForContextChange();
        }
        if (changed && contentScrollSignalFilter != null) {
            contentScrollSignalFilter.reset();
        }
        if (changed && pipeline != null) pipeline.resetContentContext();
        if (changed) {
            latestContentFingerprint = null;
            if (swipeVerification != null) swipeVerification.clear();
            latestMediaStatus = "等待分析";
            latestWhitelistStatus = "等待账号识别";
            latestAnalysisStatus = next == null ? "等待目标 App" : "等待分析";
        }
        foregroundPlatform = next;
        targetWindowState = nextWindow;
        latestWindowStatus = windowStatus(nextWindow);
        if (config != null && config.localTechnicalStatusEnabled) {
            SharedPreferences.Editor editor = getSharedPreferences(
                    "guard_state", MODE_PRIVATE).edit()
                    .putString("foreground_package", foregroundPackage)
                    .putString("foreground_source", foregroundEvidenceSource)
                    .putString("analysis_status", latestAnalysisStatus)
                    .putString("whitelist_status", latestWhitelistStatus);
            if (nextWindow != null && nextWindow.layout != null) {
                editor.putString("window_mode", nextWindow.layout.mode.name())
                        .putString("window_bounds_normalized",
                                nextWindow.layout.normalizedBounds(
                                        nextWindow.displayWidth, nextWindow.displayHeight))
                        .putBoolean("window_detection_allowed",
                                nextWindow.layout.detectionAllowed);
            } else {
                editor.putString("window_mode",
                                WindowLayoutClassifier.Mode.UNAVAILABLE.name())
                        .putString("window_bounds_normalized", "unknown")
                        .putBoolean("window_detection_allowed", false);
            }
            editor.apply();
        }
        if (changed) {
            if (next == null) writeStatus("目标 App 未在前台");
            else if (nextWindow == null || !nextWindow.isUsable()) {
                writeStatus(latestWindowStatus);
            } else {
                writeStatus("正在保护：" + next.name + "（" + latestWindowStatus + "）");
            }
        } else updateOverlay();
    }

    private WindowLayoutClassifier createWindowLayoutClassifier(GuardConfig value) {
        return new WindowLayoutClassifier(new WindowLayoutClassifier.Config(
                value.fullscreenWindowEnabled,
                value.splitScreenWindowEnabled,
                value.pictureInPictureWindowEnabled,
                value.floatingWindowEnabled,
                value.unknownWindowModeEnabled,
                value.requireTargetWindowFocused,
                value.minimumFullscreenWidthRatio,
                value.minimumFullscreenHeightRatio,
                value.minimumSplitSpanRatio,
                value.minimumSplitAreaRatio,
                value.maximumSplitAreaRatio,
                value.windowBoundsChangeToleranceRatio,
                value.gestureEdgePaddingRatio));
    }

    private static ContentScrollSignalFilter createContentScrollSignalFilter(
            GuardConfig value) {
        return new ContentScrollSignalFilter(
                value.contentTargetScrollEnabled,
                value.contentTargetScrollRequiresRecentTouch,
                value.contentTargetScrollRecentTouchWindowMs,
                value.contentTargetScrollDebounceMs,
                value.contentTargetScrollRequiresMainPageEvidence,
                value.contentTargetScrollAllowMainPageWithoutTouch);
    }

    private static TargetPageScrollClassifier createTargetPageScrollClassifier(
            GuardConfig value) {
        return new TargetPageScrollClassifier(
                value.contentTargetScrollMinimumSourceWidthRatio,
                value.contentTargetScrollMinimumSourceHeightRatio,
                value.contentTargetScrollMaximumHorizontalDeltaRatio,
                value.contentTargetScrollAllowUnknownDirection);
    }

    private TargetPageScrollClassifier.Evidence targetPageScrollEvidence(
            AccessibilityEvent event) {
        TargetWindowSnapshot target = targetWindowState;
        if (event == null || target == null || target.layout == null
                || target.layout.bounds == null) return null;
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source == null) source = mainScrollContainer(target);
            if (source == null) return null;
            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) return null;
            return new TargetPageScrollClassifier.Evidence(
                    target.layout.bounds,
                    new WindowLayoutClassifier.Bounds(
                            bounds.left, bounds.top, bounds.right, bounds.bottom),
                    event.getScrollDeltaX(), event.getScrollDeltaY(),
                    event.getFromIndex(), event.getToIndex());
        } catch (RuntimeException staleSource) {
            return null;
        }
    }

    private AccessibilityNodeInfo mainScrollContainer(TargetWindowSnapshot target) {
        if (target == null || target.layout == null || target.layout.bounds == null
                || foregroundPackage.isEmpty()) return null;
        AccessibilityNodeInfo root = targetWindowRoot(foregroundPackage);
        if (root == null) return null;
        AccessibilityNodeInfo best = null;
        long bestArea = 0L;
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty() && visited < 300) {
            AccessibilityNodeInfo node = pending.removeFirst();
            visited++;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isScrollable() && coversMainTargetSurface(target.layout.bounds, bounds)) {
                long area = Math.max(0L, (long) bounds.width() * bounds.height());
                if (area > bestArea) {
                    best = node;
                    bestArea = area;
                }
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) pending.addLast(child);
            }
        }
        return best;
    }

    private boolean coversMainTargetSurface(WindowLayoutClassifier.Bounds target, Rect source) {
        if (target == null || source == null || source.isEmpty()
                || !target.isPositive()) return false;
        int overlapWidth = Math.max(0, Math.min(target.right, source.right)
                - Math.max(target.left, source.left));
        int overlapHeight = Math.max(0, Math.min(target.bottom, source.bottom)
                - Math.max(target.top, source.top));
        return overlapWidth / (double) target.width()
                        >= config.contentTargetScrollMinimumSourceWidthRatio
                && overlapHeight / (double) target.height()
                        >= config.contentTargetScrollMinimumSourceHeightRatio;
    }

    private boolean canUseMissingSourceHeldFallback(AccessibilityEvent event) {
        if (event == null || config == null || targetPageScrollClassifier == null
                || !config.contentTargetScrollAllowMissingSourceWhenHeld
                || contentReleaseGate == null || !contentReleaseGate.isHolding()) {
            return false;
        }
        try {
            if (event.getSource() != null) return false;
            return targetPageScrollClassifier.isStrongVerticalPagingSignal(
                    event.getScrollDeltaX(), event.getScrollDeltaY(),
                    event.getFromIndex(), event.getToIndex(),
                    config.contentTargetScrollAllowMissingSourceUnknownDirectionWhenHeld);
        } catch (RuntimeException staleEvent) {
            return false;
        }
    }

    private DisplaySize displaySize() {
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager != null) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            if (bounds.width() > 0 && bounds.height() > 0) {
                return new DisplaySize(bounds.width(), bounds.height());
            }
        }
        return new DisplaySize(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
    }

    private boolean sameWindowContext(
            TargetWindowSnapshot first, TargetWindowSnapshot second) {
        if (first == null || second == null) return first == second;
        if (!first.packageName.equals(second.packageName)
                || first.displayWidth != second.displayWidth
                || first.displayHeight != second.displayHeight
                || first.layout == null || second.layout == null) return false;
        double tolerance = config == null ? 0.03
                : config.windowBoundsChangeToleranceRatio;
        return first.layout.sameContext(second.layout,
                first.displayWidth, first.displayHeight, tolerance);
    }

    private static Bitmap cropToTargetWindow(
            Bitmap source, TargetWindowSnapshot window) {
        if (source == null || window == null || window.layout == null
                || window.layout.bounds == null || window.displayWidth <= 0
                || window.displayHeight <= 0) {
            throw new IllegalArgumentException("目标窗口边界不可用");
        }
        WindowLayoutClassifier.Bounds bounds = window.layout.bounds;
        double scaleX = source.getWidth() / (double) window.displayWidth;
        double scaleY = source.getHeight() / (double) window.displayHeight;
        int left = Math.max(0, Math.min(source.getWidth() - 1,
                (int) Math.floor(bounds.left * scaleX)));
        int top = Math.max(0, Math.min(source.getHeight() - 1,
                (int) Math.floor(bounds.top * scaleY)));
        int right = Math.max(left + 1, Math.min(source.getWidth(),
                (int) Math.ceil(bounds.right * scaleX)));
        int bottom = Math.max(top + 1, Math.min(source.getHeight(),
                (int) Math.ceil(bounds.bottom * scaleY)));
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    private static String windowStatus(TargetWindowSnapshot window) {
        if (window == null || window.layout == null) return "窗口模式无法确认，已暂停监测";
        switch (window.layout.mode) {
            case FULLSCREEN: return window.layout.detectionAllowed
                    ? "全屏" : "全屏监测已关闭";
            case SPLIT_TOP: return window.layout.detectionAllowed
                    ? "上方分屏" : "分屏监测已关闭";
            case SPLIT_BOTTOM: return window.layout.detectionAllowed
                    ? "下方分屏" : "分屏监测已关闭";
            case SPLIT_LEFT: return window.layout.detectionAllowed
                    ? "左侧分屏" : "分屏监测已关闭";
            case SPLIT_RIGHT: return window.layout.detectionAllowed
                    ? "右侧分屏" : "分屏监测已关闭";
            case PICTURE_IN_PICTURE: return window.layout.detectionAllowed
                    ? "画中画" : "画中画，已暂停监测";
            case FLOATING_OR_UNKNOWN: return window.layout.detectionAllowed
                    ? "小窗或自由窗口" : "小窗或窗口模式不确定，已暂停监测";
            case NOT_FOCUSED: return "目标 App 未聚焦，已暂停监测";
            default: return "窗口模式无法确认，已暂停监测";
        }
    }

    private final InferencePipeline.Callback inferenceCallback = new InferencePipeline.Callback() {
        @Override public void onResult(InferenceResult result) {
            consecutiveErrors = 0;
            PerformanceTelemetry.recordEndToEnd(
                    GuardAccessibilityService.this, result.totalLatencyMs,
                    config != null && config.localTechnicalStatusEnabled);
            reconcileForegroundApp();
            GuardConfig.Platform active = foregroundPlatform;
            if (active == null || targetWindowState == null
                    || !targetWindowState.isUsable()
                    || !active.id.equals(result.platformId)) return;
            if (result.contentChanged) {
                updateWhitelistStatus(null);
                swipeCircuitBreakerUntil = 0L;
                SwipeVerificationTracker.Outcome changedOutcome = observeSwipeVerification(
                        result.fingerprint, true);
                latestContentFingerprint = result.fingerprint;
                latestMediaStatus = changedOutcome == SwipeVerificationTracker.Outcome.SUCCESS
                        ? "自动划走已确认切换" : result.reasonSummary;
                writeStatus(latestMediaStatus);
                return;
            }
            if (result.fingerprint != null) latestContentFingerprint = result.fingerprint;
            updateWhitelistStatus(result);
            if (contentReleaseGate != null
                    && contentReleaseGate.holdFor(result.releaseCause)) {
                InferencePipeline activePipeline = pipeline;
                if (activePipeline != null) activePipeline.cancelPendingWork();
                if (swipeVerification != null) swipeVerification.clear();
                latestAnalysisStatus = contentReleaseGate.statusText();
                latestMediaStatus = contentReleaseGate.statusText();
                persistAnalysisStatus();
                writeStatus(result.whitelisted
                        ? "白名单账号已放行，等待内容切换"
                        : "当前媒体策略已关闭，等待内容切换");
                return;
            }
            updateAnalysisStatus("持续分析");
            SwipeVerificationTracker.Outcome swipeOutcome = observeSwipeVerification(
                    result.fingerprint, false);
            if (swipeOutcome == SwipeVerificationTracker.Outcome.SUCCESS) {
                latestMediaStatus = "自动划走已确认切换";
            } else if (swipeOutcome == SwipeVerificationTracker.Outcome.RETRY) {
                if (!canAutomaticallySwipe(result)) {
                    writeStatus("媒体形态未确认，已忽略自动划走重试");
                    return;
                }
                incrementTechnicalCounter("swipe_verification_retry_count");
                writeStatus("自动划走未确认，按配置重试一次");
                swipeAway(true);
                return;
            } else if (swipeOutcome == SwipeVerificationTracker.Outcome.FAILED) {
                incrementTechnicalCounter("swipe_verification_failure_count");
                swipeCircuitBreakerUntil = SystemClock.elapsedRealtime()
                        + config.swipeVerificationFailureCircuitBreakerMs;
                writeErrorStatus("自动划走未确认生效，已停止重试并进入冷却");
                return;
            }
            String kind = result.mediaKind == GuardConfig.MediaKind.LIVE ? "直播"
                    : result.mediaKind == GuardConfig.MediaKind.SHORT_VIDEO ? "短视频" : "形态未知";
            latestMediaStatus = String.format(Locale.CHINA,
                    "%s · 风险 %.0f%% · 动作 %s · OCR %s · %s",
                    kind, result.windowScore * 100.0,
                    actionLabel(result.action), result.ocrAvailable ? "可用" : "不可用",
                    result.reasonSummary);
            if (result.whitelisted) {
                writeStatus("白名单账号已放行");
                return;
            }
            writeStatus(String.format(Locale.CHINA, "%s风险 %.0f%%", kind, result.windowScore * 100.0));
            if (result.positive && result.action == GuardConfig.Action.SWIPE) {
                if (canAutomaticallySwipe(result)) swipeAway(false);
                else writeStatus("媒体形态未确认，未执行自动划走");
            }
            else if (result.positive && result.action == GuardConfig.Action.NOTIFY) {
                notifyAlert("检测到风险内容（" + kind + "）");
            }
        }

        private boolean canAutomaticallySwipe(InferenceResult result) {
            return com.gravekeeper.inference.SwipeEligibility
                    .canAutomaticallySwipe(config, result == null ? null : result.mediaKind);
        }

        @Override public void onError(String message) {
            recordProcessingError();
            writeErrorStatus("识别错误：" + message);
        }
    };

    private InferencePipeline.Callback inferenceCallbackFor(
            TargetWindowSnapshot submittedWindow) {
        return new InferencePipeline.Callback() {
            @Override public void onResult(InferenceResult result) {
                ForegroundContext current = resolveForegroundContext();
                applyForegroundContext(current);
                if (!sameWindowContext(submittedWindow, current.window)) return;
                inferenceCallback.onResult(result);
            }

            @Override public void onError(String message) {
                ForegroundContext current = resolveForegroundContext();
                applyForegroundContext(current);
                if (!sameWindowContext(submittedWindow, current.window)) return;
                inferenceCallback.onError(message);
            }
        };
    }

    private void swipeAway(boolean retry) {
        TargetWindowSnapshot expectedWindow = targetWindowState;
        ForegroundContext beforeSwipe = resolveForegroundContext();
        applyForegroundContext(beforeSwipe);
        if (foregroundPlatform == null || !screenReadyForAnalysis()
                || beforeSwipe.window == null || !beforeSwipe.window.isUsable()
                || (expectedWindow != null
                    && !sameWindowContext(expectedWindow, beforeSwipe.window))) {
            writeStatus("窗口状态已变化，已取消自动划走");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < swipeCircuitBreakerUntil) {
            writeStatus("自动划走验证失败，熔断冷却中");
            return;
        }
        if (!retry && now - lastUserInteractionAt < config.swipeAvoidUserTouchMs) {
            writeStatus("检测到近期手势，已延后自动划走");
            return;
        }
        if (now - lastSwipeAt < config.swipeCooldownMs) return;
        if (!retry && swipeVerification != null && swipeVerification.isActive()) return;
        lastSwipeAt = now;
        WindowLayoutClassifier.Bounds bounds = beforeSwipe.window.layout.bounds;
        double padding = config.gestureEdgePaddingRatio;
        double xRatio = clamp(config.swipeXRatio, padding, 1.0 - padding);
        double startYRatio = clamp(config.swipeStartYRatio, padding, 1.0 - padding);
        double endYRatio = clamp(config.swipeEndYRatio, padding, 1.0 - padding);
        float x = bounds.xAt(xRatio);
        float startY = bounds.yAt(startYRatio);
        float endY = bounds.yAt(endYRatio);
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                        path, 0, config.swipeDurationMs))
                .build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                if (config != null && config.swipeVerificationEnabled
                        && latestContentFingerprint != null && swipeVerification != null) {
                    if (retry) swipeVerification.retryDispatched(SystemClock.elapsedRealtime());
                    else swipeVerification.start(latestContentFingerprint,
                            SystemClock.elapsedRealtime());
                    writeStatus("已执行上划，正在确认内容是否切换");
                } else {
                    writeStatus("已按配置自动划走风险内容");
                }
            }

            @Override public void onCancelled(GestureDescription gestureDescription) {
                if (retry && swipeVerification != null) swipeVerification.clear();
                writeErrorStatus("自动划走未执行，请检查无障碍服务状态");
            }
        }, main);
        if (!accepted) {
            if (retry && swipeVerification != null) swipeVerification.clear();
            writeErrorStatus("系统未接受自动划走手势，请检查无障碍服务状态");
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private SwipeVerificationTracker.Outcome observeSwipeVerification(
            ContentFingerprint fingerprint, boolean boundary) {
        if (config == null || !config.swipeVerificationEnabled
                || swipeVerification == null || !swipeVerification.isActive()) {
            return SwipeVerificationTracker.Outcome.INACTIVE;
        }
        return swipeVerification.observe(fingerprint, boundary, SystemClock.elapsedRealtime());
    }

    private void writeStatus(String status) {
        latestTechnicalStatus = status;
        if (config == null || config.localTechnicalStatusEnabled) {
            getSharedPreferences("guard_state", MODE_PRIVATE).edit()
                    .putString("status", status)
                    .putBoolean("accessibility_connected", true)
                    .apply();
        }
        updateOverlay();
        if (config != null && config.protectionEnabled) updateNotification(status);
    }

    private boolean analysisAllowed() {
        return contentReleaseGate == null || contentReleaseGate.shouldAnalyze();
    }

    private void updateAnalysisStatus(String status) {
        latestAnalysisStatus = status;
        persistAnalysisStatus();
        updateOverlay();
    }

    private void persistAnalysisStatus() {
        if (config == null || config.localTechnicalStatusEnabled) {
            getSharedPreferences("guard_state", MODE_PRIVATE).edit()
                    .putString("analysis_status", latestAnalysisStatus).apply();
        }
    }

    private void updateWhitelistStatus(InferenceResult result) {
        String status;
        if (result == null) {
            status = "等待账号识别";
        } else if (result.whitelisted) {
            status = "已命中并放行";
        } else if (result.mediaKind == GuardConfig.MediaKind.LIVE) {
            if (!result.ocrAvailable) status = "直播：OCR 不可用";
            else if (result.accountId == null || result.accountId.isEmpty()) {
                status = "直播：未识别账号名称";
            } else {
                status = "直播：已识别账号，未命中";
            }
        } else if (result.mediaKind == GuardConfig.MediaKind.SHORT_VIDEO) {
            status = "短视频：不启用名称白名单";
        } else {
            status = "媒体形态未知：未执行白名单匹配";
        }
        latestWhitelistStatus = status;
        if (config == null || config.localTechnicalStatusEnabled) {
            getSharedPreferences("guard_state", MODE_PRIVATE).edit()
                    .putString("whitelist_status", status).apply();
        }
        updateOverlay();
    }

    private void writeErrorStatus(String status) {
        latestTechnicalStatus = status;
        if (config == null || config.localTechnicalStatusEnabled) {
            getSharedPreferences("guard_state", MODE_PRIVATE).edit()
                    .putString("status", status)
                    .putBoolean("accessibility_connected", true)
                    .apply();
        }
        updateOverlay();
        notifyAlert(status);
    }

    private void updateOverlay() {
        if (statusOverlay == null || config == null) return;
        boolean visible = screenReadyForAnalysis() && config.statusOverlayEnabled
                && (config.statusOverlayShowOutsideTargets || foregroundPlatform != null);
        String bundle = activeBundle == null ? "资源未知"
                : "资源 v" + activeBundle.version + "/" + activeBundle.slot
                        + (activeBundle.fallback ? "（已回退）" : "");
        String platform = foregroundPlatform == null ? "非目标 App"
                : foregroundPlatform.name;
        String packageName = foregroundPackage.isEmpty() ? "未确认" : foregroundPackage;
        statusOverlay.update(getString(R.string.status_overlay_title)
                + "\n" + (config.protectionEnabled ? "保护开启" : "保护关闭")
                + " · " + latestTechnicalStatus
                + "\n" + platform + " · " + packageName
                + "\n证据 " + foregroundEvidenceSource
                + " · 窗口 " + latestWindowStatus
                + "\n分析：" + latestAnalysisStatus
                + "\n白名单：" + latestWhitelistStatus
                + "\n" + latestMediaStatus + " · " + bundle,
                visible);
    }

    private static String actionLabel(GuardConfig.Action action) {
        if (action == GuardConfig.Action.SWIPE) return "自动划走";
        if (action == GuardConfig.Action.NOTIFY) return "提醒";
        return "不处理";
    }

    private void updateNotification(String detail) {
        if (config == null || !config.protectionEnabled
                || (!config.statusNotificationEnabled
                    && !config.vendorLiveActivityEnabled)) return;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title_active))
                .setContentText(detail)
                .setContentIntent(openPending)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 21) builder.setCategory(Notification.CATEGORY_SERVICE);
        if (config.notificationQuickStop) {
            Intent stop = new Intent(this, QuickStopReceiver.class)
                    .setAction(QuickStopReceiver.ACTION_QUICK_STOP);
            PendingIntent stopPending = PendingIntent.getBroadcast(this, 1, stop,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "停止保护", stopPending).build());
        }
        Notification notification = LiveStatusNotificationCompat.build(
                builder, detail, config.vendorLiveActivityEnabled);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification);
    }

    private void notifyAlert(String detail) {
        // When notifications are disabled the system silently drops the notify()
        // call. Instead of a wasted allocation, fall back to the overlay status
        // line so the operator can see the alert fired.
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && !manager.areNotificationsEnabled()) {
            writeStatus("通知未开启，无法发送：" + detail);
            return;
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title_attention))
                .setContentText(detail)
                .setContentIntent(pending)
                .setAutoCancel(true);
        manager.notify(ALERT_NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void cancelNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
    }

    private void closePipeline() {
        if (pipeline != null) pipeline.close();
        pipeline = null;
    }

    private boolean screenReadyForAnalysis() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return power != null && power.isInteractive()
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private boolean batteryTooLow() {
        if (config == null || !config.pauseOnBatteryLow) return false;
        Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (charging || level < 0 || scale <= 0) return false;
        int percent = Math.round(level * 100f / scale);
        return percent <= config.minimumBatteryPercentWhileNotCharging;
    }

    private void recordProcessingError() {
        if (config == null) return;
        incrementTechnicalCounter("processing_error_count");
        consecutiveErrors++;
        if (consecutiveErrors >= config.maxConsecutiveErrors) {
            loadPausedUntil = SystemClock.elapsedRealtime() + config.errorPauseMs;
            consecutiveErrors = 0;
            writeErrorStatus("连续处理失败，已按配置临时暂停高负载检测");
        }
    }

    private void incrementTechnicalCounter(String key) {
        if (config == null || !config.localTechnicalStatusEnabled) return;
        android.content.SharedPreferences state = getSharedPreferences(
                "guard_state", MODE_PRIVATE);
        state.edit().putLong(key, state.getLong(key, 0L) + 1L).apply();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override public void onDestroy() {
        boolean shouldAlert = config != null && config.protectionEnabled;
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (configPreferences != null) {
            configPreferences.unregisterOnSharedPreferenceChangeListener(configListener);
        }
        try { unregisterReceiver(screenStateReceiver); }
        catch (IllegalArgumentException ignored) { }
        closePipeline();
        if (statusOverlay != null) statusOverlay.remove();
        foregroundTracker.clear();
        screenshotInFlight.set(false);
        if (config == null || config.localTechnicalStatusEnabled) {
            getSharedPreferences("guard_state", MODE_PRIVATE).edit()
                    .putBoolean("accessibility_connected", false)
                    .putString("status", "无障碍服务未连接")
                    .apply();
        }
        cancelNotification();
        if (shouldAlert) notifyAlert("无障碍服务已停止，请进入 App 检查授权");
        super.onDestroy();
    }
}
