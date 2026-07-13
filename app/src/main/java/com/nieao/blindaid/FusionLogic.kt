package com.nieao.blindaid

import kotlin.math.hypot

/**
 * 融合决策纯逻辑(无 Android 依赖 → 可 JVM 单元测试)。
 * 负责:距离分级、方位判定、手指指向命中、播报文本决策。
 */
object FusionLogic {

    /** 距离分级(启发式):综合框面积 + 底边 y。越大、越靠画面下方 → 越近。
     *  说明:不同类别真实尺寸不同,纯启发式不精确,MVP 够用,v2 换深度模型。 */
    fun distanceLevel(det: Detection): DistanceLevel {
        val area = det.area                       // 归一化 0-1
        val bottom = det.y2                        // 越大越靠下 = 越近
        val score = 0.55f * bottom + 0.45f * minOf(area * 3f, 1f)
        return when {
            score >= 0.62f -> DistanceLevel.NEAR
            score >= 0.38f -> DistanceLevel.MID
            else -> DistanceLevel.FAR
        }
    }

    fun direction(cx: Float): Direction = when {
        cx < 0.34f -> Direction.LEFT
        cx > 0.66f -> Direction.RIGHT
        else -> Direction.CENTER
    }

    /** 把检测列表升级为带距离/方位的障碍列表 */
    fun rank(dets: List<Detection>): List<RankedObstacle> =
        dets.map { RankedObstacle(it, distanceLevel(it), direction((it.x1 + it.x2) / 2f)) }

    /** 手指指向命中:从指尖沿方向作射线,选落在射线前方且离射线最近的障碍 */
    fun pickPointedTarget(pointer: PointerInfo, obstacles: List<RankedObstacle>): RankedObstacle? {
        if (!pointer.isPointing || obstacles.isEmpty()) return null
        val dirLen = hypot(pointer.dirX, pointer.dirY)
        if (dirLen < 1e-4f) return null
        val ux = pointer.dirX / dirLen
        val uy = pointer.dirY / dirLen
        var best: RankedObstacle? = null
        var bestScore = Float.MAX_VALUE
        for (o in obstacles) {
            val vx = o.cx - pointer.tipX
            val vy = o.cy - pointer.tipY
            val proj = vx * ux + vy * uy          // 沿射线方向的投影(前方为正)
            if (proj <= 0.02f) continue            // 在指尖后方,忽略
            val perpX = vx - proj * ux
            val perpY = vy - proj * uy
            val perp = hypot(perpX, perpY)         // 到射线的垂直距离
            if (perp > 0.25f) continue             // 偏离射线太远,不算命中
            val score = perp + 0.15f * proj        // 优先靠近射线、稍近
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best
    }

    /** 决策本帧播报文本:指向命中优先;否则播报最近障碍(远处不打扰) */
    fun decideAnnouncement(obstacles: List<RankedObstacle>, pointed: RankedObstacle?): String? {
        if (pointed != null) return "你指向的是${pointed.direction.label}的${pointed.det.label}"
        val nearest = obstacles.minWithOrNull(
            compareBy({ distanceRank(it.distance) }, { -it.det.area })
        ) ?: return null
        if (nearest.distance == DistanceLevel.FAR) return null   // 远处不主动打扰
        return "${nearest.distance.label},${nearest.direction.label}有${nearest.det.label}"
    }

    private fun distanceRank(d: DistanceLevel) = when (d) {
        DistanceLevel.NEAR -> 0
        DistanceLevel.MID -> 1
        DistanceLevel.FAR -> 2
    }
}
