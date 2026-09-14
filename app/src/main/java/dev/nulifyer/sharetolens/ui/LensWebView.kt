package dev.nulifyer.sharetolens.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.nulifyer.sharetolens.BuildConfig
import dev.nulifyer.sharetolens.LensUiState
import dev.nulifyer.sharetolens.R
import dev.nulifyer.sharetolens.SearchOrigin
import java.util.Locale

internal object WebSessionStore {
    fun clear(onCleared: () -> Unit = {}) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            onCleared()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LensResultScreen(
    state: LensUiState.Results,
    onExit: () -> Unit,
    onNewSearch: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    var webView by remember(state.url) { mutableStateOf<WebView?>(null) }
    BackHandler {
        val browser = webView
        if (browser != null && browser.canGoBack()) browser.goBack() else onExit()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_results), fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            painter = painterResource(
                                if (state.origin == SearchOrigin.Share) {
                                    R.drawable.ic_close
                                } else {
                                    R.drawable.ic_arrow_back
                                },
                            ),
                            contentDescription = stringResource(
                                if (state.origin == SearchOrigin.Share) {
                                    R.string.action_close_results
                                } else {
                                    R.string.action_back_to_chooser
                                },
                            ),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewSearch) {
                        Icon(
                            painter = painterResource(R.drawable.ic_new_search),
                            contentDescription = stringResource(R.string.action_new_search),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LensWebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onWebViewCreated = { webView = it },
            onOpenLink = onOpenLink,
            onChooseAnother = onNewSearch,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LensWebView(
    state: LensUiState.Results,
    modifier: Modifier,
    onWebViewCreated: (WebView) -> Unit,
    onOpenLink: (String) -> Unit,
    onChooseAnother: () -> Unit,
) {
    var pageProgress by remember(state.url) { mutableIntStateOf(0) }
    var pageError by remember(state.url) { mutableStateOf(false) }
    var activeWebView by remember(state.url) { mutableStateOf<WebView?>(null) }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    activeWebView = this
                    onWebViewCreated(this)
                    configureSettings(settings)
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    state.cookies.forEach { cookie ->
                        CookieManager.getInstance().setCookie("https://lens.google.com", cookie)
                    }
                    CookieManager.getInstance().flush()
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            pageProgress = newProgress.coerceIn(0, 100)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageError = false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (isAllowedWebUri(request.url)) return false
                            if (request.isForMainFrame) onOpenLink(request.url.toString())
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) pageError = true
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            pageError = true
                            return true
                        }
                    }
                    loadUrl(state.url, themeRequestHeaders(context))
                }
            },
        )

        if (pageProgress in 0..99 && !pageError) {
            LinearProgressIndicator(
                progress = { pageProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }

        if (pageError) {
            BrowserError(
                onRetry = {
                    pageError = false
                    activeWebView?.loadUrl(state.url, themeRequestHeaders(activeWebView!!.context))
                },
                onChooseAnother = onChooseAnother,
            )
        }
    }

    DisposableEffect(state.url) {
        onDispose {
            activeWebView?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                clearFormData()
                clearSslPreferences()
                removeAllViews()
                destroy()
            }
            activeWebView = null
        }
    }
}

@Composable
private fun BrowserError(
    onRetry: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.title_browser_error),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.body_browser_error),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRetry,
                ) { Text(stringResource(R.string.action_reload_page)) }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onChooseAnother,
                ) { Text(stringResource(R.string.action_new_search)) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun configureSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setGeolocationEnabled(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        settings.isAlgorithmicDarkeningAllowed = false
    } else {
        settings.forceDark = WebSettings.FORCE_DARK_OFF
    }
}

private fun isAllowedWebUri(uri: Uri?): Boolean {
    if (uri?.scheme?.lowercase(Locale.US) != "https") return false
    return when (uri.host?.lowercase(Locale.US)) {
        "lens.google.com", "www.google.com", "accounts.google.com" -> true
        else -> false
    }
}

private fun themeRequestHeaders(context: Context): Map<String, String> {
    val nightMask = context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    val theme = if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    return mapOf("Sec-CH-Prefers-Color-Scheme" to theme)
}

internal fun openExternalBrowser(context: Context, url: String): Boolean {
    val uri = url.toUri()
    if (uri.scheme?.lowercase(Locale.US) != "https") return false
    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
