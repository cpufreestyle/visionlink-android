package com.nieao.blindaid

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** 一个检测结果,坐标为归一化 0-1(相对整幅画面) */
data class Detection(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val score: Float, val classId: Int, val label: String
) {
    val area: Float get() = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
}

/**
 * YOLOv8/v11/v5u 系列 TFLite 检测器。
 * 兼容两种输出布局([1,84,N] 与 [1,N,84]),自适应归一化/像素坐标。
 * 优先用 NNAPI delegate 走 NPU;失败自动退回 CPU。
 */
class YoloDetector(
    context: Context,
    modelAsset: String,
    private val labels: List<String>,
    useNnapi: Boolean = true,
    private val confThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f
) {
    private val interpreter: Interpreter
    private var nnApiDelegate: NnApiDelegate? = null

    private val inputSize: Int
    private val numClasses: Int
    private val numChannels: Int      // 4 + numClasses
    private val numAnchors: Int
    private val transposed: Boolean   // true 表示输出为 [1, anchors, channels]
    private val imageProcessor: ImageProcessor

    val backend: String get() = if (nnApiDelegate != null) "NNAPI" else "CPU"

    init {
        val model = loadModelFile(context, modelAsset)

        var delegate: NnApiDelegate? = null
        val opts = Interpreter.Options().apply {
            numThreads = 4
            if (useNnapi) {
                try {
                    delegate = NnApiDelegate()
                    addDelegate(delegate)
                } catch (e: Throwable) {
                    delegate = null
                }
            }
        }
        interpreter = try {
            Interpreter(model, opts).also { nnApiDelegate = delegate }
        } catch (e: Throwable) {
            // NNAPI 不可用 → 退回纯 CPU,保证仍能跑
            delegate?.close()
            nnApiDelegate = null
            Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
        }

        val inShape = interpreter.getInputTensor(0).shape()   // [1, H, W, 3]
        inputSize = inShape[1]

        val outShape = interpreter.getOutputTensor(0).shape() // [1, 84, N] 或 [1, N, 84]
        val d1 = outShape[1]
        val d2 = outShape[2]
        if (d1 <= d2) {          // 84 <= 2100 → 通道在前
            numChannels = d1; numAnchors = d2; transposed = false
        } else {                 // [1, N, 84] → 通道在后
            numChannels = d2; numAnchors = d1; transposed = true
        }
        numClasses = numChannels - 4

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))   // 归一化到 0-1
            .build()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        var image = TensorImage(DataType.FLOAT32)
        image.load(bitmap)
        image = imageProcessor.process(image)

        // 输出数组必须与 tensor shape 严格一致
        val output: Array<*> = if (!transposed)
            Array(1) { Array(numChannels) { FloatArray(numAnchors) } }
        else
            Array(1) { Array(numAnchors) { FloatArray(numChannels) } }

        interpreter.run(image.buffer, output)

        // 统一取值接口:get(channel, anchor)
        @Suppress("UNCHECKED_CAST")
        val get: (Int, Int) -> Float = if (!transposed) {
            val o = (output as Array<Array<FloatArray>>)[0]
            { c, a -> o[c][a] }
        } else {
            val o = (output as Array<Array<FloatArray>>)[0]
            { c, a -> o[a][c] }
        }

        val dets = ArrayList<Detection>()
        for (a in 0 until numAnchors) {
            // 找最高分类别
            var bestId = -1
            var bestScore = confThreshold
            for (c in 0 until numClasses) {
                val s = get(4 + c, a)
                if (s > bestScore) { bestScore = s; bestId = c }
            }
            if (bestId < 0) continue

            var cx = get(0, a)
            var cy = get(1, a)
            var w = get(2, a)
            var h = get(3, a)
            // 自适应:若为像素坐标(值远大于1),归一化到 0-1
            if (cx > 2f || cy > 2f || w > 2f || h > 2f) {
                cx /= inputSize; cy /= inputSize; w /= inputSize; h /= inputSize
            }
            val x1 = (cx - w / 2f).coerceIn(0f, 1f)
            val y1 = (cy - h / 2f).coerceIn(0f, 1f)
            val x2 = (cx + w / 2f).coerceIn(0f, 1f)
            val y2 = (cy + h / 2f).coerceIn(0f, 1f)
            val label = labels.getOrElse(bestId) { "类别$bestId" }
            dets.add(Detection(x1, y1, x2, y2, bestScore, bestId, label))
        }
        return nms(dets)
    }

    /** 标准非极大值抑制 */
    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val d = it.next()
                if (d.classId == best.classId && iou(best, d) > iouThreshold) it.remove()
            }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = maxOf(a.x1, b.x1); val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2); val iy2 = minOf(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f); val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        try { interpreter.close() } catch (_: Throwable) {}
        try { nnApiDelegate?.close() } catch (_: Throwable) {}
    }

    private fun loadModelFile(context: Context, asset: String): MappedByteBuffer {
        val fd = context.assets.openFd(asset)
        FileInputStream(fd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    }
}
