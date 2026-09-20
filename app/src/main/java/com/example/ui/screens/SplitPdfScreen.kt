package com.example.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

enum class SplitMode {
    EXTRACT_SELECTED,
    SPLIT_ALL_PAGES
}

@Composable
fun SplitPdfScreen(
    onNavigateBack: () -> Unit,
    onSaveRecentFile: (RecentFileItem) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var sourcePdfUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePdfName by remember { mutableStateOf("") }
    var pageThumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isRenderingPages by remember { mutableStateOf(false) }

    val selectedPageIndices = remember { mutableStateListOf<Int>() }
    var splitMode by remember { mutableStateOf(SplitMode.EXTRACT_SELECTED) }
    var rangeInput by remember { mutableStateOf("") }
    var processingState by remember { mutableStateOf<ProcessingState>(ProcessingState.Idle) }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            sourcePdfUri = uri
            sourcePdfName = StorageHelper.getFileName(context, uri)
            selectedPageIndices.clear()
            rangeInput = ""

            scope.launch {
                isRenderingPages = true
                pageThumbnails = PdfEngine.renderAllThumbnails(context, uri, maxPages = 100)
                isRenderingPages = false
            }
        }
    }

    // Helper to parse page range like "1-3, 5" into 0-based indices
    fun applyRangeString(rangeText: String, totalPages: Int) {
        selectedPageIndices.clear()
        val parts = rangeText.split(",").map { it.trim() }
        for (part in parts) {
            if (part.contains("-")) {
                val bounds = part.split("-").mapNotNull { it.trim().toIntOrNull() }
                if (bounds.size == 2) {
                    val start = (bounds[0] - 1).coerceIn(0, totalPages - 1)
                    val end = (bounds[1] - 1).coerceIn(0, totalPages - 1)
                    val minB = kotlin.math.min(start, end)
                    val maxB = kotlin.math.max(start, end)
                    for (p in minB..maxB) {
                        if (!selectedPageIndices.contains(p)) selectedPageIndices.add(p)
                    }
                }
            } else {
                val page = part.toIntOrNull()
                if (page != null) {
                    val idx = page - 1
                    if (idx in 0 until totalPages && !selectedPageIndices.contains(idx)) {
                        selectedPageIndices.add(idx)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolkitTopAppBar(
                title = "Split PDF",
                onBackClick = onNavigateBack,
                actions = {
                    if (sourcePdfUri != null) {
                        OutlinedButton(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .pressScale()
                        ) {
                            Text("Change File", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (sourcePdfUri != null && pageThumbnails.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        val canProceed = when (splitMode) {
                            SplitMode.EXTRACT_SELECTED -> selectedPageIndices.isNotEmpty()
                            SplitMode.SPLIT_ALL_PAGES -> true
                        }

                        Button(
                            onClick = {
                                val uri = sourcePdfUri ?: return@Button
                                scope.launch {
                                    processingState = ProcessingState.Processing(0.05f, "Preparing split...")

                                    val pagesToProcess = if (splitMode == SplitMode.EXTRACT_SELECTED) {
                                        selectedPageIndices.toList()
                                    } else {
                                        (0 until pageThumbnails.size).toList()
                                    }

                                    val baseName = sourcePdfName.substringBeforeLast('.')
                                    val isSingleDoc = (splitMode == SplitMode.EXTRACT_SELECTED)

                                    val result = PdfEngine.splitPdf(
                                        context = context,
                                        inputUri = uri,
                                        selectedPages = pagesToProcess,
                                        extractAsSinglePdf = isSingleDoc,
                                        baseName = baseName
                                    ) { prog, msg ->
                                        processingState = ProcessingState.Processing(prog, msg)
                                    }

                                    result.fold(
                                        onSuccess = { list: List<ProcessedFileResult> ->
                                            if (list.isNotEmpty()) {
                                                for (item in list) {
                                                    val recentItem = RecentFileItem(
                                                        fileName = item.fileName,
                                                        uriString = item.uri.toString(),
                                                        filePath = item.path,
                                                        fileType = "PDF",
                                                        toolType = "SPLIT_PDF",
                                                        fileSizeBytes = item.sizeBytes,
                                                        details = "${item.pageCount} page(s) extracted",
                                                        thumbnailPath = item.thumbnailPath
                                                    )
                                                    onSaveRecentFile(recentItem)
                                                }

                                                activity?.let { act ->
                                                    AdManager.showInterstitial(act) {}
                                                }

                                                val primaryResult = list.first()
                                                processingState = ProcessingState.Success(
                                                    title = "Split Complete!",
                                                    message = if (list.size == 1) {
                                                        "Extracted PDF saved to Downloads folder."
                                                    } else {
                                                        "${list.size} single-page PDFs saved to Downloads folder."
                                                    },
                                                    details = "${primaryResult.fileName}\nTotal: ${list.size} file(s)",
                                                    onShare = {
                                                        if (list.size == 1) {
                                                            StorageHelper.shareFile(context, primaryResult.uri, "application/pdf")
                                                        } else {
                                                            val urisList = ArrayList(list.map { it.uri })
                                                            StorageHelper.shareMultipleFiles(context, urisList, "application/pdf")
                                                        }
                                                    },
                                                    onOpen = {
                                                        StorageHelper.openFile(context, primaryResult.uri, "application/pdf")
                                                    },
                                                    onDone = {
                                                        processingState = ProcessingState.Idle
                                                        onNavigateBack()
                                                    }
                                                )
                                            }
                                        },
                                        onFailure = { err ->
                                            processingState = ProcessingState.Error(
                                                message = err.message ?: "Failed to split PDF.",
                                                onDismiss = { processingState = ProcessingState.Idle }
                                            )
                                        }
                                    )
                                }
                            },
                            enabled = canProceed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .pressScale()
                                .testTag("split_pdf_action_button")
                        ) {
                            val text = if (splitMode == SplitMode.EXTRACT_SELECTED) {
                                "Extract ${selectedPageIndices.size} Page(s)"
                            } else {
                                "Split All into ${pageThumbnails.size} Files"
                            }
                            Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            if (sourcePdfUri == null) {
                // Initial empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CallSplit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Choose a PDF to Split",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Extract specific pages into a new PDF or split the whole document into individual single-page files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Button(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier
                            .pressScale()
                            .height(50.dp)
                            .testTag("pick_single_pdf_button")
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select PDF")
                    }
                }
            } else if (isRenderingPages) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Rendering page thumbnails...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    // Document summary card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = sourcePdfName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Total Pages: ${pageThumbnails.size} • Tap pages to toggle selection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode selectors
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = splitMode == SplitMode.EXTRACT_SELECTED,
                            onClick = { splitMode = SplitMode.EXTRACT_SELECTED },
                            label = { Text("Extract Selected") }
                        )
                        FilterChip(
                            selected = splitMode == SplitMode.SPLIT_ALL_PAGES,
                            onClick = { splitMode = SplitMode.SPLIT_ALL_PAGES },
                            label = { Text("Split All Pages") }
                        )
                    }

                    if (splitMode == SplitMode.EXTRACT_SELECTED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // Range input field and quick select buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = rangeInput,
                                onValueChange = {
                                    rangeInput = it
                                    applyRangeString(it, pageThumbnails.size)
                                },
                                label = { Text("Page Range (e.g. 1-3, 5)") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("split_page_range_field")
                            )
                            OutlinedButton(
                                onClick = {
                                    if (selectedPageIndices.size == pageThumbnails.size) {
                                        selectedPageIndices.clear()
                                        rangeInput = ""
                                    } else {
                                        selectedPageIndices.clear()
                                        selectedPageIndices.addAll(0 until pageThumbnails.size)
                                        rangeInput = "1-${pageThumbnails.size}"
                                    }
                                },
                                modifier = Modifier.pressScale()
                            ) {
                                Text(if (selectedPageIndices.size == pageThumbnails.size) "None" else "All")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Grid of page thumbnails
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        itemsIndexed(pageThumbnails) { pageIndex, thumbBitmap ->
                            val isSelected = selectedPageIndices.contains(pageIndex)
                            PageThumbnailItem(
                                pageNumber = pageIndex + 1,
                                bitmap = thumbBitmap,
                                isSelected = isSelected,
                                isSelectionMode = splitMode == SplitMode.EXTRACT_SELECTED,
                                onClick = {
                                    if (splitMode == SplitMode.EXTRACT_SELECTED) {
                                        if (isSelected) {
                                            selectedPageIndices.remove(pageIndex)
                                        } else {
                                            selectedPageIndices.add(pageIndex)
                                        }
                                        rangeInput = selectedPageIndices.sorted().map { it + 1 }.joinToString(", ")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        ProcessingStatusDialog(state = processingState)
    }
}

@Composable
fun PageThumbnailItem(
    pageNumber: Int,
    bitmap: Bitmap,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && isSelectionMode) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = isSelectionMode, onClick = onClick)
            .border(
                width = if (isSelected && isSelectionMode) 2.dp else 0.5.dp,
                color = if (isSelected && isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .pressScale()
            .testTag("page_thumbnail_$pageNumber")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
            ) {
                AsyncImage(
                    model = bitmap,
                    contentDescription = "Page $pageNumber",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.RadioButtonUnchecked,
                                contentDescription = "Unselected",
                                tint = Color.Gray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Page $pageNumber",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected && isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
