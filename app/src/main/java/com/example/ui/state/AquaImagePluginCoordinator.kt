package com.example.ui.state

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.ai.GeneratedImageItem
import com.example.ai.ImageGenUiState
import com.example.ai.ImagePluginAspectRatio
import com.example.ai.ImagePluginConfig
import com.example.ai.ImagePluginEngine
import com.example.ai.ImagePluginModel
import com.example.data.local.AquaLocalPreferences
import com.example.data.model.UserSettings
import com.example.data.repository.FileImportRepository
import com.example.domain.UserProfilePolicy
import com.google.firebase.ai.type.QuotaExceededException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ImagePluginCoord"
private const val MAX_SESSION_GALLERY = 12

class AquaImagePluginCoordinator(
    private val localPrefs: AquaLocalPreferences,
    private val fileImportRepository: FileImportRepository,
    private val settingsProvider: () -> UserSettings,
    private val scope: CoroutineScope,
    private val onAttachBitmapToChat: (Bitmap, String) -> Unit,
    private val onNavigateToTab: (String) -> Unit,
    private val onBanner: (String) -> Unit,
    private val engine: ImagePluginEngine = ImagePluginEngine()
) {
    val config: StateFlow<ImagePluginConfig> = localPrefs.imagePluginConfig

    private val _uiState = MutableStateFlow<ImageGenUiState>(ImageGenUiState.Idle)
    val uiState: StateFlow<ImageGenUiState> = _uiState.asStateFlow()

    private val _gallery = MutableStateFlow<List<GeneratedImageItem>>(emptyList())
    val gallery: StateFlow<List<GeneratedImageItem>> = _gallery.asStateFlow()

    private val _editBaseBitmap = MutableStateFlow<Bitmap?>(null)
    val editBaseBitmap: StateFlow<Bitmap?> = _editBaseBitmap.asStateFlow()

    private val _editBaseLabel = MutableStateFlow<String?>(null)
    val editBaseLabel: StateFlow<String?> = _editBaseLabel.asStateFlow()

    fun setPluginEnabled(enabled: Boolean) {
        localPrefs.setImagePluginConfig(config.value.copy(enabled = enabled))
        onBanner(if (enabled) "Image Studio Plugin enabled." else "Image Studio Plugin paused.")
    }

    fun updateConfig(
        modelId: String? = null,
        aspectRatioId: String? = null,
        imageSizeId: String? = null
    ) {
        val current = config.value
        localPrefs.setImagePluginConfig(
            current.copy(
                modelId = modelId ?: current.modelId,
                aspectRatioId = aspectRatioId ?: current.aspectRatioId,
                imageSizeId = imageSizeId ?: current.imageSizeId
            )
        )
    }

    fun pickEditBaseFromUri(uri: Uri) {
        scope.launch {
            fileImportRepository.importUri(uri)
                .onSuccess { att ->
                    val bmp = att.rawBytes?.let { fileImportRepository.decodeBitmap(it) }
                    if (bmp != null) {
                        _editBaseBitmap.value = bmp
                        _editBaseLabel.value = att.displayName
                        onBanner("Loaded '${att.displayName}' for Image-to-Image editing.")
                    } else {
                        onBanner("Selected file could not be decoded as an image bitmap.")
                    }
                }
                .onFailure { err ->
                    onBanner("Could not load reference image: ${err.localizedMessage}")
                }
        }
    }

    fun useGeneratedAsEditBase(item: GeneratedImageItem) {
        _editBaseBitmap.value = item.bitmap
        _editBaseLabel.value = "Generated (${item.aspectRatio})"
        onBanner("Loaded generated image as edit base. Describe your changes and tap Transform.")
    }

    fun clearEditBase() {
        _editBaseBitmap.value = null
        _editBaseLabel.value = null
    }

    fun selectGalleryItem(item: GeneratedImageItem) {
        _uiState.value = ImageGenUiState.Success(item)
    }

    fun deleteGalleryItem(id: String) {
        val remaining = _gallery.value.filterNot { it.id == id }
        _gallery.value = remaining
        val active = (_uiState.value as? ImageGenUiState.Success)?.item
        if (active?.id == id) {
            _uiState.value = remaining.firstOrNull()?.let { ImageGenUiState.Success(it) } ?: ImageGenUiState.Idle
        }
    }

    fun sendToCoreChat(item: GeneratedImageItem) {
        val safeLabel = "aqua_image_${item.id.take(6)}.png"
        onAttachBitmapToChat(item.bitmap, safeLabel)
        onNavigateToTab("home")
        onBanner("Attached generated image to Core Chat. Ask Aqua to analyze or refine it.")
    }

    fun generateFromUi(prompt: String) {
        if (UserProfilePolicy.isUnderAgeBlocked(settingsProvider())) {
            val msg = "Image Studio requires age 13+. Update your age in Settings -> Profile & Safety."
            _uiState.value = ImageGenUiState.Error(msg)
            onBanner(msg)
            return
        }
        val activeConfig = config.value
        if (!activeConfig.enabled) {
            val msg = "Image Generation Plugin is paused. Toggle it ON at the top of Image Studio."
            _uiState.value = ImageGenUiState.Error(msg)
            onBanner(msg)
            return
        }
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            _uiState.value = ImageGenUiState.Error("Enter a prompt describing the image you want to create.")
            return
        }

        val baseBitmap = _editBaseBitmap.value
        scope.launch {
            _uiState.value = ImageGenUiState.Loading
            try {
                val generated = engine.generateOrEdit(
                    prompt = clean,
                    config = activeConfig,
                    sourceBitmap = baseBitmap
                )
                recordGeneratedItem(generated)
                _uiState.value = ImageGenUiState.Success(generated)
            } catch (e: TimeoutCancellationException) {
                _uiState.value = ImageGenUiState.Error(
                    "Image synthesis timed out after 60 seconds. Try a simpler prompt or Nano Banana 2.1."
                )
            } catch (e: QuotaExceededException) {
                _uiState.value = ImageGenUiState.Error(
                    "Firebase AI Logic quota or rate limit exceeded (Blaze plan required). Please retry later."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image generation failed", e)
                _uiState.value = ImageGenUiState.Error(
                    e.localizedMessage ?: "Image generation failed. Verify App Check & Firebase AI Logic setup."
                )
            }
        }
    }

    suspend fun generateFromAiTool(
        prompt: String,
        aspectRatioHint: String? = null,
        modelHint: String? = null
    ): Result<GeneratedImageItem> {
        if (UserProfilePolicy.isUnderAgeBlocked(settingsProvider())) {
            return Result.failure(IllegalStateException("Image generation requires age 13+ confirmation."))
        }
        val baseCfg = config.value
        if (!baseCfg.enabled) {
            return Result.failure(IllegalStateException("Image Generation Plugin is paused by user in Image Studio."))
        }
        val effectiveCfg = baseCfg.copy(
            modelId = if (!modelHint.isNullOrBlank()) ImagePluginModel.fromId(modelHint).id else baseCfg.modelId,
            aspectRatioId = if (!aspectRatioHint.isNullOrBlank()) ImagePluginAspectRatio.fromId(aspectRatioHint).id else baseCfg.aspectRatioId
        )
        _uiState.value = ImageGenUiState.Loading
        return try {
            val item = engine.generateOrEdit(prompt = prompt, config = effectiveCfg, sourceBitmap = null)
            recordGeneratedItem(item)
            _uiState.value = ImageGenUiState.Success(item)
            Result.success(item)
        } catch (e: TimeoutCancellationException) {
            val msg = "Image generation timed out after 60 seconds."
            _uiState.value = ImageGenUiState.Error(msg)
            Result.failure(IllegalStateException(msg))
        } catch (e: QuotaExceededException) {
            val msg = "Firebase AI Logic quota exceeded. Check Firebase Blaze plan & rate limits."
            _uiState.value = ImageGenUiState.Error(msg)
            Result.failure(IllegalStateException(msg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI Tool image generation failed", e)
            val msg = e.localizedMessage ?: "Image generation failed."
            _uiState.value = ImageGenUiState.Error(msg)
            Result.failure(IllegalStateException(msg))
        }
    }

    private fun recordGeneratedItem(item: GeneratedImageItem) {
        _gallery.value = (listOf(item) + _gallery.value).take(MAX_SESSION_GALLERY)
    }
}
