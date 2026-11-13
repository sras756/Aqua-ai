package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.AquaBrowserController
import com.example.ui.AquaMainViewModel
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(viewModel: AquaMainViewModel) {
    val pageState by viewModel.browserPageState.collectAsStateWithLifecycle()
    val tabs by viewModel.browserTabs.collectAsStateWithLifecycle()
    val pendingUrl by viewModel.browserController.pendingUrl.collectAsStateWithLifecycle()
    val readerData by viewModel.browserReaderData.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var addressText by remember(pageState.currentUrl) { mutableStateOf(pageState.currentUrl) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var askQuestionText by remember { mutableStateOf("") }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var autoRecoveryCount by remember { mutableIntStateOf(0) }
    var rendererUnavailable by remember { mutableStateOf(false) }

    fun navigateOrRecover(target: String) {
        viewModel.navigateBrowser(target)
        if (rendererUnavailable) {
            autoRecoveryCount = 0
            rendererUnavailable = false
            webViewGeneration++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
            .statusBarsPadding()
    ) {
        // Primary Address Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                singleLine = true,
                placeholder = { Text("Search or enter https://...") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateOrRecover(addressText) }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AquaCyan,
                    unfocusedBorderColor = AquaBorderGlow,
                    focusedTextColor = AquaIceText,
                    unfocusedTextColor = AquaIceText
                ),
                trailingIcon = {
                    IconButton(onClick = { navigateOrRecover(addressText) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Go", tint = AquaCyan)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_url_input")
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { viewModel.summarizeActiveWebpage() },
                modifier = Modifier.testTag("browser_summarize_button")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "Summarize page with Aqua", tint = AquaTeal)
            }
        }

        // Browser Navigation & Tool Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.browserController.goBack() },
                enabled = pageState.canGoBack
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (pageState.canGoBack) AquaCyan else AquaMutedText
                )
            }
            IconButton(
                onClick = { viewModel.browserController.goForward() },
                enabled = pageState.canGoForward
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (pageState.canGoForward) AquaCyan else AquaMutedText
                )
            }
            IconButton(
                onClick = {
                    if (rendererUnavailable) {
                        autoRecoveryCount = 0
                        rendererUnavailable = false
                        webViewGeneration++
                    } else {
                        viewModel.browserController.reload()
                    }
                }
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = AquaCyan)
            }
            IconButton(
                onClick = {
                    showFindBar = !showFindBar
                    if (!showFindBar) {
                        findQuery = ""
                        viewModel.browserController.clearFindMatches()
                    }
                },
                modifier = Modifier.testTag("browser_find_toggle")
            ) {
                Icon(Icons.Filled.FindInPage, contentDescription = "Find in page", tint = AquaCyan)
            }
            IconButton(
                onClick = { viewModel.openBrowserReader() },
                modifier = Modifier.testTag("browser_reader_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, contentDescription = "Reader & Page Links", tint = AquaTeal)
            }
            IconButton(
                onClick = { viewModel.openBrowserUrlExternally(pageState.currentUrl) },
                modifier = Modifier.testTag("browser_open_external_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in external browser", tint = AquaMutedText)
            }
        }

        AnimatedVisibility(visible = showFindBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AquaDeepSurface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        viewModel.browserController.findInPage(it)
                    },
                    placeholder = { Text("Find in page...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AquaCyan,
                        unfocusedBorderColor = AquaBorderGlow,
                        focusedTextColor = AquaIceText,
                        unfocusedTextColor = AquaIceText
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("browser_find_input")
                )
                IconButton(onClick = { viewModel.browserController.findNext(false) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match", tint = AquaCyan)
                }
                IconButton(onClick = { viewModel.browserController.findNext(true) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match", tint = AquaCyan)
                }
                IconButton(
                    onClick = {
                        showFindBar = false
                        findQuery = ""
                        viewModel.browserController.clearFindMatches()
                    }
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find bar", tint = AquaMutedText)
                }
            }
        }

        if (pageState.isLoading) {
            LinearProgressIndicator(
                progress = { pageState.progress / 100f },
                color = AquaCyan,
                trackColor = AquaCardSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Saved Browser Tabs strip
        if (tabs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Surface(
                        color = AquaCardSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .border(1.dp, AquaBorderGlow, RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.browserController.navigateTo(tab.url)
                                if (rendererUnavailable) {
                                    autoRecoveryCount = 0
                                    rendererUnavailable = false
                                    webViewGeneration++
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab.title.ifBlank { tab.url }.take(24),
                                style = MaterialTheme.typography.labelSmall,
                                color = AquaIceText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close tab",
                                tint = AquaMutedText,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { viewModel.deleteBrowserTab(tab.id) }
                            )
                        }
                    }
                }
            }
        }

        LaunchedEffect(pendingUrl, webViewInstance, rendererUnavailable) {
            val target = pendingUrl
            val wv = webViewInstance
            if (target != null && wv != null && !rendererUnavailable) {
                viewModel.browserController.consumePendingUrl()
                wv.loadUrl(target)
            }
        }

        DisposableEffect(lifecycleOwner, webViewInstance) {
            val observer = LifecycleEventObserver { _, event ->
                val wv = webViewInstance ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_RESUME -> runCatching { wv.onResume() }
                    Lifecycle.Event.ON_PAUSE -> runCatching { wv.onPause() }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        DisposableEffect(webViewGeneration) {
            onDispose {
                val wv = webViewInstance
                webViewInstance = null
                safelyDisposeWebView(wv, viewModel.browserController)
            }
        }

        if (rendererUnavailable) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = AquaCardSurface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = AquaCyan,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "In-App Renderer Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = AquaIceText
                        )
                        Text(
                            text = "Android terminated the Chromium WebView renderer due to device/emulator memory constraints. You can retry inside Aqua or open the page in your external browser.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AquaMutedText
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    autoRecoveryCount = 0
                                    rendererUnavailable = false
                                    webViewGeneration++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browser_retry_webview_button")
                            ) {
                                Text("Retry WebView", color = AquaCyan)
                            }
                            Button(
                                onClick = { viewModel.openBrowserUrlExternally(pageState.currentUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browser_external_fallback_button")
                            ) {
                                Text("Open External", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            key(webViewGeneration) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                // Allow Android to reclaim offscreen renderer gracefully instead of killing mid-render
                                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
                                settings.safeBrowsingEnabled = !isRunningOnEmulator()
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    return !(url.startsWith("http://") || url.startsWith("https://"))
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    viewModel.browserController.updatePageState {
                                        it.copy(
                                            currentUrl = url ?: it.currentUrl,
                                            isLoading = true,
                                            lastError = null,
                                            canGoBack = view?.canGoBack() == true,
                                            canGoForward = view?.canGoForward() == true
                                        )
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    autoRecoveryCount = 0
                                    viewModel.browserController.updatePageState {
                                        it.copy(
                                            currentUrl = url ?: it.currentUrl,
                                            currentTitle = view?.title ?: it.currentTitle,
                                            isLoading = false,
                                            canGoBack = view?.canGoBack() == true,
                                            canGoForward = view?.canGoForward() == true
                                        )
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    Log.w("AquaBrowser", "WebView render process gone (didCrash=${detail?.didCrash()})")
                                    webViewInstance = null
                                    safelyDisposeWebView(view, viewModel.browserController)
                                    viewModel.browserController.updatePageState {
                                        it.copy(
                                            isLoading = false,
                                            lastError = "Renderer stopped by system memory limits."
                                        )
                                    }
                                    if (autoRecoveryCount < 1) {
                                        autoRecoveryCount++
                                        webViewGeneration++
                                    } else {
                                        rendererUnavailable = true
                                    }
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    viewModel.browserController.updatePageState { it.copy(progress = newProgress) }
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) {
                                        viewModel.browserController.updatePageState { it.copy(currentTitle = title) }
                                    }
                                }
                            }
                            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                viewModel.browserController.enqueueDownload(url, userAgent, contentDisposition, mimetype)
                            }
                            viewModel.browserController.attachWebView(this)
                            webViewInstance = this
                            loadUrl(viewModel.browserController.consumePendingUrl() ?: pageState.currentUrl)
                        }
                    },
                    onRelease = { wv ->
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                        safelyDisposeWebView(wv, viewModel.browserController)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }

    readerData?.let { (extractedText, extractedLinks) ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeBrowserReader() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AquaDeepSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = pageState.currentTitle.ifBlank { "Aqua Reader" },
                    style = MaterialTheme.typography.titleLarge,
                    color = AquaIceText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.speakMessage(extractedText.take(3500)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Listen", color = AquaCyan)
                    }
                    OutlinedButton(
                        onClick = { viewModel.saveActivePageToMemory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = null, tint = AquaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save Note", color = AquaTeal)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = askQuestionText,
                        onValueChange = { askQuestionText = it },
                        placeholder = { Text("Ask Aqua about this page...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AquaCyan,
                            unfocusedBorderColor = AquaBorderGlow,
                            focusedTextColor = AquaIceText,
                            unfocusedTextColor = AquaIceText
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val q = askQuestionText
                            askQuestionText = ""
                            viewModel.askAboutActiveWebpage(q)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss)
                    ) {
                        Text("Ask", fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Surface(
                            color = AquaCardSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = extractedText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AquaIceText,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (extractedLinks.isNotEmpty()) {
                        item {
                            Text(
                                text = "EXTRACTED LINKS (${extractedLinks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = AquaCyan
                            )
                        }
                        items(extractedLinks) { link ->
                            Surface(
                                color = AquaCardSurface,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.closeBrowserReader()
                                        navigateOrRecover(link.href)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = null, tint = AquaTeal, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(link.text, style = MaterialTheme.typography.labelMedium, color = AquaIceText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(link.href, style = MaterialTheme.typography.labelSmall, color = AquaMutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun safelyDisposeWebView(
    webView: WebView?,
    controller: AquaBrowserController
) {
    if (webView == null || webView.tag == "aqua_webview_disposed") return
    webView.tag = "aqua_webview_disposed"
    controller.detachWebView(webView)
    runCatching {
        webView.stopLoading()
        webView.onPause()
        webView.clearHistory()
        webView.loadUrl("about:blank")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}

private fun isRunningOnEmulator(): Boolean {
    return Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk", ignoreCase = true)
}
