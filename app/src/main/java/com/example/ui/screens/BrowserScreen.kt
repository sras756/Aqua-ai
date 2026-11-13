package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AquaMainViewModel
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(viewModel: AquaMainViewModel) {
    val pageState by viewModel.browserPageState.collectAsStateWithLifecycle()
    val tabs by viewModel.browserTabs.collectAsStateWithLifecycle()
    val pendingUrl by viewModel.browserController.pendingUrl.collectAsStateWithLifecycle()

    var addressText by remember(pageState.currentUrl) { mutableStateOf(pageState.currentUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
            .statusBarsPadding()
    ) {
        // Address & Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
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

            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                singleLine = true,
                placeholder = { Text("Search or enter https://...") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.navigateBrowser(addressText) }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AquaCyan,
                    unfocusedBorderColor = AquaBorderGlow,
                    focusedTextColor = AquaIceText,
                    unfocusedTextColor = AquaIceText
                ),
                trailingIcon = {
                    IconButton(onClick = { viewModel.navigateBrowser(addressText) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Go", tint = AquaCyan)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("browser_url_input")
            )

            IconButton(onClick = { viewModel.browserController.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = AquaCyan)
            }
            IconButton(
                onClick = { viewModel.summarizeActiveWebpage() },
                modifier = Modifier.testTag("browser_summarize_button")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "Summarize page with Aqua", tint = AquaTeal)
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
                            .clickable { viewModel.browserController.navigateTo(tab.url) }
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

        // Integrated WebView
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }

        LaunchedEffect(pendingUrl, webViewInstance) {
            val target = pendingUrl
            val wv = webViewInstance
            if (target != null && wv != null) {
                viewModel.browserController.consumePendingUrl()
                wv.loadUrl(target)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.browserController.detachWebView()
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
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
                                    canGoBack = view?.canGoBack() == true,
                                    canGoForward = view?.canGoForward() == true
                                )
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
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
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}
