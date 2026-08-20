package com.gravekeeper.inference;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public final class VisualClassifier implements AutoCloseable {
    public static final int INPUT_WIDTH = 192;
    public static final int INPUT_HEIGHT = 416;

    private final Interpreter interpreter;
    private final ByteBuffer input = ByteBuffer.allocateDirect(
            1 * 3 * INPUT_HEIGHT * INPUT_WIDTH * Float.BYTES)
            .order(ByteOrder.nativeOrder());
    private final float[][] output = new float[1][1];
    private final int[] pixels = new int[INPUT_WIDTH * INPUT_HEIGHT];

    public VisualClassifier(AssetManager assets, String path) throws IOException {
        MappedByteBuffer model = mapAsset(assets, path);
        interpreter = createInterpreter(model);
        validateContract();
    }

    public VisualClassifier(File file) throws IOException {
        MappedByteBuffer model = mapFile(file);
        interpreter = createInterpreter(model);
        validateContract();
    }

    private static Interpreter createInterpreter(MappedByteBuffer model) {
        Interpreter.Options options = new Interpreter.Options()
                .setNumThreads(4)
                .setUseXNNPACK(true);
        return new Interpreter(model, options);
    }

    private void validateContract() throws IOException {
        int[] expectedInput = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
        int[] expectedOutput = {1, 1};
        if (!Arrays.equals(interpreter.getInputTensor(0).shape(), expectedInput)
                || interpreter.getInputTensor(0).dataType() != DataType.FLOAT32) {
            throw new IOException("Visual model input must be float32 [1,3,416,192]");
        }
        if (!Arrays.equals(interpreter.getOutputTensor(0).shape(), expectedOutput)
                || interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
            throw new IOException("Visual model output must be float32 [1,1]");
        }
    }

    private static MappedByteBuffer mapFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return input.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, 0, input.getChannel().size());
        } catch (IOException error) {
            throw new IOException("缺少或无法读取已验证视觉模型：" + file, error);
        }
    }

    public synchronized double predict(Bitmap source) {
        Bitmap letterboxed = letterbox(source);
        letterboxed.getPixels(
                pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT);
        input.rewind();
        for (int channel = 0; channel < 3; channel++) {
            int shift = channel == 0 ? 16 : channel == 1 ? 8 : 0;
            for (int color : pixels) {
                input.putFloat(((color >> shift) & 0xff) / 255.0f);
            }
        }
        letterboxed.recycle();
        interpreter.run(input, output);
        return Math.max(0.0, Math.min(1.0, output[0][0]));
    }

    private static Bitmap letterbox(Bitmap source) {
        Bitmap target = Bitmap.createBitmap(
                INPUT_WIDTH, INPUT_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.drawColor(Color.BLACK);
        float scale = Math.min(
                INPUT_WIDTH / (float) source.getWidth(),
                INPUT_HEIGHT / (float) source.getHeight());
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        float left = (INPUT_WIDTH - width) / 2.0f;
        float top = (INPUT_HEIGHT - height) / 2.0f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new RectF(left, top, left + width, top + height), paint);
        return target;
    }

    private static MappedByteBuffer mapAsset(AssetManager assets, String path) throws IOException {
        try (AssetFileDescriptor descriptor = assets.openFd(path);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return input.getChannel().map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.getStartOffset(),
                    descriptor.getDeclaredLength());
        } catch (IOException error) {
            throw new IOException(
                    "缺少或无法读取视觉模型 assets/" + path + "；请先完成服务器端 LiteRT 导出",
                    error);
        }
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
