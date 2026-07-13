package com.nieao.blindaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 融合逻辑冒烟测试(纯 JVM,不依赖 Android)。验证 MVP 链路的核心决策。 */
class FusionLogicTest {

    private fun det(
        x1: Float, y1: Float, x2: Float, y2: Float,
        label: String = "人", cls: Int = 0
    ) = Detection(x1, y1, x2, y2, 0.9f, cls, label)

    @Test fun 大框且靠底部判为很近() {
        assertEquals(DistanceLevel.NEAR, FusionLogic.distanceLevel(det(0.2f, 0.5f, 0.8f, 0.98f)))
    }

    @Test fun 小框且靠顶部判为远处() {
        assertEquals(DistanceLevel.FAR, FusionLogic.distanceLevel(det(0.45f, 0.1f, 0.52f, 0.2f)))
    }

    @Test fun 方位左中右() {
        assertEquals(Direction.LEFT, FusionLogic.direction(0.1f))
        assertEquals(Direction.CENTER, FusionLogic.direction(0.5f))
        assertEquals(Direction.RIGHT, FusionLogic.direction(0.9f))
    }

    @Test fun 指向命中射线上的目标() {
        val obstacles = FusionLogic.rank(
            listOf(
                det(0.6f, 0.3f, 0.8f, 0.5f, "椅子", 56),  // 右上
                det(0.0f, 0.6f, 0.2f, 0.9f, "狗", 16)      // 左下
            )
        )
        val p = PointerInfo(0.4f, 0.6f, 0.3f, -0.3f, isPointing = true)  // 指向右上
        val hit = FusionLogic.pickPointedTarget(p, obstacles)
        assertNotNull(hit)
        assertEquals("椅子", hit!!.det.label)
    }

    @Test fun 未指向时不命中() {
        val obstacles = FusionLogic.rank(listOf(det(0.6f, 0.3f, 0.8f, 0.5f)))
        val p = PointerInfo(0.4f, 0.6f, 0.3f, -0.3f, isPointing = false)
        assertNull(FusionLogic.pickPointedTarget(p, obstacles))
    }

    @Test fun 指向播报优先于障碍播报() {
        val obstacles = FusionLogic.rank(listOf(det(0.2f, 0.5f, 0.8f, 0.98f, "人")))
        val msg = FusionLogic.decideAnnouncement(obstacles, obstacles[0])
        assertNotNull(msg)
        assertTrue(msg!!.contains("你指向"))
    }

    @Test fun 无手势时播报最近障碍() {
        val obstacles = FusionLogic.rank(
            listOf(
                det(0.4f, 0.05f, 0.5f, 0.15f, "瓶子"),   // 远
                det(0.2f, 0.6f, 0.8f, 0.98f, "人")        // 近
            )
        )
        val msg = FusionLogic.decideAnnouncement(obstacles, null)
        assertNotNull(msg)
        assertTrue(msg!!.contains("人"))
    }

    @Test fun 只有远处障碍时不打扰() {
        val obstacles = FusionLogic.rank(listOf(det(0.45f, 0.05f, 0.52f, 0.15f, "瓶子")))
        assertNull(FusionLogic.decideAnnouncement(obstacles, null))
    }
}
