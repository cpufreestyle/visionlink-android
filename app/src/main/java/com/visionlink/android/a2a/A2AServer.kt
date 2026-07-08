package com.visionlink.android.a2a

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A2A HTTP Server — 基于 NanoHTTPD 的嵌入式 HTTP 服务器
 *
 * 端点:
 * - GET  /.well-known/agent.json        → 返回所有 Agent 的 Agent Card 列表
 * - GET  /a2a/{agentId}/.well-known/agent.json → 返回指定 Agent 的 Agent Card
 * - POST /a2a/{agentId}                  → JSON-RPC 2.0 请求 (tasks/send, tasks/get, tasks/cancel, tasks/list)
 * - POST /a2a                             → JSON-RPC 2.0 请求 (广播到所有 Agent 或指定 Agent)
 *
 * @param port HTTP 端口
 * @param registry Agent 注册中心
 */
class A2AServer(
    private val port: Int = DEFAULT_PORT,
    private val registry: A2AAgentRegistry
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "A2AServer"
        const val DEFAULT_PORT = 8765
        private const val TASK_TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val taskStore = ConcurrentHashMap<String, Task>()

    /** 获取服务器基础 URL */
    fun getBaseUrl(): String {
        val hostname = "0.0.0.0"
        return "http://$hostname:$port"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                // Agent Card discovery
                uri == "/.well-known/agent.json" && method == Method.GET -> {
                    serveAgentCards(null)
                }
                uri.matches(Regex("""/a2a/[^/]+/\.well-known/agent\.json""")) && method == Method.GET -> {
                    val agentId = uri.split("/")[2]
                    serveAgentCards(agentId)
                }
                // JSON-RPC endpoint
                uri.startsWith("/a2a/") && method == Method.POST -> {
                    val agentId = uri.split("/").getOrNull(2)
                    if (agentId == null) {
                        serveJsonRpcBroadcast(session)
                    } else {
                        serveJsonRpc(session, agentId)
                    }
                }
                // Root JSON-RPC (broadcast to all)
                uri == "/a2a" && method == Method.POST -> {
                    serveJsonRpcBroadcast(session)
                }
                // Health check
                uri == "/" && method == Method.GET -> {
                    serveHealthCheck()
                }
                // 404
                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        JSONObject().put("error", "Not found: $uri").toString()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", e.message).toString()
            )
        }
    }

    // ========== Agent Card ==========

    private fun serveAgentCards(agentId: String?): Response {
        val baseUrl = getBaseUrl()
        val json = if (agentId != null) {
            val agent = registry.getAgent(agentId)
            if (agent == null) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    JSONObject().put("error", "Agent not found: $agentId").toString()
                )
            }
            agent.getAgentCard(baseUrl).toJson()
        } else {
            JSONObject().apply {
                put("agents", JSONArray().apply {
                    registry.getAllAgentCards(baseUrl).forEach { put(it.toJson()) }
                })
            }
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // ========== JSON-RPC ==========

    private fun serveJsonRpc(session: IHTTPSession, agentId: String): Response {
        val body = readBody(session)
        if (body.isEmpty()) {
            return jsonRpcError(null, JSONRPCError.PARSE_ERROR, "Empty request body")
        }

        val request = try {
            JSONRPCRequest.fromJson(JSONObject(body))
        } catch (e: Exception) {
            return jsonRpcError(null, JSONRPCError.PARSE_ERROR, "Invalid JSON: ${e.message}")
        }

        val agent = registry.getAgent(agentId)
        if (agent == null) {
            return jsonRpcResponse(request.id, JSONRPCError(
                JSONRPCError.METHOD_NOT_FOUND,
                "Agent not found: $agentId"
            ))
        }

        return handleJsonRpc(request, agent)
    }

    private fun serveJsonRpcBroadcast(session: IHTTPSession): Response {
        val body = readBody(session)
        if (body.isEmpty()) {
            return jsonRpcError(null, JSONRPCError.PARSE_ERROR, "Empty request body")
        }

        // Try to extract agentId from params
        val json = try { JSONObject(body) } catch (e: Exception) {
            return jsonRpcError(null, JSONRPCError.PARSE_ERROR, "Invalid JSON: ${e.message}")
        }

        val request = JSONRPCRequest.fromJson(json)
        val params = request.params
        val agentId = params?.optString("agentId", "").orEmpty()

        val agent = if (agentId.isNotEmpty()) {
            registry.getAgent(agentId)
        } else {
            // Default: return first agent (for single-agent scenarios)
            registry.getAllAgents().firstOrNull()
        }

        if (agent == null) {
            return jsonRpcResponse(request.id, JSONRPCError(
                JSONRPCError.METHOD_NOT_FOUND,
                "No agent available for request"
            ))
        }

        return handleJsonRpc(request, agent)
    }

    private fun handleJsonRpc(request: JSONRPCRequest, agent: A2AAgent): Response {
        Log.d(TAG, "JSON-RPC: method=${request.method}, agent=${agent.agentId}")

        val response = when (request.method) {
            A2AMethods.TASKS_SEND -> handleTaskSend(request, agent)
            A2AMethods.TASKS_SEND_SUBSCRIBE -> handleTaskSend(request, agent) // Simplified: no SSE
            A2AMethods.TASKS_GET -> handleTaskGet(request, agent)
            A2AMethods.TASKS_CANCEL -> handleTaskCancel(request, agent)
            A2AMethods.TASKS_LIST -> handleTaskList(request, agent)
            else -> JSONRPCResponse(
                id = request.id,
                error = JSONRPCError(JSONRPCError.METHOD_NOT_FOUND, "Unknown method: ${request.method}")
            )
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            response.toJson().toString()
        )
    }

    // ========== JSON-RPC Method Handlers ==========

    private fun handleTaskSend(request: JSONRPCRequest, agent: A2AAgent): JSONRPCResponse {
        val params = request.params ?: return JSONRPCResponse(
            id = request.id,
            error = JSONRPCError(JSONRPCError.INVALID_PARAMS, "Missing params")
        )

        val sendParams = try {
            TaskSendParams.fromJson(params)
        } catch (e: Exception) {
            return JSONRPCResponse(
                id = request.id,
                error = JSONRPCError(JSONRPCError.INVALID_PARAMS, "Invalid params: ${e.message}")
            )
        }

        // Process task asynchronously with timeout
        val task = runBlocking {
            withTimeoutOrNull(TASK_TIMEOUT_MS) {
                agent.processTask(sendParams)
            }
        }

        if (task == null) {
            return JSONRPCResponse(
                id = request.id,
                error = JSONRPCError(
                    JSONRPCError.INTERNAL_ERROR,
                    "Task processing timed out (${TASK_TIMEOUT_MS}ms)"
                )
            )
        }

        // Store task
        taskStore[task.id] = task

        return JSONRPCResponse(
            id = request.id,
            result = task.toJson()
        )
    }

    private fun handleTaskGet(request: JSONRPCRequest, agent: A2AAgent): JSONRPCResponse {
        val params = request.params ?: return JSONRPCResponse(
            id = request.id,
            error = JSONRPCError(JSONRPCError.INVALID_PARAMS, "Missing params")
        )

        val taskId = params.optString("id")
        val historyLength = params.optInt("historyLength", -1).takeIf { it >= 0 }

        // Check local store first
        val cachedTask = taskStore[taskId]
        if (cachedTask != null) {
            return JSONRPCResponse(id = request.id, result = cachedTask.toJson())
        }

        // Ask agent
        val task = runBlocking { agent.getTask(taskId, historyLength) }
        return if (task != null) {
            JSONRPCResponse(id = request.id, result = task.toJson())
        } else {
            JSONRPCResponse(
                id = request.id,
                error = JSONRPCError(JSONRPCError.TASK_NOT_FOUND, "Task not found: $taskId")
            )
        }
    }

    private fun handleTaskCancel(request: JSONRPCRequest, agent: A2AAgent): JSONRPCResponse {
        val params = request.params ?: return JSONRPCResponse(
            id = request.id,
            error = JSONRPCError(JSONRPCError.INVALID_PARAMS, "Missing params")
        )

        val taskId = params.optString("id")

        // Try to cancel via agent
        val task = runBlocking { agent.cancelTask(taskId) }
        if (task != null) {
            taskStore[taskId] = task
            return JSONRPCResponse(id = request.id, result = task.toJson())
        }

        // Check if task exists in store
        val cachedTask = taskStore[taskId]
        if (cachedTask != null) {
            val canceledTask = cachedTask.copy(
                status = TaskStatus(
                    state = TaskState.CANCELED,
                    timestamp = isoTimestamp()
                )
            )
            taskStore[taskId] = canceledTask
            return JSONRPCResponse(id = request.id, result = canceledTask.toJson())
        }

        return JSONRPCResponse(
            id = request.id,
            error = JSONRPCError(JSONRPCError.TASK_NOT_FOUND, "Task not found: $taskId")
        )
    }

    private fun handleTaskList(request: JSONRPCRequest, agent: A2AAgent): JSONRPCResponse {
        val tasks = taskStore.values
            .filter { it.id.startsWith(agent.agentId) }
            .sortedByDescending { it.status.timestamp }

        val result = JSONObject().apply {
            put("tasks", JSONArray().apply {
                tasks.forEach { put(it.toJson()) }
            })
        }

        return JSONRPCResponse(id = request.id, result = result)
    }

    // ========== Helpers ==========

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun jsonRpcResponse(id: Any?, error: JSONRPCError): Response {
        val response = JSONRPCResponse(id = id, error = error)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            response.toJson().toString()
        )
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): Response {
        val response = JSONRPCResponse(id = id, error = JSONRPCError(code, message))
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            response.toJson().toString()
        )
    }

    private fun serveHealthCheck(): Response {
        val json = JSONObject().apply {
            put("status", "running")
            put("protocol", "A2A")
            put("agents", registry.getAllAgentIds())
            put("port", port)
            put("timestamp", isoTimestamp())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** Generate ISO 8601 timestamp */
    private fun isoTimestamp(): String {
        return java.time.Instant.now().toString()
    }

    /** Start the server */
    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "A2A Server started on port $port")
            Log.i(TAG, "  Agent Cards: http://0.0.0.0:$port/.well-known/agent.json")
            Log.i(TAG, "  Registered agents: ${registry.getAllAgentIds()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start A2A server: ${e.message}", e)
        }
    }

    /** Stop the server */
    fun stopServer() {
        try {
            stop()
            scope.cancel()
            Log.i(TAG, "A2A Server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping A2A server: ${e.message}")
        }
    }
}
