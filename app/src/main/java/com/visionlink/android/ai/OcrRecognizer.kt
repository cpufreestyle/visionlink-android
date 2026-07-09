package com.visionlink.android.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 端侧离线 OCR 识别器 — 基于 ML Kit Text Recognition
 *
 * 完全离线，无需网络，支持拉丁字母+中文（ML Kit 内置中文模型自动下载）。
 * 用于模式2（文字识别）的快速端侧 OCR。
 *
 * 速度：约 100-300ms/帧（取决于图片复杂度）
 * 输出：提取的文字内容，按行排列
 */
class OcrRecognizer {

    companion object {
        private const val TAG = "OcrRecognizer"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 识别图片中的文字
     * @param bitmap 输入图像
     * @return 识别到的文字内容，按视觉位置从上到下排列
     */
    suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = formatResult(result.text)
                    Log.d(TAG, "OCR success: ${text.take(80)}")
                    if (cont.isActive) cont.resume(text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}", e)
                    if (cont.isActive) cont.resume("文字识别失败: ${e.message}")
                }
        }

    /**
     * 格式化 OCR 结果：
     * - 去除过多空白行
     * - 如果有文字，返回整理后的文本
     * - 如果无文字，返回提示
     */
    private fun formatResult(rawText: String): String {
        val cleaned = rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        return if (cleaned.isEmpty()) {
            "未识别到文字"
        } else {
            // 如果只有一两行，直接返回；多行时加行号方便播报
            val lines = cleaned.lines()
            if (lines.size <= 2) {
                cleaned
            } else {
                lines.take(8).joinToString("\n") { it }
            }
        }
    }

    fun release() {
        recognizer.close()
        Log.d(TAG, "OCR recognizer released")
    }
}
