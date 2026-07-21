package com.apkanalyzer.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// ======================== MCP 连接状态 ========================

/**
 * MCP 连接状态机
 * 参考 RikkaHub 的 McpStatus 设计，支持重连中间状态
 */
sealed class McpConnectionState {
    /** 未连接 / 已断开 */
    object Disconnected : McpConnectionState()

    /** 正在建立连接（首次连接） */
    object Connecting : McpConnectionState()

    /** 握手完成，工具可用 */
    object Connected : McpConnectionState()

    /** 正在重连（含重试次数信息） */
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpConnectionState()

    /** 连接错误 */
    data class Error(val message: String, val detail: String? = null) : McpConnectionState()
}

// ======================== MCP 服务器配置 ========================

/**
 * MCP 服务器配置（参考 RikkaHub 的 McpServerConfig）
 * 用于持久化存储和 UI 展示
 */
data class McpServerConfig(
    val name: String = "默认服务器",
    val url: String = "",
    val transportMode: String = "AUTO", // AUTO, SSE, STREAMABLE_HTTP
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val lastConnected: Long = 0L
)

// ======================== MCP 工具模型 ========================

/**
 * MCP 工具定义
 * 增加 enabled 开关，参考 RikkaHub 的 McpTool.needsApproval 设计
 */
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: McpToolInputSchema? = null,
    val enabled: Boolean = true
)

data class McpToolInputSchema(
    val type: String,
    val properties: JsonElement? = null,
    val required: List<String>? = null
)

// ======================== JSON-RPC 请求模型 ========================

data class McpInitializeRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String = "initialize",
    val params: McpInitializeParams
)

data class McpInitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpClientInfo = McpClientInfo()
)

data class McpClientCapabilities(
    val roots: McpRootsCapability? = null,
    val sampling: JsonElement? = null
)

data class McpRootsCapability(
    val listChanged: Boolean = true
)

data class McpClientInfo(
    val name: String = "APKAnalyzer",
    val version: String = "1.0.0"
)

data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpServerInfo
)

data class McpServerCapabilities(
    val logging: JsonElement? = null,
    val prompts: JsonElement? = null,
    val resources: JsonElement? = null,
    val tools: JsonElement? = null
)

data class McpServerInfo(
    val name: String,
    val version: String
)

data class McpToolsListRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String = "tools/list",
    val params: JsonElement? = null
)

data class McpToolsListResult(
    val tools: List<McpTool>
)

data class McpToolCallRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String = "tools/call",
    val params: McpToolCallParams
)

data class McpToolCallParams(
    val name: String,
    val arguments: Map<String, Any>
)

// ======================== MCP 工具调用结果 ========================

/**
 * MCP nextActions 中建议的下一步操作
 */
data class McpNextAction(
    val tool: String,
    val purpose: String = "",
    val description: String? = null,
    val arguments: Map<String, Any> = emptyMap()
)

/**
 * MCP 工具调用返回结果
 * content 是一个列表，每项可以是 text、image 等类型
 * nextActions 是 MCP 服务器在错误/需要后续操作时返回的建议
 */
data class McpToolCallResult(
    val content: List<McpToolContent>,
    val isError: Boolean = false,
    val nextActions: List<McpNextAction>? = null
)

data class McpToolContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)

// ======================== JSON-RPC 响应模型 ========================

data class McpJsonRpcResponse(
    val jsonrpc: String? = null,
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: McpJsonRpcError? = null
)

data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// ======================== 辅助消息模型 ========================

data class McpMessage(
    val type: String,
    val id: Int? = null,
    val method: String? = null,
    val data: String = ""
)

// ======================== MCP 常量 ========================

object McpConstants {
    /** 最大工具输出字符数（参考 RikkaHub 的 MAX_TOOL_OUTPUT_CHARS） */
    const val MAX_TOOL_OUTPUT_CHARS = 32768

    /** 工具输出预览长度 */
    const val TOOL_OUTPUT_PREVIEW_CHARS = 4096

    /** 最大重连次数（参考 RikkaHub） */
    const val MAX_RECONNECT_ATTEMPTS = 5

    /** 首次重连延迟（毫秒） */
    const val BASE_RECONNECT_DELAY_MS = 1000L

    /** 最大重连延迟（毫秒） */
    const val MAX_RECONNECT_DELAY_MS = 30000L

    /** 工具调用超时（秒） */
    const val TOOL_CALL_TIMEOUT_SECONDS = 120L

    /** 初始化超时（秒） */
    const val INIT_TIMEOUT_SECONDS = 30L

    /** Agent 最大迭代次数 */
    const val MAX_AGENT_ITERATIONS = 60
}