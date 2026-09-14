package dev.nulifyer.sharetolens;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

final class ImagePreprocessor {
    static final int MAX_EDGE = 2048;
    static final int JPEG_QUALITY = 85;
    static final long MAX_SOURCE_BYTES = 25L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long STALE_FILE_AGE_MS = 6L * 60L * 60L * 1000L;
    private static final String[] TEMP_FILE_PREFIXES = {"lens-source-", "lens-upload-"};

    private ImagePreprocessor() {}

    static void cleanStaleFiles(File cacheDir) {
        long staleBefore = System.currentTimeMillis() - STALE_FILE_AGE_MS;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                for (String prefix : TEMP_FILE_PREFIXES) {
                    if (file.isFile() && file.lastModified() < staleBefore
                            && file.getName().startsWith(prefix)) {
                        file.delete();
                        break;
                    }
                }
            }
        }

        File cameraDir = new File(cacheDir, "camera");
        File[] cameraFiles = cameraDir.listFiles();
        if (cameraFiles != null) {
            for (File file : cameraFiles) {
                if (file.isFile() && file.lastModified() < staleBefore
                        && file.getName().startsWith("lens-camera-")) file.delete();
            }
        }
    }

    static PreparedImage prepare(ContentResolver resolver, Uri uri, File cacheDir,
                                 BooleanSupplier cancelled) throws IOException {
        File source = File.createTempFile("lens-source-", ".image", cacheDir);
        File output = null;
        try {
            copySource(resolver, uri, source, cancelled);
            checkCancelled(cancelled);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Could not decode image");

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            if (decoded == null) throw new IOException("Could not decode image");
            Bitmap transformed = null;
            Bitmap flattened = null;
            try {
                transformed = transform(decoded, exifOrientation(source));
                checkCancelled(cancelled);
                flattened = flattenAndScale(transformed);
                output = File.createTempFile("lens-upload-", ".jpg", cacheDir);
                try (FileOutputStream out = new FileOutputStream(output)) {
                    if (!flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        throw new IOException("Could not encode image");
                    }
                }
                checkCancelled(cancelled);
                return new PreparedImage(output, flattened.getWidth(), flattened.getHeight());
            } finally {
                recycleDistinct(flattened, transformed, decoded);
                recycleDistinct(transformed, decoded);
                decoded.recycle();
            }
        } catch (IOException | RuntimeException e) {
            if (output != null) output.delete();
            throw e;
        } finally {
            source.delete();
        }
    }

    private static void copySource(ContentResolver resolver, Uri uri, File destination,
                                   BooleanSupplier cancelled) throws IOException {
        try (InputStream in = resolver.openInputStream(uri); FileOutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IOException("Could not open image");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long copied = 0;
            while ((read = in.read(buffer)) != -1) {
                checkCancelled(cancelled);
                copied += read;
                if (copied > MAX_SOURCE_BYTES) {
                    throw new InputTooLargeException();
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width / sample, height / sample) > MAX_EDGE * 2) sample *= 2;
        return sample;
    }

    private static int exifOrientation(File source) {
        try (FileInputStream in = new FileInputStream(source)) {
            return new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap transform(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static Bitmap flattenAndScale(Bitmap source) {
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, null, new android.graphics.Rect(0, 0, width, height), null);
        return output;
    }

    private static void recycleDistinct(Bitmap bitmap, Bitmap... others) {
        if (bitmap == null) return;
        for (Bitmap other : others) if (bitmap == other) return;
        bitmap.recycle();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws UploadCancelledException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new UploadCancelledException();
    }

    static final class PreparedImage implements AutoCloseable {
        final File file;
        final int width;
        final int height;

        PreparedImage(File file, int width, int height) {
            this.file = file;
            this.width = width;
            this.height = height;
        }

        @Override public void close() { file.delete(); }
    }

    static final class InputTooLargeException extends IOException {
        InputTooLargeException() {
            super("Image is larger than the 25 MB input limit");
        }
    }
}
