package dev.nulifyer.sharetolens;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final String TAG = "ShareToLens";
    private static final String KEY_PENDING_CAMERA_URI = "pending_camera_uri";
    private static final String LENS_V3_URL = "https://lens.google.com/v3/upload";
    private static final URI LENS_BASE_URI = URI.create("https://lens.google.com/");
    private static final int REQUEST_PICK_IMAGE = 1;
    private static final int REQUEST_TAKE_PHOTO = 2;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int UPLOAD_BUFFER_SIZE = 64 * 1024;
    private static final String CAMERA_TEMP_PREFIX = "share-to-lens-";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FrameLayout loadingOverlay;
    private ProgressBar pageProgress;
    private WebView webView;
    private String webUserAgent;
    private OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered;
    private volatile boolean destroyed;
    private volatile HttpURLConnection activeConnection;
    private Uri pendingCameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingCameraUri = savedInstanceState.getParcelable(KEY_PENDING_CAMERA_URI);
        }

        Uri imageUri = findSharedImage(getIntent());
        if (imageUri == null) {
            setContentView(createStartUi());
            return;
        }

        startUpload(new UriUploadSource(getContentResolver(), imageUri));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingCameraUri != null) {
            outState.putParcelable(KEY_PENDING_CAMERA_URI, pendingCameraUri);
        }
    }

    private void startUpload(UploadSource source) {
        if (destroyed) return;
        setContentView(createBrowserUi());
        Log.d(TAG, "Starting upload");
        executor.execute(() -> uploadAndLoad(source));
    }

    private void uploadAndLoad(UploadSource source) {
        try {
            String resultUrl = uploadToGoogle(source);
            Log.d(TAG, "Upload success. Loading results");
            runOnUiThread(() -> {
                if (destroyed || webView == null) return;
                webView.loadUrl(resultUrl, themeRequestHeaders());
            });
        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            runOnUiThread(() -> {
                if (destroyed) return;
                Toast.makeText(this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
                finish();
            });
        } finally {
            source.cleanup();
        }
    }

    private View createStartUi() {
        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        applySafeAreaPadding(root, padding, padding, padding, padding);

        TextView message = new TextView(this);
        message.setText(R.string.msg_share_hint);
        message.setGravity(Gravity.CENTER);
        root.addView(message);

        Button pickImage = new Button(this);
        pickImage.setText(R.string.action_pick_image);
        pickImage.setAllCaps(false);
        pickImage.setOnClickListener(v -> pickImage());
        root.addView(pickImage, buttonLayoutParams());

        Button takePhoto = new Button(this);
        takePhoto.setText(R.string.action_take_photo);
        takePhoto.setAllCaps(false);
        takePhoto.setOnClickListener(v -> takePhoto());
        root.addView(takePhoto, buttonLayoutParams());

        return root;
    }

    private LinearLayout.LayoutParams buttonLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        return params;
    }

    @SuppressWarnings("deprecation")
    private void pickImage() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.msg_error_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void takePhoto() {
        Uri imageUri;
        try {
            imageUri = createCameraImageUri();
        } catch (Exception e) {
            Log.e(TAG, "Could not create camera image", e);
            Toast.makeText(this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
            return;
        }
        if (imageUri == null) {
            Toast.makeText(this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
            return;
        }

        pendingCameraUri = imageUri;
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), "camera-output", imageUri));
        grantCameraUriPermissions(intent, imageUri);
        try {
            startActivityForResult(intent, REQUEST_TAKE_PHOTO);
        } catch (ActivityNotFoundException e) {
            revokeUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            deleteUri(imageUri);
            pendingCameraUri = null;
            Toast.makeText(this, R.string.msg_error_no_camera, Toast.LENGTH_LONG).show();
        }
    }

    private Uri createCameraImageUri() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, CAMERA_TEMP_PREFIX + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }

    private void grantCameraUriPermissions(Intent intent, Uri uri) {
        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(intent, 0);
        for (ResolveInfo activity : activities) {
            grantUriPermission(
                    activity.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        }
    }

    @Override
    @SuppressWarnings({"deprecation", "UseCompatLoadingForDrawables"})
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TAKE_PHOTO) {
            Uri uri = pendingCameraUri;
            pendingCameraUri = null;
            if (uri == null) {
                Toast.makeText(this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
                return;
            }

            boolean hasCapturedImage = resultCode == RESULT_OK || isUsableImage(uri);
            if (!hasCapturedImage) {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                deleteUri(uri);
                return;
            }
            startUpload(new UriUploadSource(getContentResolver(), uri, true));
            return;
        }

        if (resultCode != RESULT_OK) return;

        if (requestCode == REQUEST_PICK_IMAGE) {
            Uri uri = data == null ? null : data.getData();
            if (uri == null) {
                Toast.makeText(this, R.string.msg_error_generic, Toast.LENGTH_LONG).show();
                return;
            }
            startUpload(new UriUploadSource(getContentResolver(), uri));
            return;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private View createBrowserUi() {
        FrameLayout root = new FrameLayout(this);
        applySafeAreaPadding(root, 0, 0, 0, 0);

        webView = new WebView(this);
        applyThemeBackground(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        configureWebViewDarkMode(settings);
        webUserAgent = settings.getUserAgentString();

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

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
        applyThemeBackground(overlay);

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

    private static void applySafeAreaPadding(View view, int left, int top, int right, int bottom) {
        view.setPadding(left, top, right, bottom);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            SafeInsets safeInsets = safeInsets(insets);
            v.setPadding(
                    left + safeInsets.left,
                    top + safeInsets.top,
                    right + safeInsets.right,
                    bottom + safeInsets.bottom
            );
            return insets;
        });
        view.requestApplyInsets();
    }

    private static void applyThemeBackground(View view) {
        TypedValue value = new TypedValue();
        if (!view.getContext().getTheme().resolveAttribute(android.R.attr.windowBackground, value, true)) {
            return;
        }
        if (value.resourceId != 0) {
            view.setBackgroundResource(value.resourceId);
            return;
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            view.setBackgroundColor(value.data);
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWebViewDarkMode(WebSettings settings) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
            return;
        }

        settings.setForceDark(WebSettings.FORCE_DARK_OFF);
    }

    private Map<String, String> themeRequestHeaders() {
        return Collections.singletonMap(
                "Sec-CH-Prefers-Color-Scheme",
                isSystemDarkMode() ? "dark" : "light"
        );
    }

    private boolean isSystemDarkMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private static SafeInsets safeInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            return new SafeInsets(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom);
        }

        int left = insets.getSystemWindowInsetLeft();
        int top = insets.getSystemWindowInsetTop();
        int right = insets.getSystemWindowInsetRight();
        int bottom = insets.getSystemWindowInsetBottom();

        if (insets.getDisplayCutout() != null) {
            left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
            top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
            right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
            bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
        }

        return new SafeInsets(left, top, right, bottom);
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

    private String uploadToGoogle(UploadSource source) throws IOException {
        String boundary = "ShareToLens-" + UUID.randomUUID();
        String uploadUrl = LENS_V3_URL + "?ep=ccm&re=df&st=" + System.currentTimeMillis();

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
            activeConnection = conn;
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", webUserAgent);
            conn.setRequestProperty("X-Client-Side-Image-Upload", "true");

            ImageDimensions dimensions = source.dimensions();
            byte[] fields = multipartFields(boundary, dimensions);
            byte[] imageHeader = imageHeader(boundary, source);
            byte[] closing = ascii("\r\n--" + boundary + "--\r\n");
            long contentLength = source.contentLength();
            if (contentLength >= 0) {
                conn.setFixedLengthStreamingMode(fields.length + imageHeader.length + contentLength + closing.length);
            } else {
                conn.setChunkedStreamingMode(UPLOAD_BUFFER_SIZE);
            }

            try (
                    OutputStream out = new BufferedOutputStream(conn.getOutputStream());
                    InputStream in = source.openInputStream()
            ) {
                if (in == null) throw new IOException("Open fail");

                out.write(fields);
                out.write(imageHeader);

                byte[] buf = new byte[UPLOAD_BUFFER_SIZE];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);

                out.write(closing);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "Upload response code: " + code);
            syncCookies(conn);

            String loc = conn.getHeaderField("Location");
            if (loc == null) throw new IOException("No redirect. Code: " + code);

            return validateLensRedirect(LENS_BASE_URI.resolve(loc)).toString();
        } finally {
            if (activeConnection == conn) {
                activeConnection = null;
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] multipartFields(String boundary, ImageDimensions dimensions) {
        String fields = field(boundary, "processed_image_dimensions", dimensions.width + "," + dimensions.height)
                + field(boundary, "sbisrc", "cr_1_0_0");
        return ascii(fields);
    }

    private static String field(String boundary, String name, String value) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
    }

    private static byte[] imageHeader(String boundary, UploadSource source) {
        return ascii("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"encoded_image\"; filename=\"" + source.filename() + "\"\r\n"
                + "Content-Type: " + source.mimeType() + "\r\n\r\n");
    }

    private static URI validateLensRedirect(URI uri) throws IOException {
        if (!isAllowedWebUri(uri)) {
            throw new IOException("Unexpected redirect: " + uri);
        }
        return uri;
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

    private boolean isUsableImage(Uri uri) {
        try {
            imageDimensions(getContentResolver(), uri);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Camera did not return a usable image", e);
            return false;
        }
    }

    private static long contentLength(ContentResolver resolver, Uri imageUri) throws IOException {
        try (AssetFileDescriptor fd = resolver.openAssetFileDescriptor(imageUri, "r")) {
            if (fd == null) return -1;
            return fd.getLength();
        }
    }

    private static String imageFilename(String mimeType) {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null || extension.isEmpty()) {
            return "image";
        }
        return "image." + extension;
    }

    private void deleteUri(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not delete temporary image", e);
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isAllowedWebUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return isAllowedWebHost(scheme, host);
    }

    private static boolean isAllowedWebUri(URI uri) {
        return isAllowedWebHost(uri.getScheme(), uri.getHost());
    }

    private static boolean isAllowedWebHost(String scheme, String host) {
        if (!"https".equalsIgnoreCase(scheme) || host == null) return false;
        String normalizedHost = host.toLowerCase(Locale.US);
        return "lens.google.com".equals(normalizedHost)
                || "www.google.com".equals(normalizedHost)
                || "accounts.google.com".equals(normalizedHost);
    }

    private static final class ImageDimensions {
        final int width;
        final int height;

        ImageDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class SafeInsets {
        final int left;
        final int top;
        final int right;
        final int bottom;

        SafeInsets(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private interface UploadSource {
        String mimeType();

        String filename();

        ImageDimensions dimensions() throws IOException;

        long contentLength() throws IOException;

        InputStream openInputStream() throws IOException;

        default void cleanup() {
        }
    }

    private final class UriUploadSource implements UploadSource {
        private final ContentResolver resolver;
        private final Uri uri;
        private final String mimeType;
        private final boolean deleteAfterUpload;

        UriUploadSource(ContentResolver resolver, Uri uri) {
            this(resolver, uri, false);
        }

        UriUploadSource(ContentResolver resolver, Uri uri, boolean deleteAfterUpload) {
            this.resolver = resolver;
            this.uri = uri;
            this.mimeType = imageMimeType(resolver, uri);
            this.deleteAfterUpload = deleteAfterUpload;
        }

        @Override
        public String mimeType() {
            return mimeType;
        }

        @Override
        public String filename() {
            return imageFilename(mimeType);
        }

        @Override
        public ImageDimensions dimensions() throws IOException {
            return imageDimensions(resolver, uri);
        }

        @Override
        public long contentLength() throws IOException {
            return MainActivity.contentLength(resolver, uri);
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return resolver.openInputStream(uri);
        }

        @Override
        public void cleanup() {
            if (deleteAfterUpload) {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                deleteUri(uri);
            }
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
            updateBackCallback();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isAllowedWebUri(uri)) {
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
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && keyCode == KeyEvent.KEYCODE_BACK
                && webView != null
                && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            webView.post(this::updateBackCallback);
            return;
        }
        unregisterBackCallback();
        finish();
    }

    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;

        boolean shouldRegister = webView != null && webView.canGoBack();
        if (shouldRegister && !backCallbackRegistered) {
            if (backCallback == null) {
                backCallback = this::handleBack;
            }
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback
            );
            backCallbackRegistered = true;
            return;
        }

        if (!shouldRegister) {
            unregisterBackCallback();
        }
    }

    private void unregisterBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !backCallbackRegistered
                || backCallback == null) {
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        backCallbackRegistered = false;
    }

    private void clearPersistentWebViewData() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(value -> cookieManager.flush());

        WebStorage.getInstance().deleteAllData();

        if (webView == null) return;
        webView.stopLoading();
        webView.clearHistory();
        webView.clearCache(true);
        webView.clearFormData();
        webView.clearSslPreferences();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            conn.disconnect();
        }
        super.onDestroy();
        unregisterBackCallback();
        clearPersistentWebViewData();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        executor.shutdownNow();
    }
}
