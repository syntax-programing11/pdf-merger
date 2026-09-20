package com.example.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object MergePdf : Screen("merge_pdf")
    object SplitPdf : Screen("split_pdf")
    object ImageToPdf : Screen("image_to_pdf")
    object ConvertImage : Screen("convert_image")
}
