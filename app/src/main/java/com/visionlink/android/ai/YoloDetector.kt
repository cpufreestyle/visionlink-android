package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.tan
import kotlin.math.roundToInt

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

        // 相机垂直视场角（度），手机主摄约 45°
        private const val VERTICAL_FOV_DEG = 45f
        private val tanHalfVFov = tan(Math.toRadians(VERTICAL_FOV_DEG / 2.0)).toFloat()
        // 平均步长（米）
        private const val STEP_LENGTH_M = 0.6f
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
     *
     * 使用 CPU 代理以确保最大兼容性。GPU 代理在某些设备上会导致初始化失败。
     *
     * @return true 如果初始化成功
     */
    fun initialize(): Boolean {
        if (initialized.get()) return true

        // 先验证模型文件存在
        try {
            val assetList = context.assets.list("mediapipe")
            if (assetList == null || !assetList.contains("efficientdet_lite0.tflite")) {
                Log.e(TAG, "模型文件不存在: $MODEL_ASSET (assets/mediapipe/ 内容: ${assetList?.joinToString()})")
                return false
            }
            Log.w(TAG, "模型文件确认存在: $MODEL_ASSET")
        } catch (e: Exception) {
            Log.e(TAG, "无法访问 assets 目录: ${e.message}", e)
            return false
        }

        // 尝试 CPU 代理初始化（最大兼容性）
        val ok = tryInit(Delegate.CPU)
        if (ok) {
            Log.w(TAG, "物体检测器初始化完成 (CPU 代理, efficientdet_lite0)")
            return true
        }

        // CPU 失败时尝试 GPU 代理
        Log.w(TAG, "CPU 代理初始化失败，尝试 GPU 代理...")
        return tryInit(Delegate.GPU)
    }

    /**
     * 使用指定代理尝试初始化
     */
    private fun tryInit(delegate: Delegate): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(MIN_SCORE)
                .build()
            detector = ObjectDetector.createFromOptions(context, options)
            initialized.set(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败 ($delegate): ${e.message}", e)
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
            2 -> "文字识别请使用 OCR 引擎"
            3 -> formatSceneResult(detections)
            else -> formatSceneResult(detections)
        }
    }

    /**
     * 格式化障碍物检测结果
     * 按距离从近到远排序，播报最近的障碍物
     * 输出格式："前方两米有台阶，偏左"
     */
    private fun formatObstacleResult(detections: List<Detection>): String {
        // 按估算距离从近到远排序
        val withDistance = detections.map { det ->
            val meters = estimateMeters(det)
            det to meters
        }.sortedBy { it.second ?: Float.MAX_VALUE }

        val nearest = withDistance.take(3)

        return buildString {
            nearest.forEachIndexed { index, (det, meters) ->
                val direction = mainDirection(det.centerX)
                val offset = offsetDirection(det.centerX)
                val distance = distancePhrase(det, meters)
                if (index == 0) {
                    append("$direction$distance${det.labelZh}$offset")
                } else {
                    append("。$direction$distance${det.labelZh}$offset")
                }
            }
        }
    }

    /**
     * 使用尺寸先验 + 小孔成像模型估算距离（米）
     * distance ≈ 真实高度 / (2 × 框高占比 × tan(垂直FOV/2))
     */
    private fun estimateMeters(det: Detection): Float? {
        val realHeight = SizePriorsM.heightOf(det.label) ?: return null
        val frac = det.height
        if (frac < 0.02f) return null
        return (realHeight / (2f * frac * tanHalfVFov)).coerceIn(0.3f, 50f)
    }

    /** 距离描述：有尺寸先验时报“X米”，无先验时退回框高分档 */
    private fun distancePhrase(det: Detection, meters: Float?): String {
        if (meters != null) {
            val mStr = if (meters < 1.0f) "不到一米" else "${fmtMeters(meters)}米"
            return "${mStr}有"
        }
        val h = det.height
        return when {
            h >= 0.55f -> "跟前一两步有"
            h >= 0.30f -> "三到五步处有"
            h >= 0.12f -> "约十步处有"
            else -> "远处有"
        }
    }

    /** 米数取到 0.5 精度 */
    private fun fmtMeters(m: Float): String {
        val half = (m * 2).roundToInt() / 2f
        return if (half % 1f == 0f) half.toInt().toString() else half.toString()
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

    /** 主方向：始终是“前方”（用户面向的方向） */
    private fun mainDirection(centerX: Float): String = when {
        centerX < 0.2f -> "左侧"
        centerX > 0.8f -> "右侧"
        else -> "前方"
    }

    /** 偏移方向：偏左/偏右/无，用于补充描述 */
    private fun offsetDirection(centerX: Float): String = when {
        centerX in 0.2f..0.42f -> "，偏左"
        centerX in 0.58f..0.8f -> "，偏右"
        else -> ""
    }

    /** 根据物体中心 x 坐标给出方位描述（场景描述模式用） */
    private fun directionPhrase(centerX: Float): String = when {
        centerX < 0.2f -> "左侧"
        centerX < 0.42f -> "左前方"
        centerX <= 0.58f -> "前方"
        centerX <= 0.8f -> "右前方"
        else -> "右侧"
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
