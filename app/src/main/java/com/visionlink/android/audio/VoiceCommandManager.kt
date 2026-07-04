package com.visionlink.android.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * 语音命令管理器 v5.0 — 增强版
 *
 * 新增功能:
 * 1. 自然语言理解 (NLU) — 模糊意图识别，不再仅依赖精确关键词
 * 2. 声纹门控 — 只有注册用户才能执行敏感命令 (与 VoicePrintManager 联动)
 * 3. 语音控制命令 — 音量/语速/重复/暂停/恢复
 * 4. 连续对话模式 — 支持上下文跟进命令
 * 5. TTS 联动反馈 — 每个命令有语音确认
 * 6. 命令置信度评分 — 多匹配时选最佳
 * 7. 自定义命令注册 — 运行时动态添加命令
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit
) {

    companion object {
        private const val TAG = "VoiceCommandManager"

        // ========== 基础命令词 ==========
        val COMMANDS = mapOf(
            // 拍照分析
            "拍照" to VoiceCommand.CAPTURE_ANALYZE,
            "分析" to VoiceCommand.CAPTURE_ANALYZE,
            "拍" to VoiceCommand.CAPTURE_ANALYZE,
            "看看" to VoiceCommand.CAPTURE_ANALYZE,
            "看一眼" to VoiceCommand.CAPTURE_ANALYZE,
            "前面有什么" to VoiceCommand.CAPTURE_ANALYZE,
            "take photo" to VoiceCommand.CAPTURE_ANALYZE,
            "analyze" to VoiceCommand.CAPTURE_ANALYZE,
            "what's ahead" to VoiceCommand.CAPTURE_ANALYZE,

            // 障碍物模式
            "障碍物" to VoiceCommand.MODE_OBSTACLE,
            "避障" to VoiceCommand.MODE_OBSTACLE,
            "obstacle" to VoiceCommand.MODE_OBSTACLE,

            // 读文本模式
            "读文本" to VoiceCommand.MODE_READ_TEXT,
            "读文字" to VoiceCommand.MODE_READ_TEXT,
            "念" to VoiceCommand.MODE_READ_TEXT,
            "读一下" to VoiceCommand.MODE_READ_TEXT,
            "read text" to VoiceCommand.MODE_READ_TEXT,

            // 场景描述模式
            "场景" to VoiceCommand.MODE_SCENE,
            "描述" to VoiceCommand.MODE_SCENE,
            "描述一下" to VoiceCommand.MODE_SCENE,
            "scene" to VoiceCommand.MODE_SCENE,

            // 指向引导模式
            "指向引导" to VoiceCommand.MODE_GUIDE,
            "指向" to VoiceCommand.MODE_GUIDE,
            "引导" to VoiceCommand.MODE_GUIDE,
            "guide" to VoiceCommand.MODE_GUIDE,

            // 锁定/解锁
            "取消锁定" to VoiceCommand.UNLOCK_TARGET,
            "解锁" to VoiceCommand.UNLOCK_TARGET,
            "锁定" to VoiceCommand.LOCK_TARGET,
            "锁定目标" to VoiceCommand.LOCK_TARGET,
            "lock" to VoiceCommand.LOCK_TARGET,
            "unlock" to VoiceCommand.UNLOCK_TARGET,

            // 连续检测
            "开始检测" to VoiceCommand.START_CONTINUOUS,
            "持续检测" to VoiceCommand.START_CONTINUOUS,
            "start" to VoiceCommand.START_CONTINUOUS,

            // 停止检测
            "停止检测" to VoiceCommand.STOP_CONTINUOUS,
            "停止" to VoiceCommand.STOP_CONTINUOUS,
            "stop" to VoiceCommand.STOP_CONTINUOUS,

            // 初始化AI
            "初始化" to VoiceCommand.INIT_AI,
            "启动AI" to VoiceCommand.INIT_AI,
            "init AI" to VoiceCommand.INIT_AI,

            // 帮助
            "帮助" to VoiceCommand.HELP,
            "命令" to VoiceCommand.HELP,
            "help" to VoiceCommand.HELP,

            // ===== v5.0 新增命令 =====

            // 音量控制
            "大声一点" to VoiceCommand.VOLUME_UP,
            "音量大一点" to VoiceCommand.VOLUME_UP,
            "louder" to VoiceCommand.VOLUME_UP,
            "小声一点" to VoiceCommand.VOLUME_DOWN,
            "音量小一点" to VoiceCommand.VOLUME_DOWN,
            "softer" to VoiceCommand.VOLUME_DOWN,

            // 语速控制
            "说慢点" to VoiceCommand.SPEED_DOWN,
            "慢一点" to VoiceCommand.SPEED_DOWN,
            "slower" to VoiceCommand.SPEED_DOWN,
            "说快点" to VoiceCommand.SPEED_UP,
            "快一点" to VoiceCommand.SPEED_UP,
            "faster" to VoiceCommand.SPEED_UP,

            // 重复
            "重复" to VoiceCommand.REPEAT,
            "再说一遍" to VoiceCommand.REPEAT,
            "repeat" to VoiceCommand.REPEAT,

            // 暂停/恢复
            "暂停" to VoiceCommand.PAUSE,
            "pause" to VoiceCommand.PAUSE,
            "恢复" to VoiceCommand.RESUME,
            "继续" to VoiceCommand.RESUME,
            "resume" to VoiceCommand.RESUME,

            // 切换用户
            "切换用户" to VoiceCommand.SWITCH_USER,
            "换人" to VoiceCommand.SWITCH_USER,
            "switch user" to VoiceCommand.SWITCH_USER,

            // 注册声纹
            "注册声纹" to VoiceCommand.ENROLL_VOICEPRINT,
            "添加用户" to VoiceCommand.ENROLL_VOICEPRINT,
            "enroll" to VoiceCommand.ENROLL_VOICEPRINT,

            // 关闭/退出
            "关闭" to VoiceCommand.CLOSE,
            "退出" to VoiceCommand.CLOSE,
            "close" to VoiceCommand.CLOSE
        )

        // ========== NLU 意图关键词 (用于模糊匹配) ==========
        private val INTENT_KEYWORDS = mapOf(
            VoiceCommand.CAPTURE_ANALYZE to listOf("拍", "看", "分析", "前面", "周围", "什么"),
            VoiceCommand.MODE_OBSTACLE to listOf("障碍", "避障", "挡", "安全"),
            VoiceCommand.MODE_READ_TEXT to listOf("读", "念", "文字", "书", "信", "牌"),
            VoiceCommand.MODE_SCENE to listOf("场景", "描述", "环境", "什么样"),
            VoiceCommand.MODE_GUIDE to listOf("引导", "指向", "带路", "走"),
            VoiceCommand.VOLUME_UP to listOf("大声", "音量大", "听不清"),
            VoiceCommand.VOLUME_DOWN to listOf("小声", "音量小", "太吵"),
            VoiceCommand.SPEED_DOWN to listOf("慢", "太快"),
            VoiceCommand.SPEED_UP to listOf("快", "太慢"),
            VoiceCommand.REPEAT to listOf("重复", "再说", "没听清"),
            VoiceCommand.PAUSE to listOf("暂停", "停一下", "安静"),
            VoiceCommand.RESUME to listOf("恢复", "继续", "开始"),
            VoiceCommand.HELP to listOf("帮助", "怎么用", "命令"),
            VoiceCommand.SWITCH_USER to listOf("切换", "换人", "不是"),
            VoiceCommand.ENROLL_VOICEPRINT to listOf("注册", "添加", "声纹")
        )

        const val HELP_TEXT_ZH = """可用命令：
拍照分析：拍照、分析、看看、前面有什么
模式切换：障碍物、读文本、场景、指向引导
引导控制：锁定、取消锁定
检测控制：开始检测、停止检测
音量控制：大声一点、小声一点
语速控制：说慢点、说快点
其他：重复、暂停、恢复、切换用户、注册声纹、帮助"""

        const val HELP_TEXT_EN = """Commands:
Capture: take photo, analyze, what's ahead
Modes: obstacle, read text, scene, guide
Guide: lock, unlock
Detection: start, stop
Volume: louder, softer
Speed: slower, faster
Other: repeat, pause, resume, switch user, enroll, help"""
    }

    // ========== 命令枚举 ==========
    enum class VoiceCommand {
        CAPTURE_ANALYZE,
        MODE_OBSTACLE,
        MODE_READ_TEXT,
        MODE_SCENE,
        MODE_GUIDE,
        LOCK_TARGET,
        UNLOCK_TARGET,
        START_CONTINUOUS,
        STOP_CONTINUOUS,
        INIT_AI,
        HELP,
        // v5.0 新增
        VOLUME_UP,
        VOLUME_DOWN,
        SPEED_UP,
        SPEED_DOWN,
        REPEAT,
        PAUSE,
        RESUME,
        SWITCH_USER,
        ENROLL_VOICEPRINT,
        CLOSE,
        UNKNOWN
    }

    // ========== 命令分类 ==========
    enum class CommandSecurity {
        OPEN,       // 任何人可用
        PROTECTED   // 需要声纹验证
    }

    val COMMAND_SECURITY = mapOf(
        VoiceCommand.CAPTURE_ANALYZE to CommandSecurity.OPEN,
        VoiceCommand.MODE_OBSTACLE to CommandSecurity.OPEN,
        VoiceCommand.MODE_READ_TEXT to CommandSecurity.OPEN,
        VoiceCommand.MODE_SCENE to CommandSecurity.OPEN,
        VoiceCommand.MODE_GUIDE to CommandSecurity.OPEN,
        VoiceCommand.LOCK_TARGET to CommandSecurity.OPEN,
        VoiceCommand.UNLOCK_TARGET to CommandSecurity.OPEN,
        VoiceCommand.START_CONTINUOUS to CommandSecurity.OPEN,
        VoiceCommand.STOP_CONTINUOUS to CommandSecurity.OPEN,
        VoiceCommand.INIT_AI to CommandSecurity.PROTECTED,
        VoiceCommand.HELP to CommandSecurity.OPEN,
        VoiceCommand.VOLUME_UP to CommandSecurity.OPEN,
        VoiceCommand.VOLUME_DOWN to CommandSecurity.OPEN,
        VoiceCommand.SPEED_UP to CommandSecurity.OPEN,
        VoiceCommand.SPEED_DOWN to CommandSecurity.OPEN,
        VoiceCommand.REPEAT to CommandSecurity.OPEN,
        VoiceCommand.PAUSE to CommandSecurity.OPEN,
        VoiceCommand.RESUME to CommandSecurity.OPEN,
        VoiceCommand.SWITCH_USER to CommandSecurity.PROTECTED,
        VoiceCommand.ENROLL_VOICEPRINT to CommandSecurity.PROTECTED,
        VoiceCommand.CLOSE to CommandSecurity.PROTECTED,
        VoiceCommand.UNKNOWN to CommandSecurity.OPEN
    )

    // ========== 状态 ==========
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isEnglish = false
    private var isPaused = false
    private var lastSpokenText: String? = null  // 用于重复命令

    // 声纹验证回调
    var voicePrintGate: ((VoiceCommand, () -> Unit) -> Unit)? = null

    // 自定义命令
    private val customCommands = mutableMapOf<String, VoiceCommand>()

    // 命令历史 (用于连续对话)
    private val commandHistory = mutableListOf<VoiceCommand>()
    private val maxHistorySize = 5

    // 统计
    private var totalCommands = 0
    private var successfulMatches = 0

    // ========== 初始化 ==========

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(createListener())
            Log.d(TAG, "Speech recognizer available (v5.0)")
        } else {
            Log.w(TAG, "Speech recognizer not available")
        }
    }

    // ========== 公开方法 ==========

    fun startListening() {
        if (recognizer == null) {
            Log.e(TAG, "Speech recognizer not available")
            return
        }
        if (isListening) return
        isListening = true
        isPaused = false
        startRecognition()
        Log.d(TAG, "Started voice command listening (v5.0)")
    }

    fun stopListening() {
        isListening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        Log.d(TAG, "Stopped voice command listening")
    }

    fun setEnglish(english: Boolean) {
        isEnglish = english
    }

    fun isPaused(): Boolean = isPaused

    fun isListening(): Boolean = isListening

    /**
     * 设置最后播报的文本（用于重复命令）
     */
    fun setLastSpokenText(text: String) {
        lastSpokenText = text
    }

    /**
     * 获取帮助文本
     */
    fun getHelpText(): String = if (isEnglish) HELP_TEXT_EN else HELP_TEXT_ZH

    /**
     * 添加自定义命令
     */
    fun addCustomCommand(keyword: String, command: VoiceCommand) {
        customCommands[keyword.trim().lowercase()] = command
        Log.d(TAG, "Custom command added: '$keyword' → $command")
    }

    /**
     * 移除自定义命令
     */
    fun removeCustomCommand(keyword: String) {
        customCommands.remove(keyword.trim().lowercase())
    }

    /**
     * 获取命令统计
     */
    fun getStats(): String {
        val rate = if (totalCommands > 0) "${(successfulMatches * 100 / totalCommands)}%" else "N/A"
        return "Total: $totalCommands, Matched: $successfulMatches ($rate)"
    }

    /**
     * 释放资源
     */
    fun release() {
        isListening = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        customCommands.clear()
        commandHistory.clear()
        Log.d(TAG, "VoiceCommandManager released (v5.0)")
    }

    // ========== 语音识别 ==========

    private fun startRecognition() {
        if (!isListening || isPaused) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (isEnglish) Locale.ENGLISH else Locale.CHINESE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Recognition error: ${e.message}")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isListening && !isPaused) startRecognition()
            }, 1000)
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val shouldRetry = when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false
                else -> true
            }
            if (isListening && !isPaused && shouldRetry) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening && !isPaused) startRecognition()
                }, 500)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches.isNullOrEmpty()) {
                if (isListening && !isPaused) startRecognition()
                return
            }

            Log.d(TAG, "Voice results: $matches")
            totalCommands++

            // 匹配命令（v5.0 增强版）
            val matched = matchCommandEnhanced(matches)

            if (matched != VoiceCommand.UNKNOWN) {
                successfulMatches++
                Log.d(TAG, "Matched command: $matched from: ${matches[0]}")
                commandHistory.add(matched)
                if (commandHistory.size > maxHistorySize) commandHistory.removeAt(0)

                // 声纹门控
                val security = COMMAND_SECURITY[matched] ?: CommandSecurity.OPEN
                if (security == CommandSecurity.PROTECTED && voicePrintGate != null) {
                    voicePrintGate!!(matched) { onCommand(matched) }
                } else {
                    onCommand(matched)
                }
            } else {
                // 未匹配到命令，传递 UNKNOWN
                onCommand(VoiceCommand.UNKNOWN)
            }

            if (isListening && !isPaused) startRecognition()
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ========== 增强命令匹配 ==========

    /**
     * 增强版命令匹配 (v5.0)
     * 1. 精确匹配
     * 2. 自定义命令匹配
     * 3. 包含匹配
     * 4. NLU 意图匹配（模糊）
     */
    private fun matchCommandEnhanced(matches: List<String>): VoiceCommand {
        for (text in matches) {
            val lower = text.trim().lowercase()

            // 1. 精确匹配
            COMMANDS[lower]?.let { return it }

            // 2. 自定义命令
            customCommands[lower]?.let { return it }
        }

        // 3. 包含匹配 + 4. NLU 意图匹配
        var bestMatch: VoiceCommand = VoiceCommand.UNKNOWN
        var bestScore = 0

        for (text in matches) {
            val lower = text.trim().lowercase()

            // 包含匹配
            for ((keyword, command) in COMMANDS) {
                if (lower.contains(keyword)) {
                    val score = keyword.length  // 更长的关键词 = 更高置信度
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = command
                    }
                }
            }

            // NLU 意图匹配
            for ((command, keywords) in INTENT_KEYWORDS) {
                for (kw in keywords) {
                    if (lower.contains(kw)) {
                        val score = kw.length + 1  // 意图匹配加分
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = command
                        }
                    }
                }
            }
        }

        return bestMatch
    }

    /**
     * 获取命令历史
     */
    fun getCommandHistory(): List<VoiceCommand> = commandHistory.toList()
}
