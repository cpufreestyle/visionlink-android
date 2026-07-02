package com.visionlink.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较逻辑测试（不依赖 Android 环境，直接测纯函数逻辑副本）。
 * 与 UpdateManager.compareVersions 保持同一实现。
 */
class UpdateManagerVersionTest {

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    @Test
    fun `新版本大于旧版本`() {
        assertTrue(compareVersions("4.10.1", "4.9.1") > 0)
        assertTrue(compareVersions("4.10.0", "4.9.9") > 0)
        assertTrue(compareVersions("5.0.0", "4.10.1") > 0)
    }

    @Test
    fun `相同版本返回零`() {
        assertEquals(0, compareVersions("4.10.1", "4.10.1"))
    }

    @Test
    fun `旧版本小于新版本`() {
        assertTrue(compareVersions("4.9.1", "4.10.0") < 0)
    }

    @Test
    fun `段数不同时短版本按零补齐`() {
        assertTrue(compareVersions("4.10", "4.10.1") < 0)
        assertEquals(0, compareVersions("4.10.0", "4.10"))
    }

    @Test
    fun `容忍非数字后缀`() {
        // 4.10.1-beta 按 4.10.1.0 处理，不抛异常
        assertEquals(0, compareVersions("4.10.1-beta", "4.10.1"))
        assertTrue(compareVersions("4.10.2-rc1", "4.10.1") > 0)
    }
}
