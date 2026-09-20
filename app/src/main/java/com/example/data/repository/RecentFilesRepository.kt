package com.example.data.repository

import com.example.data.db.RecentFileDao
import com.example.data.model.RecentFileItem
import kotlinx.coroutines.flow.Flow

class RecentFilesRepository(private val recentFileDao: RecentFileDao) {
    val allRecentFiles: Flow<List<RecentFileItem>> = recentFileDao.getAllRecentFiles()

    suspend fun insert(item: RecentFileItem): Long {
        return recentFileDao.insertRecentFile(item)
    }

    suspend fun deleteById(id: Long) {
        recentFileDao.deleteById(id)
    }

    suspend fun clearAll() {
        recentFileDao.clearAll()
    }
}
