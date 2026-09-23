package com.example.subless3d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun AppScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var showHelp by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {

        // LEFT COLUMN: editor + update button
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // Open shader file
            OutlinedButton(
                onClick = {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Open shader file"
                        fileFilter = FileNameExtensionFilter(
                            "Shader files (*.glsl, *.frag, *.sksl, *.txt)",
                            "glsl", "frag", "vert", "sksl", "txt"
                        )
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val text = chooser.selectedFile.readText()
                        state.shaderCode = text
                        state.appliedShader = text
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open shader")
            }

            ShaderEditor(
                code = state.shaderCode,
                onCodeChange = { state.shaderCode = it },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            Button(
                onClick = { state.appliedShader = state.shaderCode },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Update preview from editor")
            }
        }

        // RIGHT COLUMN: preview + bake controls
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShapeSelector(
                    selected = state.selectedShape,
                    onSelect = { state.selectedShape = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.Filled.Check, contentDescription = "Help")
                }
            }

            ShaderPreview(
                shaderCode = state.appliedShader,
                shape = state.selectedShape,
                showWireframe = state.showWireframe,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.showWireframe,
                    onCheckedChange = { state.showWireframe = it }
                )
                Text("Show wireframe (triangle edges)")
            }

            ResolutionDropdown(
                resolution = state.bakeResolution,
                onResolutionChange = { state.bakeResolution = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Choose output folder"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            state.saveDirectory = chooser.selectedFile.absolutePath
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Choose output folder")
                }
                Text(
                    text = state.saveDirectory?.let { shortenPath(it) } ?: "(none)",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        state.bakeStatus = "Baking..."
                        val result = bakeShader(state)
                        state.bakeStatus = result.fold(
                            onSuccess = { "Done. Files written to ${state.saveDirectory}" },
                            onFailure = { "Error: ${it.message}" }
                        )
                    }
                },
                enabled = state.saveDirectory != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Bake to texture (Export PBR Maps)")
            }

            state.bakeStatus?.let { Text(it, fontSize = 12.sp) }
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

private fun shortenPath(path: String): String =
    if (path.length <= 40) path else "…" + path.takeLast(39)

// ============================================================
//  Editor with line numbers
// ============================================================

@Composable
fun ShaderEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )

    val lineCount = code.count { it == '\n' } + 1

    Row(
        modifier = modifier
            .background(Color(0xFF1E1E1E))
            .verticalScroll(scrollState)
    ) {
        // Line numbers column
        Column(
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, end = 6.dp)
        ) {
            for (i in 1..lineCount) {
                Text(
                    text = i.toString(),
                    style = textStyle,
                    color = Color(0xFF7A7A7A)
                )
            }
        }

        // Editor
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            textStyle = textStyle.copy(color = Color(0xFFE6E6E6)),
            cursorBrush = SolidColor(Color(0xFF00FF88)),
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp, bottom = 10.dp, end = 8.dp)
        )
    }
}

// ============================================================
//  Shape selector
// ============================================================

@Composable
fun ShapeSelector(
    selected: ShapeType,
    onSelect: (ShapeType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapeType.values().forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(type.label) }
            )
        }
    }
}

// ============================================================
//  Resolution dropdown
// ============================================================

@Composable
fun ResolutionDropdown(
    resolution: Int,
    onResolutionChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Bake resolution", fontSize = 12.sp)
        OutlinedButton(onClick = { expanded = true }) {
            Text("${resolution}px")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppState.BAKE_RESOLUTIONS.forEach { res ->
                DropdownMenuItem(
                    text = { Text("${res}px") },
                    onClick = {
                        onResolutionChange(res)
                        expanded = false
                    }
                )
            }
        }
    }
}