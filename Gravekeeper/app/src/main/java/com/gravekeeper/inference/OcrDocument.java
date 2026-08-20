package com.gravekeeper.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OCR text plus normalized line geometry. No recognized content is persisted here. */
public final class OcrDocument {
    public static final class Line {
        public final String text;
        public final double left;
        public final double top;
        public final double right;
        public final double bottom;

        public Line(String text, double left, double top, double right, double bottom) {
            this.text = text == null ? "" : text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean hasGeometry() {
            return left >= 0.0 && top >= 0.0 && right >= left && bottom >= top;
        }

        public double centerX() { return (left + right) * 0.5; }
        public double centerY() { return (top + bottom) * 0.5; }
    }

    public final String text;
    public final List<Line> lines;

    public OcrDocument(String text, List<Line> lines) {
        this.text = text == null ? "" : text;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public static OcrDocument empty() {
        return new OcrDocument("", Collections.emptyList());
    }

    public static OcrDocument fromText(String text) {
        List<Line> lines = new ArrayList<>();
        String value = text == null ? "" : text;
        for (String line : value.split("[\r\n]+")) {
            if (!line.trim().isEmpty()) lines.add(new Line(line, -1, -1, -1, -1));
        }
        return new OcrDocument(value, lines);
    }
}
