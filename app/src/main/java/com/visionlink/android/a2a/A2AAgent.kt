package com.visionlink.android.a2a

import org.json.JSONObject

/**
 * A2A Agent 接口 — 每个 Agent 需要实现此接口以接入 A2A Protocol
 *
 * Agent 是一个独立的能力单元，接收 Message 输入，产出 Task（含 Artifacts）。
 */
interface A2AAgent {

    /** Agent 唯一标识 */
    val agentId: String

    /** Agent 名称 */
    val agentName: String

    /** Agent 描述 */
    val agentDescription: String

    /** Agent 能力标签 */
    val tags: List<String>

    /** 生成 Agent Card */
    fun getAgentCard(baseUrl: String): AgentCard {
        return AgentCard(
            name = agentName,
            description = agentDescription,
            url = "$baseUrl/a2a/$agentId",
            version = "1.0.0",
            capabilities = AgentCapabilities(
                streaming = supportsStreaming(),
                pushNotifications = false,
                stateTransitionHistory = true
            ),
            skills = getSkills(),
            defaultInputModes = getInputModes(),
            defaultOutputModes = getOutputModes()
        )
    }

    /** 是否支持流式响应 */
    fun supportsStreaming(): Boolean = false

    /** 支持的输入模式 */
    fun getInputModes(): List<String> = listOf("text", "data")

    /** 支持的输出模式 */
    fun getOutputModes(): List<String> = listOf("text", "data")

    /** Agent 技能列表 */
    fun getSkills(): List<AgentSkill>

    /**
     * 处理任务请求 — 接收用户消息，返回 Task 结果
     *
     * @param params 任务发送参数（包含消息和元数据）
     * @return 完成的 Task（包含状态和 Artifacts）
     */
    suspend fun processTask(params: TaskSendParams): Task

    /**
     * 获取任务状态
     * 默认实现：返回未找到（无状态持久化的 Agent 可覆盖）
     */
    suspend fun getTask(taskId: String, historyLength: Int?): Task? = null

    /**
     * 取消任务
     * 默认实现：返回未找到
     */
    suspend fun cancelTask(taskId: String): Task? = null
}

/**
 * A2A Agent 注册中心 — 管理所有已注册的 Agent
 */
class A2AAgentRegistry {

    private val agents = mutableMapOf<String, A2AAgent>()

    /** 注册 Agent */
    fun register(agent: A2AAgent) {
        agents[agent.agentId] = agent
    }

    /** 注销 Agent */
    fun unregister(agentId: String) {
        agents.remove(agentId)
    }

    /** 获取 Agent */
    fun getAgent(agentId: String): A2AAgent? = agents[agentId]

    /** 获取所有已注册 Agent */
    fun getAllAgents(): List<A2AAgent> = agents.values.toList()

    /** 获取所有 Agent ID */
    fun getAllAgentIds(): Set<String> = agents.keys.toSet()

    /** 是否已注册 */
    fun isRegistered(agentId: String): Boolean = agents.containsKey(agentId)

    /** 生成所有 Agent 的 Agent Card 列表 */
    fun getAllAgentCards(baseUrl: String): List<AgentCard> {
        return agents.values.map { it.getAgentCard(baseUrl) }
    }

    /** 清除所有注册 */
    fun clear() {
        agents.clear()
    }
}
