package com.visionlink.android.a2a

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A2A Client — 用于调用远程 A2A Agent 的 HTTP 客户端
 *
 * 支持:
 * - 发现远程 Agent (获取 Agent Card)
 * - 发送任务 (tasks/send)
 * - 获取任务状态 (tasks/get)
 * - 取消任务 (tasks/cancel)
 * - 列出任务 (tasks/list)
 */
class A2AClient(
    timeoutSeconds: Long = 30
) {
    companion object {
        private const val TAG = "A2AClient"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    // ========== Agent Discovery ==========

    /**
     * 获取远程服务器的 Agent Card 列表
     * @param baseUrl 远程服务器地址 (如 "http://192.168.1.100:8765")
     */
    suspend fun discoverAgents(baseUrl: String): List<AgentCard> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/.well-known/agent.json"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Discover failed: ${response.code}")
                return@withContext emptyList()
            }

            val json = JSONObject(body)
            val agentsArr = json.optJSONArray("agents") ?: JSONArray()

            val cards = mutableListOf<AgentCard>()
            for (i in 0 until agentsArr.length()) {
                val cardJson = agentsArr.getJSONObject(i)
                cards.add(parseAgentCard(cardJson))
            }

            Log.i(TAG, "Discovered ${cards.size} agents from $baseUrl")
            cards
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 获取单个 Agent 的 Agent Card
     */
    suspend fun getAgentCard(baseUrl: String, agentId: String): AgentCard? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/a2a/$agentId/.well-known/agent.json"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Get card failed: ${response.code}")
                return@withContext null
            }

            parseAgentCard(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "Get card error: ${e.message}", e)
            null
        }
    }

    // ========== Task Operations ==========

    /**
     * 发送文本消息给远程 Agent
     * @param agentUrl Agent 的完整 URL (如 "http://192.168.1.100:8765/a2a/vision")
     * @param text 文本消息
     * @param taskId 可选的任务 ID（不提供则自动生成）
     * @return 完成的 Task
     */
    suspend fun sendText(agentUrl: String, text: String, taskId: String? = null): Task? =
        withContext(Dispatchers.IO) {
            val id = taskId ?: "${UUID.randomUUID()}"
            val params = JSONObject().apply {
                put("id", id)
                put("message", JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", text)
                        })
                    })
                })
            }

            val result = sendJsonRpc(agentUrl, A2AMethods.TASKS_SEND, params)
            result?.let { parseTask(it) }
        }

    /**
     * 发送数据消息给远程 Agent
     * @param agentUrl Agent 的完整 URL
     * @param data 数据内容 (键值对)
     * @param taskId 可选的任务 ID
     * @return 完成的 Task
     */
    suspend fun sendData(agentUrl: String, data: Map<String, Any>, taskId: String? = null): Task? =
        withContext(Dispatchers.IO) {
            val id = taskId ?: "${UUID.randomUUID()}"
            val params = JSONObject().apply {
                put("id", id)
                put("message", JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "data")
                            put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v) } })
                        })
                    })
                })
            }

            val result = sendJsonRpc(agentUrl, A2AMethods.TASKS_SEND, params)
            result?.let { parseTask(it) }
        }

    /**
     * 获取任务状态
     */
    suspend fun getTask(agentUrl: String, taskId: String): Task? = withContext(Dispatchers.IO) {
        val params = JSONObject().apply { put("id", taskId) }
        val result = sendJsonRpc(agentUrl, A2AMethods.TASKS_GET, params)
        result?.let { parseTask(it) }
    }

    /**
     * 取消任务
     */
    suspend fun cancelTask(agentUrl: String, taskId: String): Task? = withContext(Dispatchers.IO) {
        val params = JSONObject().apply { put("id", taskId) }
        val result = sendJsonRpc(agentUrl, A2AMethods.TASKS_CANCEL, params)
        result?.let { parseTask(it) }
    }

    /**
     * 列出任务
     */
    suspend fun listTasks(agentUrl: String): List<Task> = withContext(Dispatchers.IO) {
        val result = sendJsonRpc(agentUrl, A2AMethods.TASKS_LIST, JSONObject())
        if (result == null) return@withContext emptyList()

        val tasksArr = result.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<Task>()
        for (i in 0 until tasksArr.length()) {
            parseTask(tasksArr.getJSONObject(i))?.let { tasks.add(it) }
        }
        tasks
    }

    // ========== Internal ==========

    private suspend fun sendJsonRpc(url: String, method: String, params: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val rpcRequest = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", UUID.randomUUID().toString())
                    put("method", method)
                    put("params", params)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(rpcRequest.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body == null) {
                    Log.e(TAG, "JSON-RPC failed: ${response.code} ${response.message}")
                    return@withContext null
                }

                val json = JSONObject(body)
                if (json.has("error")) {
                    val error = json.getJSONObject("error")
                    Log.e(TAG, "JSON-RPC error: ${error.optString("message")}")
                    return@withContext null
                }

                json.optJSONObject("result")
            } catch (e: Exception) {
                Log.e(TAG, "JSON-RPC error: ${e.message}", e)
                null
            }
        }

    private fun parseAgentCard(json: JSONObject): AgentCard {
        val skillsArr = json.optJSONArray("skills") ?: JSONArray()
        val skills = mutableListOf<AgentSkill>()
        for (i in 0 until skillsArr.length()) {
            val s = skillsArr.getJSONObject(i)
            val tagsArr = s.optJSONArray("tags") ?: JSONArray()
            skills.add(AgentSkill(
                id = s.optString("id"),
                name = s.optString("name"),
                description = s.optString("description"),
                tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }
            ))
        }

        return AgentCard(
            name = json.optString("name"),
            description = json.optString("description"),
            url = json.optString("url"),
            version = json.optString("version", "1.0.0"),
            capabilities = AgentCapabilities(),
            skills = skills
        )
    }

    private fun parseTask(json: JSONObject): Task? {
        return try {
            val statusJson = json.optJSONObject("status") ?: JSONObject()
            val stateStr = statusJson.optString("state").uppercase()
            val state = runCatching { TaskState.valueOf(stateStr) }.getOrDefault(TaskState.COMPLETED)

            val artifactsArr = json.optJSONArray("artifacts")
            val artifacts = mutableListOf<Artifact>()
            if (artifactsArr != null) {
                for (i in 0 until artifactsArr.length()) {
                    val aJson = artifactsArr.getJSONObject(i)
                    val partsArr = aJson.optJSONArray("parts") ?: JSONArray()
                    val parts = mutableListOf<MessagePart>()
                    for (j in 0 until partsArr.length()) {
                        parts.add(MessagePart.fromJson(partsArr.getJSONObject(j)))
                    }
                    artifacts.add(Artifact(
                        name = aJson.optString("name").takeIf { it.isNotEmpty() },
                        description = aJson.optString("description").takeIf { it.isNotEmpty() },
                        parts = parts,
                        index = aJson.optInt("index", 0)
                    ))
                }
            }

            Task(
                id = json.optString("id"),
                sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() },
                status = TaskStatus(
                    state = state,
                    timestamp = statusJson.optString("timestamp").takeIf { it.isNotEmpty() }
                ),
                artifacts = artifacts
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse task error: ${e.message}")
            null
        }
    }

    /**
     * 从 Task 中提取文本结果
     */
    fun extractText(task: Task): String {
        return task.artifacts?.firstOrNull()?.getText() ?: ""
    }
}
