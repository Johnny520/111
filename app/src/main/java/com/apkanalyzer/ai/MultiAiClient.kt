package com.apkanalyzer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 多路 AI 客户端
 * 接口与模型智能绑定：
 * - 选择接口 → 自动匹配最佳模型
 * - 选择模型 → 自动匹配对应接口
 *
 * 注意：Agent 工具调用场景只用支持结构化输出的接口（Pollinations POST）
 * 其他接口仅适用于简单对话
 */
class MultiAiClient {

    // 内置 Provider
    private val pollinationsPost = PollinationsProvider()      // 默认模型: openai
    private val cehpointProvider = CehpointProvider()            // 默认模型: gpt-4o-mini
    private val pollinationsGet = PollinationsGetProvider()      // 默认模型: openai

    private val builtInProviders: List<AiProvider> = listOf(
        pollinationsPost,      // 1: Pollinations.ai POST（Agent 推荐）
        cehpointProvider,       // 2: Cehpoint AI（简单对话可用）
        pollinationsGet         // 3: Pollinations.ai GET（简单对话备用）
    )

    // 自定义 Provider 配置列表（替代原来的单个 customProvider）
    val customProviders = MutableStateFlow<List<CustomProviderConfig>>(emptyList())

    /**
     * 根据 CustomProviderConfig 创建一个新的 OpenAICompatibleProvider 实例
     * 每次调用创建新实例，避免状态污染
     */
    private fun createProviderFromConfig(cfg: CustomProviderConfig): OpenAICompatibleProvider {
        return OpenAICompatibleProvider().apply {
            name = cfg.name.ifBlank { "自定义" }
            baseUrl = cfg.baseUrl
            apiKey = cfg.apiKey
            modelName = cfg.modelName
            isAvailable = cfg.baseUrl.isNotBlank()
        }
    }

    /**
     * 所有 Provider 列表：内置 3 个 + 自定义 N 个
     * 注意：自定义 provider 每次访问都会重新创建实例
     */
    val allProviders: List<AiProvider>
        get() = builtInProviders + customProviders.value.map { cfg -> createProviderFromConfig(cfg) }

    // Agent 场景专用：包含能输出结构化内容的接口（Pollinations POST + Cehpoint + 已配置的自定义接口）
    val agentProviders: List<AiProvider>
        get() {
            val customList = customProviders.value
                .filter { it.baseUrl.isNotBlank() }
                .map { cfg -> createProviderFromConfig(cfg) }
            return listOf(pollinationsPost, cehpointProvider) + customList
        }

    // 接口 → 支持的模型（第一个是默认/推荐模型）—— 仅内置 provider
    private val providerModelMap = linkedMapOf(
        "Pollinations.ai"  to listOf("openai", "openai-fast"),
        "Cehpoint AI"     to listOf(
            // OpenAI 系列
            "gpt-4o-mini", "gpt-4o", "gpt-4", "gpt-4-turbo", "gpt-4.5-preview", "gpt-3.5-turbo",
            // DeepSeek 系列
            "deepseek-chat", "deepseek-r1", "deepseek-v3", "deepseek-reasoner",
            // Google 系列
            "gemini-2.0-flash", "gemini-2.5-flash", "gemini-pro",
            // Claude 系列
            "claude-3.5-sonnet", "claude-3.5-haiku", "claude-3-opus",
            // Llama 系列
            "llama-3.3-70b", "llama-3.1-70b", "llama-3.1-8b",
            // Mistral 系列
            "mistral-large", "mistral-small", "mistral-7b", "mixtral-8x7b",
            // 其他
            "qwen2.5", "qwen-coder", "qwen3",
            "phi-4", "phi-3.5-mini",
            "grok-3", "grok-mini",
            "command-r-plus", "command-r",
            "o1", "o1-mini", "o3-mini"
        ),
        "Pollinations GET" to listOf("openai")
    )

