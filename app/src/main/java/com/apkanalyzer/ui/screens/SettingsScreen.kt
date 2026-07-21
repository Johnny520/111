package com.apkanalyzer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkanalyzer.ai.CustomProviderConfig
import com.apkanalyzer.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val aiProviders by viewModel.aiProviders.collectAsState()
    val currentProvider by viewModel.aiCurrentProvider.collectAsState()
    val lastError by viewModel.aiLastError.collectAsState()
    val customProvidersList by viewModel.customProviders.collectAsState()
    var selectedIndex by remember { mutableStateOf(viewModel.selectedAiProviderIndex) }

    // Model selection
    val selectedModel by viewModel.aiSelectedModel.collectAsState()
    var modelInput by remember(selectedModel) { mutableStateOf(selectedModel) }
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val isFetchingModels = viewModel.isFetchingModels

    // 首次加载或切换接口时获取模型列表
    LaunchedEffect(selectedIndex) {
        viewModel.fetchModelsForProvider(selectedIndex)
    }

    // 模型列表更新后，如果当前模型不在列表中，自动选第一个
    LaunchedEffect(fetchedModels, modelInput) {
        if (fetchedModels.isNotEmpty() && fetchedModels.none { it.first == modelInput }) {
            modelInput = fetchedModels[0].first
            viewModel.setAiModel(fetchedModels[0].first)
        }
    }

    // 同步 ViewModel 的选中状态
    LaunchedEffect(viewModel.selectedAiProviderIndex) {
        selectedIndex = viewModel.selectedAiProviderIndex
    }

    // 自定义接口编辑对话框状态
    var showEditDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<CustomProviderConfig?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    // 删除确认对话框
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingConfigId by remember { mutableStateOf<String?>(null) }
    var deletingConfigName by remember { mutableStateOf("") }

    var expandedModel by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // 自定义接口在 allProviders 中的起始索引（内置 3 个之后）
    val builtInCount = 3

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==================== AI 接口选择 ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AI 接口选择", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (aiProviders.isEmpty()) {
                        Text("正在加载...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val allProviders = aiProviders.toList()
                        allProviders.forEachIndexed { index, provider ->
                            val isSelected = selectedIndex == index
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        // 选中状态高亮边框
                                        if (isSelected) Modifier.border(
                                            border = BorderStroke(
                                                1.5.dp,
                                                MaterialTheme.colorScheme.primary
                                            ),
                                            shape = MaterialTheme.shapes.small
                                        ) else Modifier
                                    )
                                    .clickable {
                                        selectedIndex = index
                                        viewModel.setAiProvider(index)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedIndex = index
                                        viewModel.setAiProvider(index)
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        provider.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // 自动切换选项
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedIndex == -1,
                                onClick = {
                                    selectedIndex = -1
                                    viewModel.setAiProvider(-1)
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动切换（推荐）", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "自动选择可用接口，故障时自动切换",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    if (currentProvider.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "当前: $currentProvider",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    lastError?.let {
                        Text(
                            "错误: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2
                        )
                    }
                }
            }

            // ==================== 主题切换 ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("深色模式", style = MaterialTheme.typography.titleMedium)
                            Text("切换白天/夜间主题", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = viewModel.isDarkTheme,
                            onCheckedChange = { viewModel.toggleDarkTheme() }
                        )
                    }
                }
            }

            // ==================== AI 模型选择 ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AI 模型选择", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            viewModel.setAiModel(modelInput)
                            Toast.makeText(ctx, "模型已应用: $modelInput", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用模型")
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = expandedModel,
                        onExpandedChange = { expandedModel = !expandedModel }
                    ) {
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            label = { Text("模型名称") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                        expanded = expandedModel,
                        onDismissRequest = { expandedModel = false }
                    ) {
                        if (isFetchingModels) {
                            DropdownMenuItem(
                                text = { Text("正在获取模型列表...", style = MaterialTheme.typography.bodySmall) },
                                onClick = {}
                            )
                        } else if (fetchedModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("无可用模型", style = MaterialTheme.typography.bodySmall) },
                                onClick = {}
                            )
                        } else {
                            fetchedModels.forEach { (model, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        modelInput = model
                                        viewModel.setAiModel(model)
                                        expandedModel = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ==================== 自定义 AI 接口列表 ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("自定义 AI 接口", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "支持 Groq、SiliconFlow、OpenRouter、Cloudflare、Ollama 等所有 OpenAI 兼容接口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (customProvidersList.isEmpty()) {
                        Text(
                            "暂无自定义接口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        customProvidersList.forEach { config ->
                            val configIndex = builtInCount + customProvidersList.indexOf(config)
                            val isSelected = selectedIndex == configIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isSelected) Modifier.border(
                                            border = BorderStroke(
                                                1.5.dp,
                                                MaterialTheme.colorScheme.primary
                                            ),
                                            shape = MaterialTheme.shapes.small
                                        ) else Modifier.border(
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant
                                            ),
                                            shape = MaterialTheme.shapes.small
                                        )
                                    )
                                    .clickable {
                                        // 点击整行也可以选中该接口
                                        selectedIndex = configIndex
                                        viewModel.setAiProvider(configIndex)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        config.name.ifBlank { "未命名" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        config.baseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "模型: ${config.modelName.ifBlank { "未配置" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // 编辑按钮
                                IconButton(onClick = {
                                    editingConfig = config
                                    isEditing = true
                                    showEditDialog = true
                                }) {
                                    Text("编辑", style = MaterialTheme.typography.labelMedium)
                                }
                                // 删除按钮
                                IconButton(onClick = {
                                    deletingConfigId = config.id
                                    deletingConfigName = config.name.ifBlank { "未命名" }
                                    showDeleteDialog = true
                                }) {
                                    Text(
                                        "删除",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // 添加新接口按钮
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            editingConfig = null
                            isEditing = false
                            showEditDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("添加新接口")
                    }
                }
            }

            // ==================== 检查更新 ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val downloadProgress by viewModel.downloadProgress.collectAsState()
                    val downloadedBytes by viewModel.downloadedBytes.collectAsState()
                    val totalBytes by viewModel.totalBytes.collectAsState()
                    val downloadState by viewModel.downloadState.collectAsState()
                    val downloadError by viewModel.downloadError.collectAsState()

                    // 检查按钮 / 检查中
                    Button(
                        onClick = { viewModel.checkForUpdate() },
                        enabled = !viewModel.isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (viewModel.isCheckingUpdate) "检查中..." else "检查更新")
                    }

                    // 更新信息
                    viewModel.updateInfo?.let { info ->
                        Spacer(modifier = Modifier.height(12.dp))
                        val hasUpdate = info.versionCode > viewModel.appUpdater.getCurrentVersionCode()
                        val currentVer = viewModel.appUpdater.getCurrentVersionName()

                        // 版本对比行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("当前版本: v$currentVer", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("最新版本: v${info.versionName}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        // 文件大小
                        if (info.fileSize > 0) {
                            Text(
                                "更新包大小: ${com.apkanalyzer.util.AppUpdater.formatFileSize(info.fileSize)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (hasUpdate) {
                            if (info.updateLog.isNotBlank()) {
                                Text(
                                    info.updateLog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                    lineHeight = 18.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            when (downloadState) {
                                com.apkanalyzer.util.AppUpdater.DownloadState.IDLE -> {
                                    Button(
                                        onClick = { viewModel.downloadUpdate() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("下载更新")
                                    }
                                }
                                com.apkanalyzer.util.AppUpdater.DownloadState.DOWNLOADING -> {
                                    // 进度条
                                    LinearProgressIndicator(
                                        progress = { downloadProgress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${downloadProgress}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${com.apkanalyzer.util.AppUpdater.formatFileSize(downloadedBytes)} / ${com.apkanalyzer.util.AppUpdater.formatFileSize(totalBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.cancelDownload() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("取消下载")
                                    }
                                }
                                com.apkanalyzer.util.AppUpdater.DownloadState.COMPLETED -> {
                                    Text("下载完成，正在安装...", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                                        Text("关闭")
                                    }
                                }
                                com.apkanalyzer.util.AppUpdater.DownloadState.CANCELLED -> {
                                    Text("下载已取消", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(onClick = { viewModel.downloadUpdate() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("重新下载")
                                    }
                                }
                                com.apkanalyzer.util.AppUpdater.DownloadState.FAILED -> {
                                    Text("下载失败: ${downloadError ?: "未知错误"}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(onClick = { viewModel.downloadUpdate() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("重试下载")
                                    }
                                }
                            }
                        } else {
                            Text(
                                "当前已是最新版本",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // ==================== 自定义接口编辑/添加对话框 ====================
    if (showEditDialog) {
        CustomProviderEditDialog(
            config = editingConfig,
            isEditing = isEditing,
            onDismiss = { showEditDialog = false },
            onSave = { config ->
                if (isEditing && editingConfig != null) {
                    viewModel.updateCustomProvider(editingConfig!!.id, config)
                } else {
                    viewModel.addCustomProvider(config)
                }
                showEditDialog = false
                editingConfig = null
            },
            onDelete = {
                if (editingConfig != null) {
                    deletingConfigId = editingConfig!!.id
                    deletingConfigName = editingConfig!!.name.ifBlank { "未命名" }
                    showDeleteDialog = true
                    showEditDialog = false
                }
            }
        )
    }

    // ==================== 删除确认对话框 ====================
    if (showDeleteDialog && deletingConfigId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除自定义接口「$deletingConfigName」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeCustomProvider(deletingConfigId!!)
                        showDeleteDialog = false
                        deletingConfigId = null
                        // 如果删除的是当前选中的，重置为自动
                        if (selectedIndex >= builtInCount + customProvidersList.size) {
                            selectedIndex = -1
                            viewModel.setAiProvider(-1)
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    deletingConfigId = null
                }) {
                    Text("取消")
                }
            }
        )
    }
    }
}

/**
 * 自定义 Provider 编辑/添加对话框
 */
@Composable
fun CustomProviderEditDialog(
    config: CustomProviderConfig?,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomProviderConfig) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "") }
    var baseUrl by remember { mutableStateOf(config?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var modelName by remember { mutableStateOf(config?.modelName ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEditing) "编辑自定义接口" else "添加自定义接口")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("接口名称") },
                    placeholder = { Text("如: DeepSeek") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API 基础地址") },
                    placeholder = { Text("https://api.deepseek.com/v1/chat/completions") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名称") },
                    placeholder = { Text("deepseek-chat") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Row {
                // 编辑模式下显示删除按钮
                if (isEditing) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(
                    onClick = {
                        val newConfig = if (isEditing && config != null) {
                            config.copy(
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                modelName = modelName.trim()
                            )
                        } else {
                            CustomProviderConfig(
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                modelName = modelName.trim()
                            )
                        }
                        onSave(newConfig)
                    },
                    enabled = name.trim().isNotEmpty() && baseUrl.trim().isNotEmpty() && modelName.trim().isNotEmpty()
                ) {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
