package com.visionlink.android.voiceprint

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * 声纹识别管理器 (端侧离线)
 *
 * 功能:
 * 1. 从麦克风录制语音
 * 2. 提取 FBANK 特征 + CMVN 归一化
 * 3. ONNX Runtime 推理得到 192 维声纹向量
 * 4. 余弦相似度比对
 * 5. 多用户注册+识别，支持个性化设置
 *
 * 模型: ECAPA-TDNN (speechbrain/spkrec-ecapa-voxceleb)
 * ONNX 文件: assets/models/voiceprint/ecapa_encoder.onnx (~0.5MB)
 *
 * 用法:
 * ```
 * val vpm = VoicePrintManager(context)
 * vpm.initialize()  // 加载 ONNX 模型
 *
 * // 注册
 * vpm.startEnrollment("alice", "Alice") { score ->
 *     // score == 1.0 表示成功
 * }
 *
 * // 识别
 * vpm.startIdentification { result ->
 *     // result.userId, result.score, result.name
 * }
 * ```
 */
class VoicePrintManager(private val context: Context) {

    companion object {
        private const val TAG = "VoicePrintManager"

        // 音频参数
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 录音参数
        private const val ENROLL_DURATION_S = 5.0f    // 注册录音 5 秒
        private const val VERIFY_DURATION_S = 3.0f     // 验证录音 3 秒
        private const val VAD_SILENCE_S = 1.5f         // VAD 静音停止
        private const val VAD_MAX_S = 10.0f            // VAD 最大录音

        // FBANK 参数 (与 SpeechBrain 一致)
        private const val N_FFT = 400
        private const val N_MELS = 80
        private const val HOP_LENGTH = 200
        private const val WIN_LENGTH = 400
        private const val F_MIN = 0.0f
        private const val F_MAX = 8000.0f

        // 阈值 (可通过 SharedPreferences 覆盖)
        private const val DEFAULT_VERIFY_THRESHOLD = 0.50f
        private const val DEFAULT_IDENTIFY_THRESHOLD = 0.45f

        // ========== FFT 预计算缓存 ==========
        // N_FFT=400 不是 2 的幂，用 512 作为 FFT 大小
        private const val FFT_SIZE = 512
        private val FFT_SIZE_LOG2 = (Math.log(FFT_SIZE.toDouble()) / Math.log(2.0)).toInt()

        // 预计算 bit-reversal 表
        private val BIT_REVERSE_TABLE: IntArray by lazy {
            val table = IntArray(FFT_SIZE)
            for (i in 0 until FFT_SIZE) {
                var j = 0
                var bit = FFT_SIZE / 2
                var idx = i
                while (bit > 0) {
                    if (idx and 1 != 0) j += bit
                    idx = idx shr 1
                    bit = bit shr 1
                }
                table[i] = j
            }
            table
        }

        // 预计算 twiddle factors (cos/sin 表)
        private val TWIDDLE_REAL: FloatArray by lazy {
            val table = FloatArray(FFT_SIZE / 2)
            for (i in 0 until FFT_SIZE / 2) {
                val angle = -2.0 * Math.PI * i / FFT_SIZE
                table[i] = Math.cos(angle).toFloat()
            }
            table
        }
        private val TWIDDLE_IMAG: FloatArray by lazy {
            val table = FloatArray(FFT_SIZE / 2)
            for (i in 0 until FFT_SIZE / 2) {
                val angle = -2.0 * Math.PI * i / FFT_SIZE
                table[i] = Math.sin(angle).toFloat()
            }
            table
        }

        // 预计算 Hamming 窗
        private val HAMMING_WINDOW: FloatArray by lazy {
            val w = FloatArray(WIN_LENGTH)
            for (j in 0 until WIN_LENGTH) {
                w[j] = (0.54f - 0.46f * Math.cos(2 * Math.PI * j / (WIN_LENGTH - 1)).toFloat())
            }
            w
        }

        // 预计算 Mel 滤波器组
        private val MEL_FILTERS: Array<FloatArray> by lazy {
            createMelFilterBankStatic(FFT_SIZE, N_MELS, SAMPLE_RATE, F_MIN, F_MAX)
        }

        // Mel 转换辅助函数
        private fun hzToMel(hz: Float): Float = 2595f * kotlin.math.log10(1 + hz / 700f)
        private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

        /**
         * 静态创建 Mel 滤波器组 (供预计算使用)
         */
        private fun createMelFilterBankStatic(
            nFft: Int, nMels: Int, sampleRate: Int, fMin: Float, fMax: Float
        ): Array<FloatArray> {
            val melMin = hzToMel(fMin)
            val melMax = hzToMel(fMax)
            val melPoints = FloatArray(nMels + 2)
            for (i in 0..nMels + 1) {
                melPoints[i] = melMin + (melMax - melMin) * i / (nMels + 1)
            }

            val hzPoints = FloatArray(nMels + 2) { melToHz(melPoints[it]) }
            val binPoints = IntArray(nMels + 2) { Math.round(hzPoints[it] * nFft / sampleRate).toInt() }

            val filters = Array(nMels) { FloatArray(nFft / 2 + 1) }
            for (m in 0 until nMels) {
                val left = binPoints[m]
                val center = binPoints[m + 1]
                val right = binPoints[m + 2]

                for (k in left..center) {
                    if (center > left) {
                        filters[m][k] = (k - left).toFloat() / (center - left)
                    }
                }
                for (k in center..right) {
                    if (right > center) {
                        filters[m][k] = (right - k).toFloat() / (right - center)
                    }
                }
            }
            return filters
        }

        // 文件
        private const val MODEL_DIR = "models/voiceprint"
        private const val MODEL_FILE = "ecapa_encoder.onnx"
        private const val PREFS_NAME = "voiceprint_prefs"
        private const val USERS_KEY = "enrolled_users"
    }