    // 模型 → 对应的接口名 —— 仅内置模型
    private val modelProviderMap = mapOf(
        "openai"         to "Pollinations.ai",
        "openai-fast"    to "Pollinations.ai",
        "gpt-4o-mini"    to "Cehpoint AI",
        "gpt-4o"         to "Cehpoint AI",
        "gpt-4"          to "Cehpoint AI",
        "gpt-4-turbo"    to "Cehpoint AI",
        "gpt-4.5-preview" to "Cehpoint AI",
        "gpt-3.5-turbo"  to "Cehpoint AI",
        "deepseek-chat"  to "Cehpoint AI",
        "deepseek-r1"    to "Cehpoint AI",
        "deepseek-v3"    to "Cehpoint AI",
        "deepseek-reasoner" to "Cehpoint AI",
        "gemini-2.0-flash" to "Cehpoint AI",
        "gemini-2.5-flash" to "Cehpoint AI",
        "gemini-pro"     to "Cehpoint AI",
        "claude-3.5-sonnet" to "Cehpoint AI",
        "claude-3.5-haiku" to "Cehpoint AI",
        "claude-3-opus"   to "Cehpoint AI",
        "llama-3.3-70b"   to "Cehpoint AI",
        "llama-3.1-70b"   to "Cehpoint AI",
        "llama-3.1-8b"    to "Cehpoint AI",
        "mistral-large"  to "Cehpoint AI",
        "mistral-small"  to "Cehpoint AI",
        "mistral-7b"     to "Cehpoint AI",
        "mixtral-8x7b"   to "Cehpoint AI",
        "qwen2.5"        to "Cehpoint AI",
        "qwen-coder"     to "Cehpoint AI",
        "qwen3"          to "Cehpoint AI",
        "phi-4"          to "Cehpoint AI",
        "phi-3.5-mini"   to "Cehpoint AI",
        "grok-3"         to "Cehpoint AI",
        "grok-mini"      to "Cehpoint AI",
        "command-r-plus" to "Cehpoint AI",
        "command-r"      to "Cehpoint AI",
        "o1"             to "Cehpoint AI",
        "o1-mini"        to "Cehpoint AI",
        "o3-mini"        to "Cehpoint AI"
    )

