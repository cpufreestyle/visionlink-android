package com.visionlink.android.a2a.openclaw

import android.util.Log
import com.visionlink.android.a2a.*
import com.visionlink.android.a2a.agents.A2AAgentHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenClaw Agent — 将 OpenClaw Gateway 的 Agent 接入 A2A Protocol
 *
 * 通过 OpenClawGatewayClient 的 WebSocket RPC 协议与本地 OpenClaw Gateway 通信，
 * 将 OpenClaw 的 Agent 能力暴露为标准 A2A Agent。
 *
 * A2A 调用方式:
 * - sendText: "你好" → 转发给 OpenClaw Agent，返回回复
 * - sendData: { "message": "你好", "agentId": "main", "sessionKey": "xxx" }
 *
 * 配置:
 * - gatewayUrl: OpenClaw Gateway 地址 (默认 http://127.0.0.1:18789)
 * - gatewayToken: 认证 token (可选)
 * - agentId: OpenClaw 中的 Agent ID (默认 "main")
 *
 * @param gatewayUrl OpenClaw Gateway 地址
 * @param gatewayToken 认证 token
 * @param openClawAgentId OpenClaw 中的 Agent ID
 */
class OpenClawAgent(
    private val gatewayUrl: String = "http://127.0.0.1:18789",
    private val gatewayToken: String = "",
    private val openClawAgentId: String = "main"
) : A2AAgent {

    companion object {
        private const val TAG = "OpenClawAgent"
    }

    override val agentId = "openclaw"
    override val agentName = "OpenClaw Gateway Agent"
    override val agentDescription = "OpenClaw Agent 网关 — 通过 WebSocket RPC 连接本地 OpenClaw Gateway，将 OpenClaw 的 AI Agent 能力暴露为 A2A 标准接口"
    override val tags = listOf("openclaw", "llm", "ai", "gateway", "chat", "general")

    private val gatewayClient = OpenClawGatewayClient(
        gatewayUrl = gatewayUrl,
        gatewayToken = gatewayToken,
        agentId = openClawAgentId
    )

    // 会话管理：A2A sessionId → OpenClaw sessionKey
    private val sessionMap = ConcurrentHashMap<String, String>()

    override fun supportsStreaming(): Boolean = true

    override fun getSkills(): List<AgentSkill> = listOf(
        AgentSkill(
            id = "chat",
            name = "对话",
            description = "与 OpenClaw Agent 进行自然语言对话，支持多轮上下文",
            tags = listOf("chat", "conversation", "llm")
        ),
        AgentSkill(
            id = "ask",
            name = "问答",
            description = "向 OpenClaw Agent 提问，获取 AI 回答",
            tags = listOf("qa", "question", "answer")
        ),
        AgentSkill(
            id = "task",
            name = "任务执行",
            description = "通过 OpenClaw Agent 执行复杂任务（代码生成、分析、推理等）",
            tags = listOf("task", "code", "analysis", "reasoning")
        )
    )

    override suspend fun processTask(params: TaskSendParams): Task {
        val taskId = params.id.ifEmpty { A2AAgentHelper.generateTaskId(agentId) }
        Log.i(TAG, "Processing A2A task: $taskId (agentId=$openClawAgentId)")

        // 提取消息内容
        val text = params.message.getText()
        val data = params.message.getData()

        val (message, targetAgentId, sessionKey) = when {
            data != null -> {
                val msg = data["message"]?.toString() ?: text
                val aid = data["agentId"]?.toString() ?: openClawAgentId
                val sk = data["sessionKey"]?.toString() ?: params.sessionId ?: sessionMap[params.sessionId ?: ""]
                Triple(msg, aid, sk)
            }
            else -> Triple(text, openClawAgentId, params.sessionId ?: sessionMap[params.sessionId ?: ""])
        }

        if (message.isBlank()) {
            return A2AAgentHelper.failedTask(agentId, taskId, "消息内容为空")
        }

        // 记录会话映射
        params.sessionId?.let { sid ->
            sessionKey?.let { sk -> sessionMap[sid] = sk }
        }

        // 如果指定了不同的 agentId，创建临时 client
        val client = if (targetAgentId != openClawAgentId) {
            OpenClawGatewayClient(gatewayUrl, gatewayToken, targetAgentId)
        } else {
            gatewayClient
        }

        Log.d(TAG, "Sending to OpenClaw: agentId=$targetAgentId, message=${message.take(100)}...")

        // 调用 OpenClaw Gateway
        val response = try {
            client.sendMessage(message, sessionKey)
        } catch (e: Exception) {
            Log.e(TAG, "OpenClaw call failed: ${e.message}", e)
            return A2AAgentHelper.failedTask(agentId, taskId, "OpenClaw 调用失败: ${e.message}")
        }

        if (response.isNullOrBlank()) {
            return A2AAgentHelper.failedTask(agentId, taskId, "OpenClaw 返回空响应")
        }

        Log.i(TAG, "OpenClaw response: ${response.take(100)}...")

        // 返回 A2A Task
        return A2AAgentHelper.completedTextTask(
            agentId = agentId,
            taskId = taskId,
            text = response,
            sessionId = params.sessionId
        )
    }

    /**
     * 获取 OpenClaw Gateway 连接状态
     */
    suspend fun checkConnection(): Boolean {
        return gatewayClient.ping()
    }

    /**
     * 获取配置信息
     */
    fun getConfig(): Map<String, String> = mapOf(
        "gatewayUrl" to gatewayUrl,
        "agentId" to openClawAgentId,
        "tokenConfigured" to gatewayToken.isNotEmpty().toString()
    )
}

/**
 * OpenClaw Agent 配置
 */
data class OpenClawAgentConfig(
    val gatewayUrl: String = "http://127.0.0.1:18789",
    val gatewayToken: String = "",
    val agentId: String = "main",
    val enabled: Boolean = true
) {
    companion object {
        /**
         * 从 SharedPreferences 读取配置
         */
        fun fromPreferences(prefs: android.content.SharedPreferences): OpenClawAgentConfig {
            return OpenClawAgentConfig(
                gatewayUrl = prefs.getString("openclaw_gateway_url", "http://127.0.0.1:18789") ?: "http://127.0.0.1:18789",
                gatewayToken = prefs.getString("openclaw_gateway_token", "") ?: "",
                agentId = prefs.getString("openclaw_agent_id", "main") ?: "main",
                enabled = prefs.getBoolean("openclaw_enabled", true)
            )
        }

        /**
         * 保存配置到 SharedPreferences
         */
        fun save(prefs: android.content.SharedPreferences, config: OpenClawAgentConfig) {
            prefs.edit().apply {
                putString("openclaw_gateway_url", config.gatewayUrl)
                putString("openclaw_gateway_token", config.gatewayToken)
                putString("openclaw_agent_id", config.agentId)
                putBoolean("openclaw_enabled", config.enabled)
                apply()
            }
        }
    }
}
