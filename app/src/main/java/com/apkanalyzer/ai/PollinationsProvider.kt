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
 * Pollinations.ai Provider
 * 基础URL: https://text.pollinations.ai/
 * 支持 POST / 和 GET /{prompt} 两种方式
 * 使用 Gson 序列化请求，自动处理特殊字符转义
 */
class PollinationsProvider : AiProvider {

    override var name: String = "Pollinations.ai"
    override var isAvailable: Boolean = true
    override var modelName: String = "openai"

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

            val request = Request.Builder()
                .url("https://text.pollinations.ai/")
                .post(body)
                .header("Content-Type", "application/json")
                .header("Accept", "text/plain")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(500) ?: ""
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
                val text = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                Result.success(text.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8")
            val url = "https://text.pollinations.ai/$encodedPrompt?model=openai"

            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val text = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                Result.success(text.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://text.pollinations.ai/")
                .head()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