    val allModels: List<String> = listOf(
        // Pollinations（免费免注册）
        "openai", "openai-fast",
        // OpenAI 系列
        "gpt-4o-mini", "gpt-4o", "gpt-4", "gpt-4-turbo", "gpt-4.5-preview", "gpt-3.5-turbo",
        // DeepSeek 系列
        "deepseek-chat", "deepseek-r1", "deepseek-v3", "deepseek-reasoner",
        // Google 系列
        "gemini-2.0-flash", "gemini-2.5-flash", "gemini-pro",
        // Claude 系列
        "claude-3.5-sonnet", "claude-3.5-haiku", "claude-3-opus",
        // Llama 系列
        "llama-3.3-70b", "llama-3.1-70b", "llama-3.1-8b",
        // Mistral 系列
        "mistral-large", "mistral-small", "mistral-7b", "mixtral-8x7b",
        // 其他
        "qwen2.5", "qwen-coder", "qwen3", "phi-4", "phi-3.5-mini",
        "grok-3", "grok-mini", "command-r-plus", "command-r",
        "o1", "o1-mini", "o3-mini"
    )
    val modelLabels: Map<String, String> = mapOf(
        // Pollinations（免费免注册）
        "openai"         to "openai (Pollinations 免费)",
        "openai-fast"    to "openai-fast (Pollinations 快速)",
        // OpenAI 系列
        "gpt-4o-mini"    to "GPT-4o Mini",
        "gpt-4o"         to "GPT-4o",
        "gpt-4"          to "GPT-4",
        "gpt-4-turbo"    to "GPT-4 Turbo",
        "gpt-4.5-preview" to "GPT-4.5 Preview",
        "gpt-3.5-turbo"  to "GPT-3.5 Turbo",
        // DeepSeek 系列
        "deepseek-chat"  to "DeepSeek Chat",
        "deepseek-r1"    to "DeepSeek R1 (推理)",
        "deepseek-v3"    to "DeepSeek V3",
        "deepseek-reasoner" to "DeepSeek Reasoner",
        // Google 系列
        "gemini-2.0-flash" to "Gemini 2.0 Flash",
        "gemini-2.5-flash" to "Gemini 2.5 Flash",
        "gemini-pro"     to "Gemini Pro",
        // Claude 系列
        "claude-3.5-sonnet" to "Claude 3.5 Sonnet",
        "claude-3.5-haiku" to "Claude 3.5 Haiku",
        "claude-3-opus"   to "Claude 3 Opus",
        // Llama 系列
        "llama-3.3-70b"   to "Llama 3.3 70B",
        "llama-3.1-70b"   to "Llama 3.1 70B",
        "llama-3.1-8b"    to "Llama 3.1 8B",
        // Mistral 系列
        "mistral-large"  to "Mistral Large",
        "mistral-small"  to "Mistral Small",
        "mistral-7b"     to "Mistral 7B",
        "mixtral-8x7b"   to "Mixtral 8x7B",
        // 其他
        "qwen2.5"        to "Qwen 2.5",
        "qwen-coder"     to "Qwen Coder",
        "qwen3"          to "Qwen 3",
        "phi-4"          to "Phi-4",
        "phi-3.5-mini"   to "Phi 3.5 Mini",
        "grok-3"         to "Grok-3",
        "grok-mini"      to "Grok Mini",
        "command-r-plus" to "Command R+",
        "command-r"      to "Command R",
        "o1"             to "O1",
        "o1-mini"        to "O1 Mini",
        "o3-mini"        to "O3 Mini"
    )

    private val _providerList = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val providerList: StateFlow<List<ProviderInfo>> = _providerList

    private val _currentProvider = MutableStateFlow<AiProvider?>(null)
    val currentProvider: StateFlow<AiProvider?> = _currentProvider

    private val _currentProviderName = MutableStateFlow<String>("")
    val currentProviderName: StateFlow<String> = _currentProviderName

    private val _switchCount = MutableStateFlow(0)
    val switchCount: StateFlow<Int> = _switchCount

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var manualProviderIndex: Int = -1
    private var selectedModelName: String = ""

    private val _selectedModel = MutableStateFlow<String>("auto")
    val selectedModel: StateFlow<String> = _selectedModel

    init {
        refreshProviderInfo()
    }

    /**
     * 添加自定义 Provider
     */
    fun addCustomProvider(config: CustomProviderConfig) {
        val currentList = customProviders.value.toMutableList()
        currentList.add(config)
        customProviders.value = currentList
        refreshProviderInfo()
    }

