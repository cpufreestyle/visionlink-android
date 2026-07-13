package com.nieao.blindaid

import android.content.Context

/** 障碍检测分析器:包装 YoloDetector,支持在 assets 里的多个 tflite 模型间切换 */
class ObstacleDetector(private val context: Context) : FrameAnalyzer {
    override val name = "障碍检测"
    override var enabled = true

    private val labels: List<String> =
        context.assets.open("labels_coco_zh.txt").bufferedReader()
            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
    private val models: List<String> =
        context.assets.list("")?.filter { it.endsWith(".tflite") }?.sorted() ?: emptyList()
    private var idx = 0
    private var yolo: YoloDetector? = null

    init { load() }

    private fun load() {
        yolo?.close()
        yolo = if (models.isEmpty()) {
            enabled = false; null
        } else try {
            YoloDetector(context, models[idx], labels, useNnapi = true)
        } catch (e: Throwable) {
            enabled = false; null
        }
    }

    fun detect(input: FrameInput): List<Detection> =
        yolo?.detect(input.bitmap) ?: emptyList()

    fun switchModel() {
        if (models.size < 2) return
        idx = (idx + 1) % models.size
        load()
    }

    val modelName: String get() = models.getOrElse(idx) { "无" }.removeSuffix(".tflite")
    val backend: String get() = yolo?.backend ?: "无"

    override fun close() { yolo?.close() }
}
