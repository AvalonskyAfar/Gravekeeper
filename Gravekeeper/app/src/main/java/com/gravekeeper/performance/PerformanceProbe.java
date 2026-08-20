package com.gravekeeper.performance;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import com.gravekeeper.config.GuardConfig;
import com.gravekeeper.config.BundleValidator;
import com.gravekeeper.inference.OcrEngine;
import com.gravekeeper.inference.VisualClassifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PerformanceProbe {
    public enum Level { RECOMMENDED, DEGRADED, HIGH_RISK, UNKNOWN }

    public static final class Result {
        public final Level level;
        public final long p50Ms;
        public final long p95Ms;
        public final long ocrP50Ms;
        public final long ocrP95Ms;
        public final double sustainedFps;
        public final long peakPssMb;
        public final int thermalBefore;
        public final int thermalAfter;
        public final long batteryMicroAhDelta;
        public final String note;

        Result(Level level, long p50Ms, long p95Ms, long ocrP50Ms,
                long ocrP95Ms, double sustainedFps, long peakPssMb,
                int thermalBefore, int thermalAfter, long batteryMicroAhDelta,
                String note) {
            this.level = level;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.ocrP50Ms = ocrP50Ms;
            this.ocrP95Ms = ocrP95Ms;
            this.sustainedFps = sustainedFps;
            this.peakPssMb = peakPssMb;
            this.thermalBefore = thermalBefore;
            this.thermalAfter = thermalAfter;
            this.batteryMicroAhDelta = batteryMicroAhDelta;
            this.note = note;
        }
    }

    private PerformanceProbe() {}

    public static Result run(Context context, GuardConfig config) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        BatteryManager battery = (BatteryManager) context.getSystemService(
                Context.BATTERY_SERVICE);
        int thermalBefore = power.getCurrentThermalStatus();
        long chargeBefore = battery.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long peakPssKb = readPssKb(context);
        Bitmap input = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888);
        input.eraseColor(0xff202020);
        long[] visualSamples = new long[config.performanceSampleCount];
        long[] ocrSamples = new long[config.performanceOcrSampleCount];
        long sustainedCount = 0L;
        long sustainedElapsed = 0L;
        List<String> caveats = new ArrayList<>();
        try (VisualClassifier visual = new VisualClassifier(
                BundleValidator.active(context).file(
                        "models/gravekeeper_visual.tflite"));
             OcrEngine ocr = new OcrEngine()) {
            visual.predict(input);
            visual.predict(input);
            for (int i = 0; i < visualSamples.length; i++) {
                long started = SystemClock.elapsedRealtime();
                visual.predict(input);
                visualSamples[i] = SystemClock.elapsedRealtime() - started;
                peakPssKb = Math.max(peakPssKb, readPssKb(context));
            }
            for (int i = 0; i < ocrSamples.length; i++) {
                long started = SystemClock.elapsedRealtime();
                ocr.recognize(input, config.ocrTimeoutMs);
                ocrSamples[i] = SystemClock.elapsedRealtime() - started;
                peakPssKb = Math.max(peakPssKb, readPssKb(context));
            }
            long sustainedStarted = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - sustainedStarted
                    < config.performanceSustainedDurationMs) {
                visual.predict(input);
                sustainedCount++;
                peakPssKb = Math.max(peakPssKb, readPssKb(context));
            }
            sustainedElapsed = Math.max(1L,
                    SystemClock.elapsedRealtime() - sustainedStarted);
        } catch (IOException | RuntimeException error) {
            input.recycle();
            return unknownResult(thermalBefore, power.getCurrentThermalStatus(),
                    peakPssKb, safeMessage(error));
        } catch (Exception error) {
            input.recycle();
            return unknownResult(thermalBefore, power.getCurrentThermalStatus(),
                    peakPssKb, "OCR 基准失败：" + safeMessage(error));
        } finally {
            if (!input.isRecycled()) input.recycle();
        }

        Arrays.sort(visualSamples);
        Arrays.sort(ocrSamples);
        long visualP50 = percentile(visualSamples, 0.50);
        long visualP95 = percentile(visualSamples, 0.95);
        long ocrP50 = percentile(ocrSamples, 0.50);
        long ocrP95 = percentile(ocrSamples, 0.95);
        double sustainedFps = sustainedCount * 1000.0 / sustainedElapsed;
        long peakPssMb = Math.max(1L, (peakPssKb + 1023L) / 1024L);
        int thermalAfter = power.getCurrentThermalStatus();
        long chargeAfter = battery.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long chargeDelta = validCharge(chargeBefore) && validCharge(chargeAfter)
                ? chargeAfter - chargeBefore : Long.MIN_VALUE;
        if (chargeDelta == Long.MIN_VALUE) caveats.add("设备未提供电量计量值");
        caveats.add("短时基准不能代替多机型持续温升与耗电校准");
        caveats.add("骁龙 888 仅是 SOCPK 外部参考锚点，不是芯片白名单");

        Level level = classify(visualP95, ocrP95, peakPssMb, thermalAfter,
                config.recommendedP95Ms, config.recommendedOcrP95Ms,
                config.recommendedPeakPssMb, config.degradedP95Ms,
                config.degradedOcrP95Ms, config.degradedPeakPssMb,
                config.severeThermalStatus);
        return new Result(level, visualP50, visualP95, ocrP50, ocrP95,
                sustainedFps, peakPssMb, thermalBefore, thermalAfter,
                chargeDelta, String.join("；", caveats));
    }

    public static void persist(Context context, Result result, boolean enabled) {
        if (!enabled) return;
        context.getSharedPreferences("guard_performance", Context.MODE_PRIVATE).edit()
                .putString("level", result.level.name())
                .putLong("p50_ms", result.p50Ms)
                .putLong("p95_ms", result.p95Ms)
                .putLong("ocr_p50_ms", result.ocrP50Ms)
                .putLong("ocr_p95_ms", result.ocrP95Ms)
                .putLong("sustained_fps_milli", Math.round(result.sustainedFps * 1000.0))
                .putLong("peak_pss_mb", result.peakPssMb)
                .putInt("thermal_before", result.thermalBefore)
                .putInt("thermal_after", result.thermalAfter)
                .putLong("battery_micro_ah_delta", result.batteryMicroAhDelta)
                .putString("note", result.note)
                .putLong("measured_at", System.currentTimeMillis())
                .apply();
    }

    /** Pure result classification shared by the probe and its state-matrix tests. */
    static Level classify(long visualP95, long ocrP95, long peakPssMb,
            int thermalAfter, long recommendedP95Ms,
            long recommendedOcrP95Ms, long recommendedPeakPssMb,
            long degradedP95Ms, long degradedOcrP95Ms,
            long degradedPeakPssMb, int severeThermalStatus) {
        boolean recommended = visualP95 <= recommendedP95Ms
                && ocrP95 <= recommendedOcrP95Ms
                && peakPssMb <= recommendedPeakPssMb
                && thermalAfter < severeThermalStatus;
        if (recommended) return Level.RECOMMENDED;
        boolean degraded = visualP95 <= degradedP95Ms
                && ocrP95 <= degradedOcrP95Ms
                && peakPssMb <= degradedPeakPssMb
                && thermalAfter < severeThermalStatus;
        return degraded ? Level.DEGRADED : Level.HIGH_RISK;
    }

    private static Result unknownResult(int thermalBefore, int thermalAfter,
            long peakPssKb, String note) {
        return new Result(Level.UNKNOWN, 0, 0, 0, 0, 0.0,
                Math.max(0L, peakPssKb / 1024L), thermalBefore, thermalAfter,
                Long.MIN_VALUE, note);
    }

    private static long readPssKb(Context context) {
        ActivityManager activity = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        Debug.MemoryInfo[] info = activity.getProcessMemoryInfo(
                new int[] {Process.myPid()});
        return info.length == 0 ? 0L : info[0].getTotalPss();
    }

    private static boolean validCharge(long value) {
        return value != Long.MIN_VALUE && value != 0L;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
