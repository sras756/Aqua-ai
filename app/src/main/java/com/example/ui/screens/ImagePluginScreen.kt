package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.GeneratedImageItem
import com.example.ai.ImageGenUiState
import com.example.ai.ImagePluginAspectRatio
import com.example.ai.ImagePluginEngine
import com.example.ai.ImagePluginModel
import com.example.ai.ImagePluginSize
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaSectionHeader
import com.example.ui.components.AquaStatusPill
import com.example.ui.theme.AquaTheme

private data class StudioPreset(
    val title: String,
    val prompt: String
)

private val STUDIO_PRESETS = listOf(
    StudioPreset(
        "Product Shot",
        "Minimalist titanium smart earbud floating above translucent rippled water, dramatic studio rim lighting, crisp reflections, macro photography"
    ),
    StudioPreset(
        "Cybernetic Icon",
        "Clean isometric futuristic app emblem crafted from frosted glass and glowing cyan sapphire crystal on deep obsidian backdrop"
    ),
    StudioPreset(
        "Ocean Observatory",
        "Architectural underwater glass research dome in a bioluminescent abyssal trench, volumetric ocean light shafts, ultra detailed concept art"
    ),
    StudioPreset(
        "Botanical Poster",
        "Editorial mid-century risograph botanical illustration of deep-sea coral and kelp silhouettes in teal and warm cream ink"
    )
)

