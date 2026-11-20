package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.AquaBrowserController
import com.example.browser.AquaLegalPortalAuthBridge
import com.example.ui.AquaMainViewModel
import com.example.ui.auth.onGoogleSignInClicked
import com.example.ui.components.AquaAdaptiveContainer
import com.example.ui.components.AquaAdaptiveGridRows
import com.example.ui.components.AquaAdaptiveSpec
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.theme.AquaTheme
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(viewModel: AquaMainViewModel) {
    val c = AquaTheme.colors
    val pageState by viewModel.browserPageState.collectAsStateWithLifecycle()
    val tabs by viewModel.browserTabs.collectAsStateWithLifecycle()
    val pendingUrl by viewModel.browserController.pendingUrl.collectAsStateWithLifecycle()
    val hasActiveSession by viewModel.browserController.hasActiveSession.collectAsStateWithLifecycle()
    val readerData by viewModel.browserReaderData.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    var addressText by remember(pageState.currentUrl) { mutableStateOf(pageState.currentUrl) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var askQuestionText by remember { mutableStateOf("") }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var initialUrlLoadedForGen by remember { mutableIntStateOf(-1) }
    var rendererUnavailable by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    var lastNonOAuthUrl by remember { mutableStateOf("") }
    var oauthNotice by remember { mutableStateOf<String?>(null) }

    fun triggerNativePortalSignIn() {
        onGoogleSignInClicked(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                oauthNotice = null
                val json = AquaLegalPortalAuthBridge.currentFirebaseUserJson()
                val script = "window.__aquaPortalNativeSignInSuccess && window.__aquaPortalNativeSignInSuccess(${JSONObject.quote(json)});"
                webViewInstance?.post {
                    runCatching { webViewInstance?.evaluateJavascript(script, null) }
                }
            },
            onAuthError = { err ->
                oauthNotice = err
            },
            scope = scope
        )
    }

    val portalAuthBridge = remember {
        AquaLegalPortalAuthBridge(
            onRequestNativeSignIn = { triggerNativePortalSignIn() },
            onOpenExternal = { url -> viewModel.openBrowserUrlExternally(url) }
        )
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    fun navigateOrRecover(target: String) {
        oauthNotice = null
        viewModel.navigateBrowser(target)
        if (rendererUnavailable) {
            rendererUnavailable = false
            webViewGeneration++
        }
    }

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
    AquaAdaptiveContainer(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) { spec ->
    Column(modifier = Modifier.fillMaxSize()) {
        if (spec.isTabletOrFoldable) {
            // Unified Desktop/Tablet Browser Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spec.horizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { viewModel.browserController.goBack() },
                    enabled = pageState.canGoBack
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (pageState.canGoBack) c.accentCyan else c.textMuted
                    )
                }
                IconButton(
                    onClick = { viewModel.browserController.goForward() },
                    enabled = pageState.canGoForward
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (pageState.canGoForward) c.accentCyan else c.textMuted
                    )
                }
                IconButton(
                    onClick = {
                        if (!hasActiveSession || rendererUnavailable) {
                            rendererUnavailable = false
                            viewModel.browserController.activateSession()
                            webViewGeneration++
                        } else {
                            viewModel.browserController.reload()
                        }
                    }
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = c.accentCyan)
                }

                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    singleLine = true,
                    placeholder = { Text("Search or enter https://...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigateOrRecover(addressText) }),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    trailingIcon = {
                        IconButton(onClick = { navigateOrRecover(addressText) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Go", tint = c.accentCyan)
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
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "Summarize page with Aqua", tint = c.accentTeal)
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
                    Icon(Icons.Filled.FindInPage, contentDescription = "Find in page", tint = c.accentCyan)
                }
                IconButton(
                    onClick = { viewModel.openBrowserReader() },
                    modifier = Modifier.testTag("browser_reader_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, contentDescription = "Reader & Page Links", tint = c.accentTeal)
                }
                IconButton(
                    onClick = { viewModel.openBrowserUrlExternally(pageState.currentUrl) },
                    modifier = Modifier.testTag("browser_open_external_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in external browser", tint = c.textMuted)
                }
            }
        } else {
            // Primary Address Bar (Mobile Compact)
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
                    colors = fieldColors,
                    trailingIcon = {
                        IconButton(onClick = { navigateOrRecover(addressText) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Go", tint = c.accentCyan)
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
                    Icon(Icons.Filled.AutoAwesome, contentDescription = "Summarize page with Aqua", tint = c.accentTeal)
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
                        tint = if (pageState.canGoBack) c.accentCyan else c.textMuted
                    )
                }
                IconButton(
                    onClick = { viewModel.browserController.goForward() },
                    enabled = pageState.canGoForward
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (pageState.canGoForward) c.accentCyan else c.textMuted
                    )
                }
                IconButton(
                    onClick = {
                        if (!hasActiveSession || rendererUnavailable) {
                            rendererUnavailable = false
                            viewModel.browserController.activateSession()
                            webViewGeneration++
                        } else {
                            viewModel.browserController.reload()
                        }
                    }
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = c.accentCyan)
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
                    Icon(Icons.Filled.FindInPage, contentDescription = "Find in page", tint = c.accentCyan)
                }
                IconButton(
                    onClick = { viewModel.openBrowserReader() },
                    modifier = Modifier.testTag("browser_reader_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, contentDescription = "Reader & Page Links", tint = c.accentTeal)
                }
                IconButton(
                    onClick = { viewModel.openBrowserUrlExternally(pageState.currentUrl) },
                    modifier = Modifier.testTag("browser_open_external_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in external browser", tint = c.textMuted)
                }
            }
        }

        AnimatedVisibility(visible = showFindBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.deepSurface)
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
                    colors = fieldColors,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("browser_find_input")
                )
                IconButton(onClick = { viewModel.browserController.findNext(false) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match", tint = c.accentCyan)
                }
                IconButton(onClick = { viewModel.browserController.findNext(true) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match", tint = c.accentCyan)
                }
                IconButton(
                    onClick = {
                        showFindBar = false
                        findQuery = ""
                        viewModel.browserController.clearFindMatches()
                    }
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find bar", tint = c.textMuted)
                }
            }
        }

        if (pageState.isLoading) {
            LinearProgressIndicator(
                progress = { pageState.progress / 100f },
                color = c.accentCyan,
                trackColor = c.cardSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(visible = oauthNotice != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.deepSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
                Text(
                    text = oauthNotice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val fallbackUrl = lastNonOAuthUrl.ifBlank { pageState.currentUrl }
                        oauthNotice = null
                        viewModel.openBrowserUrlExternally(fallbackUrl)
                    }
                ) {
                    Text("External", color = c.accentCyan)
                }
                IconButton(onClick = { oauthNotice = null }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss OAuth notice", tint = c.textMuted)
                }
            }
        }

        AnimatedVisibility(visible = pageState.lastSecurityWarning != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.deepSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
                Text(
                    text = pageState.lastSecurityWarning.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.amber,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.browserController.updatePageState { it.copy(lastSecurityWarning = null) } }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss warning", tint = c.textMuted)
                }
            }
        }

        // Local Multi-Tab + Incognito Strip & Saved Tabs
        val localTabSessions by viewModel.browserTabSessions.collectAsStateWithLifecycle()
        val currentTabId by viewModel.browserController.activeTabId.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(localTabSessions, key = { it.tabId }) { sess ->
                    val selected = sess.tabId == currentTabId
                    Surface(
                        color = if (sess.isIncognito) c.deepSurface else if (selected) c.accentCyan.copy(alpha = 0.16f) else c.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (sess.isIncognito) c.amber else if (selected) c.accentCyan else c.borderGlow,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.browserController.switchTab(sess.tabId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = (if (sess.isIncognito) "Incognito • " else "") + sess.title.take(18),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sess.isIncognito) c.amber else c.textPrimary,
                                maxLines = 1
                            )
                            if (localTabSessions.size > 1 || sess.isIncognito) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close tab",
                                    tint = c.textMuted,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable { viewModel.browserController.closeTab(sess.tabId) }
                                )
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { viewModel.browserController.openNewTab(isIncognito = false) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("+Tab", style = MaterialTheme.typography.labelSmall, color = c.accentCyan)
            }
            OutlinedButton(
                onClick = { viewModel.browserController.openNewTab(isIncognito = true) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("+Private", style = MaterialTheme.typography.labelSmall, color = c.amber)
            }
        }

        // Saved Browser Tabs strip
        if (tabs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Surface(
                        color = c.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .border(1.dp, c.borderGlow, RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.browserController.navigateTo(tab.url)
                                if (rendererUnavailable) {
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
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close tab",
                                tint = c.textMuted,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { viewModel.deleteBrowserTab(tab.id) }
                            )
                        }
                    }
                }
            }
        }

        LaunchedEffect(pendingUrl, webViewInstance, hasActiveSession, rendererUnavailable, webViewGeneration) {
            val wv = webViewInstance
            if (wv != null && hasActiveSession && !rendererUnavailable) {
                val target = pendingUrl
                if (target != null) {
                    viewModel.browserController.consumePendingUrl()
                    initialUrlLoadedForGen = webViewGeneration
                    wv.post { runCatching { wv.loadUrl(target) } }
                } else if (initialUrlLoadedForGen != webViewGeneration) {
                    initialUrlLoadedForGen = webViewGeneration
                    val urlToLoad = viewModel.browserController.consumePendingUrl() ?: pageState.currentUrl
                    wv.post { runCatching { wv.loadUrl(urlToLoad) } }
                }
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

        DisposableEffect(webViewGeneration, hasActiveSession) {
            onDispose {
                val wv = webViewInstance
                webViewInstance = null
                safelyDisposeWebView(wv, viewModel.browserController, rendererAlreadyGone = false)
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
                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = c.accentCyan,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "In-App Renderer Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary
                        )
                        Text(
                            text = "Android terminated the Chromium WebView renderer due to device/emulator memory constraints. You can read the live page natively in Aqua Reader, retry WebView, or open externally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textMuted
                        )
                        Button(
                            onClick = { viewModel.openBrowserReader() },
                            colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("browser_reader_fallback_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Read Page in Aqua Reader", fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    rendererUnavailable = false
                                    viewModel.browserController.activateSession()
                                    webViewGeneration++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browser_retry_webview_button")
                            ) {
                                Text("Retry WebView", color = c.accentCyan)
                            }
                            OutlinedButton(
                                onClick = { viewModel.openBrowserUrlExternally(pageState.currentUrl) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browser_external_fallback_button")
                            ) {
                                Text("Open External", color = c.textPrimary)
                            }
                        }
                    }
                }
            }
        } else if (!hasActiveSession) {
            BrowserStartHub(
                spec = spec,
                currentUrl = addressText,
                onOpenUrl = { url -> navigateOrRecover(url) },
                onOpenReader = {
                    viewModel.browserController.updatePageState {
                        it.copy(currentUrl = viewModel.browserController.normalizeUrlOrSearch(addressText))
                    }
                    viewModel.openBrowserReader()
                },
                onOpenExternal = {
                    val resolved = viewModel.browserController.normalizeUrlOrSearch(addressText)
                    viewModel.openBrowserUrlExternally(resolved)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            key(webViewGeneration) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val onEmulator = isRunningOnEmulator()
                            if (onEmulator) {
                                // Prevent SwiftShader/Vulkan/HardwareBuffer GPU compositor crashes in Android Emulators
                                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                                settings.safeBrowsingEnabled = true
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.loadsImagesAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.setGeolocationEnabled(false)
                            settings.setSupportMultipleWindows(false)
                            settings.cacheMode = if (onEmulator || pageState.isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                                        return true
                                    }
                                    val isMainFrame = request?.isForMainFrame != false
                                    if (AquaLegalPortalAuthBridge.isGoogleOriginMismatchErrorUrl(url) ||
                                        (isMainFrame && AquaLegalPortalAuthBridge.isGoogleOAuthFlowUrl(url))
                                    ) {
                                        val isPortalOrigin = AquaLegalPortalAuthBridge.isTrustedAquaPortalUrl(lastNonOAuthUrl) ||
                                            url.lowercase().contains("sras756.github.io")
                                        if (isPortalOrigin) {
                                            val portalUrl = lastNonOAuthUrl
                                                .takeIf { AquaLegalPortalAuthBridge.isTrustedAquaPortalUrl(it) }
                                                ?: "https://sras756.github.io/Aqua-ai/"
                                            view?.post { runCatching { view.loadUrl(portalUrl) } }
                                            triggerNativePortalSignIn()
                                        } else {
                                            viewModel.openBrowserUrlExternally(url)
                                            oauthNotice = "Google Sign-In opened in your system browser to comply with OAuth 2.0 security policy."
                                        }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    val target = url.orEmpty()
                                    if (AquaLegalPortalAuthBridge.isGoogleOriginMismatchErrorUrl(target) ||
                                        (AquaLegalPortalAuthBridge.isGoogleOAuthFlowUrl(target) &&
                                            AquaLegalPortalAuthBridge.isTrustedAquaPortalUrl(lastNonOAuthUrl))
                                    ) {
                                        view?.stopLoading()
                                        val recoveryUrl = lastNonOAuthUrl
                                            .takeIf { AquaLegalPortalAuthBridge.isTrustedAquaPortalUrl(it) }
                                            ?: if (target.lowercase().contains("sras756.github.io")) "https://sras756.github.io/Aqua-ai/" else null
                                        if (recoveryUrl != null) {
                                            view?.post { runCatching { view.loadUrl(recoveryUrl) } }
                                            triggerNativePortalSignIn()
                                        } else {
                                            oauthNotice = "Google Sign-In cannot run inside embedded WebViews (Error 400). Tap External to sign in with your system browser."
                                        }
                                        return
                                    }
                                    if (target.isNotBlank() && !AquaLegalPortalAuthBridge.isGoogleOAuthFlowUrl(target)) {
                                        lastNonOAuthUrl = target
                                    }
                                    portalAuthBridge.updateVerifiedUrl(url)
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
                                    portalAuthBridge.updateVerifiedUrl(url)
                                    AquaLegalPortalAuthBridge.buildPortalSyncScript(url)?.let { syncScript ->
                                        view?.evaluateJavascript(syncScript, null)
                                    }
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
                                    val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() else null
                                    Log.w("AquaBrowser", "WebView render process gone (didCrash=$didCrash)")
                                    webViewInstance = null
                                    safelyDisposeWebView(view, viewModel.browserController, rendererAlreadyGone = true)
                                    viewModel.browserController.updatePageState {
                                        it.copy(
                                            isLoading = false,
                                            lastError = "Renderer stopped by system memory limits."
                                        )
                                    }
                                    rendererUnavailable = true
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
                            addJavascriptInterface(portalAuthBridge, "AquaAndroidPortal")
                            viewModel.browserController.attachWebView(this)
                            webViewInstance = this
                        }
                    },
                    onRelease = { wv ->
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                        safelyDisposeWebView(wv, viewModel.browserController, rendererAlreadyGone = false)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
    }
    }

    readerData?.let { (extractedText, extractedLinks) ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeBrowserReader() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetMaxWidth = 720.dp,
            containerColor = c.deepSurface
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
                    color = c.textPrimary,
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
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Listen", color = c.accentCyan)
                    }
                    OutlinedButton(
                        onClick = { viewModel.saveActivePageToMemory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save Note", color = c.accentTeal)
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
                        colors = fieldColors,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val q = askQuestionText
                            askQuestionText = ""
                            viewModel.askAboutActiveWebpage(q)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas)
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
                            color = c.cardSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = extractedText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textPrimary,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (extractedLinks.isNotEmpty()) {
                        item {
                            Text(
                                text = "EXTRACTED LINKS (${extractedLinks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.accentCyan
                            )
                        }
                        items(extractedLinks) { link ->
                            Surface(
                                color = c.cardSurface,
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
                                    Icon(Icons.Filled.Link, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(link.text, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(link.href, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun BrowserStartHub(
    spec: AquaAdaptiveSpec,
    currentUrl: String,
    onOpenUrl: (String) -> Unit,
    onOpenReader: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    val quickSites = remember {
        listOf(
            "Aqua AI Trust Portal" to "https://sras756.github.io/Aqua-ai/",
            "DuckDuckGo" to "https://duckduckgo.com",
            "Wikipedia" to "https://en.m.wikipedia.org",
            "CoinGecko" to "https://www.coingecko.com",
            "GitHub" to "https://github.com",
            "Android Docs" to "https://developer.android.com"
        )
    }
    val siteCols = when {
        spec.isTwoPane -> 3
        spec.isTabletOrFoldable -> 2
        else -> 1
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 980.dp)
                .padding(horizontal = spec.horizontalPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AquaGlassCard(
                    borderColor = c.accentCyan.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = null,
                                tint = c.accentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Aqua Browser Hub",
                                style = MaterialTheme.typography.titleMedium,
                                color = c.textPrimary
                            )
                        }
                        Text(
                            text = "Enter a URL or search query above, pick a destination below, or extract clean readable text via Aqua Reader without spinning up a full Chromium renderer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textMuted
                        )
                        if (spec.isTabletOrFoldable) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onOpenUrl(currentUrl) },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .testTag("browser_launch_webview_button")
                                ) {
                                    Text("Open Live WebView", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = onOpenReader,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("browser_hub_reader_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Aqua Reader", color = c.accentTeal)
                                }
                                OutlinedButton(
                                    onClick = onOpenExternal,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("browser_hub_external_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("External", color = c.textPrimary)
                                }
                            }
                        } else {
                            Button(
                                onClick = { onOpenUrl(currentUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("browser_launch_webview_button")
                            ) {
                                Text("Open Live WebView", fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenReader,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("browser_hub_reader_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Aqua Reader", color = c.accentTeal)
                                }
                                OutlinedButton(
                                    onClick = onOpenExternal,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("browser_hub_external_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("External", color = c.textPrimary)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "QUICK LAUNCH",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accentCyan
                )
            }

            item {
                AquaAdaptiveGridRows(
                    items = quickSites,
                    columns = siteCols,
                    horizontalSpacing = 12.dp,
                    verticalSpacing = 10.dp
                ) { (label, url) ->
                    AquaGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl(url) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(url, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    controller: AquaBrowserController,
    rendererAlreadyGone: Boolean
) {
    if (webView == null || webView.tag == "aqua_webview_disposed") return
    webView.tag = "aqua_webview_disposed"
    controller.detachWebView(webView)
    runCatching {
        if (!rendererAlreadyGone) {
            webView.stopLoading()
            webView.onPause()
            webView.clearHistory()
            webView.loadUrl("about:blank")
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}

private fun isRunningOnEmulator(): Boolean {
    return Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.FINGERPRINT.startsWith("unknown", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
        Build.HARDWARE.contains("vbox", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk", ignoreCase = true) ||
        Build.PRODUCT.contains("emulator", ignoreCase = true) ||
        Build.MANUFACTURER.contains("Genymotion", ignoreCase = true)
}
