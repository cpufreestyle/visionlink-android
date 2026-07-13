package com.nieao.blindaid

/**
 * 感知框架的纯数据模型(无 Android 依赖,便于 JVM 单元测试)。
 */

/** 障碍距离级别(启发式) */
enum class DistanceLevel(val label: String) { NEAR("很近"), MID("前方"), FAR("远处") }

/** 障碍方位 */
enum class Direction(val label: String) { LEFT("左侧"), CENTER("正前方"), RIGHT("右侧") }

/** 手指指向信息:指尖归一化位置 + 指向方向向量(图像归一化坐标系,x 右 y 下) */
data class PointerInfo(
    val tipX: Float, val tipY: Float,
    val dirX: Float, val dirY: Float,
    val isPointing: Boolean
)

/** 融合后的一个障碍:原始检测 + 距离级别 + 方位 */
data class RankedObstacle(
    val det: Detection,
    val distance: DistanceLevel,
    val direction: Direction
) {
    val cx: Float get() = (det.x1 + det.x2) / 2f
    val cy: Float get() = (det.y1 + det.y2) / 2f
}

/** 一帧完整感知结果 */
data class PerceptionResult(
    val obstacles: List<RankedObstacle>,
    val pointer: PointerInfo?,
    val pointedTarget: RankedObstacle?,   // 指向命中的物体(可空)
    val announcement: String?             // 本帧决策出的播报文本(可空=不播)
)
