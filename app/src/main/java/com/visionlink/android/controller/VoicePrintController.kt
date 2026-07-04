package com.visionlink.android.controller

import android.content.Context
import android.util.Log
import com.visionlink.android.audio.TTSManager
import com.visionlink.android.audio.VoiceCommandManager
import com.visionlink.android.voiceprint.VoicePrintManager

/**
 * 声纹控制器 — 管理注册/识别/门控/偏好应用
 *
 * 从 MainActivity 抽取的职责：
 * - 声纹门控回调
 * - 自动识别用户
 * - 识别后应用偏好设置
 */
class VoicePrintController(
    private val context: Context,
    private val voicePrintManager: VoicePrintManager,
    private val voiceManager: VoiceCommandManager,
    private val ttsManager: TTSManager,
    private val onUserIdentified: (String, String) -> Unit,
    private val onUserIdentifyFailed: () -> Unit,
    private val onEnrollRequested: () -> Unit
) {
    companion object {
        private const val TAG = "VoicePrintController"
    }

    private var currentUserId: String? = null

    fun getCurrentUserId(): String? = currentUserId

    fun setCurrentUserId(userId: String?) {
        currentUserId = userId
    }

    /**
     * 设置声纹门控
     */
    fun setupVoicePrintGate() {
        voiceManager.voicePrintGate = { command, execute ->
            if (voicePrintManager.getEnrolledCount() > 0 && voicePrintManager.isReady()) {
                val targetUser = currentUserId ?: voicePrintManager.getEnrolledUsers().firstOrNull()?.userId
                if (targetUser.isNullOrEmpty()) {
                    execute()
                    return@voicePrintGate
                }
                ttsManager.speak("请先验证身份")
                voicePrintManager.startVerification(targetUser) { result ->
                    if (result.isMatch) {
                        execute()
                    } else {
                        ttsManager.speak("身份验证失败")
                    }
                }
            } else {
                execute()
            }
        }
    }

    /**
     * 自动识别当前用户
     */
    fun autoIdentifyUser() {
        if (voicePrintManager.getEnrolledCount() == 0) {
            Log.d(TAG, "No enrolled users, skip identification")
            return
        }
        if (!voicePrintManager.isReady()) {
            Log.d(TAG, "VoicePrint model not ready, skip identification")
            return
        }

        ttsManager.speak("正在识别身份")
        voicePrintManager.startIdentification { result ->
            if (result.isMatch && result.userId != null) {
                currentUserId = result.userId
                applyUserPreferences(result.userId!!, result.name ?: result.userId)
                onUserIdentified(result.userId, result.name ?: result.userId)
            } else {
                ttsManager.speak("未识别到已注册用户")
                onUserIdentifyFailed()
            }
        }
    }

    /**
     * 应用用户个性化偏好
     */
    private fun applyUserPreferences(userId: String, name: String) {
        val user = voicePrintManager.getEnrolledUsers().find { it.userId == userId } ?: return

        // 应用 TTS 偏好
        ttsManager.setSpeechRate(user.ttsRate)
        ttsManager.setPitch(user.ttsPitch)

        // 应用语言偏好
        voiceManager.setEnglish(user.isEnglish)

        Log.i(TAG, "Applied preferences for '$name': rate=${user.ttsRate}, pitch=${user.ttsPitch}, english=${user.isEnglish}")
    }

    /**
     * 切换用户
     */
    fun switchUser() {
        ttsManager.speak("正在识别身份")
        autoIdentifyUser()
    }

    /**
     * 请求注册
     */
    fun requestEnroll() {
        onEnrollRequested()
    }

    /**
     * 用户已注册后更新偏好
     */
    fun onUserEnrolled(userId: String) {
        currentUserId = userId
    }
}
