package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

data class ProcessedFileResult(
    val uri: Uri,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val thumbnailPath: String?
)

enum class ImageFitMode {
    FIT_A4_PORTRAIT,   // Standard A4 (595 x 842 pt)
    FIT_A4_LANDSCAPE,  // Standard A4 (842 x 595 pt)
    ORIGINAL_SIZE      // Use image's natural dimensions
}

object PdfEngine {

    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext 0
            renderer = PdfRenderer(pfd)
            return@withContext renderer.pageCount
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    suspend fun renderThumbnail(
        context: Context,
        uri: Uri,
        pageIndex: Int = 0,
        targetWidth: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            page = renderer.openPage(pageIndex)
            val scale = targetWidth.toFloat() / page.width.toFloat()
            val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return@withContext bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            try { page?.close() } catch (ignored: Exception) {}
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    suspend fun renderAllThumbnails(
        context: Context,
        uri: Uri,
        maxPages: Int = 100,
        targetWidth: Int = 240
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val bitmaps = mutableListOf<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
            renderer = PdfRenderer(pfd)
            val count = min(renderer.pageCount, maxPages)

            for (i in 0 until count) {
                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(i)
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bitmap)
                } finally {
                    try { page?.close() } catch (ignored: Exception) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
        return@withContext bitmaps
    }

    suspend fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        outputFileName: String,
        onProgress: (progress: Float, status: String) -> Unit
    ): Result<ProcessedFileResult> = withContext(Dispatchers.IO) {
        try {
            if (inputUris.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No PDF files selected"))
            }

            onProgress(0.05f, "Analyzing input PDFs...")
            var totalPages = 0
            val fileInfos = mutableListOf<Pair<Uri, Int>>()
            for (uri in inputUris) {
                val pages = getPageCount(context, uri)
                if (pages <= 0) {
                    return@withContext Result.failure(IllegalArgumentException("Unable to read PDF: ${StorageHelper.getFileName(context, uri)}"))
                }
                fileInfos.add(Pair(uri, pages))
                totalPages += pages
            }

            val pdfDocument = PdfDocument()
            var currentTotalPage = 0
            var firstPageThumb: Bitmap? = null

            for ((fileIndex, pair) in fileInfos.withIndex()) {
                val (uri, count) = pair
                val fileName = StorageHelper.getFileName(context, uri)
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null

                try {
                    pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: continue
                    renderer = PdfRenderer(pfd)

                    for (pageIdx in 0 until count) {
                        currentTotalPage++
                        val progressFraction = 0.1f + (currentTotalPage.toFloat() / totalPages.toFloat()) * 0.75f
                        onProgress(
                            progressFraction,
                            "Merging $fileName (page ${pageIdx + 1}/$count)..."
                        )

                        var page: PdfRenderer.Page? = null
                        try {
                            page = renderer.openPage(pageIdx)
                            val renderWidth = (page.width * 1.5f).toInt()
                            val renderHeight = (page.height * 1.5f).toInt()

                            val pageBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(pageBitmap)
                            canvas.drawColor(Color.WHITE)
                            page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            if (firstPageThumb == null) {
                                firstPageThumb = Bitmap.createScaledBitmap(pageBitmap, 240, (240f * renderHeight / renderWidth).toInt(), true)
                            }

                            val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, currentTotalPage).create()
                            val docPage = pdfDocument.startPage(pageInfo)
                            val docCanvas = docPage.canvas

                            val destRect = Rect(0, 0, page.width, page.height)
                            docCanvas.drawBitmap(pageBitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                            pdfDocument.finishPage(docPage)
                            pageBitmap.recycle()
                        } finally {
                            try { page?.close() } catch (ignored: Exception) {}
                        }
                    }
                } finally {
                    try { renderer?.close() } catch (ignored: Exception) {}
                    try { pfd?.close() } catch (ignored: Exception) {}
                }
            }

            onProgress(0.90f, "Saving combined PDF to Downloads...")
            val finalName = if (outputFileName.endsWith(".pdf", ignoreCase = true)) outputFileName else "$outputFileName.pdf"

            val saveResult = StorageHelper.saveToDownloads(
                context = context,
                fileName = finalName,
                mimeType = "application/pdf"
            ) { outStream ->
                pdfDocument.writeTo(outStream)
            }
            pdfDocument.close()

            if (saveResult == null) {
                return@withContext Result.failure(Exception("Failed to save merged PDF to storage"))
            }

            val (outUri, outPath) = saveResult
            val fileSize = StorageHelper.getFileSize(context, outUri)
            val thumbPath = firstPageThumb?.let {
                StorageHelper.saveThumbnailToCache(context, it, "merge_thumb")
            }

            onProgress(1.0f, "Merge complete!")
            Result.success(
                ProcessedFileResult(
                    uri = outUri,
                    path = outPath,
                    fileName = finalName,
                    sizeBytes = fileSize,
                    pageCount = currentTotalPage,
                    thumbnailPath = thumbPath
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun splitPdf(
        context: Context,
        inputUri: Uri,
        selectedPages: List<Int>, // 0-indexed page numbers
        extractAsSinglePdf: Boolean,
        baseName: String,
        onProgress: (progress: Float, status: String) -> Unit
    ): Result<List<ProcessedFileResult>> = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            if (selectedPages.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No pages selected to split"))
            }

            pfd = context.contentResolver.openFileDescriptor(inputUri, "r")
                ?: return@withContext Result.failure(Exception("Could not open source PDF"))
            renderer = PdfRenderer(pfd)
            val totalPagesInSource = renderer.pageCount

            val validPages = selectedPages.filter { it in 0 until totalPagesInSource }.distinct().sorted()
            if (validPages.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Selected pages are out of bounds"))
            }

            val results = mutableListOf<ProcessedFileResult>()

            if (extractAsSinglePdf) {
                // Combine selected pages into one PDF
                val pdfDocument = PdfDocument()
                var firstThumb: Bitmap? = null

                for ((idx, pageNum) in validPages.withIndex()) {
                    val progress = 0.1f + (idx.toFloat() / validPages.size.toFloat()) * 0.75f
                    onProgress(progress, "Extracting page ${pageNum + 1} of ${validPages.size}...")

                    var page: PdfRenderer.Page? = null
                    try {
                        page = renderer.openPage(pageNum)
                        val renderWidth = (page.width * 1.5f).toInt()
                        val renderHeight = (page.height * 1.5f).toInt()

                        val pageBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(pageBitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        if (firstThumb == null) {
                            firstThumb = Bitmap.createScaledBitmap(pageBitmap, 240, (240f * renderHeight / renderWidth).toInt(), true)
                        }

                        val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, idx + 1).create()
                        val docPage = pdfDocument.startPage(pageInfo)
                        docPage.canvas.drawBitmap(pageBitmap, null, Rect(0, 0, page.width, page.height), Paint(Paint.FILTER_BITMAP_FLAG))
                        pdfDocument.finishPage(docPage)
                        pageBitmap.recycle()
                    } finally {
                        try { page?.close() } catch (ignored: Exception) {}
                    }
                }

                onProgress(0.9f, "Saving extracted PDF...")
                val finalFileName = "${baseName}_extracted.pdf"
                val saveResult = StorageHelper.saveToDownloads(context, finalFileName, "application/pdf") { out ->
                    pdfDocument.writeTo(out)
                }
                pdfDocument.close()

                if (saveResult != null) {
                    val (outUri, outPath) = saveResult
                    val size = StorageHelper.getFileSize(context, outUri)
                    val thumbPath = firstThumb?.let { StorageHelper.saveThumbnailToCache(context, it, "split_thumb") }
                    results.add(
                        ProcessedFileResult(
                            uri = outUri,
                            path = outPath,
                            fileName = finalFileName,
                            sizeBytes = size,
                            pageCount = validPages.size,
                            thumbnailPath = thumbPath
                        )
                    )
                }
            } else {
                // Split into separate individual PDFs
                for ((idx, pageNum) in validPages.withIndex()) {
                    val progress = 0.1f + (idx.toFloat() / validPages.size.toFloat()) * 0.8f
                    onProgress(progress, "Generating file for page ${pageNum + 1}...")

                    val singleDoc = PdfDocument()
                    var page: PdfRenderer.Page? = null
                    var singleThumb: Bitmap? = null

                    try {
                        page = renderer.openPage(pageNum)
                        val renderWidth = (page.width * 1.5f).toInt()
                        val renderHeight = (page.height * 1.5f).toInt()

                        val pageBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(pageBitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        singleThumb = Bitmap.createScaledBitmap(pageBitmap, 240, (240f * renderHeight / renderWidth).toInt(), true)

                        val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, 1).create()
                        val docPage = singleDoc.startPage(pageInfo)
                        docPage.canvas.drawBitmap(pageBitmap, null, Rect(0, 0, page.width, page.height), Paint(Paint.FILTER_BITMAP_FLAG))
                        singleDoc.finishPage(docPage)
                        pageBitmap.recycle()
                    } finally {
                        try { page?.close() } catch (ignored: Exception) {}
                    }

                    val pageFileName = "${baseName}_page_${pageNum + 1}.pdf"
                    val saveResult = StorageHelper.saveToDownloads(context, pageFileName, "application/pdf") { out ->
                        singleDoc.writeTo(out)
                    }
                    singleDoc.close()

                    if (saveResult != null) {
                        val (outUri, outPath) = saveResult
                        val size = StorageHelper.getFileSize(context, outUri)
                        val thumbPath = singleThumb?.let { StorageHelper.saveThumbnailToCache(context, it, "split_page_${pageNum + 1}") }
                        results.add(
                            ProcessedFileResult(
                                uri = outUri,
                                path = outPath,
                                fileName = pageFileName,
                                sizeBytes = size,
                                pageCount = 1,
                                thumbnailPath = thumbPath
                            )
                        )
                    }
                }
            }

            onProgress(1.0f, "Split complete!")
            Result.success(results)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    suspend fun imagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputFileName: String,
        fitMode: ImageFitMode = ImageFitMode.FIT_A4_PORTRAIT,
        onProgress: (progress: Float, status: String) -> Unit
    ): Result<ProcessedFileResult> = withContext(Dispatchers.IO) {
        try {
            if (imageUris.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No images selected"))
            }

            val pdfDocument = PdfDocument()
            var firstThumb: Bitmap? = null

            // A4 page dimensions in PostScript points (72 points per inch)
            val (standardPageWidth, standardPageHeight) = when (fitMode) {
                ImageFitMode.FIT_A4_PORTRAIT -> Pair(595, 842)
                ImageFitMode.FIT_A4_LANDSCAPE -> Pair(842, 595)
                ImageFitMode.ORIGINAL_SIZE -> Pair(0, 0)
            }

            for ((idx, uri) in imageUris.withIndex()) {
                val progress = 0.1f + (idx.toFloat() / imageUris.size.toFloat()) * 0.75f
                val name = StorageHelper.getFileName(context, uri)
                onProgress(progress, "Adding image ${idx + 1}/${imageUris.size}: $name")

                val bitmap = ImageEngine.decodeSampledBitmapFromUri(context, uri, 1800, 1800)
                    ?: continue

                if (firstThumb == null) {
                    firstThumb = Bitmap.createScaledBitmap(
                        bitmap,
                        240,
                        (240f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1),
                        true
                    )
                }

                val (pageW, pageH) = if (fitMode == ImageFitMode.ORIGINAL_SIZE) {
                    Pair(bitmap.width, bitmap.height)
                } else {
                    Pair(standardPageWidth, standardPageHeight)
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                val docPage = pdfDocument.startPage(pageInfo)
                val canvas = docPage.canvas

                // Fill background white
                canvas.drawColor(Color.WHITE)

                // Calculate aspect ratio fit centered
                val scale = min(pageW.toFloat() / bitmap.width.toFloat(), pageH.toFloat() / bitmap.height.toFloat())
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val left = (pageW - drawW) / 2f
                val top = (pageH - drawH) / 2f

                val destRect = RectF(left, top, left + drawW, top + drawH)
                canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                pdfDocument.finishPage(docPage)

                bitmap.recycle()
            }

            onProgress(0.9f, "Saving PDF to Downloads...")
            val finalName = if (outputFileName.endsWith(".pdf", ignoreCase = true)) outputFileName else "$outputFileName.pdf"

            val saveResult = StorageHelper.saveToDownloads(context, finalName, "application/pdf") { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            if (saveResult == null) {
                return@withContext Result.failure(Exception("Failed to save image-to-PDF document"))
            }

            val (outUri, outPath) = saveResult
            val size = StorageHelper.getFileSize(context, outUri)
            val thumbPath = firstThumb?.let { StorageHelper.saveThumbnailToCache(context, it, "img2pdf_thumb") }

            onProgress(1.0f, "PDF successfully created!")
            Result.success(
                ProcessedFileResult(
                    uri = outUri,
                    path = outPath,
                    fileName = finalName,
                    sizeBytes = size,
                    pageCount = imageUris.size,
                    thumbnailPath = thumbPath
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
