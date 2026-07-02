package com.visionlink.android.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * TTS 语音管理器
 * 
 * 优化点 (v1.1):
 * - 移除冗余的 initialize() 方法 (已在 init 中初始化)
 * - pendingText 改为线程安全 (synchronized)
 * - 添加语音参数初始化调用
 * - 添加发音完成回调
 * - 添加队列管理
 */
class TTSManager(
    private val context: Context,
    private val initListener: TextToSpeech.OnInitListener
) {
    
    companion object {
        private const val TAG = "TTSManager"
    }
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private val pendingLock = Any()
    
    // 发音完成回调
    private var utteranceCallback: ((String, Boolean) -> Unit)? = null
    
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置中文语音，缺语音包时回退英文，但无论哪种语言都要完成初始化流程
                val result = tts?.setLanguage(Locale.CHINESE)

                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Chinese not supported, falling back to US English")
                    tts?.setLanguage(Locale.US)
                } else {
                    Log.d(TAG, "TTS initialized (Chinese)")
                }

                isInitialized = true

                // 设置语音参数
                setVoiceParams()

                // 设置发音监听
                setupUtteranceListener()

                // 发送待处理的文本
                synchronized(pendingLock) {
                    pendingText?.let {
                        speakInternal(it)
                        pendingText = null
                    }
                }

                // 通知初始化监听器
                initListener.onInit(status)
            } else {
                Log.e(TAG, "TTS initialization failed")
                initListener.onInit(status)
            }
        }
    }
    
    /**
     * 设置语音参数
     */
    private fun setVoiceParams() {
        // 设置语速 (1.0 = 正常)
        tts?.setSpeechRate(1.0f)
        
        // 设置音调 (1.0 = 正常)
        tts?.setPitch(1.0f)
    }
    
    /**
     * 设置发音监听
     */
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed: $utteranceId")
                utteranceCallback?.invoke(utteranceId ?: "", true)
            }
            
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                utteranceCallback?.invoke(utteranceId ?: "", false)
            }
        })
    }
    
    /**
     * 语音播报 (公开方法)
     */
    fun speak(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, caching text")
            synchronized(pendingLock) {
                pendingText = text
            }
            return
        }
        
        speakInternal(text)
    }
    
    /**
     * 内部播报方法
     */
    private fun speakInternal(text: String) {
        // 清理文本
        val cleanText = text
            .replace("\"", "")
            .replace("'", "")
            .replace(""", "")
            .replace(""", "")
            .replace("\n", " ")
        
        Log.d(TAG, "Speaking: $cleanText")
        
        // 异步播报 (清空队列，立即播报)
        val utteranceId = "VisionLink_${System.currentTimeMillis()}"
        val params = HashMap<String, String>()
        params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
        
        tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            params
        )
    }
    
    /**
     * 设置发音完成回调
     */
    fun setUtteranceCallback(callback: (String, Boolean) -> Unit) {
        this.utteranceCallback = callback
    }
    
    /**
     * 停止播报
     */
    fun stop() {
        try {
            tts?.stop()
            Log.d(TAG, "TTS stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing TTS: ${e.message}")
        }
        tts = null
        isInitialized = false
        pendingText = null
        utteranceCallback = null
        Log.d(TAG, "TTS released")
    }
    
    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean {
        return try {
            tts?.isSpeaking ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 等待播报完成
     */
    fun awaitCompletion(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (isSpeaking()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Log.w(TAG, "TTS await timeout")
                return false
            }
            Thread.sleep(100)
        }
        return true
    }

    /**
     * Switch TTS language between Chinese and English
     */
    fun switchLanguage(isEnglish: Boolean) {
        val locale = if (isEnglish) java.util.Locale.US else java.util.Locale("zh", "CN")
        val result = tts?.setLanguage(locale)
        if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            android.util.Log.w(TAG, "Language not supported: $locale")
        }
    }
}
