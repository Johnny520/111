package com.apkanalyzer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkanalyzer.ai.AgentMessage
import com.apkanalyzer.ai.CustomProviderConfig
import com.apkanalyzer.ai.McpAgentEngine
import com.apkanalyzer.ai.MultiAiClient
import com.apkanalyzer.ai.ProviderInfo
import com.apkanalyzer.analyzer.ApkAnalyzer
import com.apkanalyzer.model.ApkInfo
import com.apkanalyzer.model.McpTool
import com.apkanalyzer.mcp.McpClient
import com.apkanalyzer.model.McpConnectionState
import com.apkanalyzer.pollinations.PollinationsClient
import com.apkanalyzer.util.AppUpdater
import com.apkanalyzer.util.SettingsStore
import com.apkanalyzer.util.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val analyzer = ApkAnalyzer(application)
    val pollinationsClient = PollinationsClient()
    val multiAiClient = MultiAiClient()
    val mcpClient = McpClient()
    val agentEngine = McpAgentEngine(multiAiClient, mcpClient)

    // 设置持久化存储
    private val settingsStore = SettingsStore(application)

    // 应用更新
    val appUpdater = AppUpdater(application)
    val downloadProgress: StateFlow<Int> = appUpdater.downloadProgress
    val downloadedBytes: StateFlow<Long> = appUpdater.downloadedBytes
    val totalBytes: StateFlow<Long> = appUpdater.totalBytes
    val downloadState: StateFlow<AppUpdater.DownloadState> = appUpdater.downloadState
    val downloadError: StateFlow<String?> = appUpdater.downloadError

    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set
    var isCheckingUpdate by mutableStateOf(false)
        private set

    // 深色主题
    var isDarkTheme by mutableStateOf(settingsStore.darkTheme)
        private set

    // AI Provider state
    val aiProviders: StateFlow<List<ProviderInfo>> = multiAiClient.providerList
    val aiCurrentProvider: StateFlow<String> = multiAiClient.currentProviderName
    val aiLastError: StateFlow<String?> = multiAiClient.lastError
    var selectedAiProviderIndex by mutableStateOf(-1)
        private set

    // AI Model
    val aiSelectedModel: StateFlow<String> = multiAiClient.selectedModel

    // 自定义 Provider 配置列表（从 MultiAiClient 暴露）
    val customProviders: StateFlow<List<CustomProviderConfig>> = multiAiClient.customProviders

    // APK Analyzer state
    var apkInfo by mutableStateOf<ApkInfo?>(null)
        private set

    var isAnalyzing by mutableStateOf(false)
        private set

    var analyzeError by mutableStateOf<String?>(null)
        private set

    // Pollinations state
    var imageUrl by mutableStateOf<String?>(null)
        private set

    var isGenerating by mutableStateOf(false)
        private set

    var generationError by mutableStateOf<String?>(null)
        private set

    // MCP state
    val mcpState: StateFlow<McpConnectionState> = mcpClient.connectionState
    val mcpTools: StateFlow<List<McpTool>> = mcpClient.tools
    val mcpLogs: StateFlow<List<String>> = mcpClient.logs
    val mcpServerInfo: StateFlow<String?> = mcpClient.serverInfo

    var mcpCallResult by mutableStateOf<String?>(null)
        private set

    var isCallingTool by mutableStateOf(false)
        private set

    // AI Agent state
    val agentMessages: StateFlow<List<AgentMessage>> = agentEngine.messages
    val agentProcessing: StateFlow<Boolean> = agentEngine.isProcessing
    val agentCurrentStep: StateFlow<String> = agentEngine.currentStep

    // Settings
    var mcpServerUrl by mutableStateOf("http://127.0.0.1:8787/mcp")
        private set

    var pollinationsApiKey by mutableStateOf("")
        private set

    var selectedImageModel by mutableStateOf("flux")
        private set

    init {
        // 恢复保存的设置
        selectedAiProviderIndex = settingsStore.aiProviderIndex
        multiAiClient.setManualProvider(settingsStore.aiProviderIndex)
        if (settingsStore.aiModel != "auto") {
            multiAiClient.setModel(settingsStore.aiModel)
        }
        // 恢复自定义 provider 列表
        settingsStore.customProviders.forEach { config ->
            multiAiClient.addCustomProvider(config)
        }
        // 恢复 MCP 地址
        mcpServerUrl = settingsStore.mcpServerUrl
        pollinationsApiKey = settingsStore.pollinationsApiKey
        selectedImageModel = settingsStore.imageModel
    }

    fun analyzeApkFromUri(uri: Uri, context: Context) {
        viewModelScope.launch {
            isAnalyzing = true
            analyzeError = null
            try {
                val filePath = copyUriToCache(context, uri)
                apkInfo = analyzer.analyzeApk(filePath)
            } catch (e: Exception) {
                analyzeError = e.message
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun analyzeApkFromPath(path: String) {
        viewModelScope.launch {
            isAnalyzing = true
            analyzeError = null
            try {
                apkInfo = analyzer.analyzeApk(path)
            } catch (e: Exception) {
                analyzeError = e.message
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun generateImage(prompt: String, width: Int, height: Int, seed: Int?) {
        viewModelScope.launch {
            isGenerating = true
            generationError = null
            try {
                val url = pollinationsClient.generateImageUrl(
                    prompt = prompt,
                    model = selectedImageModel,
                    width = width,
                    height = height,
                    seed = seed,
                    apiKey = pollinationsApiKey.ifEmpty { null }
                )
                imageUrl = url
            } catch (e: Exception) {
                generationError = e.message
            } finally {
                isGenerating = false
            }
        }
    }

    // ======================== MCP 方法 ========================

    fun connectMcp(url: String, headers: Map<String, String> = emptyMap()) {
        mcpServerUrl = url
        settingsStore.mcpServerUrl = url
        mcpClient.connect(url, headers)
    }

    fun disconnectMcp() {
        mcpClient.disconnect()
    }

    /** 手动重连（参考 RikkaHub 的 reconnect） */
    fun reconnectMcp() {
        mcpClient.reconnect()
    }

    /** 切换工具启用状态 */
    fun toggleToolEnabled(toolName: String) {
        mcpClient.toggleToolEnabled(toolName)
    }

    /** 批量启用/禁用所有工具 */
    fun toggleAllTools(enabled: Boolean) {
        mcpClient.tools.value.forEach { tool ->
            if (tool.enabled != enabled) {
                mcpClient.toggleToolEnabled(tool.name)
            }
        }
    }

    /** 刷新工具列表 */
    fun refreshMcpTools() {
        viewModelScope.launch {
            mcpClient.listTools()
        }
    }

    /** 清空 MCP 日志 */
    fun clearMcpLogs() {
        mcpClient.clearLogs()
    }

    fun callMcpTool(toolName: String, arguments: Map<String, Any>) {
        viewModelScope.launch {
            isCallingTool = true
            mcpCallResult = null
            try {
                val result = mcpClient.callTool(toolName, arguments)
                result.onSuccess { toolResult ->
                    mcpCallResult = toolResult.content.joinToString("\n") {
                        it.text ?: it.data ?: ""
                    }
                }.onFailure {
                    mcpCallResult = "调用失败: ${it.message}"
                }
            } catch (e: Exception) {
                mcpCallResult = "异常: ${e.message}"
            } finally {
                isCallingTool = false
            }
        }
    }

    // ======================== AI Agent 方法 ========================

    private var agentJob: Job? = null

    fun sendAgentRequest(userMessage: String, workDir: String = "/storage/emulated/0/MT2/mcp/") {
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            agentEngine.processRequest(userMessage, workDir)
        }
    }

    fun cancelAgentRequest() {
        agentEngine.cancel()
        agentJob?.cancel()
        agentJob = null
    }

    fun clearAgentMessages() {
        agentEngine.clearMessages()
    }

    fun retryMessage(messageIndex: Int, workDir: String = "/storage/emulated/0/MT2/mcp/") {
        val userContent = agentEngine.retryFromMessage(messageIndex) ?: return
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            agentEngine.processRequest(userContent, workDir)
        }
    }

    // ======================== 动态模型获取 ========================

    private val _fetchedModels = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val fetchedModels: StateFlow<List<Pair<String, String>>> = _fetchedModels

    var isFetchingModels by mutableStateOf(false)
        private set

    /**
     * 根据 provider 索引获取模型列表
     * 内置 provider 返回预定义列表，自定义 provider 通过 /v1/models 获取
     */
    fun fetchModelsForProvider(providerIndex: Int) {
        if (providerIndex < 0) {
            // 自动模式：返回所有内置模型
            _fetchedModels.value = multiAiClient.allModels.map { it to (multiAiClient.modelLabels[it] ?: it) }
            return
        }
        viewModelScope.launch {
            isFetchingModels = true
            try {
                val provider = multiAiClient.allProviders.getOrNull(providerIndex)
                if (provider == null) {
                    _fetchedModels.value = emptyList()
                    return@launch
                }
                // 先尝试内置映射
                val builtIn = multiAiClient.getModelsForProvider(provider.name)
                if (builtIn.isNotEmpty()) {
                    _fetchedModels.value = builtIn
                } else {
                    // 自定义 provider：通过 API 获取
                    val models = provider.fetchModels()
                    if (models.isNotEmpty()) {
                        _fetchedModels.value = models.map { it to it }
                    } else {
                        // 获取失败，使用配置的模型名
                        _fetchedModels.value = if (provider.modelName.isNotBlank()) {
                            listOf(provider.modelName to provider.modelName)
                        } else emptyList()
                    }
                }
            } finally {
                isFetchingModels = false
            }
        }
    }

    // ======================== 设置方法 ========================

    fun clearMcpResult() {
        mcpCallResult = null
    }

    fun updateMcpServerUrl(url: String) {
        mcpServerUrl = url
        settingsStore.mcpServerUrl = url
    }

    fun updatePollinationsApiKey(key: String) {
        pollinationsApiKey = key
        settingsStore.pollinationsApiKey = key
    }

    fun updateSelectedImageModel(model: String) {
        selectedImageModel = model
        settingsStore.imageModel = model
    }

    fun setAiProvider(index: Int) {
        selectedAiProviderIndex = index
        multiAiClient.setManualProvider(index)
        settingsStore.aiProviderIndex = index
    }

    fun setAiModel(model: String) {
        multiAiClient.setModel(model)
        settingsStore.aiModel = model
    }

    /**
     * 添加自定义 Provider，并自动保存到持久化存储
     */
    fun addCustomProvider(config: CustomProviderConfig) {
        multiAiClient.addCustomProvider(config)
        settingsStore.customProviders = multiAiClient.getCustomProviders()
    }

    /**
     * 更新自定义 Provider，并自动保存到持久化存储
     */
    fun updateCustomProvider(id: String, config: CustomProviderConfig) {
        multiAiClient.updateCustomProvider(id, config)
        settingsStore.customProviders = multiAiClient.getCustomProviders()
    }

    /**
     * 删除自定义 Provider，并自动保存到持久化存储
     */
    fun removeCustomProvider(id: String) {
        multiAiClient.removeCustomProvider(id)
        settingsStore.customProviders = multiAiClient.getCustomProviders()
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            isCheckingUpdate = true
            val info = appUpdater.checkUpdate(settingsStore.updateConfigUrl)
            updateInfo = info
            isCheckingUpdate = false
        }
    }

    fun downloadUpdate() {
        val info = updateInfo ?: return
        viewModelScope.launch {
            appUpdater.startDownload(info.downloadUrl)
        }
    }

    fun cancelDownload() {
        appUpdater.cancelDownload()
    }

    fun resetUpdateState() {
        appUpdater.resetState()
    }

    fun toggleDarkTheme() {
        isDarkTheme = !isDarkTheme
        settingsStore.darkTheme = isDarkTheme
    }

    fun clearApkInfo() {
        apkInfo = null
    }

    fun clearImage() {
        imageUrl = null
    }

    private fun copyUriToCache(context: Context, uri: Uri): String {
        val file = java.io.File(context.cacheDir, "temp_apk_${System.currentTimeMillis()}.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("无法打开文件")
        return file.absolutePath
    }

    override fun onCleared() {
        super.onCleared()
        agentJob?.cancel()
        mcpClient.cleanup()
    }
}

sealed class Screen(val route: String, val resourceId: Int) {
    object Analyzer : Screen("analyzer", com.apkanalyzer.R.string.nav_analyzer)
    object AiAssistant : Screen("ai_assistant", com.apkanalyzer.R.string.nav_ai_assistant)
    object Pollinations : Screen("pollinations", com.apkanalyzer.R.string.nav_pollinations)
    object Mcp : Screen("mcp", com.apkanalyzer.R.string.nav_mcp)
    object Settings : Screen("settings", com.apkanalyzer.R.string.nav_settings)
    object About : Screen("about", com.apkanalyzer.R.string.nav_about)
}
