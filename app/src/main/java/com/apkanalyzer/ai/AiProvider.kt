package com.apkanalyzer.ai

/**
 * AI 文本生成 Provider 接口
 * 统一不同 AI 服务的调用方式
 */
interface AiProvider {
    var name: String
    var isAvailable: Boolean
    var modelName: String

    /**
     * 对话式文本生成
     * @param messages 消息列表，每个消息包含 role 和 content
     * @return 生成的文本内容
     */
    suspend fun chatCompletion(messages: List<Map<String, String>>): Result<String>

    /**
     * 简单文本生成（单次 prompt）
     * @param prompt 输入提示词
     * @return 生成的文本内容
     */
    suspend fun generateText(prompt: String): Result<String>

    /**
     * 检查该 provider 是否可用
     */
    suspend fun healthCheck(): Boolean

    /**
     * 获取该 provider 支持的模型列表
     * 默认返回空列表，支持 /v1/models 端点的 Provider 可重写
     * @return 模型 ID 列表
     */
    suspend fun fetchModels(): List<String> = emptyList()
}

data class ProviderInfo(
    val id: String,
    val name: String,
    val description: String,
    val isKeyless: Boolean
)

/**
 * 自定义 AI 接口配置
 * 用于保存用户添加的 OpenAI 兼容 API 接口信息
 */
data class CustomProviderConfig(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = ""
)
