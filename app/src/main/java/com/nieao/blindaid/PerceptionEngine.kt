package com.nieao.blindaid

import android.content.Context

/**
 * 感知引擎:MVP 完整链路的中枢。
 * 调度可插拔分析器(障碍检测 + 手指指向),用 FusionLogic 融合成一帧结果。
 * 任一分析器加载失败自动降级,不阻断整条链路。
 */
class PerceptionEngine(context: Context) {

    val obstacle = ObstacleDetector(context)
    val pointer: HandPointerAnalyzer? =
        try { HandPointerAnalyzer(context) } catch (e: Throwable) { null }

    fun process(input: FrameInput): PerceptionResult {
        val dets = obstacle.detect(input)
        val ranked = FusionLogic.rank(dets)
        val pinfo = pointer?.takeIf { it.enabled }?.detect(input)
        val pointed = pinfo?.let { FusionLogic.pickPointedTarget(it, ranked) }
        val announcement = FusionLogic.decideAnnouncement(ranked, pointed)
        return PerceptionResult(ranked, pinfo, pointed, announcement)
    }

    fun switchModel() = obstacle.switchModel()

    val statusLine: String
        get() = "模型:${obstacle.modelName}  后端:${obstacle.backend}  " +
                "手势:${if (pointer?.enabled == true) "开" else "关"}"

    fun close() {
        obstacle.close()
        pointer?.close()
    }
}
