package com.example

import android.app.Application
import android.util.Log
import com.example.ads.AdManager
import com.example.data.db.AppDatabase
import com.example.data.preferences.ThemePreferences
import com.example.data.repository.RecentFilesRepository
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ToolkitApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val database by lazy { AppDatabase.getDatabase(this) }
    val recentFilesRepository by lazy { RecentFilesRepository(database.recentFileDao()) }
    val themePreferences by lazy { ThemePreferences(this) }

    override fun onCreate() {
        super.onCreate()
        try {
            MobileAds.initialize(this) { status ->
                Log.d("ToolkitApp", "AdMob initialized: $status")
            }
            AdManager.loadInterstitial(this)
        } catch (e: Exception) {
            Log.w("ToolkitApp", "MobileAds initialization skipped or failed: ${e.message}")
        }
    }
}
