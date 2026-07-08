package com.visionlink.android.a2a.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hermes Agent — Hermes Agent (Nous Research) 的 A2A 包装器
 *
 * Hermes Agent 提供 OpenAI 兼容 API，运行在 WSL2 上。
 *
 * 架构:
 *   A2A Client → A2A Server → HermesAgent → http://localhost:8642/v1/chat/completions
 *                                                ↓
 *                                        Hermes Agent Gateway (WSL2)
 *                                                ↓
 *                                        LM Studio / 远程模型
 *
 * 端点:
 * - Health:  GET http://localhost:8642/health
 * - Models:  GET http://localhost:8642/v1/models
 * - Chat:    POST http://localhost:8642/v1/chat/completions
 *
 * 默认模型: hermes-agent
 *
 * @param baseUrl Hermes API base URL (默认 "http://localhost:8642/v1")
 * @param apiKey API Key (任意值即可，默认 "sk-null")
 * @param defaultModel 默认模型 ID (默认 "hermes-agent")
 */
class HermesAgent(
    baseUrl: String = "http://localhost:8642/v1",
    apiKey: String = "sk-null",
    defaultModel: String = "hermes-agent"
) : OpenAICompatibleAgent(
    agentId = "hermes",
    agentName = "Hermes Agent",
    agentDescription = "Hermes Agent (Nous Research) — 运行在 WSL2 上的 AI Agent，提供 OpenAI 兼容 API，后端可连接 LM Studio 本地模型或远程模型",
    baseUrl = baseUrl,
    apiKey = apiKey,
    defaultModel = defaultModel,
    tags = listOf("hermes", "llm", "ai", "chat", "nous", "wsl")
) {
    companion object {
        private const val TAG = "HermesAgent"
        private const val DEFAULT_BASE_URL = "http://localhost:8642/v1"
        private const val DEFAULT_MODEL = "hermes-agent"
        private const val DEFAULT_API_KEY = "sk-null"
    }

    /**
     * Hermes 专属健康检查 — 使用 /health 端点
     */
    suspend fun hermesHealthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val healthUrl = baseUrl.trimEnd('/').removeSuffix("/v1") + "/health"
            Log.d(TAG, "Health check: $healthUrl")
            val request = okhttp3.Request.Builder().url(healthUrl).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                val json = org.json.JSONObject(body)
                "ok".equals(json.optString("status"), ignoreCase = true)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    override fun getConfig(): Map<String, String> = mapOf(
        "baseUrl" to baseUrl,
        "defaultModel" to defaultModel,
        "apiKey" to apiKey,
        "healthEndpoint" to "${baseUrl.trimEnd('/').removeSuffix("/v1")}/health"
    )
}

/**
 * Hermes Agent 配置
 */
data class HermesAgentConfig(
    val baseUrl: String = "http://localhost:8642/v1",
    val apiKey: String = "sk-null",
    val model: String = "hermes-agent",
    val enabled: Boolean = true
) {
    companion object {
        fun fromPreferences(prefs: android.content.SharedPreferences): HermesAgentConfig {
            return HermesAgentConfig(
                baseUrl = prefs.getString("hermes_base_url", "http://localhost:8642/v1")
                    ?: "http://localhost:8642/v1",
                apiKey = prefs.getString("hermes_api_key", "sk-null") ?: "sk-null",
                model = prefs.getString("hermes_model", "hermes-agent") ?: "hermes-agent",
                enabled = prefs.getBoolean("hermes_enabled", true)
            )
        }

        fun save(prefs: android.content.SharedPreferences, config: HermesAgentConfig) {
            prefs.edit().apply {
                putString("hermes_base_url", config.baseUrl)
                putString("hermes_api_key", config.apiKey)
                putString("hermes_model", config.model)
                putBoolean("hermes_enabled", config.enabled)
                apply()
            }
        }
    }
}
