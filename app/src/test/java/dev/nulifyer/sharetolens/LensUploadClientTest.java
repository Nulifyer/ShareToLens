package dev.nulifyer.sharetolens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

public class LensUploadClientTest {
    private MockWebServer server;
    private ImagePreprocessor.PreparedImage image;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        File file = File.createTempFile("lens-test-", ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("jpeg-data".getBytes(StandardCharsets.US_ASCII));
        }
        image = new ImagePreprocessor.PreparedImage(file, 640, 480);
    }

    @After public void tearDown() throws Exception {
        image.close();
        server.shutdown();
    }

    @Test public void uploadsMultipartAndReportsProgress() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .addHeader("Location", "https://lens.google.com/result")
                .addHeader("Set-Cookie", "test=value"));
        RecordingListener listener = new RecordingListener();
        LensUploadClient.Result result = client().upload(image, "test-agent", listener);
        assertEquals("https://lens.google.com/result", result.url);
        assertEquals("test=value", result.cookies.get(0));
        assertEquals(100, (int) listener.progress.get(listener.progress.size() - 1));
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("640,480"));
        assertTrue(body.contains("jpeg-data"));
    }

    @Test public void retriesServerFailuresAtMostThreeTimes() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(302)
                .addHeader("Location", "https://lens.google.com/result"));
        RecordingListener listener = new RecordingListener();
        client().upload(image, "test-agent", listener);
        assertEquals(3, server.getRequestCount());
        assertEquals(2, listener.retries.size());
    }

    @Test public void doesNotRetryPermanentClientError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400));
        try {
            client().upload(image, "test-agent", new RecordingListener());
            fail("Expected failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("400"));
        }
        assertEquals(1, server.getRequestCount());
    }

    @Test public void rejectsUnexpectedRedirectHost() throws Exception {
        try {
            LensUploadClient.validateLensRedirect(URI.create("https://example.com/result"));
            fail("Expected failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unexpected redirect"));
        }
    }

    @Test public void cancellationStopsBeforeRequest() throws Exception {
        LensUploadClient client = client();
        client.cancel();
        try {
            client.upload(image, "test-agent", new RecordingListener());
            fail("Expected cancellation");
        } catch (UploadCancelledException expected) {
            assertEquals(0, server.getRequestCount());
        }
    }

    private LensUploadClient client() {
        return new LensUploadClient(new OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                server.url("/v3/upload").toString());
    }

    private static final class RecordingListener implements LensUploadClient.Listener {
        final List<Integer> progress = new ArrayList<>();
        final List<Integer> retries = new ArrayList<>();
        @Override public void onProgress(int percent) { progress.add(percent); }
        @Override public void onRetry(int nextAttempt, int maxAttempts) { retries.add(nextAttempt); }
    }
}
