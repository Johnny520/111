package com.apkanalyzer.pollinations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PollinationsClient {

    private val client: OkHttpClient

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    suspend fun generateImage(
        prompt: String,
        model: String = "flux",
        width: Int = 1024,
        height: Int = 1024,
        seed: Int? = null,
        apiKey: String? = null,
        enhance: Boolean = true
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
            val urlBuilder = StringBuilder("https://image.pollinations.ai/prompt/$encodedPrompt")
            urlBuilder.append("?model=$model")
            urlBuilder.append("&width=$width")
            urlBuilder.append("&height=$height")
            urlBuilder.append("&nologo=true")
            if (seed != null) {
                urlBuilder.append("&seed=$seed")
            }
            if (apiKey != null) {
                urlBuilder.append("&api_key=$apiKey")
            }
            if (enhance) {
                urlBuilder.append("&enhance=true")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateImageUrl(
        prompt: String,
        model: String = "flux",
        width: Int = 1024,
        height: Int = 1024,
        seed: Int? = null,
        apiKey: String? = null
    ): String {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
        val urlBuilder = StringBuilder("https://image.pollinations.ai/prompt/$encodedPrompt")
        urlBuilder.append("?model=$model")
        urlBuilder.append("&width=$width")
        urlBuilder.append("&height=$height")
        urlBuilder.append("&nologo=true")
        seed?.let { urlBuilder.append("&seed=$it") }
        apiKey?.let { urlBuilder.append("&api_key=$it") }
        return urlBuilder.toString()
    }

    /**
     * 使用 POST + JSON body 方式调用 Pollinations.ai 的对话 API
     * 解决 GET 方式 URL 长度过长被截断的问题
     */
    suspend fun chatCompletion(
        messages: List<Map<String, String>>,
        model: String = "openai",
        apiKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = buildJsonBody(model, messages, apiKey)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val requestBuilder = Request.Builder()
                .url("https://text.pollinations.ai/")
                .post(body)
                .header("Content-Type", "application/json")

            apiKey?.let { requestBuilder.header("Authorization", "Bearer $it") }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(500) ?: ""
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: $errorBody")
                    )
                }
                val text = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                Result.success(text.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 简单文本生成（GET 方式，适用于短 prompt）
     */
    suspend fun generateText(
        prompt: String,
        model: String = "openai",
        apiKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
            val url = "https://text.pollinations.ai/$encodedPrompt?model=$model"

            val requestBuilder = Request.Builder().url(url).get()
            apiKey?.let { requestBuilder.header("Authorization", "Bearer $it") }

            client.newCall(requestBuilder.build()).execute().use { response ->
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

    private fun buildJsonBody(
        model: String,
        messages: List<Map<String, String>>,
        apiKey: String?
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"model\":\"$model\",")
        sb.append("\"messages\":[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) sb.append(",")
            val role = msg["role"] ?: "user"
            val content = escapeJson(msg["content"] ?: "")
            sb.append("{\"role\":\"$role\",\"content\":\"$content\"}")
        }
        sb.append("]")
        apiKey?.let { sb.append(",\"api_key\":\"$it\"") }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun getAvailableImageModels(): List<String> {
        return listOf("flux", "turbo", "sdxl", "dall-e-3")
    }
}
