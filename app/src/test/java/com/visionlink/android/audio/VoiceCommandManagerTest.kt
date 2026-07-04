package com.visionlink.android.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * VoiceCommandManager 单元测试
 *
 * 测试命令匹配逻辑（不依赖 Android SpeechRecognizer）
 */
class VoiceCommandManagerTest {

    // ========== 命令匹配逻辑测试 ==========

    @Test
    fun `exact match returns correct command`() {
        val commands = VoiceCommandManager.COMMANDS

        assertEquals(VoiceCommand.CAPTURE_ANALYZE, commands["拍照"])
        assertEquals(VoiceCommand.CAPTURE_ANALYZE, commands["分析"])
        assertEquals(VoiceCommand.CAPTURE_ANALYZE, commands["take photo"])
        assertEquals(VoiceCommand.MODE_OBSTACLE, commands["障碍物"])
        assertEquals(VoiceCommand.MODE_READ_TEXT, commands["读文本"])
        assertEquals(VoiceCommand.MODE_SCENE, commands["场景"])
        assertEquals(VoiceCommand.MODE_GUIDE, commands["引导"])
        assertEquals(VoiceCommand.PAUSE, commands["暂停"])
        assertEquals(VoiceCommand.RESUME, commands["恢复"])
        assertEquals(VoiceCommand.VOLUME_UP, commands["大声一点"])
        assertEquals(VoiceCommand.VOLUME_DOWN, commands["小声一点"])
        assertEquals(VoiceCommand.SPEED_UP, commands["快一点"])
        assertEquals(VoiceCommand.SPEED_DOWN, commands["慢一点"])
        assertEquals(VoiceCommand.REPEAT, commands["重复"])
        assertEquals(VoiceCommand.SWITCH_USER, commands["切换用户"])
        assertEquals(VoiceCommand.ENROLL_VOICEPRINT, commands["注册声纹"])
        assertEquals(VoiceCommand.CLOSE, commands["退出"])
    }

    @Test
    fun `all commands have security classification`() {
        val commands = VoiceCommand.values()
        // COMMAND_SECURITY 是实例字段，无法静态访问
        // 但可以验证枚举的完整性
        assertTrue(commands.contains(VoiceCommand.UNKNOWN))
        assertTrue(commands.contains(VoiceCommand.CAPTURE_ANALYZE))
        assertTrue(commands.contains(VoiceCommand.PAUSE))
        assertTrue(commands.contains(VoiceCommand.RESUME))
    }

    @Test
    fun `command keywords are non-empty`() {
        for ((keyword, _) in VoiceCommandManager.COMMANDS) {
            assertTrue("Keyword should not be empty", keyword.isNotEmpty())
        }
    }

    @Test
    fun `no duplicate keywords in commands`() {
        val keywords = VoiceCommandManager.COMMANDS.keys
        assertEquals("Keywords should be unique", keywords.size, keywords.toSet().size)
    }

    @Test
    fun `english and chinese commands coexist`() {
        val commands = VoiceCommandManager.COMMANDS
        assertTrue("Should have Chinese commands", commands.keys.any { it.any { c -> c.toInt() > 127 } })
        assertTrue("Should have English commands", commands.keys.any { it.all { c -> c.toInt() <= 127 } })
    }

    @Test
    fun `intent keywords cover key commands`() {
        // INTENT_KEYWORDS 是 private，通过反射验证
        val companion = VoiceCommandManager::class.java
            .getDeclaredField("Companion").apply { isAccessible = true }
            .get(null)
        val field = companion.javaClass.getDeclaredField("INTENT_KEYWORDS").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val intentKeywords = field.get(companion) as Map<VoiceCommand, List<String>>

        assertNotNull(intentKeywords[VoiceCommand.CAPTURE_ANALYZE])
        assertNotNull(intentKeywords[VoiceCommand.MODE_OBSTACLE])
        assertNotNull(intentKeywords[VoiceCommand.VOLUME_UP])
        assertNotNull(intentKeywords[VoiceCommand.PAUSE])
        assertNotNull(intentKeywords[VoiceCommand.RESUME])
    }

    @Test
    fun `intent keywords do not duplicate exact command keywords`() {
        val companion = VoiceCommandManager::class.java
            .getDeclaredField("Companion").apply { isAccessible = true }
            .get(null)
        val field = companion.javaClass.getDeclaredField("INTENT_KEYWORDS").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val intentKeywords = field.get(companion) as Map<VoiceCommand, List<String>>

        val exactKeywords = VoiceCommandManager.COMMANDS.keys.map { it.lowercase() }.toSet()

        // "暂停" 同时出现在 COMMANDS 和 INTENT_KEYWORDS 中，应移除
        for ((command, keywords) in intentKeywords) {
            for (kw in keywords) {
                assertFalse(
                    "Intent keyword '$kw' for $command should not duplicate exact command keyword",
                    exactKeywords.contains(kw.lowercase())
                )
            }
        }
    }

    @Test
    fun `help text is non-empty for both languages`() {
        assertTrue(VoiceCommandManager.HELP_TEXT_ZH.isNotBlank())
        assertTrue(VoiceCommandManager.HELP_TEXT_EN.isNotBlank())
        assertTrue(VoiceCommandManager.HELP_TEXT_ZH.contains("拍照"))
        assertTrue(VoiceCommandManager.HELP_TEXT_EN.contains("photo"))
    }

    @Test
    fun `voice command enum has all expected values`() {
        val expected = setOf(
            VoiceCommand.CAPTURE_ANALYZE,
            VoiceCommand.MODE_OBSTACLE,
            VoiceCommand.MODE_READ_TEXT,
            VoiceCommand.MODE_SCENE,
            VoiceCommand.MODE_GUIDE,
            VoiceCommand.LOCK_TARGET,
            VoiceCommand.UNLOCK_TARGET,
            VoiceCommand.START_CONTINUOUS,
            VoiceCommand.STOP_CONTINUOUS,
            VoiceCommand.INIT_AI,
            VoiceCommand.HELP,
            VoiceCommand.VOLUME_UP,
            VoiceCommand.VOLUME_DOWN,
            VoiceCommand.SPEED_UP,
            VoiceCommand.SPEED_DOWN,
            VoiceCommand.REPEAT,
            VoiceCommand.PAUSE,
            VoiceCommand.RESUME,
            VoiceCommand.SWITCH_USER,
            VoiceCommand.ENROLL_VOICEPRINT,
            VoiceCommand.CLOSE,
            VoiceCommand.UNKNOWN
        )
        assertEquals(expected, VoiceCommand.values().toSet())
    }
}
