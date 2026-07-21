package com.apkanalyzer.ai

import com.apkanalyzer.mcp.McpClient
import com.apkanalyzer.model.McpConstants
import com.apkanalyzer.model.McpNextAction
import com.apkanalyzer.model.McpTool
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * AI Agent 引擎 - 参考 RikkaHub 的 GenerationHandler 架构改进
 *
 * 核心改进：
 * 1. 最大迭代次数限制（防止无限循环）
 * 2. 只使用已启用工具（参考 RikkaHub 的工具过滤逻辑）
 * 3. 工具结果截断（参考 RikkaHub 的 MAX_TOOL_OUTPUT_CHARS）
 * 4. 正确的 tool role 对话历史（而非 user 角色伪装）
 * 5. 异常容错（单次迭代失败不中断整个流程）
 * 6. MCP 连接状态感知
 * 7. 进度追踪（当前迭代次数）
 */
class McpAgentEngine(
    private val multiAiClient: MultiAiClient,
    private val mcpClient: McpClient
) {

    private val gson = Gson()

    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _currentStep = MutableStateFlow<String>("")
    val currentStep: StateFlow<String> = _currentStep

    private var conversationHistory = mutableListOf<MutableMap<String, String>>()
    private var mcpToolsSnapshot: List<McpTool> = emptyList()
    @Volatile
    private var cancelled = false
    /** 记住当前打开的 APK 文件名，用于 workspace 失效时自动恢复 */
    @Volatile
    private var lastOpenedApk: String = ""
    /** 记住当前 workspaceId，用于自动替换 */
    @Volatile
    private var currentWorkspaceId: String = ""

    /** 取消当前正在进行的分析 */
    fun cancel() {
        cancelled = true
        _isProcessing.value = false
        _currentStep.value = ""
        addMessage(AgentMessage(
            role = "assistant",
            content = "已停止分析。",
            isError = true
        ))
    }

    /**
     * 处理用户自然语言请求
     */
    suspend fun processRequest(userMessage: String, workDir: String = "/sdcard/") {
        cancelled = false
        _isProcessing.value = true
        var iteration = 0
        var emptyResponseCount = 0
        try {
            addMessage(AgentMessage(role = "user", content = userMessage))
            conversationHistory.add(mutableMapOf("role" to "user", "content" to userMessage))

            // 获取最新的已启用 MCP 工具列表（参考 RikkaHub 的 getAllAvailableTools 过滤）
            mcpToolsSnapshot = mcpClient.getEnabledTools()
            val hasMcpTools = mcpToolsSnapshot.isNotEmpty()

            // 构建系统提示词
            val systemPrompt = if (hasMcpTools) {
                buildSystemPrompt(workDir)
            } else {
                buildPureChatPrompt()
            }

            // AI 决策循环（带最大迭代次数限制）
            while (iteration < McpConstants.MAX_AGENT_ITERATIONS) {
                iteration++

                // 检查是否被取消
                if (cancelled) {
                    _currentStep.value = ""
                    return
                }

                // 检查 MCP 连接状态
                val mcpState = mcpClient.connectionState.value
                if (mcpToolsSnapshot.isNotEmpty() &&
                    mcpState !is com.apkanalyzer.model.McpConnectionState.Connected
                ) {
                    addMessage(AgentMessage(
                        role = "assistant",
                        content = "MCP 连接已断开，无法继续调用工具。请重新连接 MCP 服务器后重试。",
                        isError = true
                    ))
                    break
                }

                _currentStep.value = "AI 思考中... (步骤 $iteration/${McpConstants.MAX_AGENT_ITERATIONS})"

                // 1. 让 AI 分析当前情况并决定下一步
                val aiResponse = try {
                    callAI(systemPrompt)
                } catch (e: Exception) {
                    addMessage(AgentMessage(
                        role = "assistant",
                        content = "AI 调用异常: ${e.message}",
                        isError = true
                    ))
                    break
                }

                if (aiResponse == null || aiResponse.isBlank()) {
                    emptyResponseCount++
                    if (emptyResponseCount >= 5) {
                        addMessage(AgentMessage(
                            role = "assistant",
                            content = "AI 连续多次未返回有效内容，请检查 AI 接口配置或更换模型。",
                            isError = true
                        ))
                        break
                    }
                    conversationHistory.add(mutableMapOf("role" to "assistant", "content" to "(思考中...)"))
                    continue
                }
                emptyResponseCount = 0  // 有内容，重置计数

                // 2. 检查 AI 是否返回了工具调用指令
                val toolCall = if (hasMcpTools) parseToolCall(aiResponse) else null

                if (toolCall == null) {
                    // 判断是"中间思考/格式错误"还是"真正的最终回复"
                    if (isThinkingOrFormatError(aiResponse)) {
                        conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                        // 注入格式纠正提示
                        conversationHistory.add(mutableMapOf("role" to "user", "content" to
                            "格式错误！请只输出纯 JSON：{\"tool\":\"工具名\",\"arguments\":{\"参数名\":\"参数值\"}}\n" +
                            "工具名必须是上方可用工具列表中的一个，如 mt_apk_search、mt_apk_resource_read 等。\n" +
                            "不要输出 role、reasoning、tool_calls、id、type、function 等字段。\n" +
                            "如果要结束分析，直接用中文回复即可。"))
                        continue
                    }
                    // AI 没有请求调用工具，说明分析完成
                    addMessage(AgentMessage(
                        role = "assistant",
                        content = formatFinalResponse(aiResponse)
                    ))
                    conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                    break
                }

                // 校验工具名并智能纠正
                val validToolNames = mcpToolsSnapshot.map { it.name }.toSet()
                val cleanedName = toolCall.name
                    .trimEnd('.', ' ', '…', '。', '、', ',')
                    .removePrefix("tool.")
                    .removePrefix("tools.")
                val matchedName = when {
                    cleanedName in validToolNames -> cleanedName
                    toolCall.name in validToolNames -> toolCall.name
                    // 模糊匹配：去掉下划线后比较，或包含匹配
                    else -> validToolNames.find { vn ->
                        vn.equals(cleanedName, ignoreCase = true) ||
                        vn.replace("_", "").equals(cleanedName.replace("_", ""), ignoreCase = true) ||
                        vn.contains(cleanedName) || cleanedName.contains(vn)
                    }
                }

                if (matchedName == null) {
                    conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                    conversationHistory.add(mutableMapOf("role" to "user", "content" to
                        "工具名 \"${toolCall.name}\" 不存在！请从下方可用工具列表中选择一个正确的工具名。\n" +
                        "可用工具: ${validToolNames.joinToString(", ")}\n" +
                        "正确格式: {\"tool\":\"mt_apk_search\",\"arguments\":{...}}"))
                    continue
                }

                // 使用纠正后的工具名
                val actualToolName = matchedName

                // 3. 参数合理性校验：检查是否完全没有已知参数
                val toolDef = mcpToolsSnapshot.find { it.name == actualToolName }
                val knownParams = toolDef?.inputSchema?.required ?: emptyList()
                val hasAnyKnownParam = toolCall.arguments.keys.any { it in knownParams }
                val hasOnlyUnknownParams = knownParams.isNotEmpty() && !hasAnyKnownParam
                if (hasOnlyUnknownParams) {
                    conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                    val paramsHint = knownParams.joinToString(", ")
                    conversationHistory.add(mutableMapOf("role" to "user", "content" to
                        "参数错误！${actualToolName} 的必填参数是: $paramsHint\n" +
                        "你传的参数 ${toolCall.arguments.keys} 完全不对。\n" +
                        "正确格式: {\"tool\":\"$actualToolName\",\"arguments\":{\"$knownParams[0]\":\"值\",\"${knownParams.getOrElse(1) { "" }}\":\"值\"}}"))
                    continue
                }

                // 4. 执行工具调用
                _currentStep.value = "调用工具: ${toolCall.name} (步骤 $iteration/${McpConstants.MAX_AGENT_ITERATIONS})"

                addMessage(AgentMessage(
                    role = "system",
                    content = "[工具调用] ${toolCall.name}(${gson.toJson(toolCall.arguments)})"
                ))

                var toolResult: Result<com.apkanalyzer.model.McpToolCallResult> = try {
                    mcpClient.callTool(actualToolName, toolCall.arguments)
                } catch (e: Exception) {
                    Result.failure(e)
                }

                // 自动修复常见参数错误后重试
                val resultTextRaw = toolResult.getOrNull()?.content?.firstOrNull()?.text
                if (resultTextRaw != null) {
                    var retryArgs: Map<String, Any>? = null
                    val args = toolCall.arguments.toMutableMap()

                    if (resultTextRaw.contains("temporary is invalid when reopening") && args["temporary"] == true) {
                        args["temporary"] = false
                        retryArgs = args
                    }
                    // workspace 路径应该用 mt_apk_open 打开已有的
                    // 如果 AI 传了 mt://workspace/xxx 但报错，尝试去掉前缀只留文件名
                    if (resultTextRaw.contains("INVALID_ARGUMENT") &&
                        args["path"] is String &&
                        (args["path"] as String).startsWith("mt://workspace/")) {
                        val fileName = (args["path"] as String).substringAfterLast("/")
                        if (fileName.endsWith(".apk", ignoreCase = true)) {
                            args["apk"] = fileName
                            args.remove("path")
                            if (!args.containsKey("temporary")) args["temporary"] = false
                            retryArgs = args
                        }
                    }

                    if (retryArgs != null) {
                        toolResult = try {
                            mcpClient.callTool(actualToolName, retryArgs)
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }

                val resultText = toolResult.fold(
                    onSuccess = { r ->
                        if (r.isError) {
                            "[工具报错] " + r.content.joinToString("\n") {
                                truncateToolOutput(it.text ?: it.data ?: "(无内容)")
                            }
                        } else {
                            val text = r.content.joinToString("\n") {
                                truncateToolOutput(it.text ?: it.data ?: "(无内容)")
                            }
                            // 记录成功打开的 APK 和 workspaceId
                            if (actualToolName == "mt_apk_open") {
                                val apkArg = toolCall.arguments["apk"]?.toString()
                                if (!apkArg.isNullOrBlank()) lastOpenedApk = apkArg
                                // 从返回结果中提取 workspaceId
                                val wsMatch = Regex(""""workspaceId"\s*:\s*"([^"]+)""").find(text)
                                if (wsMatch != null) currentWorkspaceId = wsMatch.groupValues[1]
                            }
                            text
                        }
                    },
                    onFailure = { "调用失败: ${it.message}" }
                )

                addMessage(AgentMessage(
                    role = "system",
                    content = "[工具结果] $resultText"
                ))

                // 5. 处理 nextActions：MCP 服务器返回的建议下一步操作
                // 来源1: MCP 协议顶层 nextActions 字段
                // 来源2: MT Manager 等服务器将 nextActions 嵌在 content[0].text 的 JSON 中
                val topLevelActions = toolResult.getOrNull()?.nextActions?.takeIf { it.isNotEmpty() }
                val embeddedActions = extractNextActionsFromText(toolResult)
                val allNextActions = topLevelActions.orEmpty() + embeddedActions

                if (allNextActions.isNotEmpty()) {
                    val autoFollowed = mutableListOf<String>()
                    for (action in allNextActions) {
                        if (cancelled || iteration >= McpConstants.MAX_AGENT_ITERATIONS) break
                        // 不自动执行 close/destroy 操作，让 AI 自己决定
                        if (action.tool.contains("close", ignoreCase = true) ||
                            action.tool.contains("destroy", ignoreCase = true)) {
                            continue
                        }

                        val desc = action.description?.let { " - $it" } ?: ""
                        addMessage(AgentMessage(
                            role = "system",
                            content = "[自动跟进] ${action.tool}$desc"
                        ))
                        autoFollowed.add(action.tool)

                        val followResult: Result<com.apkanalyzer.model.McpToolCallResult> = try {
                            mcpClient.callTool(action.tool, action.arguments)
                        } catch (e: Exception) {
                            Result.failure(e)
                        }

                        val followText = when {
                            followResult.isFailure -> "调用失败: ${followResult.exceptionOrNull()?.message}"
                            else -> {
                                val r = followResult.getOrNull()!!
                                if (r.isError) {
                                    "[工具报错] " + r.content.joinToString("\n") {
                                        truncateToolOutput(it.text ?: it.data ?: "(无内容)")
                                    }
                                } else {
                                    r.content.joinToString("\n") {
                                        truncateToolOutput(it.text ?: it.data ?: "(无内容)")
                                    }
                                }
                            }
                        }

                        addMessage(AgentMessage(
                            role = "system",
                            content = "[跟进结果] $followText"
                        ))

                        // 将跟进的工具调用和结果也加入对话历史，让 AI 知道发生了什么
                        conversationHistory.add(mutableMapOf("role" to "assistant", "content" to
                            """{"tool":"${action.tool}","arguments":${gson.toJson(action.arguments)}}"""))
                        conversationHistory.add(mutableMapOf("role" to "user", "content" to
                            "[自动跟进 ${action.tool} 返回结果]:\n$followText\n\n" +
                            "提示：如果要继续分析，请输出 {\"tool\":\"工具名\",\"arguments\":{...}}；如果分析完成，请直接用中文回复。"))
                    }

                    // 反馈给 AI 的结果要包含 nextActions 的跟进情况
                    val nextActionsSummary = if (autoFollowed.isNotEmpty()) {
                        "\n\n[系统已自动执行建议操作: ${autoFollowed.joinToString(", ")}]"
                    } else ""
                    conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                    conversationHistory.add(mutableMapOf("role" to "user", "content" to
                        "[工具 ${toolCall.name} 返回结果]:\n$resultText$nextActionsSummary\n\n" +
                        "提示：如果要继续分析，请输出 {\"tool\":\"工具名\",\"arguments\":{...}}；如果分析完成，请直接用中文回复。"))
                } else {
                    // 4. 将工具调用和结果反馈给 AI 继续分析
                    // 参考 RikkaHub：使用 assistant 角色记录工具调用，user 角色传递结果
                    val isError = toolResult.getOrNull()?.isError == true
                    var autoRecovered = false
                    if (isError && resultText.contains("WORKSPACE_NOT_FOUND") && lastOpenedApk.isNotBlank()) {
                        addMessage(AgentMessage(role = "system", content = "[自动恢复] 工作区已失效，正在重新打开 APK..."))
                        val reopenResult = try {
                            mcpClient.callTool("mt_apk_open", mapOf("apk" to lastOpenedApk, "temporary" to true))
                        } catch (_: Exception) { null }
                        val reopenText = reopenResult?.getOrNull()?.content?.firstOrNull()?.text
                        if (reopenText != null) {
                            val newWsMatch = Regex(""""workspaceId"\s*:\s*"([^"]+)"""").find(reopenText)
                            if (newWsMatch != null) {
                                val newWsId = newWsMatch.groupValues[1]
                                currentWorkspaceId = newWsId
                                addMessage(AgentMessage(role = "system", content = "[自动恢复] 新 workspaceId: $newWsId"))
                                conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                                conversationHistory.add(mutableMapOf("role" to "user", "content" to
                                    "[系统自动恢复了工作区]\n$reopenText\n\n" +
                                    "工作区已自动恢复！新的 workspaceId 是 \"$newWsId\"，请用这个新的 workspaceId 继续刚才的操作。**不要放弃！**"))
                                autoRecovered = true
                            }
                        }
                    }
                    if (autoRecovered) continue
                    if (isError && resultText.contains("WORKSPACE_NOT_FOUND")) continue

                    // 自动恢复 editSession
                    if (isError && (resultText.contains("EDIT_SESSION_NOT_FOUND") || resultText.contains("INVALID_EDIT_SESSION"))
                        && currentWorkspaceId.isNotBlank()) {
                        addMessage(AgentMessage(role = "system", content = "[自动恢复] 编辑会话已失效，正在重新打开..."))
                        val reeditResult = try {
                            mcpClient.callTool("mt_apk_edit_open", mapOf("workspaceId" to currentWorkspaceId))
                        } catch (_: Exception) { null }
                        val reeditText = reeditResult?.getOrNull()?.content?.firstOrNull()?.text
                        if (reeditText != null) {
                            val newEsMatch = Regex(""""editSessionId"\s*:\s*"([^"]+)"""").find(reeditText)
                            if (newEsMatch != null) {
                                val newEsId = newEsMatch.groupValues[1]
                                addMessage(AgentMessage(role = "system", content = "[自动恢复] 新 editSessionId: $newEsId"))
                                conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                                conversationHistory.add(mutableMapOf("role" to "user", "content" to
                                    "[系统自动恢复了编辑会话]\n$reeditText\n\n" +
                                    "编辑会话已自动恢复！新的 editSessionId 是 \"$newEsId\"，请用这个新的 editSessionId 继续刚才的编辑操作。**不要放弃！**"))
                                continue
                            }
                        }
                    }

                    val hint = if (isError) {
                        when {
                            resultText.contains("TARGET_VERSION_MISMATCH") || resultText.contains("STALE_TARGET_VERSION") ->
                                "版本不匹配！请重新调用 mt_apk_read_text 或 mt_apk_resource_read 获取最新的 targetVersion。**不要放弃！**"
                            resultText.contains("TEXT_MATCH_AMBIGUOUS") ->
                                "匹配到多处相同文本！请用更长的上下文作为 matchText。**不要放弃！**"
                            resultText.contains("CURRENT_APK_NOT_AVAILABLE") ->
                                "没有当前打开的 APK！请先调用 mt_apk_list_available_apks 然后调用 mt_apk_open 打开。**不要放弃！**"
                            resultText.contains("INVALID_VALUE_XML") || resultText.contains("attr value delimiter missing") ->
                                "valueXml 格式错误！XML 属性值必须用双引号包裹，例如 <string name=\"app_name\">新名称</string>。请检查 valueXml 中每个属性值是否都有引号，然后重试。**不要放弃！**"
                            else ->
                                "工具报错了！仔细阅读错误信息，根据提示换一种方式继续。**不要放弃！**"
                        }
                    } else {
                        "如果要继续分析，请输出 {\"tool\":\"工具名\",\"arguments\":{...}}；如果分析完成，请直接用中文回复。"
                    }
                    conversationHistory.add(mutableMapOf("role" to "assistant", "content" to aiResponse))
                    conversationHistory.add(mutableMapOf("role" to "user", "content" to
                        "[工具 ${toolCall.name} 返回结果]:\n$resultText\n\n$hint"))
                }
            }

            // 检查是否因为达到最大迭代次数而退出
            if (iteration >= McpConstants.MAX_AGENT_ITERATIONS && !cancelled) {
                addMessage(AgentMessage(
                    role = "assistant",
                    content = "已达到最大分析步骤 (${McpConstants.MAX_AGENT_ITERATIONS})。如需继续，请发送新的请求。",
                    isError = true
                ))
            }
        } finally {
            _isProcessing.value = false
            _currentStep.value = ""
        }
    }

    /**
     * 从工具返回的文本内容中提取 nextActions
     * MT Manager MCP 等服务器会将 nextActions 嵌在 content[0].text 的 JSON 中
     */
    private fun extractNextActionsFromText(toolResult: Result<com.apkanalyzer.model.McpToolCallResult>): List<McpNextAction> {
        return try {
            val result = toolResult.getOrNull() ?: return emptyList()
            for (content in result.content) {
                val text = content.text ?: continue
                // 在文本中查找 nextActions JSON 数组
                val jsonStart = text.indexOf("\"nextActions\"")
                if (jsonStart == -1) continue
                // 找到 nextActions 后面的数组
                val bracketStart = text.indexOf('[', jsonStart)
                if (bracketStart == -1) continue
                var depth = 0
                var bracketEnd = bracketStart
                for (i in bracketStart until text.length) {
                    when (text[i]) {
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) { bracketEnd = i + 1; break }
                        }
                    }
                }
                if (depth != 0) continue
                val arrayStr = text.substring(bracketStart, bracketEnd)
                val array = JsonParser.parseString(arrayStr)
                if (!array.isJsonArray) continue
                val actions = mutableListOf<McpNextAction>()
                for (elem in array.asJsonArray) {
                    if (!elem.isJsonObject) continue
                    val obj = elem.asJsonObject
                    val tool = obj.get("tool")?.asString ?: continue
                    val purpose = obj.get("purpose")?.asString ?: ""
                    val description = obj.get("description")?.asString
                    val argsElement = obj.get("arguments")
                    val args = if (argsElement != null && argsElement.isJsonObject) {
                        parseValueToAny(argsElement) as? Map<String, Any> ?: emptyMap()
                    } else emptyMap()
                    actions.add(McpNextAction(tool, purpose, description, args))
                }
                if (actions.isNotEmpty()) return actions
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 截断工具输出（参考 RikkaHub 的 MAX_TOOL_OUTPUT_CHARS）
     */
    private fun truncateToolOutput(text: String): String {
        return if (text.length > McpConstants.TOOL_OUTPUT_PREVIEW_CHARS) {
            text.take(McpConstants.TOOL_OUTPUT_PREVIEW_CHARS) +
                "\n\n[... 输出已截断，共 ${text.length} 字符 ...]"
        } else {
            text
        }
    }

    private fun buildPureChatPrompt(): String {
        return """你是一个专业的 Android APK 逆向分析助手和编程问答助手。

## 重要规则：
- 你的所有回复都必须使用**简体中文**
- 回答用户关于 APK 逆向分析、Android 开发、编程技术等问题
- 如果用户请求需要操作文件，告知用户需要先连接 MCP 服务器才能使用文件操作功能
- 回答应简洁、准确、有条理
- 不要输出 JSON 格式的工具调用指令"""
    }

    private fun buildSystemPrompt(workDir: String): String {
        // 检测是否为 MT Manager 的工具链
        val isMtManager = mcpToolsSnapshot.any { it.name.startsWith("mt_apk_") }
        val mtWorkflowGuide = if (isMtManager) buildMtManagerWorkflowGuide() else ""

        val toolsDescription = mcpToolsSnapshot.joinToString("\n") { tool ->
            val requiredList = tool.inputSchema?.required ?: emptyList()
            val schema = tool.inputSchema?.let { s ->
                val props = s.properties?.let { p ->
                    if (p.isJsonObject) {
                        p.asJsonObject.entrySet().joinToString(", ") { (k, v) ->
                            val type = v.asJsonObject.get("type")?.asString ?: "string"
                            val isReq = k in requiredList
                            "$k($type${if (isReq) " 必填" else " 可选"})"
                        }
                    } else "见完整schema"
                } ?: "无参数"
                "  参数: {$props}\n  必填参数: ${requiredList.ifEmpty { "无" }}"
            } ?: "  参数: 未知"

            """- ${tool.name}: ${tool.description ?: "无描述"}
$schema"""
        }

        return """你是一个专业的 Android APK 逆向分析助手。你可以调用 MCP 工具来分析、解包、读取和编辑 APK 文件。

## 重要规则：
- 你的所有回复都必须使用**简体中文**，不能用英文或JSON格式回复
- 调用工具时，**只输出一行纯 JSON**，格式如下：{"tool": "工具名称", "arguments": {"参数名": "参数值"}}
- **绝对不要**在 JSON 前后输出任何其他文字、不要换行
- **绝对不要**输出 role、reasoning、tool_calls、id、type、function 等元数据字段
- **绝对不要**使用 OpenAI function calling 格式（{"role":"assistant","tool_calls":[...]}）
- 分析完成时，直接用**简体中文**自然语言总结结果，不要输出 JSON
- 对工具返回的结果，请用中文提炼关键信息，不要直接罗列原始 JSON

## 参数使用规则（极其重要）：
- **只传必填参数**，可选参数不要传，除非用户明确要求或前一步操作返回了该值
- **绝对不要编造参数值**！workspaceId 必须从 mt_apk_open 的返回值中获取，editSessionId 必须从 mt_apk_edit_open 的返回值中获取，targetVersion 必须从 mt_apk_read_text/edit 的返回值中获取
- **绝对不要编造不存在的参数名**，严格按照上方工具定义中的参数名使用
- 如果工具定义中没有某个参数，就不要在 arguments 中包含它
- editSessionId 在只读分析时传空字符串 ""，在编辑操作时传 mt_apk_edit_open 返回的 ID

## 工具调用策略（极其重要）：
- **绝对不要因为一次工具调用失败就放弃！** 尝试其他工具、换一种方式、或者根据错误信息中的 nextActions 继续操作
- 工具报错时，仔细阅读 error.message 和 nextActions，它们会告诉你正确的做法
- 如果一个工具不行，尝试换另一个工具完成同样的目标
- 如果不知道该怎么操作，先调用 list 类工具查看可用资源，再决定下一步
- **多步骤任务要拆分执行**：先获取信息（list/read），再执行操作（edit/write），最后保存/确认
- 完成用户的需求是你唯一的使命，**永远不要说"无法实现"**，总有办法可以做到
$mtWorkflowGuide

## 当前可用工具（${mcpToolsSnapshot.size} 个）：
$toolsDescription

## 工作目录
$workDir

## 工作流程：
1. 用户会用自然语言描述需求
2. 根据需求选择合适的工具进行操作
3. 调用工具时，只输出以下格式的 JSON（不要输出其他任何内容）：
   {"tool": "工具名称", "arguments": {"参数名": "参数值"}}
4. 工具调用完成后，系统会将结果反馈给你，你可以继续分析或调用更多工具
5. 当所有分析完成时，用**简体中文**自然语言总结结果，不要输出 JSON"""
    }

    /**
     * 构建 MT Manager MCP 工具链的专用工作流指南
     * 基于 RikkaHub 实际调用截图和 MT Manager SKILL.md 文档
     */
    private fun buildMtManagerWorkflowGuide(): String {
        return """

## MT Manager APK 操作工作流（严格遵守，这是 RikkaHub 验证过的正确流程）：

### 只读分析流程：
1. mt_apk_list_available_apks(prefix="", limit=50) → 查看可用的 APK
2. mt_apk_open(apk="APK文件名", temporary=false) → 获得 workspaceId（注意参数名是 apk 不是 path）
3. 后续所有操作都使用这个 workspaceId，editSessionId 传 ""
4. mt_apk_search / mt_apk_list / mt_apk_read_text 等 → 分析内容
5. 需要翻页时使用 mt_apk_continue(nextCursor="上一次返回的游标")
6. 分析完成后 mt_apk_close(workspaceId="你的workspaceId")

### 修改资源值（如应用名称、字符串等）的流程（推荐）：
1. mt_apk_list_available_apks(prefix="", limit=50) → 找到 APK
2. mt_apk_open(apk="APK文件名", temporary=true) → 获得 workspaceId
3. mt_apk_search(workspaceId, editSessionId="", target="resource_table_names", query="要找的资源名如app_name") → 找到资源 locator（如 resource:0x7f0f001f）
4. mt_apk_resource_read(workspaceId, editSessionId="", reads=[{locator:"上一步找到的locator", variant:"default"}]) → 获得当前值和 targetVersion
5. mt_apk_edit_open(workspaceId="第2步获得的workspaceId") → 获得 editSessionId
6. mt_apk_edit_resource(workspaceId, editSessionId, edits=[{locator, variant:"default", targetVersion:"第4步获得的值", valueXml:"修改后的完整XML"}]) → 修改资源
7. mt_apk_edit_check(workspaceId, editSessionId, runBuildChecks=true) → 验证构建可行性
8. mt_apk_build(workspaceId, sessionId="第5步获得的editSessionId", overwrite=true) → 构建并签名 APK
9. mt_apk_close(workspaceId) → 释放工作区
注意：mt_apk_build 的参数名是 sessionId（不是 editSessionId）！

### 修改 Smali/AXML 文件内容的流程：
1. mt_apk_open(apk="APK文件名", temporary=true) → workspaceId
2. mt_apk_edit_open(workspaceId) → editSessionId
3. mt_apk_read_text(workspaceId, editSessionId, locator="要修改的文件") → 获得内容和 targetVersion
4. mt_apk_edit_text(workspaceId, editSessionId, locator, targetVersion, edits=[{mode, matchText, writeText}]) → 执行修改
5. mt_apk_edit_check(workspaceId, editSessionId, runBuildChecks=true)
6. mt_apk_build(workspaceId, sessionId="editSessionId", overwrite=true)
7. mt_apk_close(workspaceId)

### Locator 格式：
- AndroidManifest.xml → "axml:AndroidManifest.xml"
- Smali 类 → "dex_class:Lcom/example/MyClass;"
- Smali 方法 → "dex_method:Lcom/example/MyClass;->methodName()V"
- ZIP 内文件 → "zip_entry:assets/config.json"
- 资源 ID → "resource:0x7f0f001f"（用 mt_apk_search 搜索 resource_table_names 获得）

### 编辑文本模式 (mt_apk_edit_text 的 edits[].mode)：
- write_target: 替换整个文件内容
- replace_match: 替换匹配的文本（matchText 必须唯一匹配，只出现一次）
- insert_before_match: 在匹配文本前插入
- insert_after_match: 在匹配文本后插入
- delete_target: 删除整个文件

### 修改资源的 valueXml 格式（极其重要，必须严格遵守）：
- 字符串资源：valueXml 必须是 <string name="app_name">新名称</string>
- 注意：XML 标签内的值就是最终显示的文本，不要写错字符！
- 示例：要改成 "GreenDexXX"，valueXml 就是 <string name="app_name">GreenDexXX</string>
- 不要在标签外添加额外内容，不要转义中文字符

### 关键注意事项（极其重要）：
- workspaceId 和 editSessionId 必须从工具返回的 JSON 中提取，**绝对不能自己编造随机字符串**
- targetVersion 必须从 mt_apk_read_text 或 mt_apk_resource_read 返回值中提取，**绝对不能编造**
- mt_apk_open 的第一个参数名是 **apk**（不是 path），值为文件名如 "GreenDexAL.apk"
- mt_apk_build 的参数名是 **sessionId**（不是 editSessionId）
- mt_apk_search 找 app_name 时用 target="resource_table_names", query="app_name"
- mt_apk_resource_read 的 reads 是数组：reads=[{locator:"resource:0x7f0f001f", variant:"default"}]
- mt_apk_edit_resource 的 edits 是数组，每项必须包含 locator, variant, targetVersion, valueXml
- 查看类结构用 mt_apk_dex_outline_class（不是 mt_apk_outline_class）
- 查看方法/字段交叉引用用 mt_apk_dex_xref（不是 mt_apk_xref_dex）
- 查看资源交叉引用用 mt_apk_resource_xref（不是 mt_apk_xref_resource）
- 读取原始字节用 mt_apk_read_bytes（不是 mt_apk_read_zip_bytes）
- 读取资源值用 mt_apk_resource_read（不是 mt_apk_read_resource）
- matchText 必须在目标文件中恰好出现一次，多次匹配会报 TEXT_MATCH_AMBIGUOUS 错误
- 修改资源时优先用 mt_apk_edit_resource（比 edit_text 更可靠）
- 修改 Smali 代码时用 mt_apk_edit_text"""
    }

    private suspend fun callAI(systemPrompt: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val messages = mutableListOf<Map<String, String>>()
                messages.add(mapOf("role" to "system", "content" to systemPrompt))
                val historySlice = conversationHistory.takeLast(40)
                for (entry in historySlice) {
                    val role = entry["role"] ?: "user"
                    val content = entry["content"] ?: ""
                    messages.add(mapOf("role" to role, "content" to content))
                }

                val providerName = multiAiClient.currentProviderName.value
                _currentStep.value = if (providerName.isNotBlank()) "AI 思考中 ($providerName)..." else "AI 思考中..."

                val result = multiAiClient.chatCompletion(messages)
                result.getOrNull()?.filterPollinationsAd()
            } catch (e: Exception) {
                throw e
            }
        }
    }

    /** 外层包装器可能携带的元数据键名 */
    private val WRAPPER_KEYS = setOf("tool", "name", "arguments", "tool_calls", "function", "role", "type", "id")

    data class ToolCall(val name: String, val arguments: Map<String, Any>)

    private fun parseToolCall(response: String): ToolCall? {
        return try {
            val cleaned = cleanResponse(response)
            val jsonStr = extractToolJson(cleaned) ?: return null
            val json = JsonParser.parseString(jsonStr)

            if (!json.isJsonObject) return null
            val jsonObj = json.asJsonObject

            var toolName: String? = null
            var rawArgs: com.google.gson.JsonElement? = null

            // 格式 1: {"tool": "xxx", "arguments": {...}}
            if (jsonObj.has("tool")) {
                val toolElem = jsonObj.get("tool")
                if (toolElem != null) {
                    if (toolElem.isJsonPrimitive && toolElem.asJsonPrimitive.isString) {
                        toolName = toolElem.asString
                    } else if (toolElem.isJsonObject) {
                        // DeepSeek 等可能返回 {"tool": {"run": "xxx", ...}} 或 {"tool": {"name": "xxx", ...}}
                        val toolObj = toolElem.asJsonObject
                        toolName = toolObj.get("run")?.asString
                            ?: toolObj.get("name")?.asString
                        // 如果 arguments 不在外层，可能在 tool 对象内
                        if (rawArgs == null) {
                            rawArgs = toolObj.get("arguments") ?: toolObj.get("input")
                        }
                    }
                    rawArgs = rawArgs ?: jsonObj.get("arguments") ?: jsonObj.get("input")
                }
            }
            // 格式 2: OpenAI function calling {"tool_calls": [{"function": {...}}]}
            else if (jsonObj.has("tool_calls")) {
                val toolCalls = jsonObj.getAsJsonArray("tool_calls")
                if (toolCalls.size() > 0) {
                    val firstCall = toolCalls[0].asJsonObject
                    val functionObj = firstCall.getAsJsonObject("function")
                    toolName = functionObj.get("name")?.asString
                    rawArgs = functionObj.get("arguments")
                }
            }
            // 格式 3: {"name": "xxx", "arguments": {...}}
            else if (jsonObj.has("name") && jsonObj.has("arguments")) {
                toolName = jsonObj.get("name")?.asString
                rawArgs = jsonObj.get("arguments")
            }

            if (toolName == null || rawArgs == null) return null

            // 处理 arguments 为 JSON 字符串的情况（部分 AI 模型会把参数序列化为字符串）
            var resolvedArgs = rawArgs
            if (resolvedArgs != null && resolvedArgs.isJsonPrimitive && resolvedArgs.asJsonPrimitive.isString) {
                resolvedArgs = try {
                    JsonParser.parseString(resolvedArgs.asString)
                } catch (_: Exception) {
                    return null
                }
            }

            if (resolvedArgs == null || !resolvedArgs.isJsonObject) return null
            val argsMap = parseValueToAny(resolvedArgs) as? Map<String, Any> ?: return null

            // 后置校验：如果 arguments 里包含外层包装器键名，说明解析未正确剥离
            // 例如 {"tool":"xxx","arguments":{...}} 被整体当作了 arguments
            val contaminated = argsMap.keys.any { it in WRAPPER_KEYS }
            if (contaminated) {
                // 尝试从嵌套结构中提取真正的 arguments
                val realArgs = when {
                    argsMap.containsKey("arguments") && argsMap["arguments"] is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        argsMap["arguments"] as Map<String, Any>
                    }
                    // 剥离所有包装器键，保留业务参数
                    else -> argsMap.filterKeys { it !in WRAPPER_KEYS }.takeIf { it.isNotEmpty() }
                }
                if (realArgs != null) {
                    return ToolCall(toolName, realArgs)
                }
                // 无法恢复，放弃此次调用
                return null
            }

            ToolCall(toolName, argsMap)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清理 AI 响应：处理 role/reasoning 等元数据包装
     */
    private fun cleanResponse(response: String): String {
        var cleaned = response.trim()

        // 如果包含 role + tool_calls，直接返回让 parseToolCall 处理
        if (cleaned.startsWith("{") && cleaned.contains("\"role\"") && cleaned.contains("\"tool_calls\"")) {
            return cleaned
        }

        // 去掉 markdown 代码块标记
        cleaned = cleaned
            .replace(Regex("^```(?:json)?\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .trim()

        return cleaned
    }

    /**
     * 从文本中提取工具调用相关的 JSON
     */
    private fun extractToolJson(text: String): String? {
        // 策略1: 直接尝试解析整个文本为 JSON
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                JsonParser.parseString(text)
                return text
            } catch (_: Exception) { }
        }

        // 策略2: 查找包含 tool/tool_calls/name 的 JSON 块
        val toolKeywordIndices = mutableListOf<Int>()
        for (keyword in listOf("\"tool\"", "\"tool_calls\"", "\"name\"")) {
            var idx = text.indexOf(keyword)
            while (idx != -1) {
                toolKeywordIndices.add(idx)
                idx = text.indexOf(keyword, idx + 1)
            }
        }

        if (toolKeywordIndices.isEmpty()) return null

        for (startSearch in toolKeywordIndices.sortedDescending()) {
            val braceIdx = text.lastIndexOf('{', startSearch)
            if (braceIdx == -1) continue

            var depth = 0
            var endIdx = braceIdx
            for (i in braceIdx until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { endIdx = i + 1; break }
                    }
                }
            }

            if (depth == 0) {
                val candidate = text.substring(braceIdx, endIdx)
                try {
                    JsonParser.parseString(candidate)
                    return candidate
                } catch (_: Exception) { }
            }
        }

        // 策略3: 提取第一个 JSON 块
        val braceIndex = text.indexOf('{')
        if (braceIndex == -1) return null
        var depth = 0
        var endIndex = braceIndex
        for (i in braceIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { endIndex = i + 1; break }
                }
            }
        }
        return if (depth == 0) text.substring(braceIndex, endIndex) else null
    }

    /**
     * 递归解析 JsonElement 为 Kotlin 原生类型
     */
    private fun parseValueToAny(element: com.google.gson.JsonElement): Any {
        return when {
            element.isJsonNull -> ""
            element.isJsonPrimitive -> {
                val prim = element.asJsonPrimitive
                when {
                    prim.isBoolean -> prim.asBoolean
                    prim.isNumber -> {
                        val num = prim.asNumber
                        if (num.toString().contains(".")) num.toDouble() else num.toInt()
                    }
                    else -> prim.asString
                }
            }
            element.isJsonObject -> {
                val map = mutableMapOf<String, Any>()
                element.asJsonObject.entrySet().forEach { (k, v) ->
                    map[k] = parseValueToAny(v)
                }
                map
            }
            element.isJsonArray -> {
                val list = mutableListOf<Any>()
                element.asJsonArray.forEach { item ->
                    list.add(parseValueToAny(item))
                }
                list
            }
            else -> element.toString()
        }
    }

    /**
     * 判断 AI 响应是"中间思考/格式错误"还是"真正的最终回复"
     * 返回 true 表示应该忽略并继续循环
     */
    private fun isThinkingOrFormatError(response: String): Boolean {
        val trimmed = response.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"role\"")) return true
        if (trimmed.startsWith("{") && trimmed.contains("\"reasoning\"")) return true
        if (trimmed.contains("\"tool_calls\"")) return true
        if (trimmed.startsWith("{") && trimmed.endsWith("}") &&
            !trimmed.any { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF }) {
            return true
        }
        return false
    }

    private fun formatFinalResponse(response: String): String {
        return response.trim()
            .replace(Regex("^```(?:json|text)?\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .trim()
            .filterPollinationsAd()
    }

    /**
     * 过滤 Pollinations.AI 的广告内容
     */
    private fun String.filterPollinationsAd(): String {
        val adPattern = Regex("""(?s)---\s*\*\*\s*Support Pollinations\.AI.*$""")
        val match = adPattern.find(this)
        return if (match != null) this.substring(0, match.range.first).trim() else this
    }

    private fun addMessage(message: AgentMessage) {
        _messages.value = _messages.value + message
    }

    fun clearMessages() {
        _messages.value = emptyList()
        conversationHistory.clear()
    }

    /**
     * 从指定消息位置重试：删除该消息及之后的所有消息和对话历史，
     * 返回需要重新发送的 user 消息内容（如果找到了的话）
     */
    fun retryFromMessage(messageIndex: Int): String? {
        val msgs = _messages.value
        if (messageIndex < 0 || messageIndex >= msgs.size) return null

        // 从当前位置往回找最近的 user 消息
        var userContent: String? = null
        var userMsgIndex = -1
        for (i in messageIndex downTo 0) {
            if (msgs[i].role == "user") {
                userContent = msgs[i].content
                userMsgIndex = i
                break
            }
        }
        if (userContent == null) return null

        // 删除该 user 消息及之后的所有显示消息
        _messages.value = msgs.take(userMsgIndex)

        // 同步清理 conversationHistory
        // 找到显示消息中第 userMsgIndex 条 user 消息对应的 conversationHistory 位置
        var convUserIndex = -1
        var userCount = 0
        for (j in conversationHistory.indices) {
            val content = conversationHistory[j]["content"] ?: ""
            if (conversationHistory[j]["role"] == "user" && !content.startsWith("[工具") && !content.startsWith("[系统")) {
                if (userCount == userMsgIndex) {
                    convUserIndex = j
                    break
                }
                userCount++
            }
        }
        if (convUserIndex >= 0) {
            while (conversationHistory.size > convUserIndex) {
                conversationHistory.removeAt(convUserIndex)
            }
        }

        return userContent
    }
}

data class AgentMessage(
    val role: String,
    val content: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)