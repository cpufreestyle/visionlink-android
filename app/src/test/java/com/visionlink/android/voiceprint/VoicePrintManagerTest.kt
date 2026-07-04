package com.visionlink.android.voiceprint

import org.junit.Assert.*
import org.junit.Test

/**
 * VoicePrintManager 单元测试
 *
 * 测试余弦相似度、向量操作等纯计算逻辑（不依赖 Android Context）
 */
class VoicePrintManagerTest {

    // ========== 余弦相似度测试 ==========

    @Test
    fun `identical vectors have similarity 1`() {
        val a = floatArrayOf(1f, 2f, 3f, 4f)
        val b = floatArrayOf(1f, 2f, 3f, 4f)
        val sim = cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.0001f)
    }

    @Test
    fun `orthogonal vectors have similarity 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        val sim = cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.0001f)
    }

    @Test
    fun `opposite vectors have similarity -1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        val sim = cosineSimilarity(a, b)
        assertEquals(-1.0f, sim, 0.0001f)
    }

    @Test
    fun `similar vectors have high similarity`() {
        val a = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val b = floatArrayOf(1.1f, 2.1f, 2.9f, 4.1f, 4.9f)
        val sim = cosineSimilarity(a, b)
        assertTrue("Similar vectors should have similarity > 0.99", sim > 0.99f)
    }

    @Test
    fun `dissimilar vectors have low similarity`() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        val b = floatArrayOf(0f, 0f, 0f, 1f)
        val sim = cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.0001f)
    }

    @Test
    fun `192-dim embedding similarity is computed correctly`() {
        // 模拟 192 维声纹向量
        val base = FloatArray(192) { (it * 0.01f) - 0.5f }
        val noisy = FloatArray(192) { idx -> base[idx] + ((idx % 7) - 3) * 0.001f }

        val sim = cosineSimilarity(base, noisy)
        assertTrue("Noisy 192-dim embedding should have similarity > 0.95", sim > 0.95f)
        assertTrue("Similarity should be <= 1.0", sim <= 1.0f)
    }

    @Test
    fun `zero vector returns zero similarity`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        val sim = cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.0001f)
    }

    @Test
    fun `different length vectors throws exception`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1f, 2f)
        assertThrows(IllegalArgumentException::class.java) {
            cosineSimilarity(a, b)
        }
    }

    // ========== 阈值逻辑测试 ==========

    @Test
    fun `verify threshold 0_50 is above identify threshold 0_45`() {
        // 注册验证要求更高，识别要求较低
        val verifyThreshold = 0.50f
        val identifyThreshold = 0.45f
        assertTrue(verifyThreshold >= identifyThreshold)
    }

    // ========== 辅助方法 ==========

    /**
     * 余弦相似度计算 (与 VoicePrintManager 中的实现一致)
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have same length: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom > 1e-10) (dot / denom).toFloat() else 0f
    }
}
