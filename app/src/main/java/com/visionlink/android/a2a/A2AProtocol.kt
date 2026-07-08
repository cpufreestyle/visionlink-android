package com.visionlink.android.a2a

import org.json.JSONArray
import org.json.JSONObject

/**
 * Google A2A (Agent-to-Agent) Protocol — 核心类型定义
 *
 * 基于 JSON-RPC 2.0 over HTTP，支持 Task 生命周期管理、流式响应 (SSE) 和 Artifacts。
 *
 * 参考: https://google.github.io/A2A/
 */

// ========== Agent Card ==========

data class AgentCard(
    val name: String,
    val description: String,
    val url: String,
    val version: String = "1.0.0",
    val protocolVersion: String = "0.3.0",
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val skills: List<AgentSkill> = emptyList(),
    val defaultInputModes: List<String> = listOf("text"),
    val defaultOutputModes: List<String> = listOf("text")
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("url", url)
        put("version", version)
        put("protocolVersion", protocolVersion)
        put("capabilities", capabilities.toJson())
        put("skills", JSONArray().apply { skills.forEach { put(it.toJson()) } })
        put("defaultInputModes", JSONArray().apply { defaultInputModes.forEach { put(it) } })
        put("defaultOutputModes", JSONArray().apply { defaultOutputModes.forEach { put(it) } })
    }
}

data class AgentCapabilities(
    val streaming: Boolean = true,
    val pushNotifications: Boolean = false,
    val stateTransitionHistory: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("streaming", streaming)
        put("pushNotifications", pushNotifications)
        put("stateTransitionHistory", stateTransitionHistory)
    }
}

data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val inputModes: List<String> = listOf("text"),
    val outputModes: List<String> = listOf("text")
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("tags", JSONArray().apply { tags.forEach { put(it) } })
        put("inputModes", JSONArray().apply { inputModes.forEach { put(it) } })
        put("outputModes", JSONArray().apply { outputModes.forEach { put(it) } })
    }
}

// ========== Task Lifecycle ==========

enum class TaskState {
    SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, CANCELED, FAILED
}

enum class TaskKind {
    TEXT, DATA, FILE, ERROR
}

data class Task(
    val id: String,
    val sessionId: String? = null,
    val status: TaskStatus,
    val kind: TaskKind = TaskKind.TEXT,
    val artifacts: List<Artifact>? = null,
    val history: List<Message>? = null,
    val metadata: Map<String, String>? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        sessionId?.let { put("sessionId", it) }
        put("status", status.toJson())
        put("kind", kind.name.lowercase())
        artifacts?.let {
            put("artifacts", JSONArray().apply { it.forEach { a -> put(a.toJson()) } })
        }
        history?.let {
            put("history", JSONArray().apply { it.forEach { m -> put(m.toJson()) } })
        }
        metadata?.let {
            put("metadata", JSONObject().apply { it.forEach { (k, v) -> put(k, v) } })
        }
    }
}

data class TaskStatus(
    val state: TaskState,
    val timestamp: String? = null,
    val message: Message? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state.name.lowercase())
        timestamp?.let { put("timestamp", it) }
        message?.let { put("message", it.toJson()) }
    }
}

// ========== Message ==========

data class Message(
    val role: String, // "user" or "agent"
    val parts: List<MessagePart>,
    val taskId: String? = null,
    val contextId: String? = null,
    val messageId: String = java.util.UUID.randomUUID().toString()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("parts", JSONArray().apply { parts.forEach { put(it.toJson()) } })
        put("messageId", messageId)
        taskId?.let { put("taskId", it) }
        contextId?.let { put("contextId", it) }
    }

    /** Extract all text from text parts */
    fun getText(): String = parts.filterIsInstance<MessagePart.TextPart>().joinToString("") { it.text }

    /** Extract data from data parts */
    fun getData(): Map<String, Any>? = parts.filterIsInstance<MessagePart.DataPart>().firstOrNull()?.data
}

sealed class MessagePart {
    abstract fun toJson(): JSONObject

    data class TextPart(val text: String) : MessagePart() {
        override fun toJson() = JSONObject().apply {
            put("type", "text")
            put("text", text)
        }
    }

    data class DataPart(val data: Map<String, Any>) : MessagePart() {
        override fun toJson() = JSONObject().apply {
            put("type", "data")
            put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v) } })
        }
    }

    data class FilePart(val fileUri: String, val mimeType: String = "application/octet-stream") : MessagePart() {
        override fun toJson() = JSONObject().apply {
            put("type", "file")
            put("file", JSONObject().apply {
                put("uri", fileUri)
                put("mimeType", mimeType)
            })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MessagePart {
            return when (json.optString("type")) {
                "text" -> TextPart(json.optString("text"))
                "data" -> {
                    val dataObj = json.optJSONObject("data") ?: JSONObject()
                    val map = mutableMapOf<String, Any>()
                    for (key in dataObj.keys()) {
                        map[key] = dataObj.get(key)
                    }
                    DataPart(map)
                }
                "file" -> {
                    val fileObj = json.optJSONObject("file") ?: JSONObject()
                    FilePart(
                        fileObj.optString("uri"),
                        fileObj.optString("mimeType", "application/octet-stream")
                    )
                }
                else -> TextPart(json.toString())
            }
        }
    }
}

