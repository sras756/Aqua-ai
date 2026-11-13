package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.ImportedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

class FileImportRepository(private val context: Context) {

    suspend fun importUri(uri: Uri): Result<ImportedAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: guessMimeFromName(queryDisplayName(uri))
            val displayName = queryDisplayName(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase()

            val rawStreamBytes = resolver.openInputStream(uri)?.use { input ->
                readBoundedBytes(input, MAX_INLINE_BYTES)
            } ?: throw IllegalStateException("Unable to read selected URI: $displayName")

            val sizeBytes = rawStreamBytes.size.toLong()
            val isImage = mimeType.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "gif")
            val isVideo = mimeType.startsWith("video/") || ext in setOf("mp4", "mov", "webm", "mkv")
            val isAudio = mimeType.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "ogg", "aac")

            val extractedPreview = when {
                isImage -> "Image file ($displayName, ${formatSize(sizeBytes)}) ready for Gemini 4 Argon visual analysis."
                isVideo -> "Video file ($displayName, ${formatSize(sizeBytes)}) ready for Gemini 4 Argon video analysis."
                isAudio -> "Audio file ($displayName, ${formatSize(sizeBytes)}) ready for Gemini multimodal analysis."
                ext == "docx" || mimeType.contains("wordprocessingml") -> extractDocxText(rawStreamBytes)
                ext == "zip" || mimeType == "application/zip" -> inspectZipManifest(rawStreamBytes)
                ext == "pdf" || mimeType == "application/pdf" ->
                    "PDF document ($displayName, ${formatSize(sizeBytes)}) loaded as inline document payload."
                isTextLike(mimeType, ext) -> {
                    rawStreamBytes.toString(Charsets.UTF_8).take(MAX_TEXT_EXTRACT)
                }
                else -> "Binary attachment ($displayName, $mimeType, ${formatSize(sizeBytes)})."
            }

            val normalizedBytes = if (isImage) {
                compressImageIfNeeded(rawStreamBytes)
            } else {
                rawStreamBytes
            }

            ImportedAttachment(
                uriString = uri.toString(),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                extractedTextPreview = extractedPreview,
                rawBytes = normalizedBytes,
                isImage = isImage,
                isVideo = isVideo,
                isAudio = isAudio
            )
        }
    }

    fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }

    private fun compressImageIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size <= 3 * 1024 * 1024) return bytes
        val bmp = decodeBitmap(bytes) ?: return bytes
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun extractDocxText(bytes: ByteArray): String {
        val sb = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(StringReader(xml))
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.TEXT) {
                            sb.append(parser.text)
                        } else if (eventType == XmlPullParser.END_TAG && parser.name == "p") {
                            sb.append('\n')
                        }
                        eventType = parser.next()
                    }
                    break
                }
                entry = zis.nextEntry
            }
        }
        return sb.toString().trim().ifBlank { "DOCX file contains no readable text nodes." }
            .take(MAX_TEXT_EXTRACT)
    }

    private fun inspectZipManifest(bytes: ByteArray): String {
        val entries = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && entries.size < 100) {
                val safeName = entry.name.replace("..", "")
                if (!entry.isDirectory) {
                    entries.add("- $safeName (${entry.size} bytes)")
                }
                entry = zis.nextEntry
            }
        }
        return if (entries.isEmpty()) {
            "Empty ZIP archive."
        } else {
            "ZIP Archive Contents (${entries.size} entries shown):\n${entries.joinToString("\n")}"
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file"
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "log" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "yaml", "yml" -> "application/x-yaml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "kt", "kts" -> "text/x-kotlin"
            "py" -> "text/x-python"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun isTextLike(mimeType: String, ext: String): Boolean {
        if (mimeType.startsWith("text/")) return true
        return ext in setOf(
            "txt", "md", "markdown", "json", "yaml", "yml", "html", "htm",
            "css", "js", "ts", "kt", "kts", "java", "py", "xml", "csv", "sh", "gradle"
        )
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException(
                    "File exceeds the 18 MB inline payload limit for safe mobile processing."
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private companion object {
        const val MAX_INLINE_BYTES = 18 * 1024 * 1024 // < 20 MB Firebase AI limit
        const val MAX_TEXT_EXTRACT = 24_000
    }
}
