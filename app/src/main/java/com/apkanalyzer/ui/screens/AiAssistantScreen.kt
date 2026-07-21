package com.apkanalyzer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkanalyzer.ai.AgentMessage
import com.apkanalyzer.ai.McpAgentEngine
import com.apkanalyzer.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(viewModel: MainViewModel) {
    var userInput by remember { mutableStateOf("") }
    var workDir by remember { mutableStateOf("/storage/emulated/0/MT2/mcp/") }
    val agentMessages by viewModel.agentMessages.collectAsState()
    val isProcessing by viewModel.agentProcessing.collectAsState()
    val currentStep by viewModel.agentCurrentStep.collectAsState()
    val mcpConnected by viewModel.mcpState.collectAsState()

    val listState = rememberLazyListState()

    // 自动滚动到最新消息
    LaunchedEffect(agentMessages.size) {
        if (agentMessages.isNotEmpty()) {
            listState.animateScrollToItem(agentMessages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        TopAppBar(
            title = {
                Column {
                    Text("AI 逆向助手", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isConnected = mcpConnected is com.apkanalyzer.model.McpConnectionState.Connected
                        val isReconnecting = mcpConnected is com.apkanalyzer.model.McpConnectionState.Reconnecting
                        val statusText = when (mcpConnected) {
                            is com.apkanalyzer.model.McpConnectionState.Connected -> "MCP 已连接"
                            is com.apkanalyzer.model.McpConnectionState.Reconnecting ->
                                "MCP 重连中 (${(mcpConnected as com.apkanalyzer.model.McpConnectionState.Reconnecting).attempt}/${(mcpConnected as com.apkanalyzer.model.McpConnectionState.Reconnecting).maxAttempts})"
                            is com.apkanalyzer.model.McpConnectionState.Connecting -> "MCP 连接中..."
                            is com.apkanalyzer.model.McpConnectionState.Error -> "MCP 连接错误"
                            else -> "MCP 未连接"
                        }
                        val statusColor = when {
                            isConnected -> Color(0xFF4CAF50)
                            isReconnecting -> Color(0xFFFF9800)
                            mcpConnected is com.apkanalyzer.model.McpConnectionState.Connecting -> Color(0xFF2196F3)
                            else -> MaterialTheme.colorScheme.error
                        }
                        val statusIcon = when {
                            isConnected -> Icons.Default.Wifi
                            isReconnecting -> Icons.Default.Sync
                            mcpConnected is com.apkanalyzer.model.McpConnectionState.Connecting -> Icons.Default.HourglassEmpty
                            else -> Icons.Default.WifiOff
                        }
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = statusColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = { viewModel.clearAgentMessages() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "清除对话")
                }
            }
        )

        // 消息列表
        if (agentMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "AI 逆向助手",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "用自然语言描述你的需求\nAI 将自动调用 MT管理器 的 MCP 工具",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(agentMessages) { index, message ->
                    MessageBubble(message, onRetry = {
                        viewModel.retryMessage(index, workDir)
                    })
                }

                // 底部间距
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        // 处理状态指示
        if (isProcessing && currentStep.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        currentStep,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // 工作目录和输入框
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 工作目录
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("工作目录:", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(4.dp))
                BasicTextField(
                    value = workDir,
                    onValueChange = { workDir = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 24.dp, max = 72.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = false,
                    maxLines = 3,
                    minLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 输入框
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    placeholder = { Text("分析一下工作目录下的APK文件。") },
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (isProcessing) {
                            viewModel.cancelAgentRequest()
                        } else if (userInput.isNotBlank()) {
                            val msg = userInput
                            userInput = ""
                            viewModel.sendAgentRequest(msg, workDir)
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    containerColor = if (isProcessing)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                ) {
                    if (isProcessing) {
                        Icon(Icons.Default.Close, contentDescription = "停止")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

/**
 * JSON 格式化函数：将压缩的 JSON 字符串格式化为缩进可读形式
 * 不截断内容，保留完整 JSON
 */
private fun formatJson(text: String): String {
    return try {
        val decoded = text.decodeUnicodeEscapes()
        val json = com.google.gson.JsonParser.parseString(decoded)
        com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
            .toJson(json)
    } catch (_: Exception) {
        text.decodeUnicodeEscapes()
    }
}

/**
 * Unicode 转义解码（\uXXXX → 实际字符）
 */
private fun String.decodeUnicodeEscapes(): String {
    return try {
        val sb = StringBuilder()
        var i = 0
        while (i < length) {
            if (i + 5 < length && this[i] == '\\' && this[i + 1] == 'u') {
                val hex = substring(i + 2, i + 6)
                try {
                    sb.append(hex.toInt(16).toChar())
                    i += 6
                    continue
                } catch (_: NumberFormatException) {}
            }
            if (i + 6 < length && this[i] == '\\' && this[i + 1] == '\\' && this[i + 2] == 'u') {
                val hex = substring(i + 3, i + 7)
                try {
                    sb.append(hex.toInt(16).toChar())
                    i += 7
                    continue
                } catch (_: NumberFormatException) {}
            }
            sb.append(this[i])
            i++
        }
        sb.toString()
    } catch (_: Exception) {
        this
    }
}

/**
 * 统一文本解码：Unicode 转义 + URL 编码
 */
private fun String.decodeAll(): String {
    return try {
        var result = this.decodeUnicodeEscapes()
        // 仅在包含 % 编码时才做 URL 解码
        if (result.contains("%")) {
            result = result.replace(Regex("%u([0-9a-fA-F]{4})")) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
            try {
                result = java.net.URLDecoder.decode(result, "UTF-8")
            } catch (_: Exception) {}
        }
        result
    } catch (_: Exception) {
        this
    }
}

/**
 * 格式化文本中嵌入的 JSON 块（如 "[工具 xxx 返回结果]:\n{...}"）
 */
private fun formatEmbeddedJson(text: String): String {
    // 检测 "[工具 xxx 返回结果]:\n" 或类似前缀后跟 JSON 的情况
    val jsonStart = text.indexOf('\n').let { if (it == -1) 0 else it + 1 }
    val rest = text.substring(jsonStart).trim()
    return if (isJson(rest)) {
        val prefix = text.substring(0, jsonStart)
        val formatted = formatJson(rest)
        prefix + "\n" + formatted
    } else {
        text
    }
}

/** 判断文本是否是 JSON */
private fun isJson(text: String): Boolean {
    val trimmed = text.trim()
    return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
           (trimmed.startsWith("[") && trimmed.endsWith("]"))
}

/**
 * 可展开/收起的内容显示组件
 * 默认折叠显示前 6 行，点击"展开全部"显示完整内容（不截断）
 */
@Composable
private fun ExpandableContentView(
    text: String,
    bgColor: Color,
    textColor: Color,
    isJsonContent: Boolean = false
) {
    val displayText = remember(text) {
        if (isJsonContent) formatJson(text) else text.decodeAll()
    }
    val expanded = remember { mutableStateOf(false) }
    val totalLines = remember(displayText) { displayText.count { it == '\n' } + 1 }
    val collapsedLines = 6

    // 折叠时只显示前 N 行
    val visibleText = if (expanded.value || totalLines <= collapsedLines) {
        displayText
    } else {
        displayText.lines().take(collapsedLines).joinToString("\n") + "\n..."
    }

    Column {
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                visibleText,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (isJsonContent) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = textColor,
                maxLines = if (expanded.value) Int.MAX_VALUE else collapsedLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
            )
        }
        if (totalLines > collapsedLines) {
            TextButton(
                onClick = { expanded.value = !expanded.value },
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            ) {
                Text(
                    if (expanded.value) "收起" else "展开全部 (${displayText.length} 字符, 共$totalLines 行)",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: AgentMessage, onRetry: (() -> Unit)? = null) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"

    val bgColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isSystem -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = MaterialTheme.colorScheme.onSurface

    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(message.timestamp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = if (isUser) 320.dp else 340.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bgColor,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    // 角色标签
                    val isToolCall = message.content.startsWith("[工具调用]")
                    val isToolResult = message.content.startsWith("[工具结果]")
                    val roleLabel = when {
                        message.role == "user" -> "你"
                        message.role == "assistant" -> "AI 助手"
                        isToolCall -> "正在调用工具"
                        isToolResult -> "工具执行结果"
                        else -> message.role
                    }

                    val roleColor = when {
                        message.role == "user" -> MaterialTheme.colorScheme.primary
                        message.role == "assistant" -> Color(0xFF2196F3)
                        isToolCall -> Color(0xFFFF9800)
                        isToolResult -> Color(0xFF4CAF50)
                        else -> Color.Gray
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isUser) {
                            Icon(
                                when (message.role) {
                                    "assistant" -> Icons.Default.SmartToy
                                    else -> Icons.Default.Build
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = roleColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            roleLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = roleColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // 重试按钮（非用户消息）
                        if (!isUser && onRetry != null) {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "重试",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        // 复制按钮
                        val ctx = LocalContext.current
                        var copied by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("APK Analyzer", message.content))
                                copied = true
                                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "复制",
                                modifier = Modifier.size(14.dp),
                                tint = if (copied) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 消息内容
                    if (isSystem && message.content.startsWith("[工具调用]")) {
                        // 工具调用：JSON 格式化显示
                        val rawJson = message.content.removePrefix("[工具调用] ").decodeAll()
                        if (isJson(rawJson)) {
                            ExpandableContentView(rawJson, Color(0xFF263238), Color(0xFF80CBC4), isJsonContent = true)
                        } else {
                            Surface(
                                color = Color(0xFF263238),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    rawJson,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    ),
                                    color = Color(0xFF80CBC4),
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    } else if (isSystem && message.content.startsWith("[工具结果]")) {
                        // 工具结果：JSON 格式化显示，可展开
                        val resultText = message.content.removePrefix("[工具结果] ").decodeAll()
                        if (isJson(resultText)) {
                            ExpandableContentView(resultText, Color(0xFF1B3A1B), Color(0xFFA5D6A7), isJsonContent = true)
                        } else {
                            ExpandableContentView(resultText, Color(0xFF1B3A1B), Color(0xFFA5D6A7))
                        }
                    } else if (isSystem && message.content.startsWith("[跟进结果]")) {
                        // 跟进结果：同工具结果处理
                        val followText = message.content.removePrefix("[跟进结果] ").decodeAll()
                        if (isJson(followText)) {
                            ExpandableContentView(followText, Color(0xFF1B3A1B), Color(0xFFA5D6A7), isJsonContent = true)
                        } else {
                            ExpandableContentView(followText, Color(0xFF1B3A1B), Color(0xFFA5D6A7))
                        }
                    } else {
                        // AI 助手回复 / 其他系统消息（自动跟进、工具名纠正等）：统一解码 + 格式化
                        val content = message.content.decodeAll()
                        val formatted = formatEmbeddedJson(content)
                        val trimmed = formatted.trim()
                        if (isJson(trimmed)) {
                            ExpandableContentView(trimmed, Color(0xFF1A237E), textColor, isJsonContent = true)
                        } else {
                            // 长文本也用可展开组件
                            ExpandableContentView(
                                formatted,
                                if (message.isError) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                                if (message.isError) MaterialTheme.colorScheme.error else textColor
                            )
                        }
                    }
                }
            }
        }
    }
}
