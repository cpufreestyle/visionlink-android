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
 * 语音命令管理器 (v4.5)
 * 
 * 盲人无障碍交互核心模块
 * - 持续监听语音命令
 * - 支持自定义唤醒词和命令词
 * - 与 TTSManager 联动实现语音反馈
 */
class VoiceCommandManager(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit
) {
    
    companion object {
        private const val TAG = "VoiceCommandManager"
        
        // 命令词定义（支持中英文）
        val COMMANDS = mapOf(
            // 拍照分析
            "拍照" to VoiceCommand.CAPTURE_ANALYZE,
            "分析" to VoiceCommand.CAPTURE_ANALYZE,
            "拍" to VoiceCommand.CAPTURE_ANALYZE,
            "take photo" to VoiceCommand.CAPTURE_ANALYZE,
            "analyze" to VoiceCommand.CAPTURE_ANALYZE,
            
            // 障碍物模式
            "障碍物" to VoiceCommand.MODE_OBSTACLE,
            "避障" to VoiceCommand.MODE_OBSTACLE,
            "obstacle" to VoiceCommand.MODE_OBSTACLE,
            
            // 读文本模式
            "读文本" to VoiceCommand.MODE_READ_TEXT,
            "读文字" to VoiceCommand.MODE_READ_TEXT,
            "read text" to VoiceCommand.MODE_READ_TEXT,
            
            // 场景描述模式
            "场景" to VoiceCommand.MODE_SCENE,
            "描述" to VoiceCommand.MODE_SCENE,
            "scene" to VoiceCommand.MODE_SCENE,

            // 指向引导模式（模式4）
            "指向引导" to VoiceCommand.MODE_GUIDE,
            "指向" to VoiceCommand.MODE_GUIDE,
            "引导" to VoiceCommand.MODE_GUIDE,
            "guide" to VoiceCommand.MODE_GUIDE,
            "pointing" to VoiceCommand.MODE_GUIDE,

            // 锁定/取消锁定目标（指向引导模式内）
            "取消锁定" to VoiceCommand.UNLOCK_TARGET,
            "解锁" to VoiceCommand.UNLOCK_TARGET,
            "unlock" to VoiceCommand.UNLOCK_TARGET,
            "锁定" to VoiceCommand.LOCK_TARGET,
            "锁定目标" to VoiceCommand.LOCK_TARGET,
            "lock" to VoiceCommand.LOCK_TARGET,

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
            "commands" to VoiceCommand.HELP
        )
        
        // 帮助文本
        const val HELP_TEXT_ZH = "可用命令：拍照、分析、障碍物模式、读文本、场景描述、指向引导、锁定、取消锁定、开始检测、停止检测、初始化AI、帮助"
        const val HELP_TEXT_EN = "Commands: take photo, analyze, obstacle mode, read text, scene, guide, lock, unlock, start, stop, init AI, help"
    }
    
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
        UNKNOWN
    }
    
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isEnglish = false
    
    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(createListener())
            Log.d(TAG, "Speech recognizer available")
        } else {
            Log.w(TAG, "Speech recognizer not available")
        }
    }
    
    /**
     * 开始持续监听
     */
    fun startListening() {
        if (recognizer == null) {
            Log.e(TAG, "Speech recognizer not available")
            return
        }
        
        if (isListening) return
        
        isListening = true
        startRecognition()
        Log.d(TAG, "Started voice command listening")
    }
    
    /**
     * 停止监听
     */
    fun stopListening() {
        isListening = false
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recognition: ${e.message}")
        }
        Log.d(TAG, "Stopped voice command listening")
    }
    
    /**
     * 切换语言模式
     */
    fun setEnglish(english: Boolean) {
        isEnglish = english
    }
    
    /**
     * 设置监听语言
     */
    private fun startRecognition() {
        if (!isListening) return
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (isEnglish) Locale.ENGLISH else Locale.CHINESE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Recognition error: ${e.message}")
            // 延迟重试
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isListening) startRecognition()
            }, 1000)
        }
    }
    
    /**
     * 创建语音识别监听器
     */
    private fun createListener() = object : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech began")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // 音频强度变化，可用于可视化反馈
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
        }
        
        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error: $error"
            }
            Log.w(TAG, "Recognition error: $errorMsg")
            
            // 自动重试（网络错误不重试）
            if (isListening && error !in listOf(
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            )) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isListening) startRecognition()
                }, 500)
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches.isNullOrEmpty()) {
                // 没有结果，重新监听
                if (isListening) startRecognition()
                return
            }
            
            Log.d(TAG, "Voice results: $matches")
            
            // 匹配命令
            for (text in matches) {
                val command = matchCommand(text.trim().lowercase())
                if (command != VoiceCommand.UNKNOWN) {
                    Log.d(TAG, "Matched command: $command from: $text")
                    onCommand(command)
                    break
                }
            }
            
            // 继续监听
            if (isListening) startRecognition()
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            // 部分结果，可用于实时反馈
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            // 特殊事件
        }
    }
    
    /**
     * 匹配命令词
     */
    private fun matchCommand(text: String): VoiceCommand {
        // 精确匹配
        COMMANDS[text]?.let { return it }
        
        // 模糊匹配（包含关键词）
        for ((keyword, command) in COMMANDS) {
            if (text.contains(keyword) || keyword.contains(text)) {
                return command
            }
        }
        
        return VoiceCommand.UNKNOWN
    }
    
    /**
     * 获取帮助文本
     */
    fun getHelpText(): String {
        return if (isEnglish) HELP_TEXT_EN else HELP_TEXT_ZH
    }
    
    /**
     * 检查是否正在监听
     */
    fun isListening(): Boolean = isListening
    
    /**
     * 释放资源
     */
    fun release() {
        isListening = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing recognizer: ${e.message}")
        }
        recognizer = null
        Log.d(TAG, "VoiceCommandManager released")
    }
}