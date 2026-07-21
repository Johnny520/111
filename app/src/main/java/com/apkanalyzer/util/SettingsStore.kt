package com.apkanalyzer.util

import android.content.Context
import android.content.SharedPreferences
import com.apkanalyzer.ai.CustomProviderConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 设置持久化存储
 * 使用 SharedPreferences 保存所有应用设置
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apkanalyzer_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    // AI 接口选择（-1 表示自动切换）
    var aiProviderIndex: Int
        get() = prefs.getInt("ai_provider_index", -1)
        set(value) = prefs.edit().putInt("ai_provider_index", value).apply()

    // AI 模型（"auto" 表示自动）
    var aiModel: String
        get() = prefs.getString("ai_model", "auto") ?: "auto"
        set(value) = prefs.edit().putString("ai_model", value).apply()

    // 自定义 provider 列表
    var customProviders: List<CustomProviderConfig>
        get() {
            val json = prefs.getString("custom_providers", null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<CustomProviderConfig>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) = prefs.edit().putString("custom_providers", gson.toJson(value)).apply()

    // MCP 服务器地址
    var mcpServerUrl: String
        get() = prefs.getString("mcp_server_url", "http://127.0.0.1:8787/mcp") ?: "http://127.0.0.1:8787/mcp"
        set(value) = prefs.edit().putString("mcp_server_url", value).apply()

    // Pollinations API Key
    var pollinationsApiKey: String
        get() = prefs.getString("pollinations_api_key", "") ?: ""
        set(value) = prefs.edit().putString("pollinations_api_key", value).apply()

    // 图片模型
    var imageModel: String
        get() = prefs.getString("image_model", "flux") ?: "flux"
        set(value) = prefs.edit().putString("image_model", value).apply()

    // 更新配置 URL
    var updateConfigUrl: String
        get() = prefs.getString("update_config_url", "https://raw.githubusercontent.com/sillycats/XiaoNaiPing/refs/heads/main/AIMCP_CheckUpdateConfig") ?: "https://raw.githubusercontent.com/sillycats/XiaoNaiPing/refs/heads/main/AIMCP_CheckUpdateConfig"
        set(value) = prefs.edit().putString("update_config_url", value).apply()

    // 深色主题
    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", false)
        set(value) = prefs.edit().putBoolean("dark_theme", value).apply()
}
