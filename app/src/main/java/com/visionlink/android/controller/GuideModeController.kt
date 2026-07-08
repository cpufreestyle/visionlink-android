package com.visionlink.android.controller

import android.util.Log
import com.visionlink.android.ai.GuidanceEngine
import com.visionlink.android.audio.TTSManager

/**
 * 引导模式控制器 — 管理指向引导/目标锁定
 *
 * 从 MainActivity 抽取的职责：
 * - 引导模式开关
 * - 目标锁定/解锁
 * - 方向播报
 */
class GuideModeController(
    private val guidanceEngine: GuidanceEngine,
    private val ttsManager: TTSManager
) {
    companion object {
        private const val TAG = "GuideModeController"
    }

    private var isGuideMode = false
    private var lockedTarget: String? = null

    fun isActive(): Boolean = isGuideMode
    fun isTargetLocked(): Boolean = lockedTarget != null
    fun getLockedTarget(): String? = lockedTarget

    fun toggle(): Boolean {
        isGuideMode = !isGuideMode
        if (isGuideMode) {
            ttsManager.speak("引导模式已开启")
        } else {
            lockedTarget = null
            ttsManager.speak("引导模式已关闭")
        }
        Log.i(TAG, "Guide mode: $isGuideMode")
        return isGuideMode
    }

    fun lockTarget(target: String) {
        lockedTarget = target
        ttsManager.speak("已锁定目标：$target")
        Log.i(TAG, "Target locked: $target")
    }

    fun unlockTarget() {
        lockedTarget?.let {
            ttsManager.speak("已取消锁定")
        }
        lockedTarget = null
        Log.i(TAG, "Target unlocked")
    }

    /**
     * 生成引导播报
     */
    fun generateGuidance(objects: List<GuidanceEngine.DetectedObject>): String? {
        if (!isGuideMode) return null

        if (lockedTarget != null) {
            // 查找锁定目标
            val target = objects.find { it.label == lockedTarget || it.labelZh == lockedTarget }
            if (target != null) {
                return "${target.labelZh}，${guidanceEngine.directionPhrase(target.box)}，${guidanceEngine.distancePhrase(target)}"
            } else {
                return "目标已丢失，请重新寻找"
            }
        } else if (objects.isNotEmpty()) {
            // 播报最近/最危险的物体
            val nearest = objects.minByOrNull { it.box.centerY }
            return "${nearest!!.labelZh}，${guidanceEngine.directionPhrase(nearest.box)}，${guidanceEngine.distancePhrase(nearest)}"
        }
        return null
    }
}
