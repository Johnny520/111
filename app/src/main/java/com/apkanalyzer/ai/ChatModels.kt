package com.apkanalyzer.ai

/**
 * Gson 数据类：用于序列化 OpenAI 兼容的 chat 请求
 * 替代手写 JSON 拼接，自动处理所有特殊字符转义
 */
data class Message(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<Message>
)

data class ChatRequestMaxTokens(
    val model: String,
    val max_tokens: Int,
    val messages: List<Message>
)