@Composable
fun ImagePluginScreen(viewModel: AquaMainViewModel) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val config by viewModel.imagePluginConfig.collectAsStateWithLifecycle()
    val uiState by viewModel.imageGenUiState.collectAsStateWithLifecycle()
    val gallery by viewModel.generatedImageGallery.collectAsStateWithLifecycle()
    val editBaseBitmap by viewModel.editBaseBitmap.collectAsStateWithLifecycle()
    val editBaseLabel by viewModel.editBaseLabel.collectAsStateWithLifecycle()

    var promptText by rememberSaveable { mutableStateOf("") }
    var pendingSaveItem by remember { mutableStateOf<GeneratedImageItem?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.pickImagePluginEditBase(uri)
        }
    }

    val savePngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val item = pendingSaveItem
        pendingSaveItem = null
        if (uri != null && item != null) {
            runCatching {
                val pngBytes = ImagePluginEngine.bitmapToPngBytes(item.bitmap)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pngBytes)
                } ?: error("Unable to open destination file stream.")
            }.onSuccess {
                viewModel.showBannerMessage("Exported high-resolution PNG image.")
            }.onFailure { err ->
                viewModel.showBannerMessage("Export failed: ${err.localizedMessage}")
            }
        }
    }

    val isLoading = uiState is ImageGenUiState.Loading
    val activeModel = ImagePluginModel.fromId(config.modelId)

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    AquaAnimatedEntrance(index = 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AquaSectionHeader(
                                eyebrow = "AI SYNTHESIS PLUGIN",
                                title = "Image Studio",
                                subtitle = "Firebase AI Logic • ${activeModel.label}",
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AquaStatusPill(
                                    label = if (config.enabled) "PLUGIN ON" else "PAUSED",
                                    accent = if (config.enabled) c.success else c.amber
                                )
                                Switch(
                                    checked = config.enabled,
                                    onCheckedChange = { viewModel.setImagePluginEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = c.onAccent,
                                        checkedTrackColor = c.accentCyan,
                                        uncheckedThumbColor = c.textMuted,
                                        uncheckedTrackColor = c.cardSurface
                                    ),
                                    modifier = Modifier.testTag("image_plugin_enabled_switch")
                                )
                            }
                        }
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 1) {
                        AquaGlassCard(
                            borderColor = c.accentCyan.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Brush,
                                        contentDescription = null,
                                        tint = c.accentCyan
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (editBaseBitmap != null) "Image-to-Image Transform" else "Text-to-Image Composer",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = c.textPrimary
                                    )
                                }

                                // Optional Edit Base Reference Image Banner
                                AnimatedVisibility(visible = editBaseBitmap != null) {
                                    editBaseBitmap?.let { baseBmp ->
                                        Surface(
                                            color = c.deepSurface,
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, c.accentTeal.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Image(
                                                    bitmap = baseBmp.asImageBitmap(),
                                                    contentDescription = "Edit source preview",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Editing Base Image",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = c.accentTeal,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = editBaseLabel ?: "Reference image",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = c.textMuted,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { viewModel.clearImagePluginEditBase() },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("image_plugin_clear_edit_base")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Close,
                                                        contentDescription = "Clear edit base image",
                                                        tint = c.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = promptText,
                                    onValueChange = { promptText = it },
                                    label = {
                                        Text(
                                            if (editBaseBitmap != null) {
                                                "Describe edits to apply to your base image"
                                            } else {
                                                "Describe the visual you want to synthesize"
                                            }
                                        )
                                    },
                                    placeholder = {
                                        Text("e.g., Futuristic underwater observatory glowing with bioluminescent cyan coral")
                                    },
                                    minLines = 3,
                                    maxLines = 5,
                                    enabled = !isLoading,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = c.accentCyan,
                                        unfocusedBorderColor = c.borderGlow,
                                        focusedTextColor = c.textPrimary,
                                        unfocusedTextColor = c.textPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("image_plugin_prompt_input")
                                )

                                // Studio Presets
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    STUDIO_PRESETS.forEach { preset ->
                                        FilterChip(
                                            selected = false,
                                            onClick = { promptText = preset.prompt },
                                            enabled = !isLoading,
                                            label = { Text(preset.title) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.AutoAwesome,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = c.deepSurface,
                                                labelColor = c.textPrimary,
                                                iconColor = c.accentCyan
                                            )
                                        )
                                    }
                                }

                                // Model Selection
                                Text(
                                    text = "IMAGE ENGINE MODEL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ImagePluginModel.entries.forEach { model ->
                                        val selected = config.modelId == model.id
                                        FilterChip(
                                            selected = selected,
                                            enabled = !isLoading,
                                            onClick = { viewModel.updateImagePluginConfig(modelId = model.id) },
                                            label = { Text(model.label) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = c.accentCyan.copy(alpha = 0.22f),
                                                selectedLabelColor = c.accentCyan,
                                                containerColor = c.deepSurface,
                                                labelColor = c.textPrimary
                                            ),
                                            modifier = Modifier.testTag("image_model_${model.id}")
                                        )
                                    }
                                }

                                // Aspect Ratio & Resolution
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "ASPECT RATIO",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textMuted
                                        )
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            ImagePluginAspectRatio.entries.forEach { ratio ->
                                                val selected = config.aspectRatioId == ratio.id
                                                FilterChip(
                                                    selected = selected,
                                                    enabled = !isLoading,
                                                    onClick = { viewModel.updateImagePluginConfig(aspectRatioId = ratio.id) },
                                                    label = { Text(ratio.id) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = c.accentTeal.copy(alpha = 0.22f),
                                                        selectedLabelColor = c.accentTeal,
                                                        containerColor = c.deepSurface,
                                                        labelColor = c.textPrimary
                                                    ),
                                                    modifier = Modifier.testTag("image_ratio_${ratio.id}")
                                                )
                                            }
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "RESOLUTION",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textMuted
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            ImagePluginSize.entries.forEach { sz ->
                                                val selected = config.imageSizeId == sz.id
                                                FilterChip(
                                                    selected = selected,
                                                    enabled = !isLoading,
                                                    onClick = { viewModel.updateImagePluginConfig(imageSizeId = sz.id) },
                                                    label = { Text(sz.id) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = c.accentAzure.copy(alpha = 0.24f),
                                                        selectedLabelColor = c.accentCyan,
                                                        containerColor = c.deepSurface,
                                                        labelColor = c.textPrimary
                                                    ),
                                                    modifier = Modifier.testTag("image_size_${sz.id}")
                                                )
                                            }
                                        }
                                    }
                                }

                                // Primary Actions Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        enabled = !isLoading,
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("image_plugin_pick_photo_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PhotoLibrary,
                                            contentDescription = "Pick photo for editing",
                                            tint = c.accentCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Edit Photo", color = c.accentCyan)
                                    }

                                    Button(
                                        onClick = { viewModel.generatePluginImage(promptText) },
                                        enabled = config.enabled && !isLoading && promptText.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = c.accentCyan,
                                            contentColor = c.onAccent
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("image_plugin_generate_button")
                                    ) {
                                        if (isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = c.onAccent
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Synthesizing...")
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (editBaseBitmap != null) "Transform Image" else "Generate Image",
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Active Synthesis Output Canvas
                item {
                    AquaAnimatedEntrance(index = 2) {
                        ImageSynthesisStageCard(
                            uiState = uiState,
                            aspectRatio = config.aspectRatioId,
                            onSavePng = { item ->
                                pendingSaveItem = item
                                savePngLauncher.launch("aqua_studio_${item.id.take(6)}.png")
                            },
                            onUseAsEditBase = { item ->
                                viewModel.useGeneratedImageAsEditBase(item)
                            },
                            onSendToChat = { item ->
                                viewModel.sendGeneratedImageToChat(item)
                            },
                            onRetry = {
                                if (promptText.isNotBlank()) {
                                    viewModel.generatePluginImage(promptText)
                                }
                            }
                        )
                    }
                }

                // Recent Generated Gallery
                if (gallery.isNotEmpty()) {
                    item {
                        AquaAnimatedEntrance(index = 3) {
                            AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "SESSION GALLERY (${gallery.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = c.accentCyan
                                        )
                                        Text(
                                            text = "Tap to inspect or edit",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textMuted
                                        )
                                    }

                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(gallery, key = { it.id }) { item ->
                                            GalleryThumbnailCard(
                                                item = item,
                                                isSelected = (uiState as? ImageGenUiState.Success)?.item?.id == item.id,
                                                onSelect = { viewModel.selectGeneratedImage(item) },
                                                onDelete = { viewModel.deleteGeneratedImage(item.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 4) {
                        AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "AI CORE PLUGIN INTEGRATION",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.accentTeal
                                )
                                Text(
                                    text = "When enabled, Aqua Core can also invoke the generate_image tool automatically during voice or text chat whenever you ask it to draw, design, or illustrate visuals.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSynthesisStageCard(
    uiState: ImageGenUiState,
    aspectRatio: String,
    onSavePng: (GeneratedImageItem) -> Unit,
    onUseAsEditBase: (GeneratedImageItem) -> Unit,
    onSendToChat: (GeneratedImageItem) -> Unit,
    onRetry: () -> Unit
) {
    val c = AquaTheme.colors
    val ratioFloat = when (aspectRatio) {
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        "4:3" -> 4f / 3f
        "3:4" -> 3f / 4f
        else -> 1f
    }

    AquaGlassCard(
        borderColor = when (uiState) {
            is ImageGenUiState.Error -> c.error.copy(alpha = 0.65f)
            is ImageGenUiState.Success -> c.accentTeal.copy(alpha = 0.6f)
            else -> c.borderGlow
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (uiState) {
                is ImageGenUiState.Idle -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.deepSurface)
                            .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                tint = c.accentCyan.copy(alpha = 0.75f),
                                modifier = Modifier.size(42.dp)
                            )
                            Text(
                                text = "Image Studio Ready",
                                style = MaterialTheme.typography.titleMedium,
                                color = c.textPrimary
                            )
                            Text(
                                text = "Enter a prompt above or select a photo to start real-time synthesis via Firebase AI Logic.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textMuted
                            )
                        }
                    }
                }

                is ImageGenUiState.Loading -> {
                    val transition = rememberInfiniteTransition(label = "image_shimmer")
                    val shimmerOffset by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1200f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "shimmer_x"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        c.deepSurface,
                                        c.accentCyan.copy(alpha = 0.18f),
                                        c.accentTeal.copy(alpha = 0.14f),
                                        c.deepSurface
                                    ),
                                    start = Offset(shimmerOffset - 400f, 0f),
                                    end = Offset(shimmerOffset, 400f)
                                )
                            )
                            .border(1.dp, c.accentCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(20.dp)
                        ) {
                            CircularProgressIndicator(color = c.accentCyan, strokeWidth = 3.dp)
                            Text(
                                text = "Synthesizing via Firebase AI Logic...",
                                style = MaterialTheme.typography.titleSmall,
                                color = c.textPrimary
                            )
                            Text(
                                text = "Image diffusion usually takes 30–60 seconds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textMuted
                            )
                        }
                    }
                }

                is ImageGenUiState.Error -> {
                    Surface(
                        color = c.error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, c.error.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                            .testTag("image_plugin_error_box")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Generation error",
                                    tint = c.error
                                )
                                Text(
                                    text = "Synthesis Failed",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textPrimary
                            )
                            OutlinedButton(
                                onClick = onRetry,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Retry Synthesis", color = c.accentCyan)
                            }
                        }
                    }
                }

                is ImageGenUiState.Success -> {
                    val item = uiState.item
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (item.isEdit) "TRANSFORMED OUTPUT" else "SYNTHESIZED OUTPUT",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.accentTeal,
                            fontWeight = FontWeight.Bold
                        )
                        AquaStatusPill(
                            label = "${item.aspectRatio} • ${item.imageSize}",
                            accent = c.accentCyan
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratioFloat.coerceIn(0.5f, 1.85f))
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = item.bitmap.asImageBitmap(),
                            contentDescription = "Generated image for ${item.prompt}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("image_plugin_result_preview")
                        )
                    }

                    Text(
                        text = item.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSavePng(item) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("image_plugin_save_png")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save PNG", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { onUseAsEditBase(item) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("image_plugin_use_as_edit")
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { onSendToChat(item) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.accentCyan,
                                contentColor = c.onAccent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("image_plugin_send_to_chat")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ask AI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryThumbnailCard(
    item: GeneratedImageItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val c = AquaTheme.colors
    Box(
        modifier = Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.deepSurface)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) c.accentCyan else c.borderGlow,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onSelect)
            .testTag("gallery_thumb_${item.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
            ) {
                Image(
                    bitmap = item.bitmap.asImageBitmap(),
                    contentDescription = item.prompt,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove generated image",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = item.prompt,
                style = MaterialTheme.typography.labelSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}
