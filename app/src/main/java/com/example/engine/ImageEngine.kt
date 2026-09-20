package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class TargetImageFormat(val extension: String, val mimeType: String, val label: String) {
    JPG("jpg", "image/jpeg", "JPEG (.jpg)"),
    PNG("png", "image/png", "PNG (.png)"),
    WEBP("webp", "image/webp", "WebP (.webp)"),
    BMP("bmp", "image/bmp", "BMP (.bmp)")
}

data class ConvertedImageItem(
    val originalUri: Uri,
    val originalFileName: String,
    val originalSizeBytes: Long,
    val convertedUri: Uri?,
    val convertedFileName: String,
    val convertedSizeBytes: Long,
    val targetFormat: TargetImageFormat,
    val width: Int,
    val height: Int,
    val thumbnailPath: String?,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

object ImageEngine {

    fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = 2048,
        reqHeight: Int = 2048
    ): Bitmap? {
        var input: InputStream? = null
        try {
            // First decode bounds
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            input = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(input, null, options)
            input?.close()

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            input = context.contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(input, null, options)
            input?.close()

            if (bitmap != null) {
                // Correct orientation if available
                try {
                    context.contentResolver.openInputStream(uri)?.use { exifStream ->
                        val exif = ExifInterface(exifStream)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        bitmap = rotateBitmapForExif(bitmap!!, orientation)
                    }
                } catch (ignored: Exception) {}
            }

            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { input?.close() } catch (ignored: Exception) {}
        }
    }

    private fun rotateBitmapForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    suspend fun convertSingleImage(
        context: Context,
        inputUri: Uri,
        targetFormat: TargetImageFormat,
        quality: Int = 90
    ): ConvertedImageItem = withContext(Dispatchers.IO) {
        val originalName = StorageHelper.getFileName(context, inputUri)
        val originalSize = StorageHelper.getFileSize(context, inputUri)

        val bitmap = decodeSampledBitmapFromUri(context, inputUri, 3000, 3000)
            ?: return@withContext ConvertedImageItem(
                originalUri = inputUri,
                originalFileName = originalName,
                originalSizeBytes = originalSize,
                convertedUri = null,
                convertedFileName = "",
                convertedSizeBytes = 0,
                targetFormat = targetFormat,
                width = 0,
                height = 0,
                thumbnailPath = null,
                isSuccess = false,
                errorMessage = "Failed to decode image"
            )

        val nameWithoutExt = originalName.substringBeforeLast('.')
        val convertedFileName = "${nameWithoutExt}_converted.${targetFormat.extension}"

        val saveResult = StorageHelper.saveToDownloads(
            context = context,
            fileName = convertedFileName,
            mimeType = targetFormat.mimeType
        ) { outputStream ->
            when (targetFormat) {
                TargetImageFormat.JPG -> {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                }
                TargetImageFormat.PNG -> {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                TargetImageFormat.WEBP -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, outputStream)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, outputStream)
                    }
                }
                TargetImageFormat.BMP -> {
                    writeBmp(bitmap, outputStream)
                }
            }
        }

        if (saveResult == null) {
            bitmap.recycle()
            return@withContext ConvertedImageItem(
                originalUri = inputUri,
                originalFileName = originalName,
                originalSizeBytes = originalSize,
                convertedUri = null,
                convertedFileName = convertedFileName,
                convertedSizeBytes = 0,
                targetFormat = targetFormat,
                width = bitmap.width,
                height = bitmap.height,
                thumbnailPath = null,
                isSuccess = false,
                errorMessage = "Failed to save converted file"
            )
        }

        val (outUri, _) = saveResult
        val newSize = StorageHelper.getFileSize(context, outUri)

        val thumb = Bitmap.createScaledBitmap(
            bitmap,
            180,
            (180f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1),
            true
        )
        val thumbPath = StorageHelper.saveThumbnailToCache(context, thumb, "convert_thumb")

        bitmap.recycle()

        ConvertedImageItem(
            originalUri = inputUri,
            originalFileName = originalName,
            originalSizeBytes = originalSize,
            convertedUri = outUri,
            convertedFileName = convertedFileName,
            convertedSizeBytes = newSize,
            targetFormat = targetFormat,
            width = bitmap.width,
            height = bitmap.height,
            thumbnailPath = thumbPath,
            isSuccess = true
        )
    }

    /**
     * Writes an uncompressed 24-bit RGB BMP stream.
     */
    private fun writeBmp(bitmap: Bitmap, output: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height

        // BMP rows must be multiple of 4 bytes
        val rowPadding = (4 - (width * 3) % 4) % 4
        val imageSize = (width * 3 + rowPadding) * height
        val fileSize = 54 + imageSize

        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)

        // Bitmap File Header (14 bytes)
        header.put('B'.code.toByte())
        header.put('M'.code.toByte())
        header.putInt(fileSize)
        header.putShort(0.toShort()) // reserved
        header.putShort(0.toShort()) // reserved
        header.putInt(54) // offset to pixel data

        // Bitmap Info Header (40 bytes)
        header.putInt(40) // header size
        header.putInt(width)
        header.putInt(height) // positive = bottom-up
        header.putShort(1.toShort()) // planes
        header.putShort(24.toShort()) // bits per pixel (RGB)
        header.putInt(0) // compression (BI_RGB)
        header.putInt(imageSize)
        header.putInt(2835) // pixels/meter horizontal (~72 DPI)
        header.putInt(2835) // pixels/meter vertical
        header.putInt(0) // colors in palette
        header.putInt(0) // important colors

        output.write(header.array())

        val pixels = IntArray(width)
        val rowBuffer = ByteArray(width * 3 + rowPadding)

        // BMP is bottom-to-top
        for (y in height - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var byteIdx = 0
            for (x in 0 until width) {
                val color = pixels[x]
                // BMP stores in BGR order
                rowBuffer[byteIdx++] = (color and 0xFF).toByte()          // Blue
                rowBuffer[byteIdx++] = ((color shr 8) and 0xFF).toByte()  // Green
                rowBuffer[byteIdx++] = ((color shr 16) and 0xFF).toByte() // Red
            }
            // Pad remainder with zeros
            for (p in 0 until rowPadding) {
                rowBuffer[byteIdx++] = 0
            }
            output.write(rowBuffer, 0, byteIdx)
        }
        output.flush()
    }
}
