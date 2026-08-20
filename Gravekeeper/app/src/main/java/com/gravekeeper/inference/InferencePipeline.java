package com.gravekeeper.inference;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.gravekeeper.config.GuardConfig;
import com.gravekeeper.config.ConfigStore;
import com.gravekeeper.config.BundleValidator;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

public final class InferencePipeline implements AutoCloseable {
    public interface Callback {
        void onResult(InferenceResult result);
        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private static final class WorkItem {
        final Bitmap bitmap;
        final GuardConfig.Platform platform;
        final String accessibilityText;
        final ContentFingerprint fingerprint;
        final long generation;
        final Callback callback;

        WorkItem(Bitmap bitmap, GuardConfig.Platform platform,
                String accessibilityText, ContentFingerprint fingerprint, long generation,
                Callback callback) {
            this.bitmap = bitmap;
            this.platform = platform;
            this.accessibilityText = accessibilityText == null ? "" : accessibilityText;
            this.fingerprint = fingerprint;
            this.generation = generation;
            this.callback = callback;
        }
    }

    private final AtomicReference<WorkItem> pending = new AtomicReference<>();
    private final AtomicLong contentGeneration = new AtomicLong();
    private final Object contentContextLock = new Object();
    private final VisualClassifier visual;
    private final OcrEngine ocr = new OcrEngine();
    private final TextClassifier text;
    private final RuleEngine rules;
    private final RiskFusion fusion;
    private final RollingEvidence rolling = new RollingEvidence();
    private final ContentIdentityTracker contentIdentity;
    private final GuardConfig config;
    private final RiskPolicyEngine policyEngine;

    private volatile OcrDocument latestOcrDocument = OcrDocument.empty();
    private volatile long latestOcrAt;
    private volatile GuardConfig.MediaKind latestMediaKind = GuardConfig.MediaKind.UNKNOWN;
    private volatile boolean closed;

    public InferencePipeline(Context context, GuardConfig config) throws IOException {
        this(context, config, BundleValidator.active(context));
    }

    public InferencePipeline(Context context, GuardConfig config,
            BundleValidator.ResourceBundle bundle) throws IOException {
        this.config = config;
        this.policyEngine = new RiskPolicyEngine(config);
        this.contentIdentity = new ContentIdentityTracker(
                config.contentVisualChangeThreshold,
                config.contentCandidateSimilarityThreshold,
                config.contentConfirmationFrames,
                config.contentMinimumResetIntervalMs);
        visual = new VisualClassifier(bundle.file("models/gravekeeper_visual.tflite"));
        text = new TextClassifier(bundle.file("models/text_classifier_int8.bin"));
        rules = new RuleEngine(bundle.file("config/rule_schema.json"), config);
        fusion = new RiskFusion(bundle.file("models/fusion_model.json"));
    }

    public InferencePipeline(Context context) throws IOException {
        this(context, new ConfigStore(context).load());
    }

    public void submit(Bitmap ownedBitmap, GuardConfig.Platform platform,
            String accessibilityText, Callback callback) {
        if (closed) {
            ownedBitmap.recycle();
            return;
        }
        ContentFingerprint fingerprint;
        try {
            fingerprint = ContentFingerprint.fromBitmap(ownedBitmap);
        } catch (RuntimeException unusableImage) {
            ownedBitmap.recycle();
            callback.onError("截图画面无效");
            return;
        }
        WorkItem replacement = new WorkItem(ownedBitmap, platform, accessibilityText,
                fingerprint, contentGeneration.get(), callback);
        WorkItem replaced = pending.getAndSet(replacement);
        if (replaced != null) replaced.bitmap.recycle();
        drain();
    }

    public void submit(Bitmap ownedBitmap, Callback callback) {
        GuardConfig.Platform fallback = config.platforms.get(0);
        submit(ownedBitmap, fallback, "", callback);
    }

    public void resetWindow() {
        synchronized (contentContextLock) {
            contentGeneration.incrementAndGet();
            contentIdentity.reset();
            rolling.reset();
        }
    }

