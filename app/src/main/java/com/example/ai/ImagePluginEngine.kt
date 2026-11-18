package com.example.ai

import android.graphics.Bitmap
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class ImagePluginModel(
    val id: String,
    val label: String,
    val summary: String
) {
    NANO_BANANA(
        id = "gemini-nano-banana-2.1",
        label = "Nano Banana 2.1",
        summary = "Fast balanced generation & image editing"
    ),
    PRO_IMAGE(
        id = "gemini-3-pro-image",
        label = "Gemini 3 Pro Image",
        summary = "High-detail studio composition"
    ),
    FLASH_LITE_IMAGE(
        id = "gemini-3.1-flash-lite-image",
        label = "3.1 Flash-Lite Image",
        summary = "Low-latency rapid synthesis"
    );

    companion object {
        fun fromId(id: String?): ImagePluginModel {
            val clean = id?.trim().orEmpty().lowercase()
            if (clean.contains("2.5") || clean == "gemini-3.1-flash-image") {
                return NANO_BANANA
            }
            return entries.firstOrNull { it.id == clean } ?: NANO_BANANA
        }
    }
}

enum class ImagePluginAspectRatio(
    val id: String,
    val label: String,
    val sdkRatio: AspectRatio?
) {
    SQUARE_1_1("1:1", "1:1 Square", null),
    LANDSCAPE_16_9("16:9", "16:9 Wide", AspectRatio.LANDSCAPE_16x9),
    PORTRAIT_9_16("9:16", "9:16 Story", AspectRatio.PORTRAIT_9x16),
    LANDSCAPE_4_3("4:3", "4:3 Classic", AspectRatio.LANDSCAPE_4x3),
    PORTRAIT_3_4("3:4", "3:4 Portrait", AspectRatio.PORTRAIT_3x4);

    companion object {
        fun fromId(id: String?): ImagePluginAspectRatio {
            val clean = id?.trim().orEmpty()
            return entries.firstOrNull { it.id == clean } ?: SQUARE_1_1
        }
    }
}

enum class ImagePluginSize(
    val id: String,
    val label: String,
    val sdkSize: ImageSize
) {
    SIZE_1K("1K", "1K (1024px)", ImageSize.SIZE_1K),
    SIZE_2K("2K", "2K Studio", ImageSize.SIZE_2K);

    companion object {
        fun fromId(id: String?): ImagePluginSize {
            val clean = id?.trim().orEmpty().uppercase()
            return entries.firstOrNull { it.id == clean } ?: SIZE_1K
        }
    }
}

data class ImagePluginConfig(
    val enabled: Boolean = true,
    val modelId: String = ImagePluginModel.NANO_BANANA.id,
    val aspectRatioId: String = ImagePluginAspectRatio.SQUARE_1_1.id,
    val imageSizeId: String = ImagePluginSize.SIZE_1K.id
) {
    fun normalized(): ImagePluginConfig = copy(
        modelId = ImagePluginModel.fromId(modelId).id,
        aspectRatioId = ImagePluginAspectRatio.fromId(aspectRatioId).id,
        imageSizeId = ImagePluginSize.fromId(imageSizeId).id
    )
}

data class GeneratedImageItem(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val modelUsed: String,
    val aspectRatio: String,
    val imageSize: String,
    val bitmap: Bitmap,
    val createdAtMs: Long = System.currentTimeMillis(),
    val isEdit: Boolean = false
)

sealed interface ImageGenUiState {
    data object Idle : ImageGenUiState
    data object Loading : ImageGenUiState
    data class Success(val item: GeneratedImageItem) : ImageGenUiState
    data class Error(val message: String) : ImageGenUiState
}

/**
 * Runtime image synthesis and Image-to-Image editing plugin powered by Firebase AI Logic.
 * Uses only current Gemini image models (`gemini-nano-banana-2.1`, `gemini-3-pro-image`,
 * `gemini-3.1-flash-lite-image`) with realistic 60s timeouts and strict error surfacing.
 */
class ImagePluginEngine {

    fun buildModel(config: ImagePluginConfig): GenerativeModel {
        val normalized = config.normalized()
        val ratio = ImagePluginAspectRatio.fromId(normalized.aspectRatioId)
        val size = ImagePluginSize.fromId(normalized.imageSizeId)
        val modelSpec = ImagePluginModel.fromId(normalized.modelId)

        val genConfig = generationConfig {
            responseModalities = listOf(ResponseModality.IMAGE)
            imageConfig = ImageConfig.builder()
                .apply {
                    ratio.sdkRatio?.let { setAspectRatio(it) }
                    setImageSize(size.sdkSize)
                }
                .build()
        }

        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelSpec.id,
            generationConfig = genConfig
        )
    }

    suspend fun generateOrEdit(
        prompt: String,
        config: ImagePluginConfig,
        sourceBitmap: Bitmap? = null
    ): GeneratedImageItem {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Enter a prompt describing the image to generate." }
        val normalized = config.normalized()
        check(normalized.enabled) { "Image Generation Plugin is currently paused. Enable it in Image Studio." }

        val model = buildModel(normalized)
        val bitmap = withTimeout(GENERATION_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val response = if (sourceBitmap != null) {
                    model.generateContent(
                        content {
                            image(sourceBitmap)
                            text(cleanPrompt)
                        }
                    )
                } else {
                    model.generateContent(cleanPrompt)
                }

                response.candidates.firstOrNull()
                    ?.content?.parts?.filterIsInstance<ImagePart>()
                    ?.firstOrNull()
                    ?.image
            }
        } ?: throw IllegalStateException(
            "Model ${normalized.modelId} finished without returning an image payload. Check Firebase Blaze billing, App Check, or reword your prompt."
        )

        return GeneratedImageItem(
            prompt = cleanPrompt,
            modelUsed = normalized.modelId,
            aspectRatio = normalized.aspectRatioId,
            imageSize = normalized.imageSizeId,
            bitmap = bitmap,
            isEdit = sourceBitmap != null
        )
    }

    companion object {
        const val GENERATION_TIMEOUT_MS = 60_000L

        fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }
}
