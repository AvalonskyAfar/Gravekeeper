package com.gravekeeper;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;

import com.gravekeeper.config.GuardConfig;
import com.gravekeeper.inference.ContentSignals;
import com.gravekeeper.inference.OcrDocument;
import com.gravekeeper.inference.OcrEngine;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

/** Runs the production OCR/account pipeline against screenshots pushed by the host. */
public final class RealScreenshotOcrRunner extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? Bundle.EMPTY : arguments;
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        new Thread(this::runChecks, "real-screenshot-ocr").start();
    }

    private void runChecks() {
        Bundle results = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            Context target = getTargetContext();
            GuardConfig config = bundledConfig(target);
            GuardConfig.Platform bundled = findPlatform(config, "douyin");
            GuardConfig.Platform platform = new GuardConfig.Platform(
                    bundled.id, bundled.name, bundled.enabled, bundled.packages,
                    Set.of("东方甄选"), bundled.riskBias, bundled.shortVideo, bundled.live,
                    bundled.unknown, bundled.accountDetection,
                    GuardConfig.WhitelistMatchMode.PREFIX);
            File directory = new File(target.getFilesDir(), "real-screenshots");
            String[] names = arguments.getString("files", "").split(",");
            Set<String> expectedNonmatches = new HashSet<>(Arrays.asList(
                    arguments.getString("expected_nonmatches", "").split(",")));
            if (names.length == 0 || names[0].trim().isEmpty()) {
                throw new IllegalArgumentException("Pass -e files name1.png,name2.png");
            }
            try (OcrEngine ocr = new OcrEngine()) {
                int checked = 0;
                int matched = 0;
                int passed = 0;
                for (String rawName : names) {
                    String name = rawName.trim();
                    Bitmap bitmap = BitmapFactory.decodeFile(new File(directory, name).getPath());
                    if (bitmap == null) throw new IllegalStateException("Cannot decode " + name);
                    OcrDocument document;
                    try {
                        document = ocr.recognizeDocument(bitmap, 20_000L);
                    } finally {
                        bitmap.recycle();
                    }
                    ContentSignals signals = ContentSignals.parse(
                            document, "直播间", config, platform);
                    boolean whitelistMatched = platform.whitelistMatches(signals.accountId);
                    boolean expectedWhitelist = !expectedNonmatches.contains(name);
                    checked++;
                    if (whitelistMatched) matched++;
                    if (whitelistMatched == expectedWhitelist) passed++;
                    results.putString("case_" + checked,
                            name + " | top=" + topLines(document)
                                    + " | account=" + signals.accountId
                                    + " | whitelist=" + whitelistMatched
                                    + " | expected=" + expectedWhitelist);
                }
                results.putString("summary", "checked=" + checked + ", matched=" + matched
                        + ", passed=" + passed);
                if (passed != checked) resultCode = Activity.RESULT_CANCELED;
            }
        } catch (Throwable error) {
            resultCode = Activity.RESULT_CANCELED;
            results.putString("error", error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()));
            results.putString("stack", Arrays.toString(error.getStackTrace()));
        }
        finish(resultCode, results);
    }

    private static GuardConfig.Platform findPlatform(GuardConfig config, String id) {
        for (GuardConfig.Platform platform : config.platforms) {
            if (id.equals(platform.id)) return platform;
        }
        throw new IllegalStateException("Missing platform " + id);
    }

    private static GuardConfig bundledConfig(Context context) throws Exception {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("config/guard_runtime_config.json"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');
        }
        return new GuardConfig(new JSONObject(json.toString()));
    }

    private static String topLines(OcrDocument document) {
        StringBuilder value = new StringBuilder();
        for (OcrDocument.Line line : document.lines) {
            if (!line.hasGeometry() || line.centerY() > 0.14) continue;
            if (value.length() > 0) value.append(" / ");
            value.append(String.format(Locale.US, "%s@(%.3f,%.3f)-(%.3f,%.3f)",
                    line.text.replace('\n', ' ').replace('\r', ' '),
                    line.left, line.top, line.right, line.bottom));
            if (value.length() >= 400) break;
        }
        return value.toString();
    }
}
