package com.visionlink.android.a2a.agents

import com.visionlink.android.a2a.*
import java.util.UUID

/**
 * A2A Agent 工具类 — 提供 Task 构建的便捷方法
 */
object A2AAgentHelper {

    /** 生成带 Agent 前缀的任务 ID */
    fun generateTaskId(agentId: String): String = "${agentId}-${UUID.randomUUID()}"

    /** 创建文本完成的 Task */
    fun completedTextTask(
        agentId: String,
        taskId: String,
        text: String,
        sessionId: String? = null
    ): Task {
        return Task(
            id = taskId,
            sessionId = sessionId,
            status = TaskStatus(
                state = TaskState.COMPLETED,
                timestamp = isoNow(),
                message = Message(
                    role = "agent",
                    parts = listOf(MessagePart.TextPart(text))
                )
            ),
            artifacts = listOf(
                Artifact(
                    name = "result",
                    parts = listOf(MessagePart.TextPart(text)),
                    index = 0,
                    lastChunk = true
                )
            )
        )
    }

    /** 创建数据完成的 Task */
    fun completedDataTask(
        agentId: String,
        taskId: String,
        data: Map<String, Any>,
        sessionId: String? = null
    ): Task {
        return Task(
            id = taskId,
            sessionId = sessionId,
            status = TaskStatus(
                state = TaskState.COMPLETED,
                timestamp = isoNow()
            ),
            artifacts = listOf(
                Artifact(
                    name = "result",
                    parts = listOf(MessagePart.DataPart(data)),
                    index = 0,
                    lastChunk = true
                )
            )
        )
    }

    /** 创建失败的 Task */
    fun failedTask(agentId: String, taskId: String, error: String): Task {
        return Task(
            id = taskId,
            status = TaskStatus(
                state = TaskState.FAILED,
                timestamp = isoNow(),
                message = Message(
                    role = "agent",
                    parts = listOf(MessagePart.TextPart("Error: $error"))
                )
            )
        )
    }

    /** 创建工作中的 Task */
    fun workingTask(agentId: String, taskId: String, message: String? = null): Task {
        return Task(
            id = taskId,
            status = TaskStatus(
                state = TaskState.WORKING,
                timestamp = isoNow(),
                message = message?.let {
                    Message(role = "agent", parts = listOf(MessagePart.TextPart(it)))
                }
            )
        )
    }

    private fun isoNow(): String = java.time.Instant.now().toString()
}