    public void resetContentContext() {
        synchronized (contentContextLock) {
            contentGeneration.incrementAndGet();
            contentIdentity.reset();
            latestOcrDocument = OcrDocument.empty();
            latestOcrAt = 0L;
            latestMediaKind = GuardConfig.MediaKind.UNKNOWN;
            rolling.reset();
        }
    }

    /** Invalidates and recycles work queued before a content-level release decision. */
    public void cancelPendingWork() {
        contentGeneration.incrementAndGet();
        WorkItem waiting = pending.getAndSet(null);
        if (waiting != null) waiting.bitmap.recycle();
    }

    public long captureIntervalMsFor(GuardConfig.Platform platform) {
        if (platform == null) return config.captureIntervalMs;
        return platform.policy(latestMediaKind).captureIntervalMs;
    }

    private void drain() {
        if (!running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            WorkItem item = pending.getAndSet(null);
            try {
                if (item != null && !closed
                        && item.generation == contentGeneration.get()) {
                    process(item, item.callback);
                }
            } catch (Throwable error) {
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
                if (item != null) main.post(() -> item.callback.onError(message));
            } finally {
                if (item != null) item.bitmap.recycle();
                running.set(false);
                if (pending.get() != null && !closed) drain();
            }
        });
    }

    private void process(WorkItem item, Callback callback) {
        Bitmap bitmap = item.bitmap;
        GuardConfig.Platform platform = item.platform;
        long generation = item.generation;
        long started = SystemClock.elapsedRealtime();
        long now = SystemClock.elapsedRealtime();

        if (item.fingerprint.isNearlyBlank()) {
            postResultIfCurrent(generation, callback, InferenceResult.skipped(
                    platform.id, "画面信息不足，已跳过本帧"));
            return;
        }
        if (config.contentIdentityEnabled
                && contentIdentity.observeVisual(item.fingerprint, now)) {
            long nextGeneration;
            synchronized (contentContextLock) {
                if (generation != contentGeneration.get()) return;
                nextGeneration = contentGeneration.incrementAndGet();
                latestOcrDocument = OcrDocument.empty();
                latestOcrAt = 0L;
                latestMediaKind = GuardConfig.MediaKind.UNKNOWN;
                rolling.reset();
            }
            postResultIfCurrent(nextGeneration, callback, InferenceResult.contentChanged(
                    platform.id, item.fingerprint));
            return;
        }

        GuardConfig.MediaPolicy requestedPolicy = platform.policy(latestMediaKind);
        long requestedOcrInterval = requestedPolicy.ocrIntervalMs;
        if (now - latestOcrAt >= requestedOcrInterval) {
            try {
                OcrDocument recognized = ocr.recognizeDocument(bitmap, config.ocrTimeoutMs);
                synchronized (contentContextLock) {
                    if (generation != contentGeneration.get()) return;
                    latestOcrDocument = recognized;
                    latestOcrAt = SystemClock.elapsedRealtime();
                }
            } catch (Exception ignored) {
                // A visual-only result is still useful; a stale OCR result expires below.
            }
        }
        boolean ocrAvailable = latestOcrAt != 0
                && SystemClock.elapsedRealtime() - latestOcrAt <= requestedOcrInterval * 3L;
        OcrDocument usableDocument = ocrAvailable ? latestOcrDocument : OcrDocument.empty();
        String usableText = usableDocument.text;
        ContentSignals signals = ContentSignals.parse(
                usableDocument, item.accessibilityText, config, platform);
        GuardConfig.MediaPolicy mediaPolicy = platform.policy(signals.mediaKind);
        synchronized (contentContextLock) {
            if (generation != contentGeneration.get()) return;
            if (latestMediaKind != signals.mediaKind) rolling.reset();
            latestMediaKind = signals.mediaKind;
        }
        if (config.contentIdentityEnabled
                && contentIdentity.observeAccount(signals.accountId, SystemClock.elapsedRealtime())) {
            long nextGeneration;
            synchronized (contentContextLock) {
                if (generation != contentGeneration.get()) return;
                nextGeneration = contentGeneration.incrementAndGet();
                latestOcrDocument = OcrDocument.empty();
                latestOcrAt = 0L;
                latestMediaKind = GuardConfig.MediaKind.UNKNOWN;
                rolling.reset();
            }
            postResultIfCurrent(nextGeneration, callback, InferenceResult.contentChanged(
                    platform.id, item.fingerprint));
            return;
        }
        if (!signals.accountId.isEmpty()
                && mediaPolicy.whitelistEnabled
                && platform.whitelistMatches(signals.accountId)) {
            InferenceResult result = new InferenceResult(
                    0.0, 0.0, 0.0, false, GuardConfig.Action.IGNORE,
                    signals.mediaKind, true, signals.accountId, platform.id, ocrAvailable,
                    0L, SystemClock.elapsedRealtime() - started, item.fingerprint,
                    "白名单账号已放行", InferenceResult.ReleaseCause.WHITELIST);
            postResultIfCurrent(generation, callback, result);
            return;
        }
        if (!mediaPolicy.enabled) {
            InferenceResult result = new InferenceResult(
                    0.0, 0.0, 0.0, false, GuardConfig.Action.IGNORE,
                    signals.mediaKind, false, signals.accountId, platform.id, ocrAvailable,
                    0L, SystemClock.elapsedRealtime() - started, item.fingerprint,
                    "当前媒体策略已关闭",
                    InferenceResult.ReleaseCause.DISABLED_MEDIA_POLICY);
            postResultIfCurrent(generation, callback, result);
            return;
        }
        double visualScore = visual.predict(bitmap);
        long visualLatency = SystemClock.elapsedRealtime() - started;
        TextScores textScores = text.predict(usableText);
        RuleFeatures ruleFeatures = rules.evaluate(usableText, signals.accountId, ocrAvailable);
        double fusionScore = fusion.predict(visualScore, textScores, ruleFeatures);
        PolicyDecision policy = policyEngine.evaluate(
                fusionScore, usableText, item.accessibilityText, platform, signals);
        RollingEvidence.Decision decision;
        synchronized (contentContextLock) {
            if (generation != contentGeneration.get()) return;
            decision = rolling.add(
                    policy.adjustedScore, SystemClock.elapsedRealtime(), policy.threshold,
                    mediaPolicy.evidenceFrames, mediaPolicy.evidenceResetGapMs,
                    mediaPolicy.evidenceAggregation);
        }
        GuardConfig.Action windowAction = policy.whitelisted
                ? GuardConfig.Action.IGNORE
                : mediaPolicy.actionFor(decision.windowScore);
        boolean positive = windowAction != GuardConfig.Action.IGNORE;
        InferenceResult result = new InferenceResult(
                visualScore,
                fusionScore,
                decision.windowScore,
                positive,
                windowAction,
                policy.mediaKind,
                policy.whitelisted,
                policy.accountId,
                platform.id,
                ocrAvailable,
                visualLatency,
                SystemClock.elapsedRealtime() - started,
                item.fingerprint,
                policy.reasonSummary());
        postResultIfCurrent(generation, callback, result);
    }

    private void postResultIfCurrent(long generation, Callback callback,
            InferenceResult result) {
        main.post(() -> {
            if (!closed && generation == contentGeneration.get()) {
                callback.onResult(result);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        synchronized (contentContextLock) {
            contentGeneration.incrementAndGet();
            contentIdentity.reset();
            latestOcrDocument = OcrDocument.empty();
            latestOcrAt = 0L;
            latestMediaKind = GuardConfig.MediaKind.UNKNOWN;
            rolling.reset();
        }
        WorkItem waiting = pending.getAndSet(null);
        if (waiting != null) waiting.bitmap.recycle();
        executor.shutdownNow();
        ocr.close();
        visual.close();
    }
}
