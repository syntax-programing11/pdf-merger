package com.example.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ads.AdManager
import com.example.data.model.RecentFileItem
import com.example.engine.ImageFitMode
import com.example.engine.PdfEngine
import com.example.engine.ProcessedFileResult
import com.example.engine.StorageHelper
import com.example.ui.components.ProcessingState
import com.example.ui.components.ProcessingStatusDialog
import com.example.ui.components.ToolkitTopAppBar
import com.example.ui.components.pressScale
import kotlinx.coroutines.launch

data class SelectedImageEntry(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long
)

@Composable
fun ImageToPdfScreen(
    onNavigateBack: () -> Unit,
    onSaveRecentFile: (RecentFileItem) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val selectedImages = remember { mutableStateListOf<SelectedImageEntry>() }
    var outputName by remember { mutableStateOf("Images_Document") }
    var fitMode by remember { mutableStateOf(ImageFitMode.FIT_A4_PORTRAIT) }
    var processingState by remember { mutableStateOf<ProcessingState>(ProcessingState.Idle) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            for (uri in uris) {
                if (selectedImages.none { it.uri == uri }) {
                    val name = StorageHelper.getFileName(context, uri)
                    val size = StorageHelper.getFileSize(context, uri)
                    selectedImages.add(SelectedImageEntry(uri, name, size))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolkitTopAppBar(
                title = "Image to PDF",
                onBackClick = onNavigateBack,
                actions = {
                    if (selectedImages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("add_more_images_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add more images")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectedImages.isNotEmpty()) {
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
                                    processingState = ProcessingState.Processing(0.05f, "Creating PDF...")
                                    val uris = selectedImages.map { it.uri }
                                    val finalDocName = outputName.trim().ifEmpty { "Images_Document" }

                                    val result = PdfEngine.imagesToPdf(
                                        context = context,
                                        imageUris = uris,
                                        outputFileName = finalDocName,
                                        fitMode = fitMode
                                    ) { progress, status ->
                                        processingState = ProcessingState.Processing(progress, status)
                                    }

                                    result.fold(
                                        onSuccess = { res: ProcessedFileResult ->
                                            val recent = RecentFileItem(
                                                fileName = res.fileName,
                                                uriString = res.uri.toString(),
                                                filePath = res.path,
                                                fileType = "PDF",
                                                toolType = "IMAGE_TO_PDF",
                                                fileSizeBytes = res.sizeBytes,
                                                details = "${res.pageCount} images converted",
                                                thumbnailPath = res.thumbnailPath
                                            )
                                            onSaveRecentFile(recent)

                                            activity?.let { act ->
                                                AdManager.showInterstitial(act) {}
                                            }

                                            processingState = ProcessingState.Success(
                                                title = "PDF Created!",
                                                message = "Your multi-page PDF has been saved to Downloads.",
                                                details = "${res.fileName}\n${res.pageCount} pages • ${StorageHelper.formatFileSize(res.sizeBytes)}",
                                                onShare = { StorageHelper.shareFile(context, res.uri, "application/pdf") },
                                                onOpen = { StorageHelper.openFile(context, res.uri, "application/pdf") },
                                                onDone = {
                                                    processingState = ProcessingState.Idle
                                                    onNavigateBack()
                                                }
                                            )
                                        },
                                        onFailure = { err ->
                                            processingState = ProcessingState.Error(
                                                message = err.message ?: "Failed to create PDF from images.",
                                                onDismiss = { processingState = ProcessingState.Idle }
                                            )
                                        }
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .pressScale()
                                .testTag("create_pdf_from_images_button")
                        ) {
                            Text(
                                text = "Create PDF (${selectedImages.size} images)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
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
            if (selectedImages.isEmpty()) {
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
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Select Images",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pick JPG, PNG, WebP, or HEIC images from your gallery to compile into a single PDF document.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .pressScale()
                            .height(50.dp)
                            .testTag("pick_images_button")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose Images")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = outputName,
                            onValueChange = { outputName = it },
                            label = { Text("Output PDF Name") },
                            trailingIcon = { Text(".pdf", modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("image_to_pdf_name_field")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Page Layout & Fit",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = fitMode == ImageFitMode.FIT_A4_PORTRAIT,
                                onClick = { fitMode = ImageFitMode.FIT_A4_PORTRAIT },
                                label = { Text("A4 Portrait") }
                            )
                            FilterChip(
                                selected = fitMode == ImageFitMode.FIT_A4_LANDSCAPE,
                                onClick = { fitMode = ImageFitMode.FIT_A4_LANDSCAPE },
                                label = { Text("A4 Landscape") }
                            )
                            FilterChip(
                                selected = fitMode == ImageFitMode.ORIGINAL_SIZE,
                                onClick = { fitMode = ImageFitMode.ORIGINAL_SIZE },
                                label = { Text("Original") }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Images (${selectedImages.size} pages)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.pressScale()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add More")
                            }
                        }
                    }

                    itemsIndexed(selectedImages, key = { _, item -> item.uri }) { index, item ->
                        ImageOrderItemCard(
                            item = item,
                            index = index,
                            totalCount = selectedImages.size,
                            onMoveUp = {
                                if (index > 0) {
                                    val temp = selectedImages[index]
                                    selectedImages[index] = selectedImages[index - 1]
                                    selectedImages[index - 1] = temp
                                }
                            },
                            onMoveDown = {
                                if (index < selectedImages.size - 1) {
                                    val temp = selectedImages[index]
                                    selectedImages[index] = selectedImages[index + 1]
                                    selectedImages[index + 1] = temp
                                }
                            },
                            onRemove = {
                                selectedImages.removeAt(index)
                            }
                        )
                    }
                }
            }
        }

        ProcessingStatusDialog(state = processingState)
    }
}

@Composable
fun ImageOrderItemCard(
    item: SelectedImageEntry,
    index: Int,
    totalCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("image_order_item_$index")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
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
                    text = "Page ${index + 1}: ${item.fileName}",
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
