package dev.kyle.sharetolens;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String LENS_UPLOAD_URL = "https://lens.google.com/upload";
    private static final String FORM_FIELD_NAME = "encoded_image";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(48, 48, 48, 48);
        statusView.setTextSize(18);
        setContentView(statusView);

        Uri imageUri = findSharedImage(getIntent());
        if (imageUri == null) {
            setStatus("Share an image here to search it with Google Lens.");
            return;
        }

        setStatus("Uploading image to Google Lens...");
        executor.execute(() -> uploadAndOpen(imageUri));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void uploadAndOpen(Uri imageUri) {
        try {
            String resultUrl = uploadToLens(imageUri);
            mainHandler.post(() -> openBrowser(resultUrl));
        } catch (Exception exception) {
            mainHandler.post(() -> {
                setStatus("Could not send this image to Google Lens.");
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    private Uri findSharedImage(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }

        Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) {
            return stream;
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            return clipData.getItemAt(0).getUri();
        }

        return null;
    }

    private String uploadToLens(Uri imageUri) throws IOException {
        ContentResolver resolver = getContentResolver();
        String mimeType = resolver.getType(imageUri);
        if (mimeType == null) {
            mimeType = "image/jpeg";
        }

        String fileName = fileNameFor(imageUri, mimeType);
        String boundary = "ShareToLens-" + UUID.randomUUID();
        HttpURLConnection connection = (HttpURLConnection) new URL(LENS_UPLOAD_URL).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("User-Agent", browserLikeUserAgent());

        try (OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
            InputStream openedImageInput = resolver.openInputStream(imageUri);
            if (openedImageInput == null) {
                throw new IOException("Unable to open the shared image.");
            }

            try (InputStream imageInput = new BufferedInputStream(openedImageInput)) {
                writeAscii(output, "--" + boundary + "\r\n");
                writeAscii(output, "Content-Disposition: form-data; name=\"" + FORM_FIELD_NAME
                        + "\"; filename=\"" + escapeQuoted(fileName) + "\"\r\n");
                writeAscii(output, "Content-Type: " + mimeType + "\r\n\r\n");
                copy(imageInput, output);
                writeAscii(output, "\r\n--" + boundary + "--\r\n");
            }
        }

        int responseCode = connection.getResponseCode();
        String location = connection.getHeaderField("Location");
        drainQuietly(connection);
        connection.disconnect();

        if (responseCode >= 300 && responseCode < 400 && location != null && !location.isEmpty()) {
            return absolutize(LENS_UPLOAD_URL, location);
        }

        throw new IOException("Google Lens returned HTTP " + responseCode + ".");
    }

    private void openBrowser(String resultUrl) {
        setStatus("Opening Google Lens...");
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(resultUrl));
        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException exception) {
            setStatus("No browser is available to open Google Lens.");
        }
    }

    private String fileNameFor(Uri imageUri, String mimeType) {
        try (Cursor cursor = getContentResolver().query(
                imageUri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String displayName = cursor.getString(index);
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        return displayName;
                    }
                }
            }
        }

        return "shared-image" + extensionFor(mimeType);
    }

    private String extensionFor(String mimeType) {
        String normalized = mimeType.toLowerCase(Locale.US);
        if (normalized.equals("image/png")) {
            return ".png";
        }
        if (normalized.equals("image/webp")) {
            return ".webp";
        }
        if (normalized.equals("image/gif")) {
            return ".gif";
        }
        return ".jpg";
    }

    private void setStatus(String status) {
        statusView.setText(status);
    }

    private static String absolutize(String base, String location) throws IOException {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Google Lens returned an invalid redirect.", exception);
        }
    }

    private static String browserLikeUserAgent() {
        return "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    }

    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static void drainQuietly(HttpURLConnection connection) {
        try (InputStream input = connection.getErrorStream() != null
                ? connection.getErrorStream()
                : connection.getInputStream()) {
            if (input == null) {
                return;
            }
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) {
                // Drain so the connection can close cleanly.
            }
        } catch (IOException ignored) {
            // Nothing useful to do while already handling the response.
        }
    }
}
