package com.apkanalyzer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Pollinations.ai GET 方式 Provider（备用通道）
 * 地址: https://text.pollinations.ai/{prompt}?model=openai&nologo=true
 * 无需 API Key，无需注册
 */
class PollinationsGetProvider : AiProvider {

    override var name: String = "Pollinations GET"
    override var isAvailable: Boolean = true
    override var modelName: String = "openai"

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
            // 将多轮对话合并为一个 prompt
            val prompt = buildPromptFromMessages(messages)
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")

            val request = Request.Builder()
                .url("https://text.pollinations.ai/${encodedPrompt}?model=${modelName}&nologo=true")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val result = response.body?.string()?.trim() ?: ""
                if (result.isBlank()) {
                    Result.failure(IOException("空响应"))
                } else {
                    Result.success(result)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateText(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")

            val request = Request.Builder()
                .url("https://text.pollinations.ai/${encodedPrompt}?model=${modelName}&nologo=true")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val result = response.body?.string()?.trim() ?: ""
                if (result.isBlank()) {
                    Result.failure(IOException("空响应"))
                } else {
                    Result.success(result)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://text.pollinations.ai/hi?model=openai&nologo=true")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildPromptFromMessages(messages: List<Map<String, String>>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            val role = msg["role"] ?: "user"
            val content = msg["content"] ?: ""
            when (role) {
                "system" -> sb.append("System: $content\n")
                "assistant" -> sb.append("Assistant: $content\n")
                else -> sb.append("User: $content\n")
            }
        }
        sb.append("Assistant:")
        return sb.toString()
    }
}