package com.visionlink.android.ai

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 指向引导引擎（纯 Kotlin 逻辑，无 Android 依赖，可直接单元测试）
 *
 * 坐标系：归一化图像坐标，x 向右 [0,1]，y 向下 [0,1]。
 *
 * 职责：
 * 1. 判断手在画面中的位置（九宫格分区）
 * 2. 从食指 MCP→TIP 计算指向射线，与场景物体框求交，得到"用户指向的物体"
 * 3. 目标锁定：锁定后跨帧跟踪目标（同标签 + IoU/中心距离匹配）
 * 4. 路径障碍：计算"用户脚下 → 目标"走廊内的其他物体，给出偏左/偏右与避让建议
 * 5. 播报节流：只有状态指纹变化或超过重复间隔才产生播报文本，避免刷屏
 */
class GuidanceEngine(
    private val minAnnounceIntervalMs: Long = 1800,
    private val repeatIntervalMs: Long = 6000,
    /** 相机垂直视场角（度）。手机主摄横屏时约 45°，用于单目测距 */
    verticalFovDeg: Float = 45f
) {

    private val tanHalfVFov = tan(Math.toRadians(verticalFovDeg / 2.0)).toFloat()

    // ========== 数据结构 ==========

    data class Point(val x: Float, val y: Float)

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun contains(p: Point): Boolean = p.x in left..right && p.y in top..bottom

        fun iou(o: Box): Float {
            val ix = maxOf(0f, minOf(right, o.right) - maxOf(left, o.left))
            val iy = maxOf(0f, minOf(bottom, o.bottom) - maxOf(top, o.top))
            val inter = ix * iy
            val union = width * height + o.width * o.height - inter
            return if (union <= 0f) 0f else inter / union
        }
    }

    data class DetectedObject(
        val label: String,
        val labelZh: String,
        val score: Float,
        val box: Box
    )

    /** 手部信息：手腕、食指掌指关节(MCP)、食指指尖(TIP)，用于计算指向射线 */
    data class Hand(val wrist: Point, val indexMcp: Point, val indexTip: Point)

    data class FrameInput(
        val hand: Hand?,
        val objects: List<DetectedObject>,
        val timestampMs: Long
    )

    data class GuideResult(
        /** 需要语音播报的文本；null 表示本帧不播报 */
        val announcement: String?,
        /** 屏幕/HUD 显示的状态行，每帧都有 */
        val statusLine: String
    )

    // ========== 内部状态 ==========

    private var lockedTarget: DetectedObject? = null
    private var lockedMissingFrames = 0
    private var lockedLostAnnounced = false

    private var handPresentFrames = 0
    private var handAbsentFrames = 0
    private var handVisible = false

    private var lastPointed: DetectedObject? = null
    private var lastHand: Hand? = null

    private var lastFingerprint: String? = null
    private var lastAnnounceTs = 0L
    private var urgentQueue = ArrayDeque<String>()

    companion object {
        /** 目标连续丢失多少帧后播报"目标丢失" */
        private const val LOST_FRAMES_THRESHOLD = 10
        /** 手出现/消失的去抖帧数 */
        private const val HAND_DEBOUNCE_FRAMES = 3
        /** 跨帧目标匹配的最小 IoU */
        private const val TRACK_MIN_IOU = 0.05f
        /** 跨帧目标匹配的最大中心距离（归一化） */
        private const val TRACK_MAX_CENTER_DIST = 0.35f
        /** 走廊起点半宽（画面底部，代表用户身位） */
        private const val CORRIDOR_BASE_HALF_WIDTH = 0.28f
        /** 平均步长（米），用于米数 → 步数换算 */
        private const val STEP_LENGTH_M = 0.6f
    }

    // ========== 对外操作 ==========

    /** 锁定当前指向的物体；返回给用户的播报文本 */
    @Synchronized
    fun lockPointedTarget(): String {
        val pointed = lastPointed
            ?: return "尚未指向任何物体，请用食指指向想去的方向后再锁定"
        lockedTarget = pointed
        lockedMissingFrames = 0
        lockedLostAnnounced = false
        lastFingerprint = null // 强制下一帧重新播报完整状态
        return "已锁定目标：${pointed.labelZh}，${directionPhrase(pointed.box)}，${distancePhrase(pointed)}"
    }

    @Synchronized
    fun unlockTarget(): String {
        val had = lockedTarget != null
        lockedTarget = null
        lockedMissingFrames = 0
        lockedLostAnnounced = false
        lastFingerprint = null
        return if (had) "已取消锁定" else "当前没有锁定的目标"
    }

    fun hasLock(): Boolean = lockedTarget != null

    @Synchronized
    fun reset() {
        lockedTarget = null
        lockedMissingFrames = 0
        lockedLostAnnounced = false
        handPresentFrames = 0
        handAbsentFrames = 0
        handVisible = false
        lastPointed = null
        lastHand = null
        lastFingerprint = null
        lastAnnounceTs = 0L
        urgentQueue.clear()
    }

    // ========== 每帧处理 ==========

    @Synchronized
    fun process(input: FrameInput): GuideResult {
        lastHand = input.hand
        updateHandPresence(input.hand)

        // 1. 指向物体：射线与物体框求交，取最近命中
        val pointed = input.hand?.let { findPointedObject(it, input.objects) }
        lastPointed = pointed ?: lastPointed?.takeIf { input.hand != null }
        if (input.hand == null) lastPointed = null

        // 2. 锁定目标跨帧跟踪
        val target = trackLockedTarget(input.objects)

        // 3. 组装状态与播报
        val status: String
        val fingerprint: String

        if (target != null) {
            val walk = walkPhrase(target.box)  // 转向指令：往左/往右/直走
            val dist = distancePhrase(target)   // 米数/步数估计（有尺寸先验时按米，随行走实时变化）
            val obstacles = findPathObstacles(target, input.objects)
            val obstaclePhrase = obstaclesPhrase(obstacles)

            status = "目标:${target.labelZh} $walk $dist" +
                    (if (obstaclePhrase.isNotEmpty()) " | $obstaclePhrase" else "")
            fingerprint = "LOCK|${target.labelZh}|$walk|$dist|" +
                    obstacles.joinToString(",") { it.first.labelZh + it.second }

            val text = buildString {
                append("目标${target.labelZh}，").append(walk).append("，").append(dist)
                if (obstaclePhrase.isNotEmpty()) append("。").append(obstaclePhrase)
            }
            return finish(text, fingerprint, status, input.timestampMs, allowRepeat = true)
        }

        // 未锁定：报告手的位置与指向
        if (input.hand != null && handVisible) {
            val zone = handZonePhrase(input.hand.indexTip)
            if (pointed != null) {
                val dir = directionPhrase(pointed.box)
                val dist = distancePhrase(pointed)
                status = "手:$zone → ${pointed.labelZh} $dir $dist"
                fingerprint = "POINT|${pointed.labelZh}|$dir|$dist"
                val text = "您指向${pointed.labelZh}，$dir，$dist。说锁定可开始引导"
                return finish(text, fingerprint, status, input.timestampMs, allowRepeat = false)
            }
            status = "手:$zone → 未指向明显物体"
            fingerprint = "HAND|$zone"
            return finish(null, fingerprint, status, input.timestampMs, allowRepeat = false)
        }

        status = if (input.objects.isEmpty()) "未检测到手和物体" else
            "未检测到手 | 场景: " + input.objects.take(3).joinToString(" ") { it.labelZh }
        return finish(null, "NOHAND", status, input.timestampMs, allowRepeat = false)
    }

    // ========== 手的出现/消失（去抖） ==========

    private fun updateHandPresence(hand: Hand?) {
        if (hand != null) {
            handPresentFrames++
            handAbsentFrames = 0
            if (!handVisible && handPresentFrames >= HAND_DEBOUNCE_FRAMES) {
                handVisible = true
                urgentQueue.add("检测到您的手，请用食指指向想了解的方向")
            }
        } else {
            handAbsentFrames++
            handPresentFrames = 0
            if (handVisible && handAbsentFrames >= HAND_DEBOUNCE_FRAMES) {
                handVisible = false
                if (lockedTarget == null) {
                    urgentQueue.add("手已移出画面")
                }
            }
        }
    }

    // ========== 指向射线求交 ==========

    /**
     * 从食指 MCP→TIP 的方向延长射线，返回沿射线最先命中的物体。
     * 排除疑似"用户自己"的 person 框（框同时包住手腕和指尖）。
     */
    fun findPointedObject(hand: Hand, objects: List<DetectedObject>): DetectedObject? {
        val dx = hand.indexTip.x - hand.indexMcp.x
        val dy = hand.indexTip.y - hand.indexMcp.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return null
        val dir = Point(dx / len, dy / len)

        var best: DetectedObject? = null
        var bestT = Float.MAX_VALUE
        for (obj in objects) {
            if (obj.label == "person" && obj.box.contains(hand.wrist) && obj.box.contains(hand.indexTip)) {
                continue // 大概率是用户自己的身体
            }
            val t = rayHits(hand.indexTip, dir, obj.box) ?: continue
            if (t < bestT) {
                bestT = t
                best = obj
            }
        }
        return best
    }

    /** 射线-矩形求交（slab 法）。命中返回进入距离 t（>=0），未命中返回 null */
    private fun rayHits(origin: Point, dir: Point, box: Box): Float? {
        var tMin = 0f
        var tMax = Float.MAX_VALUE

        if (abs(dir.x) < 1e-6f) {
            if (origin.x < box.left || origin.x > box.right) return null
        } else {
            var t1 = (box.left - origin.x) / dir.x
            var t2 = (box.right - origin.x) / dir.x
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = maxOf(tMin, t1)
            tMax = minOf(tMax, t2)
        }

        if (abs(dir.y) < 1e-6f) {
            if (origin.y < box.top || origin.y > box.bottom) return null
        } else {
            var t1 = (box.top - origin.y) / dir.y
            var t2 = (box.bottom - origin.y) / dir.y
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = maxOf(tMin, t1)
            tMax = minOf(tMax, t2)
        }

        return if (tMin <= tMax) tMin else null
    }

    // ========== 锁定目标跨帧跟踪 ==========

    private fun trackLockedTarget(objects: List<DetectedObject>): DetectedObject? {
        val locked = lockedTarget ?: return null

        // 同标签候选里找 IoU 最大者；IoU 太低则退回中心距离最近者
        val candidates = objects.filter { it.label == locked.label }
        val byIou = candidates.maxByOrNull { it.box.iou(locked.box) }
        val matched = when {
            byIou != null && byIou.box.iou(locked.box) >= TRACK_MIN_IOU -> byIou
            else -> candidates.minByOrNull { centerDist(it.box, locked.box) }
                ?.takeIf { centerDist(it.box, locked.box) <= TRACK_MAX_CENTER_DIST }
        }

        if (matched != null) {
            if (lockedLostAnnounced) {
                urgentQueue.add("重新找到目标${matched.labelZh}")
                lockedLostAnnounced = false
            }
            lockedTarget = matched
            lockedMissingFrames = 0
            return matched
        }

        lockedMissingFrames++
        if (lockedMissingFrames >= LOST_FRAMES_THRESHOLD && !lockedLostAnnounced) {
            lockedLostAnnounced = true
            urgentQueue.add("目标${locked.labelZh}暂时丢失，请缓慢转动身体寻找")
        }
        // 丢失期间仍返回旧框，方向提示基于最后已知位置
        return if (lockedMissingFrames < LOST_FRAMES_THRESHOLD) locked else null
    }

    private fun centerDist(a: Box, b: Box): Float {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        return sqrt(dx * dx + dy * dy)
    }

    // ========== 路径障碍（用户 → 目标 的走廊） ==========

    /**
     * 走廊：从画面底部中心（用户身位）到目标框底边中心的梯形通道。
     * 返回 [障碍物, 相对方位] 列表，按离用户从近到远排序。
     */
    fun findPathObstacles(
        target: DetectedObject,
        objects: List<DetectedObject>
    ): List<Pair<DetectedObject, String>> {
        val userX = 0.5f
        val userY = 1.0f
        val targetX = target.box.centerX
        val targetY = target.box.bottom
        if (targetY >= userY - 0.02f) return emptyList() // 目标就在脚下，无路径可言

        val result = mutableListOf<Pair<DetectedObject, String>>()
        for (obj in objects) {
            if (obj === target || obj.label == target.label && obj.box.iou(target.box) > 0.5f) continue
            val cy = obj.box.centerY
            // 只考虑纵向位于"目标底边 ~ 画面底部"之间的物体（即挡在路上的）
            if (cy <= targetY || cy > userY) continue

            // 该纵深处走廊中心线与半宽（线性插值）
            val s = (userY - cy) / (userY - targetY) // 0=用户处, 1=目标处
            val corridorX = userX + (targetX - userX) * s
            val halfWidth = CORRIDOR_BASE_HALF_WIDTH +
                    (maxOf(target.box.width / 2f, 0.08f) - CORRIDOR_BASE_HALF_WIDTH) * s

            val dx = obj.box.centerX - corridorX
            if (abs(dx) < halfWidth + obj.box.width / 2f) {
                val side = when {
                    dx < -0.04f -> "偏左"
                    dx > 0.04f -> "偏右"
                    else -> "正前"
                }
                result.add(obj to side)
            }
        }
        // 离用户越近（centerY 越大）越先播报
        return result.sortedByDescending { it.first.box.centerY }
    }

    private fun obstaclesPhrase(obstacles: List<Pair<DetectedObject, String>>): String {
        if (obstacles.isEmpty()) return ""
        val nearest = obstacles.first()
        val advice = when (nearest.second) {
            "偏左" -> "建议靠右通过"
            "偏右" -> "建议靠左通过"
            else -> "请止步，正前方有障碍"
        }
        val listText = obstacles.take(2).joinToString("，") { "${it.first.labelZh}${it.second}" }
        return "路径上有障碍：$listText，$advice"
    }

    // ========== 手指与目标的对齐提示 ==========

    /** 手指方向与锁定目标的偏差提示；正对目标时返回空串 */
    fun alignmentPhrase(hand: Hand, target: DetectedObject): String {
        val dx = hand.indexTip.x - hand.indexMcp.x
        val dy = hand.indexTip.y - hand.indexMcp.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return ""
        val dir = Point(dx / len, dy / len)

        if (rayHits(hand.indexTip, dir, target.box) != null) {
            return "手指正对目标"
        }
        val offX = target.box.centerX - hand.indexTip.x
        val offY = target.box.centerY - hand.indexTip.y
        // 叉积 z 分量：图像坐标 y 向下，cross>0 表示目标在指向方向的右侧
        val cross = dir.x * offY - dir.y * offX
        return if (cross > 0) "手指偏离目标，请向右调整" else "手指偏离目标，请向左调整"
    }

    // ========== 方位/距离/分区措辞 ==========

    /** 按物体中心 x 给出方位（近似时钟方位），用于"您指向了什么"的描述 */
    fun directionPhrase(box: Box): String = when {
        box.centerX < 0.2f -> "左侧九点钟方向"
        box.centerX < 0.42f -> "左前方"
        box.centerX <= 0.58f -> "正前方"
        box.centerX <= 0.8f -> "右前方"
        else -> "右侧三点钟方向"
    }

    /** 锁定目标后的行走转向指令：告诉盲人该往左还是往右 */
    fun walkPhrase(box: Box): String = when {
        box.centerX < 0.2f -> "请向左转"
        box.centerX < 0.42f -> "请稍向左，再直走"
        box.centerX <= 0.58f -> "朝正前方直走"
        box.centerX <= 0.8f -> "请稍向右，再直走"
        else -> "请向右转"
    }

    // ========== 单目测距（尺寸先验 + 小孔成像） ==========

    /**
     * 估算目标距离（米）：distance ≈ 真实高度 / (2 × 框高占比 × tan(垂直FOV/2))。
     * 该物体类别没有尺寸先验或框太小时返回 null。
     * 注意：单目估计，误差约 ±30%，播报措辞刻意带"约"。
     */
    fun estimateMeters(obj: DetectedObject): Float? {
        val realHeight = SizePriorsM.heightOf(obj.label) ?: return null
        val frac = obj.box.height
        if (frac < 0.02f) return null
        return (realHeight / (2f * frac * tanHalfVFov)).coerceIn(0.3f, 50f)
    }

    /**
     * 距离播报：有尺寸先验时报"约X米，大约Y步"（随行走实时变化，即锚点距离提示）；
     * 无先验时退回框高分档的步数估计。
     */
    fun distancePhrase(obj: DetectedObject): String {
        val meters = estimateMeters(obj)
        if (meters != null) {
            if (meters < 1.0f) return "就在跟前，不到一米"
            val steps = (meters / STEP_LENGTH_M).roundToInt().coerceAtLeast(1)
            return "还剩约${fmtMeters(meters)}米，大约${steps}步"
        }
        val h = obj.box.height
        return when {
            h >= 0.55f -> "就在跟前，大约一两步"
            h >= 0.30f -> "大约三到五步"
            h >= 0.12f -> "大约十步左右"
            else -> "较远，超过十五步"
        }
    }

    /** 米数取到 0.5 精度，读起来自然（"约3.5米" / "约6米"） */
    private fun fmtMeters(m: Float): String {
        val half = (m * 2).roundToInt() / 2f
        return if (half % 1f == 0f) half.toInt().toString() else half.toString()
    }

    /** 手（食指指尖）在画面中的九宫格位置 */
    fun handZonePhrase(tip: Point): String {
        val h = when {
            tip.x < 0.33f -> "左"
            tip.x > 0.67f -> "右"
            else -> "中"
        }
        val v = when {
            tip.y < 0.33f -> "上"
            tip.y > 0.67f -> "下"
            else -> "中"
        }
        return when {
            h == "中" && v == "中" -> "画面中央"
            h == "中" -> "画面${v}方"
            v == "中" -> "画面${h}侧"
            else -> "画面${h}${v}方"
        }
    }

    // ========== 播报节流 ==========

    private fun finish(
        text: String?,
        fingerprint: String,
        status: String,
        now: Long,
        allowRepeat: Boolean
    ): GuideResult {
        // 紧急事件（锁定/丢失/手出现等）优先，且不受节流限制。
        // 注意：不更新指纹，保证被紧急事件插队的常规状态在下一帧照常播出
        if (urgentQueue.isNotEmpty()) {
            val urgent = urgentQueue.removeFirst()
            lastAnnounceTs = now
            return GuideResult(urgent, status)
        }

        if (text == null) {
            lastFingerprint = fingerprint
            return GuideResult(null, status)
        }

        val changed = fingerprint != lastFingerprint
        val sinceLast = now - lastAnnounceTs
        val shouldSpeak = (changed && sinceLast >= minAnnounceIntervalMs) ||
                (allowRepeat && sinceLast >= repeatIntervalMs)

        return if (shouldSpeak) {
            lastAnnounceTs = now
            lastFingerprint = fingerprint
            GuideResult(text, status)
        } else {
            if (changed && sinceLast < minAnnounceIntervalMs) {
                // 状态变了但被节流：不更新指纹，让下一次达到间隔时播出
            } else {
                lastFingerprint = fingerprint
            }
            GuideResult(null, status)
        }
    }
}

