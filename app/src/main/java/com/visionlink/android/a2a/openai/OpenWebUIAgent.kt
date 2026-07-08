package com.visionlink.android.a2a.openai

/**
 * Open WebUI Agent — Open WebUI 的 A2A 包装器
 *
 * Open WebUI 提供 OpenAI 兼容 API，可连接多种后端模型（Ollama、OpenAI、LM Studio 等）。
 *
 * 架构:
 *   A2A Client → A2A Server → OpenWebUIAgent → http://localhost:8080/v1/chat/completions
 *                                                  ↓
 *                                           Open WebUI
 *                                                  ↓
 *                                           Ollama / OpenAI / LM Studio / ...
 *
 * 端点:
 * - Models:  GET http://localhost:8080/v1/models
 * - Chat:    POST http://localhost:8080/v1/chat/completions
 * - (可选) API 文档: http://localhost:8080/docs
 *
 * @param baseUrl Open WebUI API base URL (默认 "http://localhost:8080/v1")
 * @param apiKey API Key (在 Open WebUI 设置中生成)
 * @param defaultModel 默认模型 ID (如 "llama3", "qwen2.5" 等)
 */
class OpenWebUIAgent(
    baseUrl: String = "http://localhost:8080/v1",
    apiKey: String = "",
    defaultModel: String = "llama3"
) : OpenAICompatibleAgent(
    agentId = "openwebui",
    agentName = "Open WebUI",
    agentDescription = "Open WebUI — 开源 AI 界面，提供 OpenAI 兼容 API，后端可连接 Ollama、OpenAI、LM Studio 等多种模型服务",
    baseUrl = baseUrl,
    apiKey = apiKey,
    defaultModel = defaultModel,
    tags = listOf("openwebui", "llm", "ai", "chat", "ollama", "webui")
) {
    companion object {
        private const val TAG = "OpenWebUIAgent"
    }

    override fun getConfig(): Map<String, String> = mapOf(
        "baseUrl" to baseUrl,
        "defaultModel" to defaultModel,
        "apiKeyConfigured" to apiKey.isNotEmpty().toString()
    )
}

/**
 * Open WebUI Agent 配置
 */
data class OpenWebUIAgentConfig(
    val baseUrl: String = "http://localhost:8080/v1",
    val apiKey: String = "",
    val model: String = "llama3",
    val enabled: Boolean = true
) {
    companion object {
        fun fromPreferences(prefs: android.content.SharedPreferences): OpenWebUIAgentConfig {
            return OpenWebUIAgentConfig(
                baseUrl = prefs.getString("openwebui_base_url", "http://localhost:8080/v1")
                    ?: "http://localhost:8080/v1",
                apiKey = prefs.getString("openwebui_api_key", "") ?: "",
                model = prefs.getString("openwebui_model", "llama3") ?: "llama3",
                enabled = prefs.getBoolean("openwebui_enabled", true)
            )
        }

        fun save(prefs: android.content.SharedPreferences, config: OpenWebUIAgentConfig) {
            prefs.edit().apply {
                putString("openwebui_base_url", config.baseUrl)
                putString("openwebui_api_key", config.apiKey)
                putString("openwebui_model", config.model)
                putBoolean("openwebui_enabled", config.enabled)
                apply()
            }
        }
    }
}
