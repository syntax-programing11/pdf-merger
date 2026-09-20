package com.example.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ads.AdManager
import com.example.data.model.RecentFileItem
import com.example.engine.PdfEngine
import com.example.engine.ProcessedFileResult
import com.example.engine.StorageHelper
import com.example.ui.components.ProcessingState
import com.example.ui.components.ProcessingStatusDialog
import com.example.ui.components.ToolkitTopAppBar
import com.example.ui.components.pressScale
import kotlinx.coroutines.launch

data class SelectedPdfItem(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val pageCount: Int,
    val thumbnail: Bitmap? = null
)

@Composable
fun MergePdfScreen(
    onNavigateBack: () -> Unit,
    onSaveRecentFile: (RecentFileItem) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val selectedFiles = remember { mutableStateListOf<SelectedPdfItem>() }
    var outputName by remember { mutableStateOf("Merged_Document") }
    var processingState by remember { mutableStateOf<ProcessingState>(ProcessingState.Idle) }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            scope.launch {
                for (uri in uris) {
                    if (selectedFiles.none { it.uri == uri }) {
                        val name = StorageHelper.getFileName(context, uri)
                        val size = StorageHelper.getFileSize(context, uri)
                        val pages = PdfEngine.getPageCount(context, uri)
                        val thumb = PdfEngine.renderThumbnail(context, uri, 0, 160)
                        selectedFiles.add(SelectedPdfItem(uri, name, size, pages, thumb))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolkitTopAppBar(
                title = "Merge PDF",
                onBackClick = onNavigateBack,
                actions = {
                    if (selectedFiles.isNotEmpty()) {
                        IconButton(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            modifier = Modifier.testTag("add_more_pdfs_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add more")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectedFiles.size >= 2) {
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
                                    processingState = ProcessingState.Processing(0.05f, "Preparing merge...")
                                    val uris = selectedFiles.map { it.uri }
                                    val result = PdfEngine.mergePdfs(
                                        context = context,
                                        inputUris = uris,
                                        outputFileName = outputName.trim().ifEmpty { "Merged_Document" }
                                    ) { progress, status ->
                                        processingState = ProcessingState.Processing(progress, status)
                                    }

                                    result.fold(
                                        onSuccess = { res: ProcessedFileResult ->
                                            val recentItem = RecentFileItem(
                                                fileName = res.fileName,
                                                uriString = res.uri.toString(),
                                                filePath = res.path,
                                                fileType = "PDF",
                                                toolType = "MERGE_PDF",
                                                fileSizeBytes = res.sizeBytes,
                                                details = "${res.pageCount} pages combined",
                                                thumbnailPath = res.thumbnailPath
                                            )
                                            onSaveRecentFile(recentItem)

                                            // Show interstitial ad after operation completion
                                            activity?.let { act ->
                                                AdManager.showInterstitial(act) {
                                                    // Continue to success dialog
                                                }
                                            }

                                            processingState = ProcessingState.Success(
                                                title = "Merge Complete!",
                                                message = "Your combined PDF has been saved to Downloads.",
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
                                                message = err.message ?: "An unexpected error occurred while merging PDFs.",
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
                                .testTag("merge_pdf_action_button")
                        ) {
                            Text(
                                text = "Merge ${selectedFiles.size} PDFs",
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
            if (selectedFiles.isEmpty()) {
                // Empty state picker
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Select PDF Files",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pick two or more PDF documents from your device to merge into one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Button(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier
                            .pressScale()
                            .height(50.dp)
                            .testTag("pick_pdfs_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose PDFs")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        // Output filename field
                        OutlinedTextField(
                            value = outputName,
                            onValueChange = { outputName = it },
                            label = { Text("Output PDF Name") },
                            trailingIcon = { Text(".pdf", modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("merge_output_name_field")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Order of files (${selectedFiles.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.pressScale()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add More")
                            }
                        }
                    }

                    itemsIndexed(selectedFiles, key = { _, item -> item.uri }) { index, item ->
                        MergeItemCard(
                            item = item,
                            index = index,
                            totalCount = selectedFiles.size,
                            onMoveUp = {
                                if (index > 0) {
                                    val temp = selectedFiles[index]
                                    selectedFiles[index] = selectedFiles[index - 1]
                                    selectedFiles[index - 1] = temp
                                }
                            },
                            onMoveDown = {
                                if (index < selectedFiles.size - 1) {
                                    val temp = selectedFiles[index]
                                    selectedFiles[index] = selectedFiles[index + 1]
                                    selectedFiles[index + 1] = temp
                                }
                            },
                            onRemove = {
                                selectedFiles.removeAt(index)
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
fun MergeItemCard(
    item: SelectedPdfItem,
    index: Int,
    totalCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("merge_item_$index")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail or icon
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(54.dp)
            ) {
                if (item.thumbnail != null) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${index + 1}. ${item.fileName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.pageCount} pages • ${StorageHelper.formatFileSize(item.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Move buttons and remove button
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