/** COCO 80 类标签 → 中文（EfficientDet-Lite0 输出为英文标签） */
object CocoLabelsZh {
    private val map = mapOf(
        "person" to "人", "bicycle" to "自行车", "car" to "汽车", "motorcycle" to "摩托车",
        "airplane" to "飞机", "bus" to "公交车", "train" to "火车", "truck" to "卡车",
        "boat" to "船", "traffic light" to "红绿灯", "fire hydrant" to "消防栓",
        "stop sign" to "停车标志", "parking meter" to "停车计时器", "bench" to "长椅",
        "bird" to "鸟", "cat" to "猫", "dog" to "狗", "horse" to "马", "sheep" to "羊",
        "cow" to "牛", "elephant" to "大象", "bear" to "熊", "zebra" to "斑马",
        "giraffe" to "长颈鹿", "backpack" to "背包", "umbrella" to "雨伞",
        "handbag" to "手提包", "tie" to "领带", "suitcase" to "行李箱",
        "frisbee" to "飞盘", "skis" to "滑雪板", "snowboard" to "单板滑雪板",
        "sports ball" to "球", "kite" to "风筝", "baseball bat" to "棒球棒",
        "baseball glove" to "棒球手套", "skateboard" to "滑板", "surfboard" to "冲浪板",
        "tennis racket" to "网球拍", "bottle" to "瓶子", "wine glass" to "酒杯",
        "cup" to "杯子", "fork" to "叉子", "knife" to "刀", "spoon" to "勺子",
        "bowl" to "碗", "banana" to "香蕉", "apple" to "苹果", "sandwich" to "三明治",
        "orange" to "橙子", "broccoli" to "西兰花", "carrot" to "胡萝卜",
        "hot dog" to "热狗", "pizza" to "披萨", "donut" to "甜甜圈", "cake" to "蛋糕",
        "chair" to "椅子", "couch" to "沙发", "potted plant" to "盆栽",
        "bed" to "床", "dining table" to "餐桌", "toilet" to "马桶", "tv" to "电视",
        "laptop" to "笔记本电脑", "mouse" to "鼠标", "remote" to "遥控器",
        "keyboard" to "键盘", "cell phone" to "手机", "microwave" to "微波炉",
        "oven" to "烤箱", "toaster" to "烤面包机", "sink" to "水槽",
        "refrigerator" to "冰箱", "book" to "书", "clock" to "时钟", "vase" to "花瓶",
        "scissors" to "剪刀", "teddy bear" to "泰迪熊", "hair drier" to "吹风机",
        "toothbrush" to "牙刷", "door" to "门", "window" to "窗户"
    )

