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
 * Cehpoint AI Provider
 * 免费 OpenAI 兼容接口，无需 API Key，无需注册
 * 地址: https://ai-api.cehpoint.co.in/v1/chat/completions
 * 使用 Gson 序列化请求，自动处理特殊字符转义
 */
class CehpointProvider : AiProvider {

    override var name: String = "Cehpoint AI"
    override var isAvailable: Boolean = true
    override var modelName: String = "gpt-4o-mini"

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
            val bodyObj = ChatRequestMaxTokens(modelName, 2000, messages.map { Message(it["role"] ?: "user", it["content"] ?: "") })
            val jsonBody = gson.toJson(bodyObj)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://ai-api.cehpoint.co.in/v1/chat/completions")
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val responseText = response.body?.string() ?: ""
                val result = parseResponse(responseText)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = listOf(mapOf("role" to "user", "content" to prompt))
            val bodyObj = ChatRequestMaxTokens(modelName, 2000, messages.map { Message("user", prompt) })
            val jsonBody = gson.toJson(bodyObj)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://ai-api.cehpoint.co.in/v1/chat/completions")
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val responseText = response.body?.string() ?: ""
                val result = parseResponse(responseText)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            val bodyObj = ChatRequestMaxTokens("gpt-4o-mini", 50, listOf(Message("user", "hi")))
            val jsonBody = gson.toJson(bodyObj)
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://ai-api.cehpoint.co.in/v1/chat/completions")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseResponse(responseText: String): String {
        return try {
            val json = com.google.gson.JsonParser.parseString(responseText).asJsonObject
            val choices = json.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val firstChoice = choices[0].asJsonObject
                val message = firstChoice.getAsJsonObject("message")
                message?.get("content")?.asString?.trim() ?: responseText.take(500)
            } else {
                responseText.take(500)
            }
        } catch (e: Exception) {
            responseText.take(500)
        }
    }
}
