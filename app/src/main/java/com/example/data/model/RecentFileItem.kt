package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_files")
data class RecentFileItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val uriString: String,
    val filePath: String,
    val fileType: String, // "PDF" or "IMAGE"
    val toolType: String, // "MERGE_PDF", "SPLIT_PDF", "IMAGE_TO_PDF", "CONVERT_IMAGE"
    val fileSizeBytes: Long,
    val details: String, // e.g. "4 pages • 1.2 MB" or "PNG -> WEBP • 450 KB"
    val thumbnailPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
