package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ads.AdManager
import com.example.data.preferences.ThemeMode
import com.example.ui.navigation.Screen
import com.example.ui.screens.ConvertImageScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImageToPdfScreen
import com.example.ui.screens.MergePdfScreen
import com.example.ui.screens.SplitPdfScreen
import com.example.ui.theme.ToolkitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Preload interstitial ad
        AdManager.loadInterstitial(this)

        val app = application as ToolkitApplication
        val repo = app.recentFilesRepository
        val themePrefs = app.themePreferences

        setContent {
            val currentThemeMode by themePrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val scope = rememberCoroutineScope()

            ToolkitTheme(themeMode = currentThemeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val recentFiles by repo.allRecentFiles.collectAsState(initial = emptyList())

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        enterTransition = {
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeIn(tween(300))
                        },
                        exitTransition = {
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeOut(tween(300))
                        },
                        popEnterTransition = {
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeIn(tween(300))
                        },
                        popExitTransition = {
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeOut(tween(300))
                        }
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                currentThemeMode = currentThemeMode,
                                onToggleTheme = {
                                    scope.launch {
                                        val nextMode = when (currentThemeMode) {
                                            ThemeMode.SYSTEM -> ThemeMode.DARK
                                            ThemeMode.DARK -> ThemeMode.LIGHT
                                            ThemeMode.LIGHT -> ThemeMode.SYSTEM
                                        }
                                        themePrefs.setThemeMode(nextMode)
                                    }
                                },
                                recentFiles = recentFiles,
                                onDeleteRecentFile = { id ->
                                    scope.launch { repo.deleteById(id) }
                                },
                                onNavigateToMergePdf = {
                                    navController.navigate(Screen.MergePdf.route)
                                },
                                onNavigateToSplitPdf = {
                                    navController.navigate(Screen.SplitPdf.route)
                                },
                                onNavigateToImageToPdf = {
                                    navController.navigate(Screen.ImageToPdf.route)
                                },
                                onNavigateToConvertImage = {
                                    navController.navigate(Screen.ConvertImage.route)
                                }
                            )
                        }

                        composable(Screen.MergePdf.route) {
                            MergePdfScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSaveRecentFile = { item ->
                                    scope.launch { repo.insert(item) }
                                }
                            )
                        }

                        composable(Screen.SplitPdf.route) {
                            SplitPdfScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSaveRecentFile = { item ->
                                    scope.launch { repo.insert(item) }
                                }
                            )
                        }

                        composable(Screen.ImageToPdf.route) {
                            ImageToPdfScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSaveRecentFile = { item ->
                                    scope.launch { repo.insert(item) }
                                }
                            )
                        }

                        composable(Screen.ConvertImage.route) {
                            ConvertImageScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSaveRecentFile = { item ->
                                    scope.launch { repo.insert(item) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
