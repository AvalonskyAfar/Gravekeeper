package com.gravekeeper.inference;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

public final class OcrEngine implements AutoCloseable {
    private final TextRecognizer recognizer = TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build());

    public String recognize(Bitmap bitmap, long timeoutMs) throws Exception {
        return recognizeDocument(bitmap, timeoutMs).text;
    }

    public OcrDocument recognizeDocument(Bitmap bitmap, long timeoutMs) throws Exception {
        Text result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                timeoutMs,
                TimeUnit.MILLISECONDS);
        if (result == null) return OcrDocument.empty();
        List<OcrDocument.Line> lines = new ArrayList<>();
        double width = Math.max(1, bitmap.getWidth());
        double height = Math.max(1, bitmap.getHeight());
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) {
                    lines.add(new OcrDocument.Line(
                            line.getText(), -1, -1, -1, -1));
                } else {
                    lines.add(new OcrDocument.Line(line.getText(),
                            clamp01(box.left / width), clamp01(box.top / height),
                            clamp01(box.right / width), clamp01(box.bottom / height)));
                }
            }
        }
        return new OcrDocument(result.getText(), lines);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