    fun zh(label: String): String = map[label.lowercase().trim()] ?: label
}

/**
 * 常见物体的真实高度先验（米），用于单目测距。
 * 只收录高度方差较小的类别；沙发/床等取常见值，误差在可接受范围。
 */
object SizePriorsM {
    private val heights = mapOf(
        "person" to 1.65f, "door" to 2.0f, "chair" to 0.85f, "couch" to 0.8f,
        "bench" to 0.85f, "car" to 1.5f, "bus" to 3.0f, "truck" to 3.0f,
        "bicycle" to 1.0f, "motorcycle" to 1.1f, "traffic light" to 0.9f,
        "stop sign" to 0.75f, "fire hydrant" to 0.75f, "dining table" to 0.75f,
        "bed" to 0.55f, "refrigerator" to 1.7f, "tv" to 0.6f, "laptop" to 0.25f,
        "bottle" to 0.25f, "cup" to 0.1f, "backpack" to 0.45f, "suitcase" to 0.6f,
        "potted plant" to 0.5f, "toilet" to 0.75f, "microwave" to 0.3f,
        "oven" to 0.7f, "sink" to 0.3f, "cat" to 0.3f, "dog" to 0.5f
    )

    fun heightOf(label: String): Float? = heights[label.lowercase().trim()]
}
