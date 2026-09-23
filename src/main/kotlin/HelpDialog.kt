package com.example.subless3d

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Shows the contents of readme.md from the working directory.
 *
 * Lookup order:
 *   1. <working dir>/readme.md
 *   2. classpath resource /readme.md
 *
 * If neither is found, a friendly placeholder is shown.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val readme: String? = remember {
        val local = File("readme.md")
        if (local.isFile) local.readText()
        else Thread.currentThread().contextClassLoader
            ?.getResource("readme.md")
            ?.readText()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = readme
                        ?: ("readme.md not found.\n\nPlace a readme.md file next to " +
                                "the application or in src/jvmMain/resources/."),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    ),
                    modifier = Modifier.padding(4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}