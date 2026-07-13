package com.nieao.blindaid

import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.hypot

/**
 * 手指指向分析器:MediaPipe HandLandmarker(IMAGE 模式)。
 * 取食指 MCP(5)/PIP(6)/TIP(8) 与手腕(0),判断食指是否伸直并给出指尖+方向。
 * 手部关键点索引参考 MediaPipe Hands 21 点定义。
 */
class HandPointerAnalyzer(context: Context) : FrameAnalyzer {
    override val name = "手指指向"
    override var enabled = true
    private var landmarker: HandLandmarker? = null

    init {
        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .build()
            landmarker = HandLandmarker.createFromOptions(context, opts)
        } catch (e: Throwable) {
            landmarker = null
            enabled = false
        }
    }

    fun detect(input: FrameInput): PointerInfo? {
        val lm = landmarker ?: return null
        return try {
            val image = BitmapImageBuilder(input.bitmap).build()
            val res = lm.detect(image)
            val hands = res.landmarks()
            if (hands.isEmpty()) return null
            val h = hands[0]
            if (h.size < 9) return null
            val wrist = h[0]; val mcp = h[5]; val pip = h[6]; val tip = h[8]
            val dTip = hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
            val dPip = hypot(pip.x() - wrist.x(), pip.y() - wrist.y())
            val pointing = dTip > dPip * 1.15f   // 食指伸直的粗略判断(MVP 近似)
            PointerInfo(
                tipX = tip.x(), tipY = tip.y(),
                dirX = tip.x() - mcp.x(), dirY = tip.y() - mcp.y(),
                isPointing = pointing
            )
        } catch (e: Throwable) {
            null
        }
    }

    override fun close() {
        try { landmarker?.close() } catch (_: Throwable) {}
    }
}
