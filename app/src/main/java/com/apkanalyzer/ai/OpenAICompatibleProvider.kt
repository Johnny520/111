package com.apkanalyzer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通用 OpenAI 兼容 API Provider
 * 支持自定义 URL、API Key、Model
 * 使用 Gson 序列化请求，自动处理特殊字符转义
 */
class OpenAICompatibleProvider : AiProvider {

    override var name: String = "自定义"
    override var isAvailable: Boolean = true

    var baseUrl: String = ""
    var apiKey: String = ""
    override var modelName: String = ""

    private val gson = com.google.gson.Gson()
    private val client: OkHttpClient

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun chatCompletion(messages: List<Map<String, String>>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = ChatRequest(modelName, messages.map { Message(it["role"] ?: "user", it["content"] ?: "") })
            val jsonBody = gson.toJson(bodyObj)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(body)
                .header("Content-Type", "application/json")

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(500) ?: ""
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
                val responseText = response.body?.string() ?: ""
                val result = parseOpenAIResponse(responseText)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = listOf(mapOf("role" to "user", "content" to prompt))
            val bodyObj = ChatRequest(modelName, messages.map { Message("user", prompt) })
            val jsonBody = gson.toJson(bodyObj)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(body)
                .header("Content-Type", "application/json")

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val responseText = response.body?.string() ?: ""
                val result = parseOpenAIResponse(responseText)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            val requestBuilder = Request.Builder()
                .url(baseUrl.replace("/chat/completions", "/models"))
                .get()
            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun fetchModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            // 从 baseUrl 推导 models 端点
            val modelsUrl = baseUrl
                .replace("/chat/completions", "/models")
                .replace("/completions", "/models")
            val requestBuilder = Request.Builder()
                .url(modelsUrl)
                .get()
            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val data = json.getAsJsonArray("data") ?: return@withContext emptyList()
                data.mapNotNull { elem ->
                    elem.asJsonObject.get("id")?.asString
                }.sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseOpenAIResponse(responseText: String): String {
        return try {
            val json = com.google.gson.JsonParser.parseString(responseText).asJsonObject

            // 检查 API 级别错误
            val error = json.get("error")
            if (error != null && error.isJsonObject) {
                val errMsg = error.asJsonObject.get("message")?.asString ?: "API 返回错误"
                throw IOException("API 错误: $errMsg")
            }

            val choices = json.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val firstChoice = choices[0].asJsonObject
                // 检查 finish_reason
                val finishReason = firstChoice.get("finish_reason")?.asString
                if (finishReason == "content_filter") {
                    return "[内容被过滤]"
                }
                val message = firstChoice.getAsJsonObject("message")
                // 优先取 content
                val content = message?.get("content")?.asString
                if (!content.isNullOrBlank()) {
                    return content.trim()
                }
                // DeepSeek 等推理模型：content 为 null 时检查 reasoning_content
                val reasoningContent = message?.get("reasoning_content")?.asString
                if (!reasoningContent.isNullOrBlank()) {
                    // 如果 finish_reason 是 stop，说明已结束，把 reasoning_content 作为内容
                    if (finishReason == "stop") {
                        return reasoningContent.trim()
                    }
                    // 否则是思考中间阶段，返回空让 agent 继续
                    return ""
                }
                // 如果 content 为 null 但有 tool_calls，序列化整个 choices[0] 让 parseToolCall 处理
                val toolCalls = message?.get("tool_calls")
                if (toolCalls != null) {
                    return com.google.gson.GsonBuilder().create().toJson(firstChoice)
                }
                // 都没有，返回空
                ""
            } else {
                ""
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }
}
