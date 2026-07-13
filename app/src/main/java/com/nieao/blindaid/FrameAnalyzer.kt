package com.nieao.blindaid

import android.graphics.Bitmap

/** 送入各分析器的一帧输入(已旋转正向的 RGBA Bitmap) */
data class FrameInput(val bitmap: Bitmap, val timestampMs: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * 感知分析器抽象。可插拔:每个能力(障碍检测/手指指向/…)实现本接口,
 * 由 PerceptionEngine 统一注册、调度与释放。任一分析器加载失败(enabled=false)
 * 不影响其他分析器 —— 支持诚实降级。
 */
interface FrameAnalyzer {
    val name: String
    var enabled: Boolean
    fun close()
}
