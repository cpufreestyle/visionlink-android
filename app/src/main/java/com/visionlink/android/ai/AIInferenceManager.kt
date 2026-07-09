package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ConnectionPool
import org.json.JSONArray
import org.json.JSONObject
import com.google.ai.edge.litertlm.*
import com.visionlink.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI Inference Manager - v4.7.0 (Real API Integration)
 *
 * Now supports real image analysis via Moonshot API (Kimi)
 */
class AIInferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "AIInferenceManager"

        // StepFun API Configuration (阶跃星辰)
        private const val STEPFUN_API_URL = "https://api.stepfun.com/v1/chat/completions"
        private val STEPFUN_API_KEY = BuildConfig.STEPFUN_API_KEY
        private const val STEPFUN_TEXT_MODEL = "step-3.5-flash"
        // 图像分析用 vision 模型
        private const val STEPFUN_VISION_MODEL = "step-1o-turbo-vision"
        
        // Google AI Edge LiteRT-LM Configuration
        private const val GEMMA_MODEL_NAME = "gemma-4-e2b-it"
        private const val MODEL_DIR = "litert_models"

        const val MODEL_TYPE_GEMMA = "gemma4_e2b"
        const val MODEL_TYPE_GEMINI = "gemini_nano"
        private const val TEMPERATURE = 0.1f
        private const val MAX_TOKENS = 256
        private const val IMAGE_SIZE = 448
        private const val MIN_RAM_MB = 4096L
        private const val MIN_SDK = 33
    }

    enum class InferenceEngine { NONE, AICORE, EDGE, LITERT_LM, CLOUD, STEPFUN, LM_STUDIO, CUSTOM, YOLO }
    enum class InferenceMode { SINGLE_SHOT, CONTINUOUS }

    data class ManagerState(
        val engine: InferenceEngine = InferenceEngine.STEPFUN,
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

    private var currentEngine: InferenceEngine = InferenceEngine.STEPFUN
    private var currentMode: InferenceMode = InferenceMode.SINGLE_SHOT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
    private val pingHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ========== API Test Method ==========


    suspend fun testApiConnection(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Testing API connection...")
        
        try {
            // 发送一个简单的测试请求
            val jsonBody = JSONObject().apply {
                put("model", STEPFUN_TEXT_MODEL)
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
                .url(STEPFUN_API_URL)
                .addHeader("Authorization", "Bearer ${BuildConfig.STEPFUN_API_KEY_TEST}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending test request to api.stepfun.com...")
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Log.d(TAG, "Response code: ${response.code}")
            Log.d(TAG, "Response body: ${responseBody?.take(200)}")
            
            if (!response.isSuccessful) {
                return@withContext "错误 ${response.code}: ${response.message}\n${responseBody?.take(200)}"
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
            return@withContext "网络错误: ${e.message}\n可能网络无法访问 api.stepfun.com"
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

    // ========== Custom API Configuration ==========

    private var customConfig: ModelApiConfig? = null

    /**
     * 设置自定义 API 配置并切换到 CUSTOM 引擎
     */
    fun setCustomConfig(config: ModelApiConfig) {
        customConfig = config
        setEngine(InferenceEngine.CUSTOM)
        Log.d(TAG, "Custom API config set: ${config.name} -> ${config.apiUrl}")
    }

    /**
     * 获取当前自定义配置
     */
    fun getCustomConfig(): ModelApiConfig? = customConfig

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initializing AI Inference Manager with ${currentEngine.name}")

        // 对 Moonshot 引擎做一次 ping 检查，验证 API Key 有效
        // 注意：ping 失败不阻塞初始化，仅记录警告（网络波动不应阻止引擎切换）
        if (currentEngine == InferenceEngine.STEPFUN) {
            val pingOk = pingMoonshotAPI()
            if (!pingOk) {
                Log.w(TAG, "StepFun API ping failed — 可能是网络波动，初始化继续")
            }
        }

        // YOLO 引擎：初始化端侧物体检测器
        if (currentEngine == InferenceEngine.YOLO) {
            if (yoloDetector == null) {
                yoloDetector = YoloDetector(context)
            }
            val ok = yoloDetector!!.initialize()
            if (!ok) {
                updateState {
                    copy(
                        engine = currentEngine,
                        isInitialized = false,
                        initError = "YOLO 检测器初始化失败"
                    )
                }
                return@withContext false
            }
            Log.i(TAG, "YOLO engine initialized (EfficientDet-Lite0)")
        }

        // 对自定义 API 引擎做连通性检查
        if (currentEngine == InferenceEngine.CUSTOM) {
            val config = customConfig
            if (config == null) {
                updateState {
                    copy(
                        engine = currentEngine,
                        isInitialized = false,
                        initError = "未配置自定义 API"
                    )
                }
                return@withContext false
            }
            if (config.apiUrl.isBlank() || config.apiKey.isBlank()) {
                updateState {
                    copy(
                        engine = currentEngine,
                        isInitialized = false,
                        initError = "API URL 和 Key 不能为空"
                    )
                }
                return@withContext false
            }
            Log.i(TAG, "Custom API [${config.name}] configured: ${config.apiUrl}")
        }

        updateState {
            copy(
                engine = currentEngine,
                isInitialized = true,
                modelDownloaded = true,
                downloadProgress = 100,
                initError = null
            )
        }
        Log.d(TAG, "AI initialized with ${currentEngine.name}")
        true
    }

    /**
     * Ping Moonshot API 验证连通性
     */
    private suspend fun pingMoonshotAPI(): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = pingHttpClient

            val requestBody = JSONObject().apply {
                put("model", STEPFUN_TEXT_MODEL)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    })
                })
                put("max_tokens", 1)
            }.toString()

            val request = Request.Builder()
                .url(STEPFUN_API_URL)
                .addHeader("Authorization", "Bearer $STEPFUN_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful || response.code == 400  // 400 可能是 max_tokens 太小但 Key 有效
        } catch (e: Exception) {
            Log.e(TAG, "Moonshot ping error: ${e.message}")
            false
        }
    }

    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String =
        withContext(Dispatchers.IO) {
            if (!isInitialized()) {
                return@withContext initError() ?: "AI not initialized"
            }

            val prompt = buildPrompt(mode)
            Log.d(TAG, "Analyzing image with prompt: ${prompt.take(50)}...")

            val result = when (currentEngine) {
                InferenceEngine.STEPFUN -> callMoonshotAPI(prompt, bitmap)
                InferenceEngine.LM_STUDIO -> runLmStudioInference(prompt, bitmap)
                InferenceEngine.EDGE -> runEdgeInference(prompt, bitmap)
                InferenceEngine.CUSTOM -> callCustomAPI(prompt, bitmap)
                InferenceEngine.YOLO -> {
                    // YOLO 引擎：模式2用离线OCR，其他模式用物体检测
                    if (mode == 2) runOcrRecognition(bitmap)
                    else runYoloDetection(bitmap, mode)
                }
                else -> "Unsupported engine: $currentEngine"
            }

            updateFps()
            Log.d(TAG, "Result: ${result.take(80)}")
            result.trim()
        }

    // ========== Perplexity API Integration ==========


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
            val maxRetries = 3

            while (retryCount <= maxRetries) {
                try {
                    val base64Image = bitmapToBase64(bitmap)
                    if (base64Image.isEmpty()) {
                        return@withContext "图像转换失败"
                    }

                    // OpenAI 兼容格式 + vision 模型，图像随请求一起发送
                    val jsonBody = JSONObject().apply {
                        put("model", STEPFUN_VISION_MODEL)
                        put("temperature", TEMPERATURE)
                        put("max_tokens", MAX_TOKENS)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$base64Image")
                                        })
                                    })
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }

                    val requestBody = jsonBody.toString()
                        .toRequestBody("application/json".toMediaType())
                    
                    val request = Request.Builder()
                        .url(STEPFUN_API_URL)
                        .addHeader("Authorization", "Bearer $STEPFUN_API_KEY")
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
                            delay(1500)
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
                        delay(1500)
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
                        delay(3000)
                        continue
                    }
                    return@withContext "网络错误: ${e.message}"
                } catch (e: Exception) {
                    Log.e(TAG, "API error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(2000)
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
     * Local OpenAI-compatible proxy. Use adb reverse for USB-connected devices.
     */
    private var lmStudioUrl: String = BuildConfig.LM_STUDIO_URL
    
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
                        delay(2000)
                        continue
                    }
                    return@withContext "网络错误: 无法连接到 LM Studio ($lmStudioUrl)\n请确保:\n1. 手机和电脑在同一网络\n2. LM Studio 已启动\n3. 防火墙允许端口 1234"
                } catch (e: Exception) {
                    Log.e(TAG, "LM Studio error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(1000)
                        continue
                    }
                    return@withContext "LM Studio 错误: ${e.message}"
                }
            }

            return@withContext "重试次数已用尽"
        }

    // ========== Custom API Integration (OpenAI-compatible) ==========

    /**
     * 调用用户自定义的 OpenAI 兼容 API
     */
    private suspend fun callCustomAPI(prompt: String, bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            val config = customConfig ?: return@withContext "未配置自定义 API"
            var retryCount = 0
            val maxRetries = 2

            while (retryCount <= maxRetries) {
                try {
                    val base64Image = bitmapToBase64(bitmap)
                    if (base64Image.isEmpty()) {
                        return@withContext "图像转换失败"
                    }

                    val jsonBody = JSONObject().apply {
                        put("model", config.visionModel)
                        put("temperature", TEMPERATURE)
                        put("max_tokens", MAX_TOKENS)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$base64Image")
                                        })
                                    })
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }

                    val requestBody = jsonBody.toString()
                        .toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url(config.apiUrl)
                        .addHeader("Authorization", "Bearer ${config.apiKey}")
                        .addHeader("Content-Type", "application/json")
                        .post(requestBody)
                        .build()

                    Log.d(TAG, "Calling Custom API [${config.name}] (attempt ${retryCount + 1})...")
                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful || responseBody == null) {
                        Log.e(TAG, "Custom API call failed: ${response.code}, ${response.message}")
                        if (retryCount < maxRetries) {
                            retryCount++
                            delay(1000)
                            continue
                        }
                        return@withContext "API 调用失败 [${config.name}]: ${response.code} ${response.message}"
                    }

                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.has("error")) {
                        val errorMsg = jsonResponse.getJSONObject("error").getString("message")
                        Log.e(TAG, "Custom API error: $errorMsg")
                        if (retryCount < maxRetries) {
                            retryCount++
                            delay(1000)
                            continue
                        }
                        return@withContext "API 错误 [${config.name}]: $errorMsg"
                    }

                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val content = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        Log.d(TAG, "Custom API success: ${content.take(100)}")
                        return@withContext content.trim()
                    }

                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(1000)
                        continue
                    }
                    return@withContext "API 返回为空"

                } catch (e: IOException) {
                    Log.e(TAG, "Custom API network error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(2000)
                        continue
                    }
                    return@withContext "网络错误 [${config.name}]: ${e.message}"
                } catch (e: Exception) {
                    Log.e(TAG, "Custom API error: ${e.message}", e)
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(1000)
                        continue
                    }
                    return@withContext "错误 [${config.name}]: ${e.message}"
                }
            }

            return@withContext "重试次数已用尽"
        }

    // ========== Prompt Building ==========

    private fun buildPrompt(mode: Int): String {
        return when (mode) {
            1 -> "你是视障用户的视觉辅助助手。请识别图像中最近的障碍物，" +
                 "估算其距离（以米为单位）和方向（偏左/正前方/偏右）。" +
                 "用中文回答，不超过20个字。格式示例：\"前方两米有台阶，偏左\""
            2 -> "请精确提取图像中所有可见的文字（中文和英文）。" +
                 "按从上到下、从左到右的顺序输出。只输出识别到的文字内容，不要添加解释。"
            3 -> "你是视障用户的场景描述助手。请用简洁自然的中文描述当前场景，" +
                 "包括环境类型、光线条件和主要物体。不超过30个字。" +
                 "格式示例：\"你在一个明亮的室内走廊\""
            else -> "请用中文简要描述这张图片的内容，不超过50个字。"
        }
    }

    // ========== Lifecycle ==========

    fun release() {
        scope.cancel()
        yoloDetector?.release()
        yoloDetector = null
        ocrRecognizer?.release()
        ocrRecognizer = null
        litertEngine?.close()
        litertEngine = null
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

    // ========== LiteRT-LM Engine Instance ==========
    private var litertEngine: Engine? = null

    // ========== YOLO Object Detector Instance ==========
    private var yoloDetector: YoloDetector? = null

    // ========== OCR Recognizer Instance (ML Kit) ==========
    private var ocrRecognizer: OcrRecognizer? = null

    // ========== Google AI Edge LiteRT-LM Inference ==========

    /**
     * 手动设置模型文件路径（由文件选择器调用）
     * 设置后下次初始化 EDGE 引擎时将使用此路径
     */
    fun setManualModelPath(path: String) {
        manualModelPath = path
        Log.w(TAG, "手动设置模型路径: $path")
    }

    private var manualModelPath: String? = null

    /**
     * 在多个可能的位置查找 .litertlm 模型文件
     * Google AI Edge Gallery 下载的模型可能存放在不同位置
     */
    private fun findModelFile(modelDir: File): File? {
        // 0. 优先使用手动指定的路径
        manualModelPath?.let { path ->
            val f = File(path)
            if (f.exists() && f.isFile && f.length() > 1_000_000) {
                Log.w(TAG, "使用手动指定的模型: ${f.absolutePath} (${f.length() / 1048576}MB)")
                return f
            }
        }

        // 1. 先查默认目录（递归）
        val inModelDir = scanForLitertlm(modelDir)
        if (inModelDir != null) return inModelDir

        // 2. 查内部存储 models 目录（getGemmaModelPath 使用的目录）
        val internalModels = File(context.filesDir, "models")
        scanForLitertlm(internalModels)?.let { return it }

        // 3. 查外部存储的常见下载位置
        val searchDirs = mutableListOf<File>()
        
        // /sdcard/Download/
        File(System.getenv("EXTERNAL_STORAGE") ?: "/sdcard", "Download")?.let { searchDirs.add(it) }
        // /sdcard/Documents/
        File(System.getenv("EXTERNAL_STORAGE") ?: "/sdcard", "Documents")?.let { searchDirs.add(it) }
        // /sdcard/ 根目录（某些下载工具会放在根目录）
        File(System.getenv("EXTERNAL_STORAGE") ?: "/sdcard")?.let { searchDirs.add(it) }
        // getExternalFilesDir(null)/ 根目录
        context.getExternalFilesDir(null)?.let { searchDirs.add(it) }
        // getExternalFilesDir(null)/litert_models/
        context.getExternalFilesDir(null)?.let { searchDirs.add(File(it, MODEL_DIR)) }
        // getExternalFilesDir(null)/models/
        context.getExternalFilesDir(null)?.let { searchDirs.add(File(it, "models")) }
        // getExternalFilesDir(null)/Download/
        context.getExternalFilesDir(null)?.let { searchDirs.add(File(it, "Download")) }
        // cacheDir/litert_models/
        searchDirs.add(File(context.cacheDir, MODEL_DIR))
        // cacheDir/models/
        searchDirs.add(File(context.cacheDir, "models"))
        // Google AI Edge Gallery 可能的存储路径
        File("/sdcard/Android/data/com.google.ai.edge.gallery/files")?.let { searchDirs.add(it) }
        File("/sdcard/Android/data/com.google.ai.edge.gallery/files/Download")?.let { searchDirs.add(it) }
        File("/sdcard/Android/data/com.google.ai.edge.gallery/files/models")?.let { searchDirs.add(it) }

        for (dir in searchDirs) {
            val found = scanForLitertlm(dir)
            if (found != null) {
                Log.w(TAG, "在备用位置找到模型: ${dir.absolutePath}")
                return found
            }
        }
        return null
    }

    /** 递归扫描目录下的 .litertlm 文件（最多 3 层深度） */
    private fun scanForLitertlm(dir: File, depth: Int = 0): File? {
        if (!dir.exists() || !dir.isDirectory || depth > 3) return null
        val files = dir.listFiles { f -> 
            f.isFile && f.extension.equals("litertlm", ignoreCase = true) && f.length() > 1_000_000
        }?.sortedByDescending { it.length() }
        
        // 直接找到文件
        if (!files.isNullOrEmpty()) return files.first()
        
        // 递归搜索子目录
        val subDirs = dir.listFiles { f -> f.isDirectory } ?: return null
        for (subDir in subDirs) {
            val found = scanForLitertlm(subDir, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private suspend fun runEdgeInference(prompt: String, bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Running LiteRT-LM inference with Gemma 4 E2B...")

                // 1. Find model file (.litertlm format) — 自动匹配任意文件名
                val modelDir = File(context.filesDir, MODEL_DIR)
                if (!modelDir.exists()) modelDir.mkdirs()

                val modelFile = findModelFile(modelDir)
                if (modelFile == null || !modelFile.exists()) {
                    Log.w(TAG, "No .litertlm model found in ${modelDir.absolutePath}")
                    val existing = modelDir.listFiles()?.joinToString { it.name } ?: "(empty)"
                    Log.w(TAG, "Files in model dir: $existing")
                    return@withContext "错误: 本地模型未下载。请从 Google AI Edge Gallery 下载 Gemma 模型，或将 .litertlm 文件复制到 ${modelDir.absolutePath}\n当前目录文件: $existing"
                }
                Log.i(TAG, "Using model: ${modelFile.name} (${modelFile.length() / 1048576}MB)")

                // 2. Initialize LiteRT-LM Engine
                if (litertEngine == null) {
                    val engineConfig = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.GPU(),
                        visionBackend = Backend.GPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                    litertEngine = Engine(engineConfig)
                    litertEngine!!.initialize()
                    Log.d(TAG, "LiteRT-LM engine initialized")
                }

                // 3. Create conversation with multimodal input
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of("You are a vision assistant. Describe what you see in the image concisely."),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.4)
                )

                litertEngine!!.createConversation(conversationConfig).use { conversation ->
                    // 4. Send image + text prompt
                    // Convert bitmap to byte array for ImageBytes content
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val imageBytes = stream.toByteArray()

                    val contents = Contents.of(
                        Content.ImageBytes(imageBytes),
                        Content.Text(prompt.ifEmpty { "Describe this image in detail." })
                    )

                    val response = conversation.sendMessage(contents)
                    val responseText = response.toString()
                    Log.d(TAG, "LiteRT-LM response: $responseText")
                    responseText
                }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM inference failed: ${e.message}", e)
                // Release engine on error
                litertEngine?.close()
                litertEngine = null
                "错误: LiteRT-LM 推理失败 - ${e.message}"
            }
        }

    /**
     * Test LiteRT-LM engine connection (check model exists and engine can load)
     */
    suspend fun testEdgeConnection(): String = withContext(Dispatchers.IO) {
        try {
            // 搜索所有可能的位置
            val modelDir = File(context.filesDir, MODEL_DIR)
            val modelFile = findModelFile(modelDir)

            if (modelFile == null) {
                // 列出所有搜索过的目录及其内容
                val searchPaths = mutableListOf<String>()
                searchPaths.add("${modelDir.absolutePath}: ${modelDir.listFiles()?.joinToString { it.name } ?: "(不存在)"}")
                val internalModels = File(context.filesDir, "models")
                searchPaths.add("${internalModels.absolutePath}: ${internalModels.listFiles()?.joinToString { it.name } ?: "(不存在)"}")
                context.getExternalFilesDir(null)?.let { 
                    searchPaths.add("${it.absolutePath}: ${it.listFiles()?.joinToString { it.name } ?: "(空)"}")
                    val d = File(it, MODEL_DIR)
                    searchPaths.add("${d.absolutePath}: ${d.listFiles()?.joinToString { it.name } ?: "(不存在)"}")
                }
                val downloadDir = File(System.getenv("EXTERNAL_STORAGE") ?: "/sdcard", "Download")
                val litertlmFiles = downloadDir.listFiles { f -> f.extension.equals("litertlm", ignoreCase = true) }
                searchPaths.add("${downloadDir.absolutePath}: ${litertlmFiles?.joinToString { f -> "${f.name}(${f.length()/1048576}MB)" } ?: "(无 litertlm)"}")
                // Google AI Edge Gallery
                val galleryDir = File("/sdcard/Android/data/com.google.ai.edge.gallery/files")
                if (galleryDir.exists()) {
                    searchPaths.add("${galleryDir.absolutePath}: ${galleryDir.listFiles()?.joinToString { it.name } ?: "(空)"}")
                }
                "⚠️ 未找到 .litertlm 模型文件\n已搜索以下位置:\n${searchPaths.joinToString("\n")}\n\n请将 .litertlm 模型文件复制到以下任一位置:\n1. ${modelDir.absolutePath}\n2. ${downloadDir.absolutePath}\n3. ${context.getExternalFilesDir(null)?.absolutePath}\n\n或通过“更多 → 选择模型文件”手动指定"
            } else {
                val sizeMb = modelFile.length() / (1024 * 1024)
                "✅ 模型已就绪: ${modelFile.name} (${sizeMb}MB)\n路径: ${modelFile.absolutePath}"
            }
        } catch (e: Exception) {
            "❌ 检查失败: ${e.message}"
        }
    }

    // ========== OCR Text Recognition (ML Kit) ==========

    /**
     * 使用 ML Kit 进行端侧离线文字识别（模式2）
     *
     * 完全离线，支持中英文，速度约 100-300ms/帧。
     * 与 API/Gemma 的区别：
     * - 速度：~200ms vs ~2-3s
     * - 网络：完全离线
     * - 输出：纯文字内容（无语义理解）
     */
    private suspend fun runOcrRecognition(bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            try {
                if (ocrRecognizer == null) {
                    ocrRecognizer = OcrRecognizer()
                }
                val result = ocrRecognizer!!.recognize(bitmap)
                Log.d(TAG, "OCR result: ${result.take(80)}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "OCR recognition failed: ${e.message}", e)
                "错误: 文字识别失败 - ${e.message}"
            }
        }

    // ========== YOLO Object Detection ==========

    /**
     * 使用 YOLO（EfficientDet-Lite0）进行快速端侧物体检测
     *
     * 与 Gemma 4 (EDGE) 的区别：
     * - 速度：~30ms/帧 vs ~2s/帧
     * - 输出：物体标签+位置 vs 自然语言描述
     * - 网络：完全离线 vs 完全离线（但需模型文件）
     *
     * @param bitmap 输入图像
     * @param mode 检测模式: 1=障碍物, 3=场景描述
     */
    private suspend fun runYoloDetection(bitmap: Bitmap, mode: Int): String =
        withContext(Dispatchers.IO) {
            try {
                val detector = yoloDetector
                if (detector == null || !detector.isReady()) {
                    return@withContext "错误: YOLO 检测器未初始化"
                }
                val result = detector.analyzeForMode(bitmap, mode)
                Log.d(TAG, "YOLO result: ${result.take(80)}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "YOLO detection failed: ${e.message}", e)
                "错误: YOLO 检测失败 - ${e.message}"
            }
        }

    /**
     * 测试 YOLO 检测器是否可用
     */
    suspend fun testYoloConnection(): String = withContext(Dispatchers.IO) {
        try {
            if (yoloDetector == null) {
                yoloDetector = YoloDetector(context)
            }
            val ok = yoloDetector!!.initialize()
            if (ok) {
                "✅ YOLO 检测器就绪 (EfficientDet-Lite0, COCO 80类)\n模型: mediapipe/efficientdet_lite0.tflite\n完全离线推理，约30ms/帧"
            } else {
                "❌ YOLO 检测器初始化失败"
            }
        } catch (e: Exception) {
            "❌ YOLO 检测失败: ${e.message}"
        }
    }
}
