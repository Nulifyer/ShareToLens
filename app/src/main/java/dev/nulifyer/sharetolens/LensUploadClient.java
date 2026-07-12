package dev.nulifyer.sharetolens;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

final class LensUploadClient {
    static final int MAX_ATTEMPTS = 3;
    private static final String DEFAULT_UPLOAD_URL = "https://lens.google.com/v3/upload";
    private static final URI LENS_BASE_URI = URI.create("https://lens.google.com/");
    private static final int BUFFER_SIZE = 64 * 1024;

    interface Listener {
        void onProgress(int percent);
        void onRetry(int nextAttempt, int maxAttempts);
    }

    static final class Result {
        final String url;
        final List<String> cookies;

        Result(String url, List<String> cookies) {
            this.url = url;
            this.cookies = cookies;
        }
    }

    private final OkHttpClient client;
    private final String uploadUrl;
    private volatile boolean cancelled;
    private volatile Call activeCall;

    LensUploadClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build(), DEFAULT_UPLOAD_URL);
    }

    LensUploadClient(OkHttpClient client, String uploadUrl) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        this.uploadUrl = uploadUrl;
    }

    Result upload(ImagePreprocessor.PreparedImage image, String userAgent, Listener listener)
            throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            if (attempt > 1) listener.onRetry(attempt, MAX_ATTEMPTS);
            Response response = null;
            try {
                Request request = buildRequest(image, userAgent, listener);
                Call call = client.newCall(request);
                activeCall = call;
                response = call.execute();
                int code = response.code();
                if (code >= 300 && code < 400) {
                    String location = response.header("Location");
                    if (location == null) throw new PermanentUploadException("Upload response has no redirect");
                    URI redirect = validateLensRedirect(LENS_BASE_URI.resolve(location));
                    return new Result(redirect.toString(), response.headers("Set-Cookie"));
                }
                if (!isRetryable(code) || attempt == MAX_ATTEMPTS) {
                    throw new PermanentUploadException("Upload failed with HTTP " + code);
                }
                sleepBeforeRetry(attempt, retryAfterMillis(response.headers()));
            } catch (IOException e) {
                if (cancelled) throw new UploadCancelledException();
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS || !isRetryable(e)) throw e;
                sleepBeforeRetry(attempt, 0);
            } finally {
                activeCall = null;
                if (response != null) response.close();
            }
        }
        throw lastFailure == null ? new IOException("Upload failed") : lastFailure;
    }

    void cancel() {
        cancelled = true;
        Call call = activeCall;
        if (call != null) call.cancel();
    }

    private Request buildRequest(ImagePreprocessor.PreparedImage image, String userAgent, Listener listener) {
        String boundary = "ShareToLens-" + UUID.randomUUID();
        byte[] prefix = ascii("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"processed_image_dimensions\"\r\n\r\n"
                + image.width + "," + image.height + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"sbisrc\"\r\n\r\ncr_1_0_0\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"encoded_image\"; filename=\"image.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n");
        byte[] suffix = ascii("\r\n--" + boundary + "--\r\n");
        RequestBody body = new ProgressRequestBody(image.file, prefix, suffix, listener, this::isCancelled);
        return new Request.Builder()
                .url(uploadUrl + "?ep=ccm&re=df&st=" + System.currentTimeMillis())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", userAgent)
                .header("X-Client-Side-Image-Upload", "true")
                .post(body)
                .build();
    }

    private boolean isCancelled() { return cancelled; }

    private void checkCancelled() throws UploadCancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new UploadCancelledException();
    }

    private void sleepBeforeRetry(int attempt, long serverDelay) throws IOException {
        long delay = serverDelay > 0 ? Math.min(serverDelay, 30_000) : (1L << (attempt - 1)) * 1_000;
        long remaining = delay;
        while (remaining > 0) {
            checkCancelled();
            long slice = Math.min(remaining, 200);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UploadCancelledException();
            }
            remaining -= slice;
        }
    }

    static boolean isRetryable(int code) {
        return code == 408 || code == 429 || code >= 500 && code <= 599;
    }

    private static boolean isRetryable(IOException error) {
        return !(error instanceof UploadCancelledException) && !(error instanceof PermanentUploadException);
    }

    private static long retryAfterMillis(Headers headers) {
        String value = headers.get("Retry-After");
        if (value == null) return 0;
        try {
            return Long.parseLong(value.trim()) * 1_000;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static URI validateLensRedirect(URI uri) throws IOException {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null) throw new PermanentUploadException("Unexpected redirect");
        String normalized = host.toLowerCase(Locale.US);
        if (!"lens.google.com".equals(normalized) && !"www.google.com".equals(normalized)
                && !"accounts.google.com".equals(normalized)) {
            throw new PermanentUploadException("Unexpected redirect");
        }
        return uri;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private interface CancellationCheck { boolean isCancelled(); }

    private static final class PermanentUploadException extends IOException {
        PermanentUploadException(String message) { super(message); }
    }

    private static final class ProgressRequestBody extends RequestBody {
        private final File file;
        private final byte[] prefix;
        private final byte[] suffix;
        private final Listener listener;
        private final CancellationCheck cancellation;

        ProgressRequestBody(File file, byte[] prefix, byte[] suffix, Listener listener,
                            CancellationCheck cancellation) {
            this.file = file;
            this.prefix = prefix;
            this.suffix = suffix;
            this.listener = listener;
            this.cancellation = cancellation;
        }

        @Override public MediaType contentType() { return null; }
        @Override public long contentLength() { return prefix.length + file.length() + suffix.length; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            long total = contentLength();
            long written = 0;
            sink.write(prefix);
            written += prefix.length;
            listener.onProgress((int) (written * 100 / total));
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancellation.isCancelled()) throw new UploadCancelledException();
                    sink.write(buffer, 0, read);
                    written += read;
                    listener.onProgress((int) (written * 100 / total));
                }
            }
            sink.write(suffix);
            listener.onProgress(100);
        }
    }
}