    /**
     * 更新自定义 Provider
     */
    fun updateCustomProvider(id: String, config: CustomProviderConfig) {
        val currentList = customProviders.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) {
            currentList[index] = config
            customProviders.value = currentList
            refreshProviderInfo()
        }
    }

    /**
     * 删除自定义 Provider
     */
    fun removeCustomProvider(id: String) {
        val currentList = customProviders.value.toMutableList()
        currentList.removeAll { it.id == id }
        customProviders.value = currentList

        // 如果删除的 provider 在当前选中的索引上，需要调整
        val builtInCount = builtInProviders.size
        if (manualProviderIndex >= builtInCount) {
            // 选中的是自定义 provider，可能需要重置
            manualProviderIndex = -1
            _selectedModel.value = "auto"
            pollinationsPost.modelName = "openai"
            cehpointProvider.modelName = "gpt-4o-mini"
            pollinationsGet.modelName = "openai"
        }
        refreshProviderInfo()
    }

    /**
     * 获取所有自定义 Provider 配置列表
     */
    fun getCustomProviders(): List<CustomProviderConfig> = customProviders.value.toList()

    /**
     * 获取指定 provider 名称对应的模型列表（含 label）
     * 返回 List<Pair<modelId, label>>
     */
    fun getModelsForProvider(providerName: String): List<Pair<String, String>> {
        // 先查内置映射
        providerModelMap[providerName]?.let { models ->
            return models.map { it to (modelLabels[it] ?: it) }
        }
        // 自定义 provider 只有一个模型
        return emptyList()
    }

    /**
     * 获取指定索引的 provider 名称
     */
    fun getProviderNameAt(index: Int): String? {
        val providers = allProviders
        return if (index in providers.indices) providers[index].name else null
    }

    /**
     * 配置自定义 Provider：如果 id 为空则添加，否则更新
     */
    fun configureCustomProvider(config: CustomProviderConfig) {
        if (config.id.isBlank()) {
            // 新建
            addCustomProvider(config.copy(id = java.util.UUID.randomUUID().toString().take(8)))
        } else {
            // 更新
            val existing = customProviders.value.find { it.id == config.id }
            if (existing != null) {
                updateCustomProvider(config.id, config)
            } else {
                addCustomProvider(config)
            }
        }
    }

    fun setManualProvider(index: Int) {
        manualProviderIndex = index
        selectedModelName = ""
        _selectedModel.value = "auto"
        if (index >= 0) {
            val provider = getProviderByIndex(index)
            if (provider != null) {
                val models = providerModelMap[provider.name]
                if (models != null) {
                    // 内置 provider：使用映射中的模型
                    provider.modelName = models[0]
                    _selectedModel.value = models[0]
                }
                // 自定义 provider 的 modelName 已在 createProviderFromConfig 中设置
                _currentProvider.value = provider
                _currentProviderName.value = "${provider.name} (${provider.modelName})"
            }
        } else {
            pollinationsPost.modelName = "openai"
            cehpointProvider.modelName = "gpt-4o-mini"
            pollinationsGet.modelName = "openai"
        }
        refreshProviderInfo()
    }

    fun setModel(model: String) {
        selectedModelName = model
        _selectedModel.value = model

        val targetProviderName = modelProviderMap[model]
        if (targetProviderName != null) {
            val provider = allProviders.find { it.name == targetProviderName }
            if (provider != null) {
                provider.modelName = model
                manualProviderIndex = allProviders.indexOf(provider)
                _currentProvider.value = provider
                _currentProviderName.value = "${provider.name} ($model)"
            }
        } else {
            pollinationsPost.modelName = model
        }
        refreshProviderInfo()
    }

    private fun getProviderByIndex(index: Int): AiProvider? {
        val all = allProviders
        return if (index in all.indices) all[index] else null
    }

    fun refreshProviderInfo() {
        val infoList = mutableListOf<ProviderInfo>()
        val all = allProviders
        for (provider in all) {
            val models = providerModelMap[provider.name]
            val modelHint = if (models != null) models.joinToString(" / ") else (provider.modelName.ifEmpty { "auto" })
            infoList.add(
                ProviderInfo(
                    id = provider.name,
                    name = provider.name,
                    description = when (provider) {
                        is PollinationsProvider -> "Pollinations.ai POST（模型: $modelHint）${if (manualProviderIndex != 0) " ★推荐" else ""}"
                        is CehpointProvider -> "Cehpoint AI（模型: $modelHint）"
                        is PollinationsGetProvider -> "Pollinations.ai GET（模型: $modelHint）"
                        is OpenAICompatibleProvider -> {
                            // 自定义 provider：显示 名称: 模型名
                            "${provider.name}: ${provider.modelName.ifEmpty { "未配置模型" }}"
                        }
                        else -> "未知 Provider"
                    },
                    isKeyless = true
                )
            )
        }
        _providerList.value = infoList
    }

    /**
     * Agent 工具调用场景：只用能输出结构化工具调用 JSON 的接口
     */
    suspend fun chatCompletion(messages: List<Map<String, String>>): Result<String> {
        return withContext(Dispatchers.IO) {
            // 手动模式
            if (manualProviderIndex >= 0) {
                val provider = getProviderByIndex(manualProviderIndex)
                if (provider != null) {
                    _currentProvider.value = provider
                    _currentProviderName.value = "${provider.name} (${provider.modelName})"
                    _lastError.value = null
                    return@withContext validateResponse(provider.chatCompletion(messages))
                }
            }

            // 自动模式：Agent 场景只用 agentProviders（Pollinations POST + 自定义）
            for ((index, provider) in agentProviders.withIndex()) {
                // 自定义 provider 的模型已在 createProviderFromConfig 中设置
                val models = providerModelMap[provider.name]
                if (models != null && selectedModelName.isBlank()) {
                    provider.modelName = models[0]
                }

                _currentProvider.value = provider
                _currentProviderName.value = "${provider.name} (${provider.modelName})"
                _lastError.value = null

                val result = try {
                    provider.chatCompletion(messages)
                } catch (e: Exception) {
                    Result.failure(e)
                }

                val validated = validateResponse(result)
                if (validated.isSuccess) {
                    if (index > 0) _switchCount.value += 1
                    return@withContext validated
                }
                _lastError.value = "${provider.name} (${provider.modelName}) 失败，切换下一个接口..."
            }

            Result.failure(Exception("所有 AI 接口均不可用。请检查网络连接或稍后重试。"))
        }
    }

    suspend fun generateText(prompt: String): Result<String> {
        return withContext(Dispatchers.IO) {
            if (manualProviderIndex >= 0) {
                val provider = getProviderByIndex(manualProviderIndex)
                if (provider != null) {
                    _currentProvider.value = provider
                    _currentProviderName.value = "${provider.name} (${provider.modelName})"
                    _lastError.value = null
                    return@withContext validateResponse(provider.generateText(prompt))
                }
            }

            for ((index, provider) in allProviders.withIndex()) {
                if (provider is OpenAICompatibleProvider && provider.baseUrl.isBlank()) continue

                val models = providerModelMap[provider.name]
                if (models != null && selectedModelName.isBlank()) {
                    provider.modelName = models[0]
                }

                _currentProvider.value = provider
                _currentProviderName.value = "${provider.name} (${provider.modelName})"
                _lastError.value = null

                val result = try {
                    provider.generateText(prompt)
                } catch (e: Exception) {
                    Result.failure(e)
                }

                val validated = validateResponse(result)
                if (validated.isSuccess) {
                    if (index > 0) _switchCount.value += 1
                    return@withContext validated
                }
            }
            Result.failure(Exception("所有 AI 接口均不可用。"))
        }
    }

    private fun validateResponse(result: Result<String>): Result<String> {
        if (result.isFailure) return result
        val content = result.getOrNull() ?: ""
        if (isErrorResponse(content)) {
            return Result.failure(IOException("返回错误响应"))
        }
        return result
    }

    private fun isErrorResponse(content: String): Boolean {
        val trimmed = content.trim()
        return when {
            trimmed.isBlank() -> true
            trimmed.startsWith("{\"error\"") -> true
            trimmed.startsWith("{\"detail\"") -> true
            trimmed.startsWith("Error:") -> true
            trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") -> true
            trimmed.contains("\"status\":404") || trimmed.contains("\"status\":429") ||
                trimmed.contains("\"status\":500") || trimmed.contains("\"status\":503") -> true
            else -> false
        }
    }

    suspend fun healthCheck() {
        withContext(Dispatchers.IO) {
            for (provider in allProviders) {
                if (provider is OpenAICompatibleProvider && provider.baseUrl.isBlank()) continue
                provider.isAvailable = provider.healthCheck()
            }
            refreshProviderInfo()
        }
    }
}