// ========== Artifact ==========

data class Artifact(
    val name: String? = null,
    val description: String? = null,
    val parts: List<MessagePart>,
    val index: Int = 0,
    val lastChunk: Boolean? = null,
    val metadata: Map<String, String>? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        put("parts", JSONArray().apply { parts.forEach { put(it.toJson()) } })
        put("index", index)
        lastChunk?.let { put("lastChunk", it) }
        metadata?.let { put("metadata", JSONObject().apply { it.forEach { (k, v) -> put(k, v) } }) }
    }

    fun getText(): String = parts.filterIsInstance<MessagePart.TextPart>().joinToString("") { it.text }
}

// ========== JSON-RPC 2.0 ==========

data class JSONRPCRequest(
    val jsonrpc: String = "2.0",
    val id: Any? = null, // String or Int
    val method: String,
    val params: JSONObject? = null
) {
    companion object {
        fun fromJson(json: JSONObject): JSONRPCRequest {
            return JSONRPCRequest(
                jsonrpc = json.optString("jsonrpc", "2.0"),
                id = json.opt("id"),
                method = json.optString("method"),
                params = json.optJSONObject("params")
            )
        }
    }
}

data class JSONRPCResponse(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val result: JSONObject? = null,
    val error: JSONRPCError? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jsonrpc", jsonrpc)
        id?.let { put("id", it) }
        result?.let { put("result", it) }
        error?.let { put("error", it.toJson()) }
    }
}

data class JSONRPCError(
    val code: Int,
    val message: String,
    val data: Any? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
        data?.let { put("data", it) }
    }

    companion object {
        // Standard JSON-RPC error codes
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // A2A-specific error codes
        const val TASK_NOT_FOUND = -32001
        const val TASK_NOT_CANCELABLE = -32002
        const val PUSH_NOTIFICATION_NOT_SUPPORTED = -32003
        const val UNSUPPORTED_OPERATION = -32004
    }
}

// ========== A2A JSON-RPC Methods ==========

object A2AMethods {
    const val TASKS_SEND = "tasks/send"
    const val TASKS_SEND_SUBSCRIBE = "tasks/sendSubscribe"
    const val TASKS_GET = "tasks/get"
    const val TASKS_CANCEL = "tasks/cancel"
    const val TASKS_LIST = "tasks/list"
    const val TASKS_PUSH_NOTIFICATION_SET = "tasks/pushNotification/set"
    const val TASKS_PUSH_NOTIFICATION_GET = "tasks/pushNotification/get"

    /** All supported methods */
    val ALL = setOf(
        TASKS_SEND,
        TASKS_SEND_SUBSCRIBE,
        TASKS_GET,
        TASKS_CANCEL,
        TASKS_LIST
    )
}

// ========== Helper: Parse Message from JSON ==========

fun parseMessage(json: JSONObject): Message {
    val partsArr = json.optJSONArray("parts") ?: JSONArray()
    val parts = mutableListOf<MessagePart>()
    for (i in 0 until partsArr.length()) {
        val partObj = partsArr.optJSONObject(i) ?: continue
        parts.add(MessagePart.fromJson(partObj))
    }
    return Message(
        role = json.optString("role", "user"),
        parts = parts,
        taskId = json.optString("taskId").takeIf { it.isNotEmpty() },
        contextId = json.optString("contextId").takeIf { it.isNotEmpty() },
        messageId = json.optString("messageId", java.util.UUID.randomUUID().toString())
    )
}

// ========== Helper: Parse TaskSendParams from JSON ==========

data class TaskSendParams(
    val id: String,
    val sessionId: String? = null,
    val message: Message,
    val pushNotification: PushNotificationConfig? = null,
    val historyLength: Int? = null,
    val metadata: Map<String, String>? = null
) {
    companion object {
        fun fromJson(json: JSONObject): TaskSendParams {
            return TaskSendParams(
                id = json.optString("id"),
                sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() },
                message = parseMessage(json.optJSONObject("message") ?: JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray())
                }),
                historyLength = json.optInt("historyLength", -1).takeIf { it >= 0 },
                metadata = null
            )
        }
    }
}

data class PushNotificationConfig(
    val url: String,
    val token: String? = null,
    val authentication: Map<String, String>? = null
)
