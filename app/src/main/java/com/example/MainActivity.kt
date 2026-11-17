package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.example.ai.configureAppCheck
import com.example.ui.navigation.AquaRootApp
import com.example.voice.AquaWakeWordService

class MainActivity : FragmentActivity() {

    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    private var wakeCommand by mutableStateOf<String?>(null)
    private var browserQuery by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MUST be the very first statement before setContent or any Firebase.ai call
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            AquaRootApp(
                sharedUris = sharedUris,
                onConsumeSharedUris = { sharedUris = emptyList() },
                wakeCommand = wakeCommand,
                onConsumeWakeCommand = { wakeCommand = null },
                browserQuery = browserQuery,
                onConsumeBrowserQuery = { browserQuery = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = extractParcelableUri(intent)
                if (uri != null) {
                    sharedUris = listOf(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = extractParcelableUriList(intent)
                if (uris.isNotEmpty()) {
                    sharedUris = uris
                }
            }
        }
        if (intent.hasExtra(AquaWakeWordService.EXTRA_WAKE_COMMAND)) {
            wakeCommand = intent.getStringExtra(AquaWakeWordService.EXTRA_WAKE_COMMAND) ?: ""
        }
        if (intent.hasExtra("aqua_browser_query")) {
            val q = intent.getStringExtra("aqua_browser_query")
            if (!q.isNullOrBlank()) {
                browserQuery = q
            }
        }
    }

    private fun extractParcelableUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractParcelableUriList(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
