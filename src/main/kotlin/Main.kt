package com.example.subless3d

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.loadImageBitmap
import java.io.File
import java.io.InputStream

fun main() = application {
    val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)
    val icon = BitmapPainter(
        File("src/main/resources/app_icon.png").inputStream().buffered().use {
            loadImageBitmap(it)
        }
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "Subless3D",
        icon = icon,
        state = windowState
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val state = remember { AppState() }
                AppScreen(state)
            }
        }
    }
}