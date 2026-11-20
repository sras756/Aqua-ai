package com.example.core.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.KeyEvent
import com.example.device.AquaNotificationListenerService

data class ActiveMediaSessionInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean
)

data class SafFileEntry(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

class MediaPlaybackController(private val context: Context) {

    fun dispatchCommand(command: String): Result<String> = runCatching {
        val normalized = command.trim().lowercase()
        val keyCode = when (normalized) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip", "forward" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> throw IllegalArgumentException(
                "Unsupported media command '$command'. Use play_pause, play, pause, next, previous, or stop."
            )
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        "Dispatched media control command '$normalized' to active Android media session."
    }

    fun queryActiveSessions(): List<ActiveMediaSessionInfo> {
        return runCatching {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return emptyList()
            val listener = ComponentName(context, AquaNotificationListenerService::class.java)
            manager.getActiveSessions(listener).map { controller ->
                val meta = controller.metadata
                val state = controller.playbackState
                ActiveMediaSessionInfo(
                    packageName = controller.packageName,
                    title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                    artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                    isPlaying = state?.state == PlaybackState.STATE_PLAYING
                )
            }
        }.getOrDefault(emptyList())
    }
}

class AquaSafFileController(private val context: Context) {

    fun persistDirectoryPermission(treeUri: Uri, allowWrite: Boolean = true): Result<String> = runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (allowWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        "Authorized SAF directory: ${resolveDisplayName(treeUri)}"
    }

    fun getAuthorizedTreeUris(): List<Uri> {
        return context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
    }

    fun revokeAuthorizedUri(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        }.getOrDefault(false)
    }

    fun listTreeChildren(treeUri: Uri, maxItems: Int = 100): Result<List<SafFileEntry>> = runCatching {
        val resolver = context.contentResolver
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val entries = mutableListOf<SafFileEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext() && entries.size < maxItems) {
                val childId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                val childUri = if (childId.isNotEmpty()) {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString()
                } else {
                    treeUri.toString()
                }
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                entries.add(
                    SafFileEntry(
                        uriString = childUri,
                        displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "Document",
                        mimeType = mime,
                        sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        entries.sortedWith(compareByDescending<SafFileEntry> { it.isDirectory }.thenBy { it.displayName.lowercase() })
    }

    fun readTextDocument(uri: Uri, maxChars: Int = 64_000): Result<String> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText().take(maxChars)
        } ?: throw IllegalStateException("Unable to open document URI.")
    }

    fun writeTextDocument(uri: Uri, content: String): Result<Int> = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            out.write(bytes)
            out.flush()
            bytes.size
        } ?: throw IllegalStateException("Unable to open output stream for document URI.")
    }

    fun resolveDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx).orEmpty() else ""
                } else ""
            }.orEmpty()
        }.getOrDefault("").ifBlank { uri.lastPathSegment ?: "Folder" }
    }
}
