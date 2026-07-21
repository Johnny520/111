package com.apkanalyzer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.apkanalyzer.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollinationsScreen(viewModel: MainViewModel) {
    val imageUrl = viewModel.imageUrl
    val isGenerating = viewModel.isGenerating
    val error = viewModel.generationError
    val context = LocalContext.current

    var prompt by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("1024") }
    var height by remember { mutableStateOf("1024") }
    var seed by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(viewModel.selectedImageModel) }
    val models = remember { viewModel.pollinationsClient.getAvailableImageModels() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pollinations.ai") },
                actions = {
                    if (imageUrl != null) {
                        IconButton(onClick = { viewModel.clearImage() }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("提示词") },
                placeholder = { Text("输入图像描述提示词...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter { c -> c.isDigit() } },
                    label = { Text("宽度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() } },
                    label = { Text("高度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it.filter { c -> c.isDigit() } },
                    label = { Text("种子 (可选)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    model = m
                                    viewModel.updateSelectedImageModel(m)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    viewModel.generateImage(
                        prompt = prompt,
                        width = (width.toIntOrNull() ?: 1024).coerceIn(256, 2048),
                        height = (height.toIntOrNull() ?: 1024).coerceIn(256, 2048),
                        seed = seed.toIntOrNull()
                    )
                },
                enabled = prompt.isNotBlank() && !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("生成图像")
            }

            if (error != null) {
                Text(
                    "图像生成失败，请稍后重试，或换一个提示词再试。",
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            imageUrl?.let { url ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Image(
                            painter = rememberAsyncImagePainter(url),
                            contentDescription = "生成的图像",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio((width.toFloatOrNull() ?: 1024f) / (height.toFloatOrNull() ?: 1024f)),
                            contentScale = ContentScale.Fit
                        )
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                // Open in browser
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            }) {
                                Text("在浏览器中打开")
                            }
                        }
                    }
                }
            }
        }
    }
}