    // ONNX Runtime
    private var ortSession: ai.onnxruntime.OrtSession? = null
    private var isModelLoaded = false

    // 录音状态
    private val isRecording = AtomicBoolean(false)
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 已注册用户 {userId: VoicePrintUser}
    private val enrolledUsers = java.util.concurrent.ConcurrentHashMap<String, VoicePrintUser>()

    // 可配置阈值
    private var verifyThreshold: Float = DEFAULT_VERIFY_THRESHOLD
    private var identifyThreshold: Float = DEFAULT_IDENTIFY_THRESHOLD

    fun setVerifyThreshold(threshold: Float) {
        verifyThreshold = threshold.coerceIn(0f, 1f)
    }

    fun setIdentifyThreshold(threshold: Float) {
        identifyThreshold = threshold.coerceIn(0f, 1f)
    }

    // ========== 数据类 ==========

    data class VoicePrintUser(
        val userId: String,
        val name: String,
        val embedding: FloatArray,  // 192 维
        val enrolledAt: Long,
        // 个性化设置
        var preferredMode: Int = 1,        // 1=避障 2=读文字 3=场景 4=引导
        var ttsRate: Float = 1.0f,         // 语速
        var ttsPitch: Float = 1.0f,        // 音调
        var isEnglish: Boolean = false     // 语言偏好
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("userId", userId)
                put("name", name)
                put("enrolledAt", enrolledAt)
                put("preferredMode", preferredMode)
                put("ttsRate", ttsRate)
                put("ttsPitch", ttsPitch)
                put("isEnglish", isEnglish)
                // embedding 转 JSON array
                val embArray = org.json.JSONArray()
                for (v in embedding) embArray.put(v.toDouble())
                put("embedding", embArray)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): VoicePrintUser {
                val embArray = json.getJSONArray("embedding")
                val emb = FloatArray(embArray.length())
                for (i in 0 until embArray.length()) {
                    emb[i] = embArray.getDouble(i).toFloat()
                }
                return VoicePrintUser(
                    userId = json.getString("userId"),
                    name = json.optString("name", json.getString("userId")),
                    embedding = emb,
                    enrolledAt = json.optLong("enrolledAt", System.currentTimeMillis()),
                    preferredMode = json.optInt("preferredMode", 1),
                    ttsRate = json.optDouble("ttsRate", 1.0).toFloat(),
                    ttsPitch = json.optDouble("ttsPitch", 1.0).toFloat(),
                    isEnglish = json.optBoolean("isEnglish", false)
                )
            }
        }
    }

    data class IdentificationResult(
        val userId: String?,
        val name: String?,
        val score: Float,
        val isMatch: Boolean
    )

    // ========== 初始化 ==========

    /**
     * 加载 ONNX 模型
     */
    fun initialize(): Boolean {
        return try {
            // 复制 ONNX 模型到 cache 目录
            val modelFile = copyAssetToCache(MODEL_FILE, "$MODEL_DIR/$MODEL_FILE")

            // 强类型 ONNX Runtime API
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            ortSession = env.createSession(modelFile.absolutePath, opts)
            isModelLoaded = true

            // 加载已注册用户
            loadEnrolledUsers()

            Log.i(TAG, "ONNX model loaded, ${enrolledUsers.size} user(s) enrolled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model: ${e.message}", e)
            // 回退模式：仅用简单能量特征
            isModelLoaded = false
            loadEnrolledUsers()
            false
        }
    }

    /**
     * 检查模型是否已加载
     */
    fun isReady(): Boolean = isModelLoaded

    /**
     * 获取已注册用户数
     */
    fun getEnrolledCount(): Int = enrolledUsers.size

    fun getEnrolledUsers(): List<VoicePrintUser> = enrolledUsers.values.toList()

    // ========== 注册 ==========

    /**
     * 注册声纹
     * @param userId 用户ID
     * @param name 显示名称
     * @param callback 完成回调 (score: Float, success: Boolean)
     */
    fun startEnrollment(
        userId: String,
        name: String,
        callback: (score: Float, success: Boolean) -> Unit
    ) {
        if (isRecording.get()) {
            callback(0f, false)
            return
        }

        recordJob?.cancel()
        recordJob = scope.launch {
            isRecording.set(true)
            try {
                Log.i(TAG, "Enrolling '$userId' ($ENROLL_DURATION_S s)...")
                val audio = recordAudio(ENROLL_DURATION_S)

                if (audio.size < SAMPLE_RATE) {  // < 1s
                    Log.e(TAG, "Audio too short: ${audio.size} samples")
                    withContext(Dispatchers.Main) { callback(0f, false) }
                    return@launch
                }

                val embedding = extractEmbedding(audio)
                if (embedding == null) {
                    Log.e(TAG, "Embedding extraction failed")
                    withContext(Dispatchers.Main) { callback(0f, false) }
                    return@launch
                }

                // 自相似度检查
                val audio2 = recordAudio(ENROLL_DURATION_S)
                val embedding2 = extractEmbedding(audio2)
                val score = if (embedding2 != null) {
                    cosineSimilarity(embedding, embedding2)
                } else 1.0f

                // 保存
                val user = VoicePrintUser(
                    userId = userId,
                    name = name,
                    embedding = embedding,
                    enrolledAt = System.currentTimeMillis()
                )
                enrolledUsers[userId] = user
                saveEnrolledUsers()

                Log.i(TAG, "Enrolled '$userId': score=$score, emb_dim=${embedding.size}")
                withContext(Dispatchers.Main) { callback(score, score > 0.5f) }
            } catch (e: Exception) {
                Log.e(TAG, "Enrollment error: ${e.message}", e)
                withContext(Dispatchers.Main) { callback(0f, false) }
            } finally {
                isRecording.set(false)
            }
        }
    }

    // ========== 验证 (1:1) ==========

    /**
     * 验证身份
     * @param userId 声称的用户
     * @param callback (result: IdentificationResult)
     */
    fun startVerification(
        userId: String,
        callback: (result: IdentificationResult) -> Unit
    ) {
        val user = enrolledUsers[userId]
        if (user == null) {
            callback(IdentificationResult(null, null, 0f, false))
            return
        }

        if (isRecording.get()) {
            callback(IdentificationResult(null, null, 0f, false))
            return
        }

        recordJob?.cancel()
        recordJob = scope.launch {
            isRecording.set(true)
            try {
                val audio = recordAudio(VERIFY_DURATION_S)
                if (audio.size < SAMPLE_RATE / 2) {
                    withContext(Dispatchers.Main) {
                        callback(IdentificationResult(null, null, 0f, false))
                    }
                    return@launch
                }

                val embedding = extractEmbedding(audio)
                if (embedding == null) {
                    withContext(Dispatchers.Main) {
                        callback(IdentificationResult(null, null, 0f, false))
                    }
                    return@launch
                }

                val score = cosineSimilarity(embedding, user.embedding)
                val isMatch = score >= verifyThreshold

                Log.i(TAG, "Verify '$userId': score=$score, match=$isMatch")
                withContext(Dispatchers.Main) {
                    callback(IdentificationResult(
                        userId = if (isMatch) userId else null,
                        name = if (isMatch) user.name else null,
                        score = score,
                        isMatch = isMatch
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Verification error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(IdentificationResult(null, null, 0f, false))
                }
            } finally {
                isRecording.set(false)
            }
        }
    }

    // ========== 识别 (1:N) ==========

    /**
     * 识别说话人
     * @param callback (result: IdentificationResult)
     */
    fun startIdentification(
        callback: (result: IdentificationResult) -> Unit
    ) {
        if (enrolledUsers.isEmpty()) {
            callback(IdentificationResult(null, null, 0f, false))
            return
        }

        if (isRecording.get()) {
            callback(IdentificationResult(null, null, 0f, false))
            return
        }

        recordJob?.cancel()
        recordJob = scope.launch {
            isRecording.set(true)
            try {
                val audio = recordAudio(VERIFY_DURATION_S)
                if (audio.size < SAMPLE_RATE / 2) {
                    withContext(Dispatchers.Main) {
                        callback(IdentificationResult(null, null, 0f, false))
                    }
                    return@launch
                }

                val embedding = extractEmbedding(audio)
                if (embedding == null) {
                    withContext(Dispatchers.Main) {
                        callback(IdentificationResult(null, null, 0f, false))
                    }
                    return@launch
                }

                // 与所有用户比对
                var bestUser: VoicePrintUser? = null
                var bestScore = -1.0f

                for (user in enrolledUsers.values) {
                    val score = cosineSimilarity(embedding, user.embedding)
                    if (score > bestScore) {
                        bestScore = score
                        bestUser = user
                    }
                }

                val isMatch = bestScore >= identifyThreshold
                Log.i(TAG, "Identify: best='$bestUser' score=$bestScore match=$isMatch")

                withContext(Dispatchers.Main) {
                    callback(IdentificationResult(
                        userId = if (isMatch) bestUser?.userId else null,
                        name = if (isMatch) bestUser?.name else null,
                        score = bestScore,
                        isMatch = isMatch
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Identification error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(IdentificationResult(null, null, 0f, false))
                }
            } finally {
                isRecording.set(false)
            }
        }
    }

    // ========== VAD 录音 ==========

    /**
     * VAD 录音（检测到静音自动停止）
     */
    fun startVadRecording(
        callback: (result: IdentificationResult) -> Unit
    ) {
        if (enrolledUsers.isEmpty() || isRecording.get()) {
            callback(IdentificationResult(null, null, 0f, false))
            return
        }

        recordJob?.cancel()
        recordJob = scope.launch {
            isRecording.set(true)
            try {
                val audio = recordWithVad(VAD_SILENCE_S, VAD_MAX_S)
                if (audio.size < SAMPLE_RATE / 2) {
                    withContext(Dispatchers.Main) {
                        callback(IdentificationResult(null, null, 0f, false))
                    }
                    return@launch
                }

                val embedding = extractEmbedding(audio)
                if (embedding == null) {
                    withContext(Dispatchers.Main) {
                        callback(IdentificationResult(null, null, 0f, false))
                    }
                    return@launch
                }

                // 1:N 识别
                var bestUser: VoicePrintUser? = null
                var bestScore = -1.0f
                for (user in enrolledUsers.values) {
                    val score = cosineSimilarity(embedding, user.embedding)
                    if (score > bestScore) {
                        bestScore = score
                        bestUser = user
                    }
                }

                val isMatch = bestScore >= identifyThreshold
                withContext(Dispatchers.Main) {
                    callback(IdentificationResult(
                        userId = if (isMatch) bestUser?.userId else null,
                        name = if (isMatch) bestUser?.name else null,
                        score = bestScore,
                        isMatch = isMatch
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "VAD error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(IdentificationResult(null, null, 0f, false))
                }
            } finally {
                isRecording.set(false)
            }
        }
    }

    /**
     * 停止录音
     */
    fun stopRecording() {
        isRecording.set(false)
        recordJob?.cancel()
    }

    // ========== 删除用户 ==========

    fun deleteUser(userId: String): Boolean {
        val removed = enrolledUsers.remove(userId) != null
        if (removed) saveEnrolledUsers()
        return removed
    }

    /**
     * 更新用户个性化设置
     */
    fun updateUserSettings(
        userId: String,
        preferredMode: Int? = null,
        ttsRate: Float? = null,
        ttsPitch: Float? = null,
        isEnglish: Boolean? = null
    ) {
        val user = enrolledUsers[userId] ?: return
        preferredMode?.let { user.preferredMode = it }
        ttsRate?.let { user.ttsRate = it }
        ttsPitch?.let { user.ttsPitch = it }
        isEnglish?.let { user.isEnglish = it }
        saveEnrolledUsers()
    }

    fun getUser(userId: String): VoicePrintUser? = enrolledUsers[userId]

    // ========== 音频录制 ==========

    /**
     * 录制指定时长音频
     */
    private fun recordAudio(durationS: Float): FloatArray {
        val totalSamples = (SAMPLE_RATE * durationS).toInt()
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            totalSamples * 2
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        val shortBuffer = ShortArray(totalSamples)
        recorder.startRecording()

        var read = 0
        while (read < totalSamples) {
            val n = recorder.read(shortBuffer, read, totalSamples - read)
            if (n <= 0) break
            read += n
        }

        recorder.stop()
        recorder.release()

        // Short → Float [-1, 1]
        return FloatArray(read) { shortBuffer[it] / 32768.0f }
    }

    /**
     * VAD 录音
     */
    private fun recordWithVad(silenceDurationS: Float, maxDurationS: Float): FloatArray {
        val maxSamples = (SAMPLE_RATE * maxDurationS).toInt()
        val silenceSamples = (SAMPLE_RATE * silenceDurationS).toInt()
        val frameSize = 320 // 20ms at 16kHz

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            SAMPLE_RATE * 2
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        val audioBuffer = java.io.ByteArrayOutputStream()
        val frame = ShortArray(frameSize)
        recorder.startRecording()

        var totalSamples = 0
        var silenceCount = 0
        var hasSpeech = false
        val energyThreshold = 500.0 // Short 值能量阈值

        while (totalSamples < maxSamples && isRecording.get()) {
            val n = recorder.read(frame, 0, frameSize)
            if (n <= 0) continue

            // 计算能量
            var energy = 0.0
            for (i in 0 until n) {
                energy += frame[i].toDouble() * frame[i]
            }
            energy /= n

            if (energy > energyThreshold) {
                hasSpeech = true
                silenceCount = 0
            } else if (hasSpeech) {
                silenceCount += n
                if (silenceCount >= silenceSamples) break
            }

            // 写入 buffer
            for (i in 0 until n) {
                audioBuffer.write(frame[i].toInt() and 0xFF)
                audioBuffer.write((frame[i].toInt() shr 8) and 0xFF)
            }
            totalSamples += n
        }

        recorder.stop()
        recorder.release()

        // Byte → Float
        val bytes = audioBuffer.toByteArray()
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val s = (bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)
            samples[i] = (s.toShort().toInt() / 32768.0f)
        }
        return samples
    }

    // ========== 特征提取 ==========

    /**
     * 提取 FBANK 特征 + CMVN
     */
    private fun extractFbank(audio: FloatArray): Array<FloatArray> {
        // 1. 预加重
        val preEmphasis = 0.97f
        val emphasized = FloatArray(audio.size)
        emphasized[0] = audio[0]
        for (i in 1 until audio.size) {
            emphasized[i] = audio[i] - preEmphasis * audio[i - 1]
        }

        // 2. 分帧
        val numFrames = maxOf(1, (emphasized.size - WIN_LENGTH) / HOP_LENGTH + 1)
        val frames = Array(numFrames) { FloatArray(WIN_LENGTH) }
        for (i in 0 until numFrames) {
            val start = i * HOP_LENGTH
            for (j in 0 until WIN_LENGTH) {
                val idx = start + j
                frames[i][j] = if (idx < emphasized.size) emphasized[idx] else 0f
            }
        }

        // 3. Hamming 窗 (使用预计算)
        for (i in 0 until numFrames) {
            for (j in 0 until WIN_LENGTH) {
                frames[i][j] *= HAMMING_WINDOW[j]
            }
        }

        // 4. FFT → 功率谱 (使用 FFT_SIZE=512)
        val nFft = FFT_SIZE
        val powerSpec = Array(numFrames) { FloatArray(nFft / 2 + 1) }
        for (i in 0 until numFrames) {
            val fftInput = FloatArray(nFft)
            System.arraycopy(frames[i], 0, fftInput, 0, minOf(WIN_LENGTH, nFft))
            val fftResult = fftCached(fftInput)
            for (k in 0..nFft / 2) {
                val re = fftResult[2 * k]
                val im = fftResult[2 * k + 1]
                powerSpec[i][k] = re * re + im * im
            }
        }

        // 5. Mel 滤波器组 (使用预计算)
        val melFilters = MEL_FILTERS
        val fbank = Array(numFrames) { FloatArray(N_MELS) }
        for (i in 0 until numFrames) {
            for (m in 0 until N_MELS) {
                var sum = 0f
                for (k in 0..nFft / 2) {
                    sum += powerSpec[i][k] * melFilters[m][k]
                }
                fbank[i][m] = if (sum > 1e-10f) kotlin.math.log10(sum) else -10f
            }
        }

        // 6. CMVN (sentence 级归一化)
        for (m in 0 until N_MELS) {
            var mean = 0f
            for (i in 0 until numFrames) mean += fbank[i][m]
            mean /= numFrames

            var std = 0f
            for (i in 0 until numFrames) {
                val d = fbank[i][m] - mean
                std += d * d
            }
            std = kotlin.math.sqrt(std / numFrames)
            if (std < 1e-6f) std = 1f

            for (i in 0 until numFrames) {
                fbank[i][m] = (fbank[i][m] - mean) / std
            }
        }

        return fbank  // (numFrames, 80)
    }

    /**
     * 使用预计算表的 FFT (radix-2)
     */
    private fun fftCached(input: FloatArray): FloatArray {
        val n = FFT_SIZE
        require(input.size >= n) { "Input too small for FFT_SIZE" }
        val real = FloatArray(n)
        val imag = FloatArray(n)
        System.arraycopy(input, 0, real, 0, minOf(input.size, n))

        // Bit reversal (使用预计算表)
        for (i in 0 until n) {
            val j = BIT_REVERSE_TABLE[i]
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }

        // Cooley-Tukey (使用预计算 twiddle factors)
        var len = 2
        var stage = 1
        while (len <= n) {
            val halfLen = len / 2
            val stride = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until halfLen) {
                    val twIdx = k * stride
                    val wr = TWIDDLE_REAL[twIdx]
                    val wi = TWIDDLE_IMAG[twIdx]
                    val tr = wr * real[i + k + halfLen] - wi * imag[i + k + halfLen]
                    val ti = wr * imag[i + k + halfLen] + wi * real[i + k + halfLen]
                    real[i + k + halfLen] = real[i + k] - tr
                    imag[i + k + halfLen] = imag[i + k] - ti
                    real[i + k] += tr
                    imag[i + k] += ti
                }
                i += len
            }
            len *= 2
            stage++
        }

        // Interleave output
        val result = FloatArray(2 * n)
        for (i in 0 until n) {
            result[2 * i] = real[i]
            result[2 * i + 1] = imag[i]
        }
        return result
    }

    /**
     * 旧版 FFT (保留兼容，未使用预计算表)
     */
    private fun fft(input: FloatArray): FloatArray {
        val n = input.size
        val real = input.copyOf()
        val imag = FloatArray(n)

        // Bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }

        // Cooley-Tukey
        var len = 2
        while (len <= n) {
            val angle = -2 * Math.PI / len
            val wReal = Math.cos(angle).toFloat()
            val wImag = Math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until len / 2) {
                    val tr = wr * real[i + k + len / 2] - wi * imag[i + k + len / 2]
                    val ti = wr * imag[i + k + len / 2] + wi * real[i + k + len / 2]
                    real[i + k + len / 2] = real[i + k] - tr
                    imag[i + k + len / 2] = imag[i + k] - ti
                    real[i + k] += tr
                    imag[i + k] += ti
                    val nw = wr * wReal - wi * wImag
                    wi = wr * wImag + wi * wReal
                    wr = nw
                }
                i += len
            }
            len *= 2
        }

        // Interleave output
        val result = FloatArray(2 * n)
        for (i in 0 until n) {
            result[2 * i] = real[i]
            result[2 * i + 1] = imag[i]
        }
        return result
    }

    /**
     * 创建 Mel 滤波器组 (实例方法，委托给静态版本)
     */
    private fun createMelFilterBank(
        nFft: Int, nMels: Int, sampleRate: Int, fMin: Float, fMax: Float
    ): Array<FloatArray> = createMelFilterBankStatic(nFft, nMels, sampleRate, fMin, fMax)

    // ========== ONNX 推理 ==========

    /**
     * 提取声纹向量
     * @param audio PCM Float [-1, 1], 16kHz
     * @return 192 维向量, 或 null
     */
    private fun extractEmbedding(audio: FloatArray): FloatArray? {
        if (!isModelLoaded || ortSession == null) {
            // 回退：用简单特征
            return simpleEmbedding(audio)
        }

        return try {
            // 1. 提取 FBANK 特征
            val fbank = extractFbank(audio)  // (T, 80)

            // 2. ONNX 推理 (强类型 API)
            val session = ortSession!!
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()

            // 输入 shape: (1, T, 80)
            val shape = longArrayOf(1, fbank.size.toLong(), N_MELS.toLong())
            val input = ai.onnxruntime.OnnxTensor.createTensor(
                env, FloatBuffer.wrap(fbank.flatMap { it.toList() }.toFloatArray()), shape
            )

            val output = session.run(mapOf(session.inputNames.iterator().next() to input))
            val result = output[0].value as Array<FloatArray>  // (1, 1, 192) → flatten

            input.close()
            output.close()

            // 提取 192 维向量
            val emb = result[0]  // first row
            // L2 归一化
            var norm = 0f
            for (v in emb) norm += v * v
            norm = Math.sqrt(norm.toDouble()).toFloat()
            if (norm > 0) {
                for (i in emb.indices) emb[i] /= norm
            }

            emb
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}", e)
            null
        }
    }

    /**
     * 简单回退特征（无 ONNX 时）
     */
    private fun simpleEmbedding(audio: FloatArray): FloatArray {
        // 用 MFCC-like 特征作为回退
        val fbank = extractFbank(audio)
        // 取均值+标准差 作为 160 维向量
        val emb = FloatArray(N_MELS * 2)
        for (m in 0 until N_MELS) {
            var mean = 0f
            for (t in fbank.indices) mean += fbank[t][m]
            mean /= fbank.size

            var variance = 0f
            for (t in fbank.indices) {
                val d = fbank[t][m] - mean
                variance += d * d
            }
            variance /= fbank.size

            emb[m] = mean
            emb[N_MELS + m] = Math.sqrt(variance.toDouble()).toFloat()
        }

        // L2 归一化
        var norm = 0f
        for (v in emb) norm += v * v
        norm = Math.sqrt(norm.toDouble()).toFloat()
        if (norm > 0) for (i in emb.indices) emb[i] /= norm

        return emb
    }

    // ========== 相似度 ==========

    /**
     * 余弦相似度
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f

        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denom = Math.sqrt(normA.toDouble()).toFloat() * Math.sqrt(normB.toDouble()).toFloat()
        return if (denom > 0) dot / denom else 0f
    }

    // ========== 持久化 ==========

    private fun loadEnrolledUsers() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(USERS_KEY, null) ?: return

        try {
            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val user = VoicePrintUser.fromJson(jsonArray.getJSONObject(i))
                enrolledUsers[user.userId] = user
            }
            Log.i(TAG, "Loaded ${enrolledUsers.size} enrolled users")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load users: ${e.message}")
        }
    }

    private fun saveEnrolledUsers() {
        val jsonArray = org.json.JSONArray()
        for (user in enrolledUsers.values) {
            jsonArray.put(user.toJson())
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(USERS_KEY, jsonArray.toString())
            .apply()
    }

    // ========== 工具 ==========

    private fun copyAssetToCache(assetPath: String, fullPath: String): File {
        val outFile = File(context.cacheDir, fullPath)
        if (outFile.exists()) return outFile

        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "Copied $assetPath → ${outFile.absolutePath}")
        return outFile
    }

    /**
     * 释放资源
     */
    fun release() {
        stopRecording()
        try {
            (ortSession)?.close()
        } catch (_: Exception) {}
        ortSession = null
        isModelLoaded = false
        scope.cancel()
        Log.i(TAG, "VoicePrintManager released")
    }
}
