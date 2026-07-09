package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * YOLO / 物体检测器 — 基于 MediaPipe ObjectDetector (EfficientDet-Lite0)
 *
 * 端侧实时物体检测，COCO 80 类，无需网络。
 * 用于模式1（障碍物检测）和模式3（场景描述）的快速离线推理。
 *
 * 与 Gemma 4 (LiteRT-LM) 的区别：
 * - YOLO: 快速（~30ms/帧），返回物体标签+位置+置信度，适合障碍物检测
 * - Gemma 4: 较慢（~2s/帧），返回自然语言描述，适合场景理解
 *
 * 可通过 AIInferenceManager.setEngine(YOLO) 切换使用。
 */
class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_ASSET = "mediapipe/efficientdet_lite0.tflite"
        private const val MAX_RESULTS = 10
        private const val MIN_SCORE = 0.35f
    }

    data class Detection(
        val label: String,
        val labelZh: String,
        val score: Float,
        val left: Float,   // 归一化 [0,1]
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    private var detector: ObjectDetector? = null
    private val initialized = AtomicBoolean(false)

    /**
     * 初始化检测器（同步，耗时约几百毫秒）
     * @return true 如果初始化成功
     */
    fun initialize(): Boolean {
        if (initialized.get()) return true
        return try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(MIN_SCORE)
                .build()
            detector = ObjectDetector.createFromOptions(context, options)
            initialized.set(true)
            Log.i(TAG, "物体检测器初始化完成 (efficientdet_lite0)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "物体检测器初始化失败: ${e.message}", e)
            false
        }
    }

    fun isReady(): Boolean = initialized.get()

    /**
     * 检测图像中的物体
     * @param bitmap 输入图像
     * @return 检测结果列表（按置信度降序）
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!initialized.get() || detector == null) return emptyList()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = detector!!.detect(mpImage)

            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()

            result.detections().mapNotNull { det ->
                val cat = det.categories().firstOrNull() ?: return@mapNotNull null
                val label = cat.categoryName() ?: return@mapNotNull null
                val box = det.boundingBox()
                Detection(
                    label = label,
                    labelZh = CocoLabelsZh.zh(label),
                    score = cat.score(),
                    left = (box.left / w).coerceIn(0f, 1f),
                    top = (box.top / h).coerceIn(0f, 1f),
                    right = (box.right / w).coerceIn(0f, 1f),
                    bottom = (box.bottom / h).coerceIn(0f, 1f)
                )
            }.sortedByDescending { it.score }
        } catch (e: Exception) {
            Log.e(TAG, "检测失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 根据模式分析图像并返回文本结果
     *
     * @param bitmap 输入图像
     * @param mode 检测模式: 1=障碍物检测, 2=文字识别(不支持), 3=场景描述
     * @return 分析结果文本
     */
    fun analyzeForMode(bitmap: Bitmap, mode: Int): String {
        val detections = detect(bitmap)
        if (detections.isEmpty()) {
            return when (mode) {
                1 -> "前方未检测到明显障碍物"
                3 -> "场景中未检测到明显物体"
                else -> "未检测到物体"
            }
        }

        return when (mode) {
            1 -> formatObstacleResult(detections)
            2 -> "YOLO 模式不支持文字识别，请切换到 Gemma 4 或 API 引擎"
            3 -> formatSceneResult(detections)
            else -> formatSceneResult(detections)
        }
    }

    /**
     * 格式化障碍物检测结果
     * 按距离（框高度）从近到远排序，播报最近的障碍物
     */
    private fun formatObstacleResult(detections: List<Detection>): String {
        // 按框高度降序（高度越大=越近）
        val sorted = detections.sortedByDescending { it.height }
        val nearest = sorted.take(3)

        return buildString {
            append("检测到 ${detections.size} 个物体。")
            nearest.forEachIndexed { index, det ->
                val direction = directionPhrase(det.centerX)
                val distance = distancePhrase(det.height)
                if (index == 0) {
                    append("最近障碍物：${det.labelZh}，$direction，$distance。")
                } else {
                    append("${det.labelZh}，$direction，$distance。")
                }
            }
        }
    }

    /**
     * 格式化场景描述结果
     * 列出检测到的所有物体及其位置
     */
    private fun formatSceneResult(detections: List<Detection>): String {
        // 按位置分组（左/中/右）
        val left = detections.filter { it.centerX < 0.35f }
        val center = detections.filter { it.centerX in 0.35f..0.65f }
        val right = detections.filter { it.centerX > 0.65f }

        return buildString {
            append("场景共检测到 ${detections.size} 个物体。")
            if (left.isNotEmpty()) {
                append("左侧：${left.joinToString("、") { it.labelZh }}。")
            }
            if (center.isNotEmpty()) {
                append("正前方：${center.joinToString("、") { it.labelZh }}。")
            }
            if (right.isNotEmpty()) {
                append("右侧：${right.joinToString("、") { it.labelZh }}。")
            }
        }
    }

    /** 根据物体中心 x 坐标给出方位描述 */
    private fun directionPhrase(centerX: Float): String = when {
        centerX < 0.2f -> "左侧九点钟方向"
        centerX < 0.42f -> "左前方"
        centerX <= 0.58f -> "正前方"
        centerX <= 0.8f -> "右前方"
        else -> "右侧三点钟方向"
    }

    /** 根据框高度估算距离（简化版） */
    private fun distancePhrase(boxHeight: Float): String = when {
        boxHeight >= 0.55f -> "就在跟前，约一两步"
        boxHeight >= 0.30f -> "约三到五步"
        boxHeight >= 0.12f -> "约十步左右"
        else -> "较远，超过十五步"
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            detector?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭检测器失败: ${e.message}")
        }
        detector = null
        initialized.set(false)
        Log.d(TAG, "物体检测器已释放")
    }
}
