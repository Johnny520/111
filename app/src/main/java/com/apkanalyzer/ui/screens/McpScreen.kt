package com.apkanalyzer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkanalyzer.model.McpConnectionState
import com.apkanalyzer.model.McpTool
import com.apkanalyzer.ui.MainViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.mcpState.collectAsState()
    val tools by viewModel.mcpTools.collectAsState()
    val logs by viewModel.mcpLogs.collectAsState()
    val serverInfo by viewModel.mcpServerInfo.collectAsState()
    var serverUrl by remember { mutableStateOf(viewModel.mcpServerUrl) }
    var showToolDialog by remember { mutableStateOf(false) }
    var selectedTool by remember { mutableStateOf<McpTool?>(null) }
    val callResult = viewModel.mcpCallResult
    val isCalling = viewModel.isCallingTool
    var showLogs by remember { mutableStateOf(false) }
    var showHeadersDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务器") },
                actions = {
                    // 日志切换按钮
                    IconButton(onClick = { showLogs = !showLogs }) {
                        Icon(
                            if (showLogs) Icons.Default.Terminal else Icons.Default.Description,
                            contentDescription = if (showLogs) "隐藏日志" else "显示日志"
                        )
                    }
                    // 重连按钮（仅在断开/错误状态时显示）
                    if (connectionState is McpConnectionState.Error ||
                        connectionState is McpConnectionState.Disconnected ||
                        connectionState is McpConnectionState.Reconnecting
                    ) {
                        IconButton(onClick = { viewModel.reconnectMcp() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重连")
                        }
                    }
                    // 断开按钮
                    if (connectionState is McpConnectionState.Connected) {
                        IconButton(onClick = { viewModel.disconnectMcp() }) {
                            Icon(Icons.Default.Close, contentDescription = "断开")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Server URL input（未连接时隐藏，已在居中区域显示）
            if (connectionState !is McpConnectionState.Disconnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("MCP 服务器地址") },
                    modifier = Modifier.weight(1f),
                    enabled = connectionState !is McpConnectionState.Connected &&
                              connectionState !is McpConnectionState.Connecting &&
                              connectionState !is McpConnectionState.Reconnecting,
                    singleLine = true
                )
                when (connectionState) {
                    is McpConnectionState.Connected -> {
                        FilledTonalButton(onClick = { viewModel.disconnectMcp() }) {
                            Text("断开")
                        }
                    }
                    is McpConnectionState.Connecting -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("连接中")
                        }
                    }
                    is McpConnectionState.Reconnecting -> {
                        val state = connectionState as McpConnectionState.Reconnecting
                        OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重连 ${state.attempt}/${state.maxAttempts}")
                        }
                    }
                    else -> {
                        Button(
                            onClick = {
                                viewModel.updateMcpServerUrl(serverUrl)
                                viewModel.connectMcp(serverUrl)
                            }
                        ) {
                            Text("连接")
                        }
                    }
                }
            }
            }

            // Connection status（未连接时隐藏，让居中提示更突出）
            if (connectionState !is McpConnectionState.Disconnected) {
                StatusRow(connectionState, serverInfo)
            }

            // 主体内容区域
            if (showLogs) {
                // 日志视图
                LogsSection(logs, viewModel)
            } else if (tools.isNotEmpty()) {
                // 工具列表
                val enabledCount = tools.count { it.enabled }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "可用工具 ($enabledCount/${tools.size} 已启用)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(onClick = { viewModel.toggleAllTools(!tools.all { it.enabled }) }) {
                        Text(if (tools.all { it.enabled }) "全部禁用" else "全部启用")
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tools, key = { it.name }) { tool ->
                        ToolCard(
                            tool = tool,
                            onCall = {
                                selectedTool = tool
                                showToolDialog = true
                            },
                            onToggle = { viewModel.toggleToolEnabled(tool.name) }
                        )
                    }
                }
            } else if (connectionState is McpConnectionState.Connected) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("暂无可用工具", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                // 未连接状态 — 全部居中
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // URL 输入也放入居中区域
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("MCP 服务器地址") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    viewModel.updateMcpServerUrl(serverUrl)
                                    viewModel.connectMcp(serverUrl)
                                }
                            ) {
                                Text("连接")
                            }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "未连接 MCP 服务器",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "输入 MCP 服务器地址并点击连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "MCP 是一种让本应用连接外部工具的接口。默认地址指向本机服务，需要你自己在电脑或手机上启动对应的 MCP 服务后才能连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Call result
            if (callResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("调用结果", style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { viewModel.clearMcpResult() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                        Text(
                            callResult,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            maxLines = 8,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    if (showToolDialog && selectedTool != null) {
        ToolCallDialog(
            tool = selectedTool!!,
            onDismiss = { showToolDialog = false },
            onCall = { args ->
                viewModel.callMcpTool(selectedTool!!.name, args)
                showToolDialog = false
            }
        )
    }
}

@Composable
private fun StatusRow(state: McpConnectionState, serverInfo: String?) {
    val (text, color, icon) = when (state) {
        is McpConnectionState.Connected -> Triple(
            if (serverInfo != null) "已连接 - $serverInfo" else "已连接",
            Color(0xFF4CAF50),
            Icons.Default.CheckCircle
        )
        is McpConnectionState.Connecting -> Triple(
            "连接中...",
            MaterialTheme.colorScheme.tertiary,
            Icons.Default.HourglassEmpty
        )
        is McpConnectionState.Reconnecting -> Triple(
            "重连中 (${state.attempt}/${state.maxAttempts})...",
            Color(0xFFFF9800),
            Icons.Default.Sync
        )
        is McpConnectionState.Error -> Triple(
            "错误: ${state.message}" + (state.detail?.let { "\n$it" } ?: ""),
            MaterialTheme.colorScheme.error,
            Icons.Default.Error
        )
        is McpConnectionState.Disconnected -> Triple(
            "未连接",
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            Icons.Default.CloudOff
        )
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun LogsSection(logs: List<String>, viewModel: MainViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("连接日志 (${logs.size})", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { viewModel.clearMcpLogs() }) {
            Text("清空")
        }
    }
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            logs.takeLast(100).forEach { log ->
                Text(
                    log,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    ),
                    color = Color(0xFFCCCCCC)
                )
            }
        }
    }
}

@Composable
fun ToolCard(tool: McpTool, onCall: () -> Unit, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (tool.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 启用/禁用开关
            Switch(
                checked = tool.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(end = 8.dp)
            )

            // 工具信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (tool.enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (!tool.description.isNullOrEmpty()) {
                    Text(
                        tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // 调用按钮
            IconButton(onClick = onCall, enabled = tool.enabled) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "调用",
                    tint = if (tool.enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
fun ToolCallDialog(tool: McpTool, onDismiss: () -> Unit, onCall: (Map<String, Any>) -> Unit) {
    val gson = Gson()
    var argsJson by remember { mutableStateOf("{}") }
    var parseError by remember { mutableStateOf<String?>(null) }

    // 根据 inputSchema 生成默认参数模板
    LaunchedEffect(tool) {
        val schema = tool.inputSchema
        if (schema != null && schema.properties != null && schema.properties.isJsonObject) {
            val defaults = mutableMapOf<String, Any>()
            schema.properties.asJsonObject.entrySet().forEach { (key, propObj) ->
                val prop = propObj.asJsonObject
                val type = prop.get("type")?.asString ?: "string"
                val isRequired = schema.required?.contains(key) == true
                if (!isRequired) return@forEach
                defaults[key] = when (type) {
                    "number", "integer" -> 0
                    "boolean" -> false
                    "array" -> emptyList<Any>()
                    "object" -> emptyMap<String, Any>()
                    else -> ""
                }
            }
            if (defaults.isNotEmpty()) {
                argsJson = gson.toJson(defaults)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调用: ${tool.name}") },
        text = {
            Column {
                if (!tool.description.isNullOrEmpty()) {
                    Text(tool.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // 参数说明
                val requiredParams = tool.inputSchema?.required ?: emptyList()
                if (requiredParams.isNotEmpty()) {
                    Text(
                        "必填参数: ${requiredParams.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text("参数 (JSON):", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = argsJson,
                    onValueChange = { argsJson = it; parseError = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    isError = parseError != null,
                    supportingText = { parseError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val jsonObj = gson.fromJson(argsJson, JsonObject::class.java)
                    val args = mutableMapOf<String, Any>()
                    jsonObj.entrySet().forEach { (key, value) ->
                        when {
                            value.isJsonPrimitive -> {
                                val primitive = value.asJsonPrimitive
                                args[key] = when {
                                    primitive.isBoolean -> primitive.asBoolean
                                    primitive.isNumber -> primitive.asNumber
                                    else -> primitive.asString
                                }
                            }
                            value.isJsonArray -> args[key] = value.asJsonArray
                            value.isJsonObject -> args[key] = value.asJsonObject
                        }
                    }
                    parseError = null
                    onCall(args)
                } catch (e: Exception) {
                    parseError = "JSON解析错误: ${e.message}"
                }
            }) {
                Text("调用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}