package com.visionlink.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GuidanceEngine 纯逻辑单元测试（JVM 直跑，无需设备）
 *
 * 坐标系：归一化图像坐标，x 向右 [0,1]，y 向下 [0,1]。
 */
class GuidanceEngineTest {

    private fun obj(label: String, zh: String, l: Float, t: Float, r: Float, b: Float) =
        GuidanceEngine.DetectedObject(label, zh, 0.9f, GuidanceEngine.Box(l, t, r, b))

    /** 手在画面下方中央，食指竖直向上指 */
    private fun handPointingUp(tipX: Float = 0.5f, tipY: Float = 0.6f) = GuidanceEngine.Hand(
        wrist = GuidanceEngine.Point(tipX, tipY + 0.25f),
        indexMcp = GuidanceEngine.Point(tipX, tipY + 0.1f),
        indexTip = GuidanceEngine.Point(tipX, tipY)
    )

    // ========== 指向射线 ==========

    @Test
    fun `指向射线命中正上方的物体`() {
        val engine = GuidanceEngine()
        val chair = obj("chair", "椅子", 0.4f, 0.2f, 0.6f, 0.4f)   // 正上方
        val tv = obj("tv", "电视", 0.8f, 0.2f, 0.95f, 0.4f)        // 右上角，不在射线上

        val pointed = engine.findPointedObject(handPointingUp(), listOf(chair, tv))
        assertNotNull(pointed)
        assertEquals("chair", pointed!!.label)
    }

    @Test
    fun `射线沿途有多个物体时取最近的`() {
        val engine = GuidanceEngine()
        val near = obj("bottle", "瓶子", 0.45f, 0.45f, 0.55f, 0.55f) // 离指尖近
        val far = obj("chair", "椅子", 0.4f, 0.1f, 0.6f, 0.3f)       // 同方向更远

        val pointed = engine.findPointedObject(handPointingUp(), listOf(far, near))
        assertEquals("bottle", pointed!!.label)
    }

    @Test
    fun `不指向任何物体时返回null`() {
        val engine = GuidanceEngine()
        val tv = obj("tv", "电视", 0.85f, 0.7f, 0.99f, 0.9f) // 右下角，射线向上打不到

        val pointed = engine.findPointedObject(handPointingUp(), listOf(tv))
        assertNull(pointed)
    }

    @Test
    fun `包住整只手的person框视为用户自己被排除`() {
        val engine = GuidanceEngine()
        val self = obj("person", "人", 0.2f, 0.3f, 0.8f, 1.0f) // 包住手腕和指尖
        val pointed = engine.findPointedObject(handPointingUp(), listOf(self))
        assertNull(pointed)
    }

    // ========== 方位与距离措辞 ==========

    @Test
    fun `方位按中心x分档`() {
        val engine = GuidanceEngine()
        assertEquals("左侧九点钟方向", engine.directionPhrase(GuidanceEngine.Box(0.0f, 0.3f, 0.2f, 0.6f)))
        assertEquals("左前方", engine.directionPhrase(GuidanceEngine.Box(0.2f, 0.3f, 0.4f, 0.6f)))
        assertEquals("正前方", engine.directionPhrase(GuidanceEngine.Box(0.4f, 0.3f, 0.6f, 0.6f)))
        assertEquals("右前方", engine.directionPhrase(GuidanceEngine.Box(0.6f, 0.3f, 0.8f, 0.6f)))
        assertEquals("右侧三点钟方向", engine.directionPhrase(GuidanceEngine.Box(0.85f, 0.3f, 1.0f, 0.6f)))
    }

    @Test
    fun `无尺寸先验的物体按框高换算大致步数`() {
        val engine = GuidanceEngine()
        // "kite" 不在尺寸先验表里，走框高分档
        assertEquals("就在跟前，大约一两步", engine.distancePhrase(obj("kite", "风筝", 0f, 0.2f, 1f, 0.9f)))
        assertEquals("大约三到五步", engine.distancePhrase(obj("kite", "风筝", 0f, 0.3f, 1f, 0.65f)))
        assertEquals("大约十步左右", engine.distancePhrase(obj("kite", "风筝", 0f, 0.4f, 1f, 0.55f)))
        assertEquals("较远，超过十五步", engine.distancePhrase(obj("kite", "风筝", 0f, 0.45f, 1f, 0.5f)))
    }

    @Test
    fun `有尺寸先验的物体给出米数和步数（单目测距）`() {
        val engine = GuidanceEngine(verticalFovDeg = 45f)
        // 门真实高度 2 米，框高占画面 40%：distance = 2.0 / (2*0.4*tan22.5°) ≈ 6.0 米 → 10 步
        val door = obj("door", "门", 0.4f, 0.3f, 0.6f, 0.7f)
        val meters = engine.estimateMeters(door)
        assertNotNull(meters)
        assertEquals(6.0f, meters!!, 0.3f)
        val phrase = engine.distancePhrase(door)
        assertTrue("应含米数，实际: $phrase", phrase.contains("米"))
        assertTrue("应含步数，实际: $phrase", phrase.contains("步"))
    }

    @Test
    fun `行走靠近时米数随之减小（锚点距离实时变化）`() {
        val engine = GuidanceEngine(verticalFovDeg = 45f)
        val doorFar = obj("door", "门", 0.45f, 0.4f, 0.55f, 0.6f)  // 框高 0.2
        val doorNear = obj("door", "门", 0.35f, 0.2f, 0.65f, 0.8f) // 框高 0.6（走近了）
        val far = engine.estimateMeters(doorFar)!!
        val near = engine.estimateMeters(doorNear)!!
        assertTrue("走近后估距应变小: far=$far near=$near", near < far / 2)
    }

    @Test
    fun `锁定后按目标位置给出转向指令`() {
        val engine = GuidanceEngine()
        assertEquals("请向左转", engine.walkPhrase(GuidanceEngine.Box(0.0f, 0.3f, 0.2f, 0.6f)))
        assertEquals("请稍向左，再直走", engine.walkPhrase(GuidanceEngine.Box(0.2f, 0.3f, 0.4f, 0.6f)))
        assertEquals("朝正前方直走", engine.walkPhrase(GuidanceEngine.Box(0.4f, 0.3f, 0.6f, 0.6f)))
        assertEquals("请稍向右，再直走", engine.walkPhrase(GuidanceEngine.Box(0.6f, 0.3f, 0.8f, 0.6f)))
        assertEquals("请向右转", engine.walkPhrase(GuidanceEngine.Box(0.85f, 0.3f, 1.0f, 0.6f)))
    }

    @Test
    fun `手的九宫格位置`() {
        val engine = GuidanceEngine()
        assertEquals("画面中央", engine.handZonePhrase(GuidanceEngine.Point(0.5f, 0.5f)))
        assertEquals("画面左侧", engine.handZonePhrase(GuidanceEngine.Point(0.1f, 0.5f)))
        assertEquals("画面右下方", engine.handZonePhrase(GuidanceEngine.Point(0.9f, 0.9f)))
        assertEquals("画面上方", engine.handZonePhrase(GuidanceEngine.Point(0.5f, 0.1f)))
    }

    // ========== 路径障碍 ==========

    @Test
    fun `用户与目标之间的走廊内物体判为障碍并给出方位`() {
        val engine = GuidanceEngine()
        val target = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.4f)          // 远处正前方
        val obstacleLeft = obj("chair", "椅子", 0.25f, 0.55f, 0.45f, 0.75f) // 路径中间偏左
        val awayRight = obj("tv", "电视", 0.9f, 0.55f, 1.0f, 0.75f)     // 走廊外

        val obstacles = engine.findPathObstacles(target, listOf(target, obstacleLeft, awayRight))
        assertEquals(1, obstacles.size)
        assertEquals("chair", obstacles[0].first.label)
        assertEquals("偏左", obstacles[0].second)
    }

    @Test
    fun `目标身后的物体不算路径障碍`() {
        val engine = GuidanceEngine()
        val target = obj("door", "门", 0.4f, 0.3f, 0.6f, 0.6f)
        val behind = obj("chair", "椅子", 0.45f, 0.05f, 0.55f, 0.25f) // 比目标更远（更靠上）

        val obstacles = engine.findPathObstacles(target, listOf(target, behind))
        assertTrue(obstacles.isEmpty())
    }

    @Test
    fun `多个障碍按离用户从近到远排序`() {
        val engine = GuidanceEngine()
        val target = obj("door", "门", 0.4f, 0.05f, 0.6f, 0.25f)
        val farOb = obj("chair", "椅子", 0.4f, 0.3f, 0.6f, 0.5f)
        val nearOb = obj("backpack", "背包", 0.4f, 0.6f, 0.6f, 0.85f)

        val obstacles = engine.findPathObstacles(target, listOf(target, farOb, nearOb))
        assertEquals(2, obstacles.size)
        assertEquals("backpack", obstacles[0].first.label) // 离用户最近的先报
    }

    // ========== 手指与目标对齐 ==========

    @Test
    fun `手指正对目标时提示正对`() {
        val engine = GuidanceEngine()
        val target = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.4f)
        assertEquals("手指正对目标", engine.alignmentPhrase(handPointingUp(), target))
    }

    @Test
    fun `目标在指向右侧时提示向右调整`() {
        val engine = GuidanceEngine()
        val target = obj("door", "门", 0.8f, 0.1f, 0.95f, 0.4f) // 目标在右上，手指竖直向上
        assertEquals("手指偏离目标，请向右调整", engine.alignmentPhrase(handPointingUp(), target))
    }

    @Test
    fun `目标在指向左侧时提示向左调整`() {
        val engine = GuidanceEngine()
        val target = obj("door", "门", 0.05f, 0.1f, 0.2f, 0.4f)
        assertEquals("手指偏离目标，请向左调整", engine.alignmentPhrase(handPointingUp(), target))
    }

    // ========== 锁定 + 全流程 + 节流 ==========

    @Test
    fun `完整流程 - 指向后锁定并持续引导`() {
        val engine = GuidanceEngine(minAnnounceIntervalMs = 0, repeatIntervalMs = 100000)
        val door = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.4f)
        var ts = 0L

        // 喂 3 帧让手部去抖生效（第 3 帧触发"检测到手"紧急播报）
        repeat(3) {
            ts += 300
            engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
        }
        // 再喂一帧，应播报指向内容
        ts += 300
        val r = engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
        assertNotNull(r.announcement)
        assertTrue(r.announcement!!.contains("门"))

        // 锁定
        val lockMsg = engine.lockPointedTarget()
        assertTrue(lockMsg.contains("已锁定目标：门"))
        assertTrue(engine.hasLock())

        // 目标移动到右侧 → 引导播报转向指令变化
        val doorMoved = obj("door", "门", 0.7f, 0.1f, 0.9f, 0.4f)
        ts += 300
        val r2 = engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(doorMoved), ts))
        assertNotNull(r2.announcement)
        assertTrue(r2.announcement!!.contains("目标门"))
        assertTrue(r2.announcement!!.contains("稍向右"))
    }

    @Test
    fun `锁定后路径障碍出现在播报里`() {
        val engine = GuidanceEngine(minAnnounceIntervalMs = 0, repeatIntervalMs = 100000)
        val door = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.35f)
        var ts = 0L
        repeat(4) {
            ts += 300
            engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
        }
        engine.lockPointedTarget()

        val chair = obj("chair", "椅子", 0.3f, 0.5f, 0.5f, 0.75f) // 路径中偏左
        ts += 300
        val r = engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door, chair), ts))
        assertNotNull(r.announcement)
        assertTrue(r.announcement!!.contains("椅子"))
        assertTrue(r.announcement!!.contains("建议靠右通过"))
    }

    @Test
    fun `状态不变时被节流不重复播报`() {
        val engine = GuidanceEngine(minAnnounceIntervalMs = 1800, repeatIntervalMs = 60000)
        val door = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.4f)
        var ts = 0L
        var announcements = 0
        // 20 帧同样的场景（每帧 250ms），去抖+首报后不应反复播报
        repeat(20) {
            ts += 250
            val r = engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
            if (r.announcement != null) announcements++
        }
        // 只允许：1 次"检测到手" + 1 次指向播报
        assertTrue("播报次数应<=2，实际 $announcements", announcements <= 2)
    }

    @Test
    fun `目标连续丢失后播报丢失再出现播报重新找到`() {
        val engine = GuidanceEngine(minAnnounceIntervalMs = 0, repeatIntervalMs = 100000)
        val door = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.4f)
        var ts = 0L
        repeat(4) {
            ts += 300
            engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
        }
        engine.lockPointedTarget()

        // 目标从画面消失 12 帧
        var lostMsg: String? = null
        repeat(12) {
            ts += 300
            val r = engine.process(GuidanceEngine.FrameInput(handPointingUp(), emptyList(), ts))
            if (r.announcement?.contains("丢失") == true) lostMsg = r.announcement
        }
        assertNotNull("应播报目标丢失", lostMsg)

        // 目标重新出现
        ts += 300
        var refound = false
        repeat(3) {
            ts += 300
            val r = engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
            if (r.announcement?.contains("重新找到") == true) refound = true
        }
        assertTrue("应播报重新找到目标", refound)
    }

    @Test
    fun `未指向物体时锁定给出引导提示`() {
        val engine = GuidanceEngine()
        val msg = engine.lockPointedTarget()
        assertTrue(msg.contains("尚未指向"))
        assertFalse(engine.hasLock())
    }

    @Test
    fun `取消锁定`() {
        val engine = GuidanceEngine(minAnnounceIntervalMs = 0, repeatIntervalMs = 100000)
        val door = obj("door", "门", 0.4f, 0.1f, 0.6f, 0.4f)
        var ts = 0L
        repeat(4) {
            ts += 300
            engine.process(GuidanceEngine.FrameInput(handPointingUp(), listOf(door), ts))
        }
        engine.lockPointedTarget()
        assertTrue(engine.hasLock())
        assertEquals("已取消锁定", engine.unlockTarget())
        assertFalse(engine.hasLock())
    }

    @Test
    fun `COCO标签中文映射`() {
        assertEquals("椅子", CocoLabelsZh.zh("chair"))
        assertEquals("门", CocoLabelsZh.zh("door"))
        assertEquals("红绿灯", CocoLabelsZh.zh("traffic light"))
        assertEquals("unknown_thing", CocoLabelsZh.zh("unknown_thing")) // 未收录原样返回
    }
}
