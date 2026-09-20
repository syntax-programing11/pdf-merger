package com.example.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ads.AdManager
import com.example.data.model.RecentFileItem
import com.example.engine.ConvertedImageItem
import com.example.engine.ImageEngine
import com.example.engine.StorageHelper
import com.example.engine.TargetImageFormat
import com.example.ui.components.ProcessingState
import com.example.ui.components.ProcessingStatusDialog
import com.example.ui.components.ToolkitTopAppBar
import com.example.ui.components.pressScale
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

data class PendingConvertItem(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertImageScreen(
    onNavigateBack: () -> Unit,
    onSaveRecentFile: (RecentFileItem) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val pendingImages = remember { mutableStateListOf<PendingConvertItem>() }
    val convertedResults = remember { mutableStateListOf<ConvertedImageItem>() }

    var selectedFormat by remember { mutableStateOf(TargetImageFormat.PNG) }
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var quality by remember { mutableFloatStateOf(90f) }
    var processingState by remember { mutableStateOf<ProcessingState>(ProcessingState.Idle) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            convertedResults.clear()
            for (uri in uris) {
                if (pendingImages.none { it.uri == uri }) {
                    val name = StorageHelper.getFileName(context, uri)
                    val size = StorageHelper.getFileSize(context, uri)
                    pendingImages.add(PendingConvertItem(uri, name, size))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolkitTopAppBar(
                title = "Convert Image Format",
                onBackClick = onNavigateBack,
                actions = {
                    if (pendingImages.isNotEmpty() && convertedResults.isEmpty()) {
                        IconButton(
                            onClick = {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.testTag("add_more_images_convert")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Images")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (pendingImages.isNotEmpty() && convertedResults.isEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    processingState = ProcessingState.Processing(0.05f, "Converting images...")
                                    convertedResults.clear()

                                    for ((idx, item) in pendingImages.withIndex()) {
                                        val progress = 0.1f + (idx.toFloat() / pendingImages.size.toFloat()) * 0.85f
                                        processingState = ProcessingState.Processing(
                                            progress,
                                            "Converting ${idx + 1}/${pendingImages.size}: ${item.fileName}"
                                        )

                                        val converted = ImageEngine.convertSingleImage(
                                            context = context,
                                            inputUri = item.uri,
                                            targetFormat = selectedFormat,
                                            quality = quality.toInt()
                                        )
                                        convertedResults.add(converted)

                                        if (converted.isSuccess && converted.convertedUri != null) {
                                            val recent = RecentFileItem(
                                                fileName = converted.convertedFileName,
                                                uriString = converted.convertedUri.toString(),
                                                filePath = converted.convertedFileName,
                                                fileType = "IMAGE",
                                                toolType = "CONVERT_IMAGE",
                                                fileSizeBytes = converted.convertedSizeBytes,
                                                details = "${item.fileName.substringAfterLast('.').uppercase()} -> ${selectedFormat.name}",
                                                thumbnailPath = converted.thumbnailPath
                                            )
                                            onSaveRecentFile(recent)
                                        }
                                    }

                                    activity?.let { act ->
                                        AdManager.showInterstitial(act) {}
                                    }

                                    processingState = ProcessingState.Idle
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .pressScale()
                                .testTag("convert_images_action_button")
                        ) {
                            Text(
                                text = "Convert ${pendingImages.size} Image(s) to ${selectedFormat.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else if (convertedResults.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val successfulUris = convertedResults.mapNotNull { it.convertedUri }
                        FilledTonalButton(
                            onClick = {
                                if (successfulUris.size == 1) {
                                    StorageHelper.shareFile(context, successfulUris.first(), selectedFormat.mimeType)
                                } else if (successfulUris.size > 1) {
                                    StorageHelper.shareMultipleFiles(context, ArrayList(successfulUris), selectedFormat.mimeType)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .pressScale()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share All")
                        }

                        Button(
                            onClick = {
                                pendingImages.clear()
                                convertedResults.clear()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .pressScale()
                        ) {
                            Text("Convert More")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (pendingImages.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFEF3C7),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Transform,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Choose Images to Convert",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Select images to transform into JPG, PNG, WebP, or BMP locally with detailed before/after size comparisons.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Button(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier
                            .pressScale()
                            .height(50.dp)
                            .testTag("pick_images_to_convert_button")
                    ) {
                        Icon(Icons.Default.Transform, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Images")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (convertedResults.isEmpty()) {
                        item {
                            // Target format selector
                            Text(
                                text = "Target Format",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TargetImageFormat.values().forEach { format ->
                                    FilterChip(
                                        selected = selectedFormat == format,
                                        onClick = { selectedFormat = format },
                                        label = { Text(format.name) },
                                        modifier = Modifier.testTag("format_chip_${format.name}")
                                    )
                                }
                            }

                            // Quality slider for lossy formats (JPG, WebP)
                            if (selectedFormat == TargetImageFormat.JPG || selectedFormat == TargetImageFormat.WEBP) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Compression Quality",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "${quality.toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = quality,
                                    onValueChange = { quality = it },
                                    valueRange = 20f..100f,
                                    steps = 15,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Images to Convert (${pendingImages.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        itemsIndexed(pendingImages, key = { _, item -> item.uri }) { index, item ->
                            PendingImageCard(
                                item = item,
                                onRemove = { pendingImages.removeAt(index) }
                            )
                        }
                    } else {
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = SuccessGreen.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Conversion Complete!",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = SuccessGreen
                                        )
                                        Text(
                                            text = "${convertedResults.count { it.isSuccess }} files converted & saved to Downloads",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Before & After Results",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        items(convertedResults, key = { it.convertedFileName.ifEmpty { it.originalFileName } }) { result ->
                            ConvertedResultCard(result = result)
                        }
                    }
                }
            }
        }

        ProcessingStatusDialog(state = processingState)
    }
}

@Composable
fun PendingImageCard(
    item: PendingConvertItem,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = StorageHelper.formatFileSize(item.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ConvertedResultCard(result: ConvertedImageItem) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = result.convertedUri ?: result.originalUri,
                        contentDescription = result.convertedFileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.convertedFileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Before vs After file size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = StorageHelper.formatFileSize(result.originalSizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = StorageHelper.formatFileSize(result.convertedSizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Percentage savings / difference badge
                    if (result.originalSizeBytes > 0 && result.convertedSizeBytes > 0) {
                        val diff = result.convertedSizeBytes - result.originalSizeBytes
                        val percent = (diff.toDouble() / result.originalSizeBytes.toDouble() * 100).toInt()
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (percent <= 0) SuccessGreen.copy(alpha = 0.15f) else Color(0xFFFEE2E2),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = if (percent <= 0) "$percent% smaller" else "+$percent% larger",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = if (percent <= 0) SuccessGreen else ErrorRed,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Share button
                if (result.convertedUri != null) {
                    IconButton(
                        onClick = {
                            StorageHelper.shareFile(context, result.convertedUri, result.targetFormat.mimeType)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                }
            }
        }
    }
}
