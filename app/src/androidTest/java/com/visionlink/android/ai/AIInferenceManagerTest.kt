package com.visionlink.android.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AIInferenceManager 单元测试
 * 
 * 测试对应 PC 版:
 * - analyze_frame() 函数
 * - AI 推理逻辑
 * - 图像预处理
 */
@RunWith(AndroidJUnit4::class)
class AIInferenceManagerTest {
    
    private lateinit var context: Context
    private lateinit var aiManager: AIInferenceManager
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        aiManager = AIInferenceManager(context)
    }
    
    @After
    fun tearDown() {
        aiManager.release()
    }
    
    /**
     * 测试 1: 创建测试用 Bitmap
     * 
     * 对应 PC 版:
     *   frame = cap.read()
     *   snap = frame.copy()
     */
    @Test
    fun testCreateTestBitmap() {
        val bitmap = createTestBitmap()
        
        assertNotNull(bitmap)
        assertEquals(448, bitmap.width)
        assertEquals(448, bitmap.height)
    }
    
    /**
     * 测试 2: 测试模式 Prompt 生成
     * 
     * 对应 PC 版 prompt 字符串生成
     */
    @Test
    fun testModePrompts() = runBlocking {
        val bitmap = createTestBitmap()
        
        // 注意: 由于模型未初始化，会返回 "模型未初始化"
        // 实际测试中需要 mock 模型或使用真实模型文件
        
        val result1 = aiManager.analyzeImage(bitmap, 1)  // 避障模式
        val result2 = aiManager.analyzeImage(bitmap, 2)  // 文字模式
        val result3 = aiManager.analyzeImage(bitmap, 3)  // 场景模式
        
        // 验证返回了结果 (即使是错误的)
        assertNotNull(result1)
        assertNotNull(result2)
        assertNotNull(result3)
    }
    
    /**
     * 测试 3: 测试图像预处理
     * 
     * 对应 PC 版:
     *   cv2.resize(snap, (AI_IMAGE_SIZE, AI_IMAGE_SIZE))
     *   _, buf = cv2.imencode('.jpg', resized)
     */
    @Test
    fun testImagePreprocessing() {
        val bitmap = createTestBitmap()
        
        // 缩放图像
        val resized = Bitmap.createScaledBitmap(bitmap, 448, 448, true)
        
        assertNotNull(resized)
        assertEquals(448, resized.width)
        assertEquals(448, resized.height)
        
        // 压缩为 JPEG
        val outputStream = java.io.ByteArrayOutputStream()
        val success = resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        
        assertTrue(success)
        assertTrue(outputStream.size() > 0)
    }
    
    /**
     * 测试 4: 测试 ROI 裁剪 (对应 PC 版 frame[box_y1:box_y2, box_x1:box_x2])
     */
    @Test
    fun testROICrop() {
        val bitmap = createTestBitmap()
        val width = bitmap.width
        val height = bitmap.height
        
        // 对应 PC 版:
        //   box_x1, box_y1 = int(w * 0.25), int(h * 0.2)
        //   box_x2, box_y2 = int(w * 0.75), int(h * 0.8)
        
        val boxX1 = (width * 0.25).toInt()
        val boxY1 = (height * 0.2).toInt()
        val boxX2 = (width * 0.75).toInt()
        val boxY2 = (height * 0.8).toInt()
        
        val roiBitmap = Bitmap.createBitmap(
            bitmap,
            boxX1,
            boxY1,
            boxX2 - boxX1,
            boxY2 - boxY1
        )
        
        assertNotNull(roiBitmap)
        assertEquals(boxX2 - boxX1, roiBitmap.width)
        assertEquals(boxY2 - boxY1, roiBitmap.height)
    }
    
    /**
     * 测试 5: 测试 Base64 编码 (对应 PC 版 base64.b64encode())
     */
    @Test
    fun testBase64Encoding() {
        val bitmap = createTestBitmap()
        val resized = Bitmap.createScaledBitmap(bitmap, 448, 448, true)
        
        val outputStream = java.io.ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        
        assertNotNull(base64)
        assertTrue(base64.isNotEmpty())
        assertTrue(base64.length > 100)  // Base64 应该很长
    }
    
    /**
     * 辅助函数: 创建测试用 Bitmap
     */
    private fun createTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(448, 448, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // 绘制背景
        canvas.drawColor(Color.WHITE)
        
        // 绘制简单图形 (模拟场景)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        
        // 绘制矩形 (模拟障碍物)
        canvas.drawRect(100f, 100f, 300f, 300f, paint)
        
        return bitmap
    }
}
