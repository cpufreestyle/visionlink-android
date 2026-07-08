package com.visionlink.android.a2a.openclaw

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenClaw Gateway WebSocket RPC 客户端
 *
 * 实现 OpenClaw Gateway 的 WebSocket RPC 协议，通过本地 Gateway 与 Agent 交互。
 *
 * 协议流程:
 * 1. 连接 WebSocket (ws://127.0.0.1:18789)
 * 2. 收到 connect.challenge 事件
 * 3. 发送 connect 帧 (含 auth token、protocol version)
 * 4. 收到 connect ack
 * 5. 发送 agent 请求帧 (message, agentId, sessionKey)
 * 6. 收到流式 agent 事件 (payload.data.delta)
 * 7. 收到 chat 事件 (state=final 表示完成)
 *
 * @param gatewayUrl Gateway 地址，如 "http://127.0.0.1:18789"
 * @param gatewayToken 认证 token (可选)
 * @param agentId 目标 Agent ID，默认 "main"
 * @param timeoutMs 超时时间（毫秒）
 */
class OpenClawGatewayClient(
    private val gatewayUrl: String = "http://127.0.0.1:18789",
    private val gatewayToken: String = "",
    private val agentId: String = "main",
    private val timeoutMs: Long = 60_000L
) {
    companion object {
        private const val TAG = "OpenClawGateway"
        private const val PROTOCOL_VERSION = 3
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    /**
     * 发送消息给 OpenClaw Agent，收集所有流式响应后返回完整文本
     *
     * @param message 用户消息文本
     * @param sessionKey 会话 key（可选，用于多轮对话）
     * @return Agent 回复的完整文本，或 null 表示失败
     */
    suspend fun sendMessage(message: String, sessionKey: String? = null): String? {
        return withContext(Dispatchers.IO) {
            val wsUrl = toWsUrl(gatewayUrl)
            Log.i(TAG, "Connecting to OpenClaw Gateway: $wsUrl (agentId=$agentId)")

            val result = CompletableDeferred<String?>()
            val deltas = StringBuilder()
            var connectAcked = false
            var agentRequestSent = false
            val connectRequestId = UUID.randomUUID().toString()
            val agentRequestId = UUID.randomUUID().toString()
            val effectiveSessionKey = sessionKey ?: UUID.randomUUID().toString()
            val idempotencyKey = UUID.randomUUID().toString()

            val requestBuilder = Request.Builder().url(wsUrl)
            if (gatewayToken.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $gatewayToken")
            }

            val webSocket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to $wsUrl")
                    // 等待 connect.challenge，不主动发消息
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "<-- RECV: ${text.take(300)}")

                    val parsed = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid JSON: ${text.take(100)}")
                        return
                    }

                    // 1. 收到 connect.challenge → 发送 connect 帧
                    if (parsed.optString("event") == "connect.challenge") {
                        val connectFrame = JSONObject().apply {
                            put("type", "req")
                            put("id", connectRequestId)
                            put("method", "connect")
                            put("params", JSONObject().apply {
                                put("minProtocol", PROTOCOL_VERSION)
                                put("maxProtocol", PROTOCOL_VERSION)
                                put("client", JSONObject().apply {
                                    put("id", "visionlink-android")
                                    put("version", "1.0.0")
                                    put("platform", "android")
                                    put("mode", "cli")
                                })
                                put("caps", org.json.JSONArray())
                                put("role", "operator")
                                put("scopes", org.json.JSONArray().put("operator.admin"))
                                if (gatewayToken.isNotEmpty()) {
                                    put("auth", JSONObject().put("token", gatewayToken))
                                }
                            })
                        }
                        Log.d(TAG, "--> SEND connect: ${connectFrame.toString().take(200)}")
                        webSocket.send(connectFrame.toString())
                        return
                    }

                    // 2. 收到 connect ack → 发送 agent 请求
                    if (!connectAcked && parsed.has("id") && parsed.has("ok")) {
                        connectAcked = true
                        if (!parsed.optBoolean("ok")) {
                            val errorMsg = parsed.optJSONObject("error")?.optString("message") ?: "Connect failed"
                            Log.e(TAG, "Connect failed: $errorMsg")
                            result.complete(null)
                            webSocket.close(1000, "Connect failed")
                            return
                        }

                        if (!agentRequestSent) {
                            agentRequestSent = true
                            val agentFrame = JSONObject().apply {
                                put("type", "req")
                                put("id", agentRequestId)
                                put("method", "agent")
                                put("params", JSONObject().apply {
                                    put("message", message)
                                    put("agentId", agentId)
                                    put("sessionKey", effectiveSessionKey)
                                    put("idempotencyKey", idempotencyKey)
                                    put("deliver", false)
                                })
                            }
                            Log.d(TAG, "--> SEND agent: ${agentFrame.toString().take(300)}")
                            webSocket.send(agentFrame.toString())
                        }
                        return
                    }

                    // 3. 处理流式事件
                    val event = parsed.optString("event")
                    if (event.isNotEmpty()) {
                        val payload = parsed.optJSONObject("payload") ?: return

                        // agent 事件 - 流式 delta
                        if (event == "agent") {
                            val stream = payload.optString("stream")
                            val data = payload.optJSONObject("data")
                            if (stream == "assistant" && data != null) {
                                val delta = data.optString("delta")
                                if (delta.isNotEmpty()) {
                                    deltas.append(delta)
                                    Log.d(TAG, "  delta: $delta")
                                }
                            }
                            // agent phase=end → 完成
                            if (data?.optString("phase") == "end") {
                                Log.i(TAG, "Agent phase=end, response length=${deltas.length}")
                                result.complete(deltas.toString())
                                webSocket.close(1000, "Done")
                                return
                            }
                        }

                        // chat 事件 - 状态变更
                        if (event == "chat") {
                            val state = payload.optString("state")
                            when (state) {
                                "final" -> {
                                    Log.i(TAG, "Chat final, response length=${deltas.length}")
                                    result.complete(deltas.toString())
                                    webSocket.close(1000, "Done")
                                }
                                "error" -> {
                                    val errMsg = payload.optString("errorMessage", "Agent error")
                                    Log.e(TAG, "Chat error: $errMsg")
                                    result.complete(null)
                                    webSocket.close(1000, "Error")
                                }
                                "aborted" -> {
                                    Log.w(TAG, "Chat aborted")
                                    result.complete(deltas.toString().ifEmpty { null })
                                    webSocket.close(1000, "Aborted")
                                }
                            }
                        }
                        return
                    }

                    // 4. agent 请求的响应帧
                    if (parsed.optString("id") == agentRequestId && !parsed.optBoolean("ok", true)) {
                        val errMsg = parsed.optJSONObject("error")?.optString("message") ?: "RPC error"
                        Log.e(TAG, "Agent RPC error: $errMsg")
                        result.complete(null)
                        webSocket.close(1000, "RPC error")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code $reason")
                    if (!result.isCompleted) {
                        result.complete(deltas.toString().ifEmpty { null })
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    if (!result.isCompleted) {
                        result.complete(deltas.toString().ifEmpty { null })
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)
                    if (!result.isCompleted) {
                        result.complete(null)
                    }
                }
            })

            // 等待结果，带超时
            val response = withTimeoutOrNull(timeoutMs) { result.await() }

            // 确保关闭 WebSocket
            try {
                webSocket.cancel()
            } catch (_: Exception) {}

            if (response == null) {
                Log.e(TAG, "Timeout or failure after ${timeoutMs}ms")
            } else {
                Log.i(TAG, "OpenClaw response: ${response.take(100)}...")
            }

            response
        }
    }

    /**
     * 检查 Gateway 是否可达
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = sendMessage("ping")
            result != null
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed: ${e.message}")
            false
        }
    }

    /**
     * 将 HTTP URL 转为 WebSocket URL
     */
    private fun toWsUrl(url: String): String {
        return url
            .replace("http://", "ws://")
            .replace("https://", "wss://")
    }
}

/**
 * OpenClaw Gateway 异常
 */
class OpenClawGatewayException(
    val code: String,
    message: String
) : Exception(message)
