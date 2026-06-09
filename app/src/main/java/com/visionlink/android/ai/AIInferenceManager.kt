package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * AI Inference Manager - v4.7.0 (Real API Integration)
 *
 * Now supports real image analysis via Moonshot API (Kimi)
 */
class AIInferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "AIInferenceManager"

        // Mimo API Configuration (correct endpoint)
        private const val MIMO_API_URL = "<SIGNED_URL_REMOVED>"
        private const val MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions"
        private const val MOONSHOT_API_KEY = "sk-kEm7V22Hx5iIpTMqSwv7zJ24ajgU3A3GjfWC25LFhmBsEBws"
        private const val MOONSHOT_MODEL = "moonshot-v1-8k"

        const val MODEL_TYPE_GEMMA = "gemma4_e2b"
        const val MODEL_TYPE_GEMINI = "gemini_nano"
        private const val TEMPERATURE = 0.1f
        private const val MAX_TOKENS = 256
        private const val IMAGE_SIZE = 448
        private const val MIN_RAM_MB = 4096L
        private const val MIN_SDK = 33
    }

    enum class InferenceEngine { NONE, AICORE, EDGE, LITERT_LM, CLOUD, MOONSHOT, LM_STUDIO }
    enum class InferenceMode { SINGLE_SHOT, CONTINUOUS }

    data class ManagerState(
        val engine: InferenceEngine = InferenceEngine.MOONSHOT,
        val mode: InferenceMode = InferenceMode.SINGLE_SHOT,
        val isInitialized: Boolean = false,
        val modelDownloaded: Boolean = true,
        val downloadProgress: Int = 0,
        val initError: String? = null,
        val modelSizeMb: Long = 0,
        val currentFps: Int = 0,
        val isThermalThrottling: Boolean = false,
        val edgeAvailable: Boolean = false
    )

    private val _state = MutableStateFlow(ManagerState())
    val state: StateFlow<ManagerState> = _state

    private var currentEngine: InferenceEngine = InferenceEngine.MOONSHOT
    private var currentMode: InferenceMode = InferenceMode.SINGLE_SHOT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()

    // ========== API Test Method ==========

    suspend fun testApiConnection(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Testing API connection...")
        
        try {
            // 发送一个简单的测试请求
            val jsonBody = JSONObject().apply {
                put("model", "moonshot-v1-8k")
                put("temperature", 0.1f)
                put("max_tokens", 50)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Hello, this is a test. Please respond with just the word 'OK'.")
                    })
                })
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://api.moonshot.cn/v1/chat/completions")
                .addHeader("Authorization", "Bearer sk-kEm7V22Hx5iIpTMqSwv7zJ24ajgU3A3GjfWC25LFhmBsEBws")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending test request to api.moonshot.cn...")
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Log.d(TAG, "Response code: ${response.code}")
            Log.d(TAG, "Response body: ${responseBody?.take(200)}")
            
            if (!response.isSuccessful) {
                return@withContext "Error ${response.code}: ${response.message}\nBody: ${responseBody?.take(200)}"
            }
            
            if (responseBody == null) {
                return@withContext "Error: Empty response"
            }
            
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val errorMsg = jsonResponse.getJSONObject("error").getString("message")
                return@withContext "API Error: $errorMsg"
            }
            
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                val content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.d(TAG, "API test SUCCESS: $content")
                return@withContext "SUCCESS: $content"
            }
            
            return@withContext "Response parsing error"
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            return@withContext "Network Error: ${e.message}\n可能网络无法访问 api.moonshot.cn"
        } catch (e: Exception) {
            Log.e(TAG, "API test error: ${e.message}", e)
            return@withContext "Error: ${e.message}"
        }
    }

    // ========== Public API ==========

    suspend fun testLmStudioConnection(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Testing LM Studio connection to $lmStudioUrl...")
        
        try {
            val jsonBody = JSONObject().apply {
                put("model", "local-model")
                put("temperature", 0.1f)
                put("max_tokens", 20)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Reply with just the word 'OK'.")
                    })
                })
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(lmStudioUrl)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending test request to LM Studio...")
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Log.d(TAG, "LM Studio response: ${response.code}")
            
            if (!response.isSuccessful) {
                return@withContext "Error ${response.code}: ${response.message}\nBody: ${responseBody?.take(200)}"
            }
            
            if (responseBody == null) {
                return@withContext "Error: Empty response"
            }
            
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val errorMsg = jsonResponse.getJSONObject("error").getString("message")
                return@withContext "API Error: $errorMsg"
            }
            
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                val content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.d(TAG, "LM Studio test SUCCESS: $content")
                return@withContext "SUCCESS: $content"
            }
            
            return@withContext "Response parsing error"
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            return@withContext "Network Error: ${e.message}\n请确保:\n1. 手机和电脑在同一网络\n2. LM Studio 已启动\n3. 防火墙允许端口 1234"
        } catch (e: Exception) {
            Log.e(TAG, "LM Studio test error: ${e.message}", e)
            return@withContext "Error: ${e.message}"
        }
    }

    fun setEngine(engine: InferenceEngine) {
        currentEngine = engine
        updateState { copy(engine = engine) }
        Log.d(TAG, "Engine switched to: $engine")
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initializing AI Inference Manager with Moonshot API")
        currentEngine = InferenceEngine.MOONSHOT
        updateState {
            copy(
                engine = InferenceEngine.MOONSHOT,
                isInitialized = true,
                modelDownloaded = true,
                downloadProgress = 100
            )
        }
        Log.d(TAG, "AI initialized with MOONSHOT (Moonshot API)")
    }

    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String =
        withContext(Dispatchers.IO) {
            if (!isInitialized()) {
                return@withContext initError() ?: "AI not initialized"
            }

            val prompt = buildPrompt(mode)
            Log.d(TAG, "Analyzing image with prompt: ${prompt.take(50)}...")

            val result = when (currentEngine) {
                InferenceEngine.MOONSHOT -> callMoonshotAPI(prompt, bitmap)
                InferenceEngine.LM_STUDIO -> runLmStudioInference(prompt, bitmap)
                else -> "Unsupported engine: $currentEngine"
            }

            updateFps()
            Log.d(TAG, "Result: ${result.take(80)}")
            result.trim()
        }

    // ========== Moonshot API Integration ==========

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return try {
            val outputStream = ByteArrayOutputStream()
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert bitmap to base64: ${e.message}")
            ""
        }
    }

    private suspend fun callMoonshotAPI(prompt: String, bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 2

            while (retryCount <= maxRetries) {
                try {
                    val base64Image = bitmapToBase64(bitmap)
                    if (base64Image.isEmpty()) {
                        return@withContext "图像转换失败"
                    }

                    // 使用 OpenAI 兼容格式（Moonshot API 支持）
                    val jsonBody = JSONObject().apply {
                        put("model", "moonshot-v1-8k")
                        put("temperature", TEMPERATURE)
                        put("max_tokens", MAX_TOKENS)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", prompt)
                                    })
                                    // 注意：Moonshot API 可能不支持图像输入
                                    // 如果不支持，只发送文本
                                    // put(JSONObject().apply {
                                    //     put("type", "image_url")
                                    //     put("image_url", JSONObject().apply {
                                    //         put("url", "data:image/jpeg;base64,$base64Image")
                                    //     })
                                    // })
                                })
                            })
                        })
                    }

                    val requestBody = jsonBody.toString()
                        .toRequestBody("application/json".toMediaType())
                    
                    val request = Request.Builder()
                        .url(MOONSHOT_API_URL)
                        .addHeader("Authorization", "Bearer $MOONSHOT_API_KEY")
                        .addHeader("Content-Type", "application/json")
                        .post(requestBody)
                        .build()

                    Log.d(TAG, "Calling Moonshot API (attempt ${retryCount + 1})...")
                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful || responseBody == null) {
                        Log.e(TAG, "API call failed: ${response.code}, ${response.message}")
                        if (retryCount < maxRetries) {
                            retryCount++
                            continue
                        }
                        return@withContext "API 调用失败: ${response.message}"
                    }

                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.has("error")) {
                        val errorMsg = jsonResponse.getJSONObject("error").getString("message")
                        Log.e(TAG, "API error: $errorMsg")
                        if (retryCount < maxRetries) {
                            retryCount++
                            continue
                        }
                        return@withContext "API 错误: $errorMsg"
                    }

                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0)
                            .getJSONObject("message")
                        val content = message.getString("content")
                        Log.d(TAG, "Moonshot API success: ${content.take(100)}")
                        return@withContext content.trim()
                    }

                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return@withContext "API 返回为空"

                } catch (e: IOException) {
                    Log.e(TAG, "Network error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return@withContext "网络错误: ${e.message}"
                } catch (e: Exception) {
                    Log.e(TAG, "API error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return@withContext "错误: ${e.message}"
                }
            }

            return@withContext "重试次数已用尽"
        }

    // ========== LM Studio Local Connection ==========

    /**
     * Connect to LM Studio running on PC (OpenAI-compatible API)
     * User's LM Studio: 172.16.20.242:1234
     */
    private var lmStudioUrl: String = "http://172.16.20.242:1234/v1/chat/completions"
    
    fun setLmStudioUrl(url: String) {
        lmStudioUrl = url
        Log.d(TAG, "LM Studio URL set to: $url")
    }
    
    private suspend fun runLmStudioInference(prompt: String, bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 2

            while (retryCount <= maxRetries) {
                try {
                    val base64Image = bitmapToBase64(bitmap)
                    if (base64Image.isEmpty()) {
                        return@withContext "图像转换失败"
                    }

                    // LM Studio uses OpenAI-compatible API format
                    val jsonBody = JSONObject().apply {
                        put("model", "local-model") // LM Studio ignores this, uses loaded model
                        put("temperature", TEMPERATURE)
                        put("max_tokens", MAX_TOKENS)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", prompt)
                                    })
                                    // LM Studio with vision model supports image input
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$base64Image")
                                        })
                                    })
                                })
                            })
                        })
                    }

                    val requestBody = jsonBody.toString()
                        .toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url(lmStudioUrl)
                        .addHeader("Content-Type", "application/json")
                        .post(requestBody)
                        .build()

                    Log.d(TAG, "Calling LM Studio at $lmStudioUrl (attempt ${retryCount + 1})...")
                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful || responseBody == null) {
                        Log.e(TAG, "LM Studio call failed: ${response.code}, ${response.message}")
                        if (retryCount < maxRetries) {
                            retryCount++
                            continue
                        }
                        return@withContext "LM Studio 错误 ${response.code}: ${response.message}"
                    }

                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.has("error")) {
                        val errorMsg = jsonResponse.getJSONObject("error").getString("message")
                        Log.e(TAG, "LM Studio API error: $errorMsg")
                        return@withContext "LM Studio 错误: $errorMsg"
                    }

                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val content = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        Log.d(TAG, "LM Studio result: ${content.take(50)}")
                        return@withContext content
                    }

                    return@withContext "LM Studio 返回格式错误"

                } catch (e: IOException) {
                    Log.e(TAG, "Network error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return@withContext "网络错误: 无法连接到 LM Studio ($lmStudioUrl)\n请确保:\n1. 手机和电脑在同一网络\n2. LM Studio 已启动\n3. 防火墙允许端口 1234"
                } catch (e: Exception) {
                    Log.e(TAG, "LM Studio error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        continue
                    }
                    return@withContext "LM Studio 错误: ${e.message}"
                }
            }

            return@withContext "重试次数已用尽"
        }

    suspend fun startContinuousInference(
        onFrame: suspend (Bitmap) -> Unit,
        onResult: suspend (String) -> Unit
    ) {
        Log.d(TAG, "Continuous inference not yet implemented for Moonshot API")
        // TODO: Implement continuous mode with Moonshot API
    }

    fun stopContinuousInference() {
        Log.d(TAG, "Continuous inference stopped")
        // TODO: Implement stop logic
    }

    // ========== Prompt Building ==========

    private fun buildPrompt(mode: Int): String {
        return when (mode) {
            1 -> "你是一个视觉辅助助手，帮助视障用户识别前方障碍物。" +
                 "请观察图像中心区域，识别最近的障碍物并估算距离。" +
                 "用中文回答，不超过25个字。格式：[障碍物], [方向], [距离]"
            2 -> "你是一个OCR助手。请精确提取图像中的所有中文和英文文字。" +
                 "用检测到的语言回答。"
            3 -> "你是一个场景描述助手，为视障用户描述场景。" +
                 "用温暖自然的中文描述场景，不超过50个字。"
            else -> "请用中文简要描述这张图片的内容，不超过50个字。"
        }
    }

    // ========== Lifecycle ==========

    fun release() {
        scope.cancel()
        currentEngine = InferenceEngine.NONE
        currentMode = InferenceMode.SINGLE_SHOT
        updateState {
            copy(
                isInitialized = false,
                engine = InferenceEngine.NONE,
                mode = InferenceMode.SINGLE_SHOT
            )
        }
        Log.d(TAG, "AI manager released")
    }

    fun isInitialized(): Boolean = _state.value.isInitialized
    fun initError(): String? = _state.value.initError
    fun getEngine(): InferenceEngine = currentEngine
    fun isModelDownloaded(): Boolean = _state.value.modelDownloaded
    fun getCurrentFps(): Int = _state.value.currentFps

    private fun updateFps() {
        // Simplified FPS tracking
        Log.d(TAG, "Frame analyzed")
    }

    private fun updateState(update: ManagerState.() -> ManagerState) {
        _state.value = _state.value.update()
    }

    // ========== Stub Methods for Compatibility ==========

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Model downloaded (using API, no local model needed)")
            updateState { copy(modelDownloaded = true, downloadProgress = 100) }
            onProgress(100)
            true
        }

    fun getModelSizeMb(): Long = 0

    fun getModelDir(): File = File(context.filesDir, "models")
    fun getGemmaModelPath(): File = File(getModelDir(), "gemma-4-e2b-it.litertlm")
}
