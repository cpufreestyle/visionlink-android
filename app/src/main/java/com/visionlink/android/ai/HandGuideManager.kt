package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 指向引导管理器（模式4）
 *
 * 全离线端侧实时管线：
 * - MediaPipe HandLandmarker（21 个手部关键点，assets 内置模型）→ 手的位置 + 食指指向射线
 * - MediaPipe ObjectDetector（EfficientDet-Lite0，COCO 80 类）→ 场景物体框
 * - GuidanceEngine → 指向解析 / 目标锁定跟踪 / 路径障碍 / 播报节流
 *
 * 帧来源：CameraManager 的实时帧回调（约 4-5 FPS，单线程串行处理）。
 * 播报通过 onAnnounce 回调交给 TTS；状态行通过 onStatus 回调更新 HUD。
 */
class HandGuideManager(
    private val context: Context,
    private val onAnnounce: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "HandGuideManager"
        private const val HAND_MODEL_ASSET = "mediapipe/hand_landmarker.task"
        private const val DETECTOR_MODEL_ASSET = "mediapipe/efficientdet_lite0.tflite"
        private const val MAX_DETECTIONS = 8
        private const val MIN_DETECTION_SCORE = 0.40f
        private const val MIN_HAND_SCORE = 0.5f

        // MediaPipe 21 点手部关键点索引
        private const val LM_WRIST = 0
        private const val LM_INDEX_MCP = 5
        private const val LM_INDEX_TIP = 8
    }

    private var handLandmarker: HandLandmarker? = null
    private var objectDetector: ObjectDetector? = null
    private val engine = GuidanceEngine()

    private val initialized = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /**
     * 加载两个端侧模型（同步，耗时约几百毫秒，请在 IO 线程调用）。
     * @return true=就绪；false=模型加载失败（详见 logcat）
     */
    fun initialize(): Boolean {
        if (initialized.get()) return true
        return try {
            val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(HAND_MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(MIN_HAND_SCORE)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(context, handOptions)

            val detectorOptions = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(DETECTOR_MODEL_ASSET).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_DETECTIONS)
                .setScoreThreshold(MIN_DETECTION_SCORE)
                .build()
            objectDetector = ObjectDetector.createFromOptions(context, detectorOptions)

            initialized.set(true)
            Log.d(TAG, "指向引导模型加载完成 (hand_landmarker + efficientdet_lite0)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "指向引导模型加载失败: ${e.message}", e)
            release()
            false
        }
    }

    fun isReady(): Boolean = initialized.get()
    fun isRunning(): Boolean = running.get()

    fun start() {
        engine.reset()
        running.set(true)
        Log.d(TAG, "指向引导已启动")
    }

    fun stop() {
        running.set(false)
        Log.d(TAG, "指向引导已停止")
    }

    /** 锁定当前指向的物体（语音"锁定"/指环单击触发） */
    fun lockTarget() {
        if (!running.get()) return
        onAnnounce(engine.lockPointedTarget())
    }

    /** 取消锁定 */
    fun unlockTarget() {
        if (!running.get()) return
        onAnnounce(engine.unlockTarget())
    }

    fun hasLock(): Boolean = engine.hasLock()

    /**
     * 处理一帧（在相机分析线程调用，已做忙碌丢帧保护）。
     * bitmap 必须已按 rotationDegrees 转正。
     */
    fun processFrame(bitmap: Bitmap) {
        if (!running.get() || !initialized.get()) return
        if (!busy.compareAndSet(false, true)) return // 上一帧还在处理，丢弃本帧

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()

            val handResult = handLandmarker?.detect(mpImage)
            val detResult = objectDetector?.detect(mpImage)

            val hand = handResult?.landmarks()?.firstOrNull()?.let { lm ->
                if (lm.size <= LM_INDEX_TIP) null
                else GuidanceEngine.Hand(
                    wrist = GuidanceEngine.Point(lm[LM_WRIST].x(), lm[LM_WRIST].y()),
                    indexMcp = GuidanceEngine.Point(lm[LM_INDEX_MCP].x(), lm[LM_INDEX_MCP].y()),
                    indexTip = GuidanceEngine.Point(lm[LM_INDEX_TIP].x(), lm[LM_INDEX_TIP].y())
                )
            }

            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val objects = detResult?.detections()?.mapNotNull { det ->
                val cat = det.categories().firstOrNull() ?: return@mapNotNull null
                val label = cat.categoryName() ?: return@mapNotNull null
                val box = det.boundingBox()
                GuidanceEngine.DetectedObject(
                    label = label,
                    labelZh = CocoLabelsZh.zh(label),
                    score = cat.score(),
                    box = GuidanceEngine.Box(
                        left = (box.left / w).coerceIn(0f, 1f),
                        top = (box.top / h).coerceIn(0f, 1f),
                        right = (box.right / w).coerceIn(0f, 1f),
                        bottom = (box.bottom / h).coerceIn(0f, 1f)
                    )
                )
            } ?: emptyList()

            val result = engine.process(
                GuidanceEngine.FrameInput(hand, objects, System.currentTimeMillis())
            )
            onStatus(result.statusLine)
            result.announcement?.let(onAnnounce)
        } catch (e: Exception) {
            Log.e(TAG, "指向引导帧处理失败: ${e.message}", e)
        } finally {
            busy.set(false)
        }
    }

    fun release() {
        running.set(false)
        initialized.set(false)
        try { handLandmarker?.close() } catch (e: Exception) { Log.w(TAG, "关闭手部模型失败: ${e.message}") }
        try { objectDetector?.close() } catch (e: Exception) { Log.w(TAG, "关闭检测模型失败: ${e.message}") }
        handLandmarker = null
        objectDetector = null
        Log.d(TAG, "指向引导资源已释放")
    }
}
