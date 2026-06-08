package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.LiteRTLM
import com.google.ai.edge.litertlm.LiteRTLMOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * AI 推理管理器
 *
 * 功能映射 (对应 PC 版 main.py):
 * - 加载 Gemma 4 E2B 模型 (LiteRT-LM)
 * - 根据模式生成 Prompt
 * - 执行推理
 *
 * LiteRT-LM API 参考:
 * - https://developers.google.com/litert-lm
 * - LiteRTLM.createFromFile(context, modelPath, options)
 * - model.generateAsync(prompt, callback)
 */
class AIInferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "AIInferenceManager"

        // 模型文件路径
        private const val GEMMA_MODEL_PATH = "models/gemma-4-e2b-it.litertlm"

        // 推理参数
        private const val TEMPERATURE = 0.1f
        private const val MAX_TOKENS = 256
        private const val IMAGE_SIZE = 448

        // Mock 模式开关 (设置为 false 启用真实推理)
        private const val MOCK_MODE = true
    }

    private var gemmaModel: LiteRTLM? = null
    private var isInitialized = false

    /**
     * 初始化 AI 模型
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting AI model initialization...")

            // 1. 检查模型文件是否存在
            val modelFile = copyAssetToCache(GEMMA_MODEL_PATH)
            if (!modelFile.exists()) {
                Log.e(TAG, "Gemma model file not found: ${modelFile.absolutePath}")
                Log.e(TAG, "Please run download_models.ps1 to download model")
                return@withContext
            }

            Log.d(TAG, "Loading Gemma 4 E2B model: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")

            // 2. 配置推理参数
            val options = LiteRTLMOptions.builder()
                .setTemperature(TEMPERATURE)
                .setMaxTokens(MAX_TOKENS)
                .setTopK(40)
                .setTopP(0.95f)
                .build()

            // 3. 创建 LiteRT-LM 实例
            gemmaModel = LiteRTLM.createFromFile(modelFile.absolutePath, options)

            isInitialized = true
            Log.d(TAG, "AI inference engine initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Model loading failed: ${e.message}")
            e.printStackTrace()
            Log.e(TAG, "Please check:")
            Log.e(TAG, "  1. Model file exists in assets/models/")
            Log.e(TAG, "  2. Model format is correct (.litertlm)")
            Log.e(TAG, "  3. LiteRT-LM dependency is correct in build.gradle.kts")
            Log.e(TAG, "  4. Device has enough RAM (4GB+)")
        }
    }

    /**
     * 分析图像
     *
     * @param bitmap 摄像头捕获的图像
     * @param mode 当前模式 (1=避障, 2=文字, 3=场景)
     * @return AI 分析结果文本
     */
    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "Model not initialized, cannot analyze")
            return@withContext "Model not initialized, please try again"
        }

        try {
            Log.d(TAG, "Analyzing image (mode: $mode)...")

            // 1. 预处理图像
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)

            // 2. 构建 Prompt
            val prompt = buildPrompt(mode)
            Log.d(TAG, "Prompt: $prompt")

            // 3. 执行推理
            val result = if (MOCK_MODE) {
                runGemmaInferenceMock(prompt)
            } else {
                runGemmaInferenceReal(prompt, resized)
            }

            Log.d(TAG, "AI inference complete: $result")
            return@withContext result.trim()

        } catch (e: Exception) {
            Log.e(TAG, "AI inference failed: ${e.message}")
            e.printStackTrace()
            return@withContext "Recognition failed: ${e.message}"
        }
    }

    /**
     * 构建 Prompt (根据模式)
     */
    private fun buildPrompt(mode: Int): String {
        return when (mode) {
            1 -> "You are an obstacle avoidance assistant for visually impaired users. Observe the center of the image carefully, identify the nearest obstacle and estimate the distance using common sense. Answer in Chinese only, within 25 characters."
            2 -> "You are an OCR text reading assistant. Extract and read aloud all Chinese and English text from this image precisely."
            3 -> "You are a scene description assistant for visually impaired users. Describe the scene in front of the user in warm, natural Chinese, within 50 characters."
            else -> "Please describe this image in Chinese."
        }
    }

    /**
     * 将 Bitmap 转为 Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * 运行 Gemma 4 E2B 推理 (真实 API)
     *
     * LiteRT-LM 真实 API:
     *   val result = gemmaModel?.generate(prompt)
     *   return result?.text ?: "Inference failed"
     */
    private suspend fun runGemmaInferenceReal(prompt: String, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (gemmaModel == null) {
            Log.e(TAG, "Gemma model not initialized")
            return@withContext "Model not initialized"
        }

        try {
            Log.d(TAG, "Running Gemma 4 E2B real inference...")

            // 多模态推理: 文本 + 图像
            val base64Image = bitmapToBase64(bitmap)
            val multimodalPrompt = "$prompt\n\n[image_data: $base64Image]"

            // TODO: 替换为真实的 LiteRT-LM API 调用
            // 参考文档: https://developers.google.com/litert-lm/docs/reference/android
            //
            // 真实 API 用法 (待验证):
            // val result = gemmaModel?.generate(multimodalPrompt)
            // return result?.text ?: "Inference failed"

            Log.w(TAG, "Real API not yet implemented, falling back to mock")
            return@withContext runGemmaInferenceMock(prompt)

        } catch (e: Exception) {
            Log.e(TAG, "Gemma inference failed: ${e.message}")
            e.printStackTrace()
            return@withContext "Inference failed: ${e.message}"
        }
    }

    /**
     * 运行模拟推理 (开发测试用)
     */
    private fun runGemmaInferenceMock(prompt: String): String {
        // 模拟推理延迟
        Thread.sleep(1500)

        return when {
            prompt.contains("obstacle", ignoreCase = true) -> listOf(
                "Step ahead in 2 meters, watch your step",
                "Obstacle on the left, about 1.5m, suggest detour",
                "Path is clear, safe to proceed",
                "Glass door ahead, please be careful"
            ).random()

            prompt.contains("OCR", ignoreCase = true) || prompt.contains("text", ignoreCase = true) -> listOf(
                "Cannot recognize text, please adjust angle",
                "Text recognized: EXIT ->",
                "Text recognized: Caution - Slippery Floor",
                "Text recognized: Elevator 3F"
            ).random()

            prompt.contains("scene", ignoreCase = true) || prompt.contains("describe", ignoreCase = true) -> listOf(
                "A bright indoor corridor with windows on the left",
                "Outdoor street with trees and buildings, sunny weather",
                "Elevator lobby with buttons and floor indicators",
                "Inside a shop with various products on shelves"
            ).random()

            else -> "Unable to recognize, please try again"
        }
    }

    /**
     * 复制 assets 文件到缓存目录
     */
    private fun copyAssetToCache(assetPath: String): File {
        val file = File(context.cacheDir, assetPath.replace("/", "_"))

        if (!file.exists()) {
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model file copied to cache: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot copy assets file (may not exist in assets): $assetPath")
                Log.w(TAG, "Please ensure model file is in app/src/main/assets/$assetPath")
            }
        }

        return file
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            gemmaModel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model: ${e.message}")
        }
        isInitialized = false
        Log.d(TAG, "AI model released")
    }

    /**
     * 检查是否初始化
     */
    fun isInitialized(): Boolean = isInitialized
}
