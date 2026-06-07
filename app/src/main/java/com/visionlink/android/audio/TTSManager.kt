package com.visionlink.android.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * TTS 语音管理器
 * 
 * 功能映射 (对应 PC 版 main.py):
 * - speak(text) → TTSManager.speak(text)
 * - System.Speech.Synthesis.SpeechSynthesizer → Android TTS
 * - Add-Type -AssemblyName System.Speech → android.speech.tts
 * 
 * 技术栈: Android TTS (TextToSpeech)
 * 输出: 手机扬声器 + 眼镜音频 (通过 CXR-M)
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
    
    init {
        tts = TextToSpeech(context, initListener)
    }
    
    /**
     * 初始化 TTS (对应 PC 版 $s = New-Object System.Speech.Synthesis.SpeechSynthesizer)
     */
    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置中文语音 (对应 PC 版中文播报)
                val result = tts?.setLanguage(Locale.CHINESE)
                
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "❌ 中文语音不支持")
                    // 降级到默认语音
                    tts?.setLanguage(Locale.US)
                } else {
                    Log.d(TAG, "✅ TTS 初始化成功 (中文)")
                    isInitialized = true
                    
                    // 发送待处理的文本
                    pendingText?.let {
                        speak(it)
                        pendingText = null
                    }
                }
            } else {
                Log.e(TAG, "❌ TTS 初始化失败")
            }
        }
    }
    
    /**
     * 语音播报 (对应 PC 版 speak(text) 函数)
     * 
     * PC 版代码:
     *   print(f"🎧 [语音播报]: {text}")
     *   clean_text = text.replace('"', '').replace("'", "")...
     *   command = f'''powershell -c "Add-Type -AssemblyName System.Speech; ..."""
     *   threading.Thread(target=run_cmd, daemon=True).start()
     * 
     * Android 版: 直接调用 TTS，异步执行
     */
    fun speak(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ TTS 未初始化，缓存文本")
            pendingText = text
            return
        }
        
        // 清理文本 (对应 PC 版 clean_text 处理)
        val cleanText = text
            .replace("\"", "")
            .replace("'", "")
            .replace(""", "")
            .replace(""", "")
            .replace("\n", " ")
        
        Log.d(TAG, "🎧 [语音播报]: $cleanText")
        
        // 异步播报 (对应 PC 版 threading.Thread)
        tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,  // 清空队列，立即播报
            null,
            "VisionLink_${System.currentTimeMillis()}"
        )
    }
    
    /**
     * 设置语音参数 (可选优化)
     */
    fun setVoiceParams() {
        // 设置语速 (0.5 = 慢, 1.0 = 正常, 2.0 = 快)
        tts?.setSpeechRate(1.0f)
        
        // 设置音调 (0.5 = 低, 1.0 = 正常, 2.0 = 高)
        tts?.setPitch(1.0f)
    }
    
    /**
     * 停止播报
     */
    fun stop() {
        tts?.stop()
        Log.d(TAG, "✅ TTS 已停止")
    }
    
    /**
     * 释放资源 (对应 PC 版 $s.Dispose())
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "✅ TTS 已释放")
    }
    
    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }
}
