package com.nieao.blindaid

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 中文 TTS 播报 + 节流(同内容 3 秒内不重复,不同内容 1.2 秒间隔) */
class SpeechManager(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null
    private var lastTs = 0L
    private var lastMsg = ""

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ready = true
            }
        }
    }

    fun speak(msg: String?) {
        val m = msg ?: return
        if (!ready) return
        val now = System.currentTimeMillis()
        val interval = if (m == lastMsg) 3000 else 1200
        if (now - lastTs < interval) return
        lastTs = now
        lastMsg = m
        tts?.speak(m, TextToSpeech.QUEUE_FLUSH, null, "perception")
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
    }
}
