package com.apkanalyzer.util

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val updateLog: String = "",
    val forceUpdate: Boolean = false,
    val fileSize: Long = 0
)

class AppUpdater(private val context: Context) {

    // 下载状态
    private val _downloadProgress = MutableStateFlow(0) // 0-100
    val downloadProgress: StateFlow<Int> = _downloadProgress

    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = _downloadedBytes

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes

    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    @Volatile
    private var cancelFlag = false

    enum class DownloadState { IDLE, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

    suspend fun checkUpdate(configUrl: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(configUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val info = Gson().fromJson(response, UpdateInfo::class.java)
            // 如果配置里没有 fileSize，尝试 HEAD 请求获取
            if (info != null && info.fileSize <= 0 && info.downloadUrl.isNotBlank()) {
                try {
                    val headConn = URL(info.downloadUrl).openConnection() as HttpURLConnection
                    headConn.requestMethod = "HEAD"
                    headConn.connectTimeout = 5000
                    headConn.connect()
                    info.copy(fileSize = headConn.contentLengthLong.coerceAtLeast(0))
                } catch (_: Exception) { info }
            } else info
        } catch (e: Exception) {
            null
        }
    }

    fun getCurrentVersionCode(): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0)
                .let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt() else it.versionCode }
        } catch (_: Exception) { 1 }
    }

    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } catch (_: Exception) { "未知" }
    }

    suspend fun startDownload(downloadUrl: String) = withContext(Dispatchers.IO) {
        cancelFlag = false
        _downloadState.value = DownloadState.DOWNLOADING
        _downloadProgress.value = 0
        _downloadedBytes.value = 0L
        _totalBytes.value = 0L
        _downloadError.value = null

        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            val fileSize = conn.contentLengthLong
            _totalBytes.value = if (fileSize > 0) fileSize else -1

            val fileName = "APKAnalyzer_v${getCurrentVersionName()}_update.apk"
            val destFile = File(context.getExternalFilesDir(null), fileName)

            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (true) {
                        if (cancelFlag) {
                            _downloadState.value = DownloadState.CANCELLED
                            destFile.delete()
                            return@withContext
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        _downloadedBytes.value = totalRead
                        if (fileSize > 0) {
                            _downloadProgress.value = ((totalRead * 100) / fileSize).toInt().coerceIn(0, 100)
                        }
                    }
                }
            }
            conn.disconnect()

            _downloadProgress.value = 100
            _downloadState.value = DownloadState.COMPLETED

            // 触发安装
            android.widget.Toast.makeText(context, "下载完成，正在安装...", android.widget.Toast.LENGTH_SHORT).show()
            installApk(destFile)
        } catch (e: Exception) {
            _downloadError.value = e.message ?: "下载失败"
            _downloadState.value = DownloadState.FAILED
        }
    }

    fun cancelDownload() {
        cancelFlag = true
    }

    fun resetState() {
        _downloadState.value = DownloadState.IDLE
        _downloadProgress.value = 0
        _downloadedBytes.value = 0L
        _totalBytes.value = 0L
        _downloadError.value = null
    }

    private fun installApk(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // FileProvider 失败，尝试 file:// scheme
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.fromFile(file), "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "无法启动安装，请手动安装: ${file.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "未知大小"
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}