package com.visionlink.android.a2a.openai

import android.util.Log
import com.visionlink.android.a2a.*
import com.visionlink.android.a2a.agents.A2AAgentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 API Agent 基类
 *
 * 将任何提供 OpenAI 兼容 /v1/chat/completions 接口的服务接入 A2A Protocol。
 * 子类只需提供 baseUrl、apiKey、defaultModel 等配置。
 *
 * 支持的 A2A 调用方式:
 * - sendText: "你好" → 调用 /v1/chat/completions，返回回复
 * - sendData: { "message": "你好", "model": "xxx", "sessionKey": "xxx" }
 *
 * @param agentId A2A Agent ID
 * @param agentName 显示名称
 * @param agentDescription 描述
 * @param baseUrl OpenAI 兼容 API 的 base URL (如 "http://localhost:8642/v1")
 * @param apiKey API Key
 * @param defaultModel 默认模型 ID
 * @param tags 标签列表
 */
abstract class OpenAICompatibleAgent(
    override val agentId: String,
    override val agentName: String,
    override val agentDescription: String,
    protected val baseUrl: String,
    protected val apiKey: String,
    protected val defaultModel: String,
    override val tags: List<String> = listOf("llm", "ai", "chat")
) : A2AAgent {

    companion object {
        private const val TAG = "OpenAIAgent"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    protected val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 会话历史：sessionId → 消息列表
    private val sessionHistory = ConcurrentHashMap<String, MutableList<JSONObject>>()

    override fun supportsStreaming(): Boolean = true

    override fun getSkills(): List<AgentSkill> = listOf(
        AgentSkill(
            id = "chat",
            name = "对话",
            description = "与 $agentName 进行自然语言对话，支持多轮上下文",
            tags = listOf("chat", "conversation")
        ),
        AgentSkill(
            id = "models",
            name = "模型列表",
            description = "获取 $agentName 可用的模型列表",
            tags = listOf("models", "info")
        )
    )

    override suspend fun processTask(params: TaskSendParams): Task {
        val taskId = params.id.ifEmpty { A2AAgentHelper.generateTaskId(agentId) }
        Log.i(TAG, "[$agentName] Processing task: $taskId")

        val text = params.message.getText()
        val data = params.message.getData()

        val (message, model) = when {
            data != null -> {
                val msg = data["message"]?.toString() ?: text
                val mdl = data["model"]?.toString() ?: defaultModel
                Pair(msg, mdl)
            }
            else -> Pair(text, defaultModel)
        }

        if (message.isBlank()) {
            return A2AAgentHelper.failedTask(agentId, taskId, "消息内容为空")
        }

        // 特殊命令: 获取模型列表
        if (message.trim().lowercase() in listOf("/models", "list models", "模型列表")) {
            val models = getModels()
            return if (models != null) {
                A2AAgentHelper.completedTextTask(agentId, taskId, models, params.sessionId)
            } else {
                A2AAgentHelper.failedTask(agentId, taskId, "获取模型列表失败")
            }
        }

        // 调用 Chat Completions
        val sessionId = params.sessionId ?: taskId
        val response = chatCompletion(message, model, sessionId)

        if (response == null) {
            return A2AAgentHelper.failedTask(agentId, taskId, "$agentName API 调用失败")
        }

        Log.i(TAG, "[$agentName] Response: ${response.take(100)}...")
        return A2AAgentHelper.completedTextTask(agentId, taskId, response, params.sessionId)
    }

    /**
     * 调用 OpenAI 兼容 /v1/chat/completions 接口
     *
     * @param message 用户消息
     * @param model 模型 ID
     * @param sessionId 会话 ID（用于多轮对话上下文）
     * @return AI 回复文本，或 null 表示失败
     */
    suspend fun chatCompletion(
        message: String,
        model: String = defaultModel,
        sessionId: String = defaultModel
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 维护会话历史
            val history = sessionHistory.getOrPut(sessionId) { mutableListOf() }
            history.add(JSONObject().apply {
                put("role", "user")
                put("content", message)
            })

            // 限制历史长度（保留最近 20 条）
            while (history.size > 20) {
                history.removeAt(0)
            }

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    history.forEach { put(it) }
                })
                put("stream", false)
                put("temperature", 0.7)
            }

            val url = "${baseUrl.trimEnd('/')}/chat/completions"
            Log.d(TAG, "[$agentName] POST $url (model=$model)")

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .apply { if (apiKey.isNotEmpty()) addHeader("Authorization", "Bearer $apiKey") }
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "[$agentName] API error: ${response.code} ${response.message}")
                // 回滚历史
                history.removeLastOrNull()
                return@withContext null
            }

            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.e(TAG, "[$agentName] No choices in response")
                return@withContext null
            }

            val reply = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                ?.trim()

            if (reply.isNullOrEmpty()) {
                Log.e(TAG, "[$agentName] Empty content in response")
                return@withContext null
            }

            // 保存 assistant 回复到历史
            history.add(JSONObject().apply {
                put("role", "assistant")
                put("content", reply)
            })

            reply
        } catch (e: Exception) {
            Log.e(TAG, "[$agentName] chatCompletion failed: ${e.message}", e)
            null
        }
    }

    /**
     * 获取可用模型列表
     */
    suspend fun getModels(): String? = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/models"
            Log.d(TAG, "[$agentName] GET $url")

            val request = Request.Builder()
                .url(url)
                .apply { if (apiKey.isNotEmpty()) addHeader("Authorization", "Bearer $apiKey") }
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "[$agentName] Models API error: ${response.code}")
                return@withContext null
            }

            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext null
            val models = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id")
                if (id.isNotEmpty()) models.add(id)
            }
            models.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "[$agentName] getModels failed: ${e.message}", e)
            null
        }
    }

    /**
     * 健康检查
     */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            getModels() != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 清除指定会话的历史
     */
    fun clearSession(sessionId: String) {
        sessionHistory.remove(sessionId)
    }

    /**
     * 清除所有会话历史
     */
    fun clearAllSessions() {
        sessionHistory.clear()
    }

    /**
     * 获取配置信息
     */
    open fun getConfig(): Map<String, String> = mapOf(
        "baseUrl" to baseUrl,
        "defaultModel" to defaultModel,
        "apiKeyConfigured" to apiKey.isNotEmpty().toString()
    )
}
