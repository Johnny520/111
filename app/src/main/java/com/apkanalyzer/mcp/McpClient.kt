package com.apkanalyzer.mcp

import com.apkanalyzer.model.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 客户端 - 参考 RikkaHub 的 McpSessionRegistry 架构重写
 *
 * 核心改进：
 * 1. 指数退避自动重连（Mutex 保证唯一重连任务）
 * 2. Streamable HTTP 会话管理（mcp-session-id）
 * 3. SSE 事件流正确处理（区分 endpoint/message 事件）
 * 4. 工具输出截断保护
 * 5. 生命周期管理（取消安全）
 * 6. 自定义 HTTP 头支持
 */
class McpClient {

    private val gson = Gson()
    private val client: OkHttpClient
    private var currentEventSource: EventSource? = null
    private val messageId = AtomicInteger(1)
    private val pendingResponses = mutableMapOf<Int, CompletableDeferred<McpJsonRpcResponse?>>()

    // 使用 SupervisorJob 确保 cancel 不会影响整个 scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 重连相关（参考 RikkaHub McpSessionRegistry）
    private val reconnectMutex = Mutex()
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    // 会话管理（Streamable HTTP）
    private var sessionId: String? = null

    // ======================== 公开 StateFlow ========================

    private val _connectionState = MutableStateFlow<McpConnectionState>(McpConnectionState.Disconnected)
    val connectionState: StateFlow<McpConnectionState> = _connectionState

    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val tools: StateFlow<List<McpTool>> = _tools

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /** 服务器信息（初始化后填充） */
    private val _serverInfo = MutableStateFlow<String?>(null)
    val serverInfo: StateFlow<String?> = _serverInfo

    // ======================== 内部状态 ========================

    private var serverUrl: String = ""
    private var messageEndpoint: String = ""
    private var transportMode: TransportMode = TransportMode.AUTO
    private var customHeaders: Map<String, String> = emptyMap()

    /** 连接成功后的工具快照（用于重连后对比） */
    private var lastSuccessfulTools: List<McpTool> = emptyList()

