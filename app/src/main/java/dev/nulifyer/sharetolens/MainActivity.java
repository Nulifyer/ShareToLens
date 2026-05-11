package dev.nulifyer.sharetolens;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "ShareToLens";
    private static final String LENS_V3_URL = "https://lens.google.com/v3/upload";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FrameLayout loadingOverlay;
    private ProgressBar pageProgress;
    private WebView webView;
    private String webUserAgent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createBrowserUi());

        Uri imageUri = findSharedImage(getIntent());
        if (imageUri == null) {
            Toast.makeText(this, R.string.msg_share_hint, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Log.d(TAG, "Starting upload");
        executor.execute(() -> uploadAndLoad(imageUri));
    }

    private void uploadAndLoad(Uri imageUri) {
        try {
            String resultUrl = uploadToGoogle(imageUri);
            Log.d(TAG, "Upload success. Loading results");
            runOnUiThread(() -> webView.loadUrl(resultUrl));
        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private View createBrowserUi() {
        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webUserAgent = settings.getUserAgentString();

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new LensWebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setVisibility(View.GONE);
        root.addView(pageProgress, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (3 * getResources().getDisplayMetrics().density),
                Gravity.TOP
        ));

        loadingOverlay = createLoadingOverlay();
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        return root;
    }

    private FrameLayout createLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFFFFFFFF);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        content.addView(spinner);

        TextView message = new TextView(this);
        message.setText(R.string.msg_uploading);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        messageParams.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        content.addView(message, messageParams);

        overlay.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return overlay;
    }

    private Uri findSharedImage(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = sharedStreamExtra(intent);
            if (stream != null) return stream;
            ClipData cd = intent.getClipData();
            if (cd != null && cd.getItemCount() > 0) return cd.getItemAt(0).getUri();
        }
        return null;
    }

    private static Uri sharedStreamExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        return legacySharedStreamExtra(intent);
    }

    @SuppressWarnings("deprecation")
    private static Uri legacySharedStreamExtra(Intent intent) {
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private String uploadToGoogle(Uri imageUri) throws IOException {
        ContentResolver resolver = getContentResolver();
        String boundary = "ShareToLens-" + UUID.randomUUID();
        String uploadUrl = LENS_V3_URL + "?ep=ccm&re=df&st=" + System.currentTimeMillis();

        HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", webUserAgent);
        conn.setRequestProperty("X-Client-Side-Image-Upload", "true");

        String imageMimeType = imageMimeType(resolver, imageUri);
        String filename = imageFilename(imageMimeType);
        ImageDimensions dimensions = imageDimensions(resolver, imageUri);

        try (
                OutputStream out = new BufferedOutputStream(conn.getOutputStream());
                InputStream in = resolver.openInputStream(imageUri)
        ) {
            if (in == null) throw new IOException("Open fail");
            
            writeField(out, boundary, "processed_image_dimensions", dimensions.width + "," + dimensions.height);
            writeField(out, boundary, "sbisrc", "cr_1_0_0");
            
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"encoded_image\"; filename=\"" + filename + "\"\r\n");
            writeAscii(out, "Content-Type: " + imageMimeType + "\r\n\r\n");
            
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
        }

        int code = conn.getResponseCode();
        Log.d(TAG, "Upload response code: " + code);
        syncCookies(conn);

        String loc = conn.getHeaderField("Location");
        if (loc == null) throw new IOException("No redirect. Code: " + code);
        
        return URI.create("https://lens.google.com/").resolve(loc).toString();
    }

    private void syncCookies(HttpURLConnection conn) {
        CookieManager cookieManager = CookieManager.getInstance();
        for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
            String name = header.getKey();
            if (name == null || !"set-cookie".equals(name.toLowerCase(Locale.US))) continue;
            for (String cookie : header.getValue()) {
                cookieManager.setCookie("https://lens.google.com", cookie);
            }
        }
        cookieManager.flush();
    }

    private static String imageMimeType(ContentResolver resolver, Uri imageUri) {
        String mimeType = resolver.getType(imageUri);
        if (mimeType == null || !mimeType.toLowerCase(Locale.US).startsWith("image/")) {
            return "image/jpeg";
        }
        return mimeType;
    }

    private static ImageDimensions imageDimensions(ContentResolver resolver, Uri imageUri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(imageUri)) {
            if (in == null) throw new IOException("Open fail");
            BitmapFactory.decodeStream(in, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("Could not read image dimensions");
        }
        return new ImageDimensions(options.outWidth, options.outHeight);
    }

    private static String imageFilename(String mimeType) {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null || extension.isEmpty()) {
            return "image";
        }
        return "image." + extension;
    }

    private static void writeField(OutputStream out, String b, String name, String val) throws IOException {
        writeAscii(out, "--" + b + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeAscii(out, val + "\r\n");
    }

    private static void writeAscii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class ImageDimensions {
        final int width;
        final int height;

        ImageDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private final class LensWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageProgress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loadingOverlay.setVisibility(View.GONE);
            pageProgress.setVisibility(View.GONE);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return false;
            }
            openExternal(uri);
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                loadingOverlay.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (request.isForMainFrame()) {
                Log.e(TAG, "WebView HTTP error: " + errorResponse.getStatusCode());
            }
        }
    }

    private void openExternal(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.msg_error_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        executor.shutdownNow();
    }
}
