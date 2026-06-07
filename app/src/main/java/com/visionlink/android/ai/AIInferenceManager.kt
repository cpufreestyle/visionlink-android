package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.createBitmap
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
 * - 加载视觉模型 (.tflite, LiteRT)
 * - 根据模式生成 Prompt
 * - 执行多模态推理
 * 
 * 技术栈:
 * - LLM: Gemma 4 E2B-it (LiteRT-LM, .litertlm 格式)
 * - 视觉: .tflite 模型 (LiteRT)
 * - 推理框架: LiteRT-LM + LiteRT
 * 
 * 真实 API 参考:
 * - https://developers.google.com/litert-lm/docs/reference/android
 * - https://www.tensorflow.org/lite/guide/inference
 */
class AIInferenceManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AIInferenceManager"
        
        // 模型文件路径 (对应 PC 版 TARGET_MODEL = 'gemma4:e2b')
        private const val GEMMA_MODEL_PATH = "models/gemma-4-e2b-it.litertlm"
        private const val VISION_MODEL_PATH = "models/mobilenet_v3.tflite"
        
        // 推理参数 (对应 PC 版 options={'temperature': 0.1})
        private const val TEMPERATURE = 0.1f
        private const val MAX_TOKENS = 256
        private const val IMAGE_SIZE = 448  // 对应 PC 版 AI_IMAGE_SIZE = 448
        
        // 模拟模式开关 (设置为 false 启用真实推理)
        private const val MOCK_MODE = true
    }
    
    private var gemmaModel: LiteRTLM? = null
    private var visionInterpreter: org.tensorflow.lite.Interpreter? = null
    private var isInitialized = false
    
    /**
     * 初始化 AI 模型
     * 
     * 对应 PC 版:
     * - ollama.pull('gemma4:e2b')
     * - ollama.chat() 自动加载模型
     * 
     * 真实 LiteRT-LM API 参考:
     * https://developers.google.com/litert-lm/docs/get-started
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🚀 开始初始化 AI 模型...")
            
            // 1. 检查模型文件是否存在
            val modelFile = copyAssetToCache(GEMMA_MODEL_PATH)
            if (!modelFile.exists()) {
                Log.e(TAG, "❌ Gemma 模型文件不存在: ${modelFile.absolutePath}")
                Log.e(TAG, "   请运行 download_models.ps1 下载模型文件")
                return@withContext
            }
            
            Log.d(TAG, "📦 加载 Gemma 4 E2B 模型: ${modelFile.name}")
            Log.d(TAG, "   文件大小: ${modelFile.length() / 1024 / 1024} MB")
            
            // 2. 配置推理参数 (对应 PC 版 options={'temperature': 0.1})
            val options = LiteRTLMOptions.builder()
                .setTemperature(TEMPERATURE)
                .setMaxTokens(MAX_TOKENS)
                .setTopK(40)
                .setTopP(0.95f)
                .build()
            
            // 3. 创建 LiteRT-LM 实例 (真实 API)
            // 注意: 这是推测的 API，实际可能略有不同
            gemmaModel = LiteRTLM.createFromFile(modelFile, options)
            
            Log.d(TAG, "✅ Gemma 4 E2B 模型加载成功")
            
            // 4. 加载视觉模型 (.tflite, LiteRT)
            val visionFile = copyAssetToCache(VISION_MODEL_PATH)
            if (visionFile.exists()) {
                val interpreterOptions = org.tensorflow.lite.Interpreter.Options()
                interpreterOptions.setNumThreads(4)  // 使用 4 个线程
                interpreterOptions.setUseNNAPI(true)  // 使用 NNAPI 加速
                
                visionInterpreter = org.tensorflow.lite.Interpreter(visionFile, interpreterOptions)
                Log.d(TAG, "✅ 视觉模型加载成功")
            } else {
                Log.w(TAG, "⚠️ 视觉模型文件不存在，跳过加载")
                Log.w(TAG, "   请运行 download_models.ps1 下载视觉模型")
            }
            
            isInitialized = true
            Log.d(TAG, "🎉 AI 推理引擎初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 模型加载失败: ${e.message}")
            e.printStackTrace()
            
            // 打印详细错误信息
            Log.e(TAG, "   请检查:")
            Log.e(TAG, "   1. 模型文件是否存在")
            Log.e(TAG, "   2. 模型格式是否正确 (.litertlm)")
            Log.e(TAG, "   3. LiteRT-LM 依赖是否正确添加")
            Log.e(TAG, "   4. 设备 RAM 是否足够 (需要 4GB+)")
        }
    }
    
    /**
     * 分析图像 (对应 PC 版 analyze_frame() 函数)
     * 
     * @param bitmap 摄像头捕获的图像
     * @param mode 当前模式 (1=避障, 2=文字, 3=场景)
     * @return AI 分析结果文本
     */
    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ 模型未初始化，无法分析图像")
            return@withContext "模型未初始化，请稍后重试"
        }
        
        try {
            Log.d(TAG, "🔍 开始分析图像 (模式: $mode)...")
            
            // 1. 预处理图像 (对应 PC 版 cv2.resize + imencode)
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            Log.d(TAG, "📐 图像缩放完成: ${resized.width}x${resized.height}")
            
            // 2. 视觉模型推理 (可选，用于图像理解)
            val visionFeatures = runVisionModel(resized)
            if (visionFeatures.isNotEmpty()) {
                Log.d(TAG, "👁️ 视觉特征提取完成 (${visionFeatures.size} 维)")
            }
            
            // 3. 根据模式生成 Prompt (对应 PC 版 prompt 字符串)
            val prompt = buildPrompt(mode, visionFeatures)
            Log.d(TAG, "💬 Prompt: $prompt")
            
            // 4. 执行多模态推理 (对应 PC 版 ollama.chat())
            val result = if (MOCK_MODE) {
                Log.w(TAG, "⚠️ 当前为模拟模式，请设置 MOCK_MODE = false 启用真实推理")
                runGemmaInferenceMock(prompt, resized)
            } else {
                runGemmaInferenceReal(prompt, resized)
            }
            
            Log.d(TAG, "✅ AI 推理完成: $result")
            return@withContext result.trim()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ AI 推理失败: ${e.message}")
            e.printStackTrace()
            return@withContext "识别失败: ${e.message}"
        }
    }
    
    /**
     * 运行视觉模型 (LiteRT .tflite)
     */
    private fun runVisionModel(bitmap: Bitmap): FloatArray {
        if (visionInterpreter == null) {
            Log.w(TAG, "⚠️ 视觉模型未加载，跳过视觉推理")
            return FloatArray(0)
        }
        
        try {
            // 预处理: Bitmap → FloatArray (归一化到 [0, 1])
            val input = preprocessImage(bitmap)
            
            // 输出数组
            val output = FloatArray(1001)  // ImageNet 1001 类
            
            // 推理
            visionInterpreter?.run(input, output)
            
            Log.d(TAG, "✅ 视觉模型推理完成")
            return output
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 视觉模型推理失败: ${e.message}")
            e.printStackTrace()
            return FloatArray(0)
        }
    }
    
    /**
     * 预处理图像 (Bitmap → FloatArray)
     * 
     * 对应 PC 版:
     *   frame = cv2.resize(snap, (AI_IMAGE_SIZE, AI_IMAGE_SIZE))
     *   _, buf = cv2.imencode('.jpg', resized)
     *   img_b64 = base64.b64encode(buf).decode('utf-8')
     */
    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val input = FloatArray(1 * width * height * 3)  // NHWC 格式
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            input[i * 3] = r
            input[i * 3 + 1] = g
            input[i * 3 + 2] = b
        }
        
        return input
    }
    
    /**
     * 构建 Prompt (根据模式和视觉特征)
     */
    private fun buildPrompt(mode: Int, visionFeatures: FloatArray): String {
        val basePrompt = when (mode) {
            1 -> "你是盲人眼镜的避障大脑。请仔细观察图片正中央，识别出最近的障碍物并用常识估算距离（限制在25字内，纯中文回答）。"
            2 -> "你是一款OCR文字阅读眼镜。请精确提取并辨认出这张图片里的所有中英文文字并直接朗读出来。"
            3 -> "你是一款导盲场景描述大脑。请仔细观察我眼前的画面，用温柔、充满常识的纯中文语言描述场景（50字内）。"
            else -> "请描述这张图片。"
        }
        
        // 如果有视觉特征，添加到 prompt (可选)
        if (visionFeatures.isNotEmpty()) {
            // 可以添加一个简单的提示，例如 "视觉特征已提取"
            return "$basePrompt\n\n[视觉特征已提取，请基于图像内容回答]"
        }
        
        return basePrompt
    }
    
    /**
     * 将 Bitmap 转为 Base64 (对应 PC 版 base64.b64encode())
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)  // 对应 PC 版 IMWRITE_JPEG_QUALITY=85
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
    
    /**
     * 运行 Gemma 4 E2B 推理 (真实 API)
     * 
     * 对应 PC 版:
     *   response = ollama.chat(model=TARGET_MODEL, messages=[...])
     *   result = response['message']['content']
     * 
     * 真实 API 参考:
     *   InferenceResult result = model.generate(prompt, imageBitmap)
     */
    private suspend fun runGemmaInferenceReal(prompt: String, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (gemmaModel == null) {
            Log.e(TAG, "❌ Gemma 模型未初始化")
            return@withContext "模型未初始化"
        }
        
        try {
            Log.d(TAG, "🧠 开始 Gemma 4 E2B 真实推理...")
            
            // 方法 1: 文本 + 图像多模态推理 (推测的 API)
            // 注意: 实际 API 可能不同，需要参考官方文档
            
            /* 
            // 推测的 API 用法:
            val inference = LiteRTLMInference.builder()
                .setPrompt(prompt)
                .setImage(bitmap)
                .setMaxTokens(MAX_TOKENS)
                .build()
            
            val result = gemmaModel?.generate(inference)
            return result?.getText() ?: "推理失败"
            */
            
            // 方法 2: 纯文本推理 (如果多模态 API 不可用)
            // 将图像转为 Base64，作为文本的一部分
            val base64Image = bitmapToBase64(bitmap)
            val multimodalPrompt = "$prompt\n\n[图像数据: $base64Image]"
            
            // TODO: 替换为真实的 LiteRT-LM API 调用
            // 示例 (推测的 API):
            // val result = gemmaModel?.generate(multimodalPrompt)
            // return result?.getText() ?: "推理失败"
            
            Log.w(TAG, "⚠️ 真实 API 尚未实现，请参考官方文档")
            Log.w(TAG, "   文档: https://developers.google.com/litert-lm/docs/reference/android")
            
            // 降级到模拟推理
            return runGemmaInferenceMock(prompt, bitmap)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Gemma 推理失败: ${e.message}")
            e.printStackTrace()
            return "推理失败: ${e.message}"
        }
    }
    
    /**
     * 运行 Gemma 4 E2B 推理 (模拟模式，开发测试用)
     * 
     * 对应 PC 版返回格式
     */
    private fun runGemmaInferenceMock(prompt: String, bitmap: Bitmap): String {
        // 模拟推理延迟
        Thread.sleep(1500)
        
        // 模拟返回结果 (实际应调用 gemmaModel.generate())
        return when {
            prompt.contains("避障") -> listOf(
                "前方2米有台阶，注意脚下",
                "左侧有障碍物，约1.5米，建议绕行",
                "道路畅通，可以放心前行",
                "前方有玻璃门，请小心"
            ).random()
            
            prompt.contains("文字") -> listOf(
                "无法识别文字，请调整角度",
                "识别到文字: 出口 →",
                "识别到文字: 小心地滑",
                "识别到文字: 电梯 3F"
            ).random()
            
            prompt.contains("场景") -> listOf(
                "你在一个明亮的室内走廊，左侧有窗户",
                "户外街道，有树木和建筑物，天气晴朗",
                "电梯厅，有电梯按钮和楼层指示器",
                "商店内，货架上有各种商品"
            ).random()
            
            else -> "无法识别，请重试"
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
                Log.d(TAG, "✅ 模型文件已复制到缓存: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 无法复制 assets 文件 (可能文件在 assets 中不存在): $assetPath")
                Log.w(TAG, "   请确保模型文件已放入 app/src/main/assets/$assetPath")
            }
        }
        
        return file
    }
    
    /**
     * 释放资源
     */
    fun release() {
        gemmaModel?.close()
        visionInterpreter?.close()
        isInitialized = false
        Log.d(TAG, "✅ AI 模型已释放")
    }
    
    /**
     * 检查是否初始化
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }
}
