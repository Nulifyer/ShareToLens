package dev.nulifyer.sharetolens;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "ShareToLens";
    private static final String LENS_V3_URL = "https://lens.google.com/v3/upload";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private WebView webView;
    private ProgressBar progressBar;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "LogConditional"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        
        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(desktopUserAgent());
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                progressBar.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Log.e(TAG, "WebView Error: " + error.getDescription() + " for " + request.getUrl());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Log.e(TAG, "WebView HTTP Error: " + errorResponse.getStatusCode() + " for " + request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "JS Console: " + consoleMessage.message());
                return true;
            }
        });

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(150, 150);
        lp.gravity = android.view.Gravity.CENTER;
        
        root.addView(webView);
        root.addView(progressBar, lp);
        setContentView(root);

        Uri imageUri = findSharedImage(getIntent());
        if (imageUri == null) {
            Toast.makeText(this, "Please share an image to this app.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Log.d(TAG, "Starting upload for: " + imageUri);
        executor.execute(() -> uploadAndLoad(imageUri));
    }

    private void uploadAndLoad(Uri imageUri) {
        try {
            String resultUrl = uploadToGoogle(imageUri);
            Log.d(TAG, "Upload success. Loading URL: " + resultUrl);
            runOnUiThread(() -> webView.loadUrl(resultUrl));
        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    private Uri findSharedImage(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) return stream;
            ClipData cd = intent.getClipData();
            if (cd != null && cd.getItemCount() > 0) return cd.getItemAt(0).getUri();
        }
        return null;
    }

    private String uploadToGoogle(Uri imageUri) throws IOException {
        ContentResolver resolver = getContentResolver();
        String boundary = "ShareToLens-" + UUID.randomUUID();
        String uploadUrl = LENS_V3_URL + "?ep=ccm&re=df&st=" + System.currentTimeMillis();

        HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", desktopUserAgent());
        conn.setRequestProperty("X-Client-Side-Image-Upload", "true");

        try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
            InputStream in = resolver.openInputStream(imageUri);
            if (in == null) throw new IOException("Open fail");
            
            writeField(out, boundary, "processed_image_dimensions", "1000,1000");
            writeField(out, boundary, "sbisrc", "cr_1_0_0");
            
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"encoded_image\"; filename=\"i.jpg\"\r\n");
            writeAscii(out, "Content-Type: image/jpeg\r\n\r\n");
            
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            in.close();
            
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
        }

        int code = conn.getResponseCode();
        Log.d(TAG, "Upload response code: " + code);

        // Sync cookies from response to WebView
        Map<String, List<String>> headerFields = conn.getHeaderFields();
        List<String> cookiesHeader = headerFields.get("Set-Cookie");
        if (cookiesHeader != null) {
            CookieManager cookieManager = CookieManager.getInstance();
            for (String cookie : cookiesHeader) {
                cookieManager.setCookie("https://lens.google.com", cookie);
                Log.d(TAG, "Setting cookie: " + cookie);
            }
        }

        String loc = conn.getHeaderField("Location");
        if (loc == null) throw new IOException("No redirect. Code: " + code);
        
        return URI.create("https://lens.google.com/").resolve(loc).toString();
    }

    private static String desktopUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    }

    private static void writeField(OutputStream out, String b, String name, String val) throws IOException {
        writeAscii(out, "--" + b + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeAscii(out, val + "\r\n");
    }

    private static void writeAscii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