    enum class TransportMode {
        AUTO,
        SSE,
        STREAMABLE_HTTP
    }

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 允许 HTTP 0.9/1.0 响应（某些 MCP 服务器可能返回非标准响应）
            .retryOnConnectionFailure(true)
            .build()
    }

    // ======================== 连接管理 ========================

    /**
     * 连接 MCP 服务器
     * 自动检测传输协议：
     * - SSE：URL 包含 /sse
     * - Streamable HTTP：URL 包含 /mcp 或检测到标准 HTTP JSON-RPC 端点
     * - AUTO：先尝试 Streamable HTTP，失败则尝试 SSE
     */
    fun connect(url: String, headers: Map<String, String> = emptyMap()) {
        scope.launch {
            disconnectInternal()
            serverUrl = url
            customHeaders = headers
            reconnectAttempt = 0
            _connectionState.value = McpConnectionState.Connecting
            addLog("正在连接: $url")

            // 检测传输模式
            transportMode = when {
                url.contains("/sse", ignoreCase = true) -> TransportMode.SSE
                url.contains("/mcp", ignoreCase = true) -> TransportMode.STREAMABLE_HTTP
                else -> TransportMode.AUTO
            }
            addLog("传输模式: ${transportMode.name}")

            val connected = when (transportMode) {
                TransportMode.SSE -> connectSSE(url)
                TransportMode.STREAMABLE_HTTP -> connectStreamableHttp(url)
                TransportMode.AUTO -> {
                    if (!tryConnectStreamableHttp(url)) {
                        addLog("Streamable HTTP 失败，尝试 SSE...")
                        connectSSE(url)
                    } else {
                        true
                    }
                }
            }

            if (connected) {
                reconnectAttempt = 0
            }
        }
    }

    /**
     * 重新连接（使用上次的 URL 和 Headers）
     * 参考触发重连（用户手动点击）
     */
    fun reconnect() {
        if (serverUrl.isBlank()) {
            addLog("无可用的服务器地址，无法重连")
            return
        }
        addLog("手动重连中...")
        connect(serverUrl, customHeaders)
    }

    /**
     * 触发自动重连（由传输层错误回调触发）
     * 参考 RikkaHub 的指数退避重连策略
     */
    private suspend fun scheduleReconnect() {
        if (reconnectAttempt >= McpConstants.MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = McpConnectionState.Error(
                "重连失败",
                "已达最大重试次数 (${McpConstants.MAX_RECONNECT_ATTEMPTS})"
            )
            addLog("重连失败：已达到最大重试次数")
            return
        }

        // Mutex 保证每个 session 最多一个重连任务
        reconnectMutex.withLock {
            // 双重检查
            if (_connectionState.value is McpConnectionState.Disconnected) return

            reconnectAttempt++
            val delay = calculateBackoff(reconnectAttempt)
            _connectionState.value = McpConnectionState.Reconnecting(
                attempt = reconnectAttempt,
                maxAttempts = McpConstants.MAX_RECONNECT_ATTEMPTS
            )
            addLog("将在 ${delay / 1000}秒 后重连 (${reconnectAttempt}/${McpConstants.MAX_RECONNECT_ATTEMPTS})")

            delay(delay)

            // 检查是否已被外部断开
            if (_connectionState.value is McpConnectionState.Disconnected) return

            addLog("正在重连 (${reconnectAttempt}/${McpConstants.MAX_RECONNECT_ATTEMPTS})...")
            _connectionState.value = McpConnectionState.Connecting

            // 清理旧连接
            currentEventSource?.cancel()
            currentEventSource = null
            pendingResponses.values.forEach { it.cancel() }
            pendingResponses.clear()
            sessionId = null

            val connected = when (transportMode) {
                TransportMode.SSE -> connectSSE(serverUrl)
                TransportMode.STREAMABLE_HTTP -> connectStreamableHttp(serverUrl)
                TransportMode.AUTO -> {
                    if (!tryConnectStreamableHttp(serverUrl)) {
                        connectSSE(serverUrl)
                    } else {
                        true
                    }
                }
            }

            if (connected) {
                addLog("重连成功")
                reconnectAttempt = 0
            }
        }
    }

    /**
     * 指数退避计算（参考 RikkaHub）
     */
    private fun calculateBackoff(attempt: Int): Long {
        val delay = McpConstants.BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1))
        return delay.coerceAtMost(McpConstants.MAX_RECONNECT_DELAY_MS)
    }

    // ======================== Streamable HTTP 传输 ========================

    /**
     * Streamable HTTP 传输：POST JSON-RPC 到 MCP 端点
     * 修复：正确处理 mcp-session-id 头和 SSE 内联响应
     */
    private suspend fun connectStreamableHttp(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            messageEndpoint = url
            val id = messageId.getAndIncrement()
            val initRequest = McpInitializeRequest(
                id = id,
                params = McpInitializeParams()
            )
            val response = postJsonRpcAndWait(initRequest)
            if (response?.result != null) {
                _connectionState.value = McpConnectionState.Connected
                addLog("Streamable HTTP 连接成功")
                addLog("服务器: ${extractServerName(response.result)}")
                sendInitializedNotification()
                listToolsInternal()
                true
            } else {
                _connectionState.value = McpConnectionState.Error(
                    response?.error?.message ?: "初始化失败",
                    null
                )
                addLog("Streamable HTTP 初始化失败: ${response?.error?.message}")
                false
            }
        }
    }

    private suspend fun tryConnectStreamableHttp(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            messageEndpoint = url
            val id = messageId.getAndIncrement()
            val initRequest = McpInitializeRequest(
                id = id,
                params = McpInitializeParams()
            )
            try {
                val response = postJsonRpcAndWait(initRequest)
                val success = response?.result != null
                if (success) {
                    // tryConnect 只做探测，不改变状态
                    // 但需要保存 session
                    addLog("Streamable HTTP 探测成功")
                    sendInitializedNotification()
                    listToolsInternal()
                    _connectionState.value = McpConnectionState.Connected
                    addLog("Streamable HTTP 连接成功")
                    addLog("服务器: ${extractServerName(response!!.result)}")
                }
                success
            } catch (e: Exception) {
                addLog("Streamable HTTP 探测异常: ${e.message}")
                false
            }
        }
    }

    // ======================== SSE 传输 ========================

    /**
     * SSE 传输：通过 Server-Sent Events 接收服务器推送
     * 修复：正确处理 endpoint 事件设置消息端点，增强错误回调
     */
    private suspend fun connectSSE(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
            // 添加自定义头
            customHeaders.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    addLog("SSE 连接已打开，等待初始化...")
                    // SSE onOpen 不代表握手完成，需要等初始化成功
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    handleSseEvent(type, data)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val msg = t?.message ?: response?.message ?: "SSE 连接失败"
                    addLog("SSE 连接失败: $msg")

                    // 只有在已连接状态下才触发重连
                    if (_connectionState.value is McpConnectionState.Connected) {
                        scope.launch { scheduleReconnect() }
                    } else {
                        _connectionState.value = McpConnectionState.Error(msg, t?.stackTraceToString()?.take(500))
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    addLog("SSE 连接已关闭")
                    // 服务端主动关闭 → 触发重连
                    if (_connectionState.value is McpConnectionState.Connected ||
                        _connectionState.value is McpConnectionState.Reconnecting
                    ) {
                        scope.launch { scheduleReconnect() }
                    } else {
                        _connectionState.value = McpConnectionState.Disconnected
                    }
                }
            }

            currentEventSource = EventSources.createFactory(client).newEventSource(requestBuilder.build(), listener)

            // 等待初始化完成（在 handleSseEvent 中异步处理）
            // SSE 初始化是异步的（onOpen → sendInitialize → 收到响应）
            // 这里我们给一个合理的等待时间
            delay(3000)

            // 检查是否成功连接
            _connectionState.value is McpConnectionState.Connected
        }
    }

    // ======================== 断开连接 ========================

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        currentEventSource?.cancel()
        currentEventSource = null
        pendingResponses.values.forEach { it.cancel() }
        pendingResponses.clear()
        sessionId = null
        lastSuccessfulTools = emptyList()
        _tools.value = emptyList()
        _serverInfo.value = null
        _connectionState.value = McpConnectionState.Disconnected
        messageEndpoint = ""
        addLog("已断开连接")
    }

    // ======================== SSE 事件处理 ========================

    private fun handleSseEvent(type: String?, data: String) {
        when (type) {
            "endpoint" -> {
                messageEndpoint = resolveEndpoint(data)
                addLog("消息端点: $messageEndpoint")
                // 收到 endpoint 后发送初始化
                scope.launch { sendInitialize() }
            }
            "message" -> {
                try {
                    val response = gson.fromJson(data, McpJsonRpcResponse::class.java)
                    response?.id?.let { id ->
                        pendingResponses[id]?.complete(response)
                        pendingResponses.remove(id)
                    }
                    handleNotification(response)
                } catch (e: Exception) {
                    addLog("解析消息失败: ${e.message}")
                }
            }
            else -> {
                // 未知事件类型，忽略（避免日志刷屏）
            }
        }
    }

    private fun resolveEndpoint(endpoint: String): String {
        return if (endpoint.startsWith("http")) {
            endpoint
        } else {
            val base = serverUrl.removeSuffix("/sse").removeSuffix("/").removeSuffix("/mcp")
            if (endpoint.startsWith("/")) "$base$endpoint" else "$base/$endpoint"
        }
    }

    private fun extractServerName(result: JsonElement?): String {
        return try {
            val name = result?.asJsonObject
                ?.getAsJsonObject("serverInfo")?.get("name")?.asString ?: "未知"
            _serverInfo.value = name
            name
        } catch (_: Exception) {
            "未知"
        }
    }

    private fun handleNotification(response: McpJsonRpcResponse?) {
        response?.result?.let { result ->
            try {
                val resultObj = result.asJsonObject
                if (resultObj.has("tools")) {
                    val toolsResult = gson.fromJson(resultObj, McpToolsListResult::class.java)
                    // 保留之前的 enabled 状态
                    val mergedTools = toolsResult.tools.map { newTool ->
                        val previous = lastSuccessfulTools.find { it.name == newTool.name }
                        if (previous != null) newTool.copy(enabled = previous.enabled) else newTool
                    }
                    _tools.value = mergedTools
                    lastSuccessfulTools = mergedTools
                    addLog("发现 ${mergedTools.size} 个工具")
                }
            } catch (_: Exception) {}
        }
    }

    // ======================== 初始化协议 ========================

    private suspend fun sendInitialize() {
        val id = messageId.getAndIncrement()
        val request = McpInitializeRequest(
            id = id,
            params = McpInitializeParams()
        )
        val response = sendJsonRpcRequest(id, request, McpConstants.INIT_TIMEOUT_SECONDS)
        if (response?.result != null) {
            addLog("初始化成功")
            addLog("服务器: ${extractServerName(response.result)}")
            _connectionState.value = McpConnectionState.Connected
            sendInitializedNotification()
            listToolsInternal()
        } else {
            addLog("初始化失败: ${response?.error?.message}")
            _connectionState.value = McpConnectionState.Error(
                response?.error?.message ?: "初始化超时或失败",
                null
            )
        }
    }

    private suspend fun sendInitializedNotification() {
        val json = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        when (transportMode) {
            TransportMode.SSE -> postSseMessage(json)
            else -> postStreamableHttpMessage(json)
        }
    }

    // ======================== 工具操作 ========================

    private suspend fun listToolsInternal() {
        val id = messageId.getAndIncrement()
        val request = McpToolsListRequest(id = id)
        val response = when (transportMode) {
            TransportMode.SSE -> sendJsonRpcRequest(id, request, 15)
            else -> postJsonRpcAndWait(request)
        }
        try {
            val result = response?.result?.let {
                gson.fromJson(it, McpToolsListResult::class.java)
            }
            val toolList = result?.tools ?: emptyList()
            // 首次连接时默认全部启用；重连时保留之前的 enabled 状态
            val mergedTools = if (lastSuccessfulTools.isEmpty()) {
                toolList.map { it.copy(enabled = true) }
            } else {
                toolList.map { newTool ->
                    val previous = lastSuccessfulTools.find { it.name == newTool.name }
                    if (previous != null) newTool.copy(enabled = previous.enabled) else newTool.copy(enabled = true)
                }
            }
            _tools.value = mergedTools
            lastSuccessfulTools = mergedTools
            addLog("发现 ${mergedTools.size} 个工具")
        } catch (e: Exception) {
            addLog("获取工具列表失败: ${e.message}")
        }
    }

    suspend fun listTools(): Result<List<McpTool>> {
        return try {
            listToolsInternal()
            Result.success(_tools.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 切换工具启用状态
     */
    fun toggleToolEnabled(toolName: String) {
        val updated = _tools.value.map { tool ->
            if (tool.name == toolName) tool.copy(enabled = !tool.enabled) else tool
        }
        _tools.value = updated
        lastSuccessfulTools = updated
    }

    /**
     * 获取所有已启用的工具（参考 RikkaHub 的 getAllAvailableTools 过滤逻辑）
     */
    fun getEnabledTools(): List<McpTool> {
        return _tools.value.filter { it.enabled }
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>): Result<McpToolCallResult> {
        val id = messageId.getAndIncrement()
        val request = McpToolCallRequest(
            id = id,
            params = McpToolCallParams(name = name, arguments = arguments)
        )
        val response = when (transportMode) {
            TransportMode.SSE -> sendJsonRpcRequest(id, request, McpConstants.TOOL_CALL_TIMEOUT_SECONDS)
            else -> postJsonRpcAndWait(request)
        }
        return try {
            val result = response?.result?.let {
                gson.fromJson(it, McpToolCallResult::class.java)
            }
            if (result != null) {
                // 截断过长的工具输出（参考 RikkaHub 的 MAX_TOOL_OUTPUT_CHARS）
                val truncatedContent = result.content.map { content ->
                    content.text?.let { text ->
                        if (text.length > McpConstants.MAX_TOOL_OUTPUT_CHARS) {
                            content.copy(text = text.take(McpConstants.MAX_TOOL_OUTPUT_CHARS) +
                                "\n\n[... 已截断，共 ${text.length} 字符 ...]")
                        } else content
                    } ?: content
                }
                Result.success(result.copy(content = truncatedContent))
            } else {
                Result.failure(IOException("调用失败: ${response?.error?.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ======================== Streamable HTTP 通信 ========================

    /**
     * Streamable HTTP: POST JSON-RPC 并同步等待响应
     * 修复：正确处理 mcp-session-id、SSE 内联响应
     */
    private suspend fun postJsonRpcAndWait(request: Any): McpJsonRpcResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(request)
                addLog(">> POST: ${json.take(150)}${if (json.length > 150) "..." else ""}")

                val body = json.toRequestBody("application/json".toMediaType())
                val httpRequest = Request.Builder()
                    .url(messageEndpoint)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                // 添加自定义头
                customHeaders.forEach { (key, value) ->
                    httpRequest.header(key, value)
                }
                // 添加会话 ID（如果有的话）
                sessionId?.let { httpRequest.header("mcp-session-id", it) }
                val builtRequest = httpRequest.build()

                client.newCall(builtRequest).execute().use { response ->
                    // 保存 mcp-session-id（参考 RikkaHub 的会话管理）
                    response.header("mcp-session-id")?.let { sid ->
                        if (sessionId == null) {
                            sessionId = sid
                            addLog("会话 ID: $sid")
                        }
                    }

                    if (!response.isSuccessful) {
                        addLog("HTTP 错误: ${response.code} ${response.message}")
                        if (response.code == 401) {
                            addLog("服务器需要授权（401），请检查是否需要配置认证头")
                        }
                        return@withContext null
                    }

                    val contentType = response.header("Content-Type") ?: ""
                    val responseBody = response.body?.string()
                        ?: return@withContext null

                    if (contentType.contains("text/event-stream")) {
                        // SSE 响应：解析 event: message 数据
                        addLog("收到 SSE 响应")
                        parseSseResponse(responseBody)
                    } else {
                        // JSON 响应：直接解析
                        addLog("收到 JSON 响应")
                        try {
                            gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
                        } catch (e: Exception) {
                            addLog("解析响应失败: ${e.message}")
                            addLog("原始响应: ${responseBody.take(300)}")
                            null
                        }
                    }
                }
            } catch (e: IOException) {
                addLog("请求异常: ${e.message}")
                // 网络异常 → 触发重连
                if (_connectionState.value is McpConnectionState.Connected) {
                    scope.launch { scheduleReconnect() }
                }
                null
            }
        }
    }

    /**
     * 解析 SSE 格式的响应文本
     * 修复：正确解析多行 SSE 数据
     */
    private fun parseSseResponse(data: String): McpJsonRpcResponse? {
        var lastJsonData = ""
        for (line in data.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                lastJsonData = trimmed.removePrefix("data:").trim()
            }
            // 忽略 event: 行和其他 SSE 字段
        }
        return if (lastJsonData.isNotBlank()) {
            try {
                gson.fromJson(lastJsonData, McpJsonRpcResponse::class.java)
            } catch (e: Exception) {
                addLog("SSE 解析失败: ${e.message}")
                null
            }
        } else null
    }

    // ======================== SSE 通信 ========================

    /**
     * SSE 模式：发送 JSON-RPC 并等待 SSE 事件回调
     * 修复：增加可配置的超时时间
     */
    private suspend fun sendJsonRpcRequest(
        id: Int,
        request: Any,
        timeoutSeconds: Long = 30
    ): McpJsonRpcResponse? {
        val json = gson.toJson(request)
        val deferred = CompletableDeferred<McpJsonRpcResponse?>()
        pendingResponses[id] = deferred

        return try {
            postSseMessage(json)
            withTimeout(timeoutSeconds * 1000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingResponses.remove(id)
            addLog("请求超时 (${timeoutSeconds}s): id=$id")
            null
        }
    }

    private suspend fun postSseMessage(json: String) {
        val endpoint = messageEndpoint.ifEmpty {
            serverUrl.replace("/sse", "/message")
        }
        val body = json.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Content-Type", "application/json")
        // 添加自定义头
        customHeaders.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        withContext(Dispatchers.IO) {
            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        addLog("POST 失败: ${response.code} ${response.message}")
                    }
                }
            } catch (e: IOException) {
                addLog("POST 异常: ${e.message}")
            }
        }
    }

    private suspend fun postStreamableHttpMessage(json: String) {
        val body = json.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(messageEndpoint)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        // 添加自定义头
        customHeaders.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        // 添加会话 ID
        sessionId?.let { requestBuilder.header("mcp-session-id", it) }

        withContext(Dispatchers.IO) {
            try {
                client.newCall(requestBuilder.build()).execute().use { _ ->
                    // 通知消息不需要等待响应
                }
            } catch (_: IOException) {}
        }
    }

    // ======================== 日志管理 ========================

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date())
        val logEntry = "[$timestamp] $message"
        // 限制日志数量，防止内存泄漏
        val currentLogs = _logs.value
        _logs.value = if (currentLogs.size > 200) {
            (currentLogs + logEntry).takeLast(100)
        } else {
            currentLogs + logEntry
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ======================== 生命周期 ========================

    fun cleanup() {
        scope.launch { disconnectInternal() }
        scope.cancel()
    }
}