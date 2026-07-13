package com.nieao.blindaid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 在相机预览之上绘制:按距离分级着色的检测框 + 手指指尖与指向 + 指向命中高亮 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var obstacles: List<RankedObstacle> = emptyList()
    private var pointer: PointerInfo? = null
    private var pointed: RankedObstacle? = null

    // 距离配色:近=暗红,中=金,远=灰
    private val colorNear = Color.parseColor("#C0563A")
    private val colorMid = Color.parseColor("#D4AF37")
    private val colorFar = Color.parseColor("#6B6555")
    private val colorPointed = Color.parseColor("#F4D98B")

    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val pointedPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true; color = colorPointed
    }
    private val textPaint = Paint().apply { color = Color.BLACK; textSize = 32f; isAntiAlias = true }
    private val labelBgPaint = Paint()
    private val pointerPaint = Paint().apply {
        color = colorPointed; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val rayPaint = Paint().apply {
        color = colorPointed; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }

    fun setResults(obs: List<RankedObstacle>, ptr: PointerInfo?, hit: RankedObstacle?) {
        obstacles = obs; pointer = ptr; pointed = hit
        postInvalidate()
    }

    private fun colorOf(d: DistanceLevel) = when (d) {
        DistanceLevel.NEAR -> colorNear
        DistanceLevel.MID -> colorMid
        DistanceLevel.FAR -> colorFar
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (o in obstacles) {
            val d = o.det
            val l = d.x1 * w; val t = d.y1 * h; val r = d.x2 * w; val b = d.y2 * h
            val isHit = pointed === o
            if (isHit) {
                canvas.drawRect(l, t, r, b, pointedPaint)
            } else {
                boxPaint.color = colorOf(o.distance)
                canvas.drawRect(l, t, r, b, boxPaint)
            }
            val txt = "${d.label}·${o.distance.label}"
            val tw = textPaint.measureText(txt)
            val top = (t - 42f).coerceAtLeast(0f)
            labelBgPaint.color = if (isHit) colorPointed else colorOf(o.distance)
            canvas.drawRect(l, top, l + tw + 16f, top + 40f, labelBgPaint)
            canvas.drawText(txt, l + 8f, top + 30f, textPaint)
        }

        // 手指指尖 + 指向射线
        pointer?.let { p ->
            val px = p.tipX * w; val py = p.tipY * h
            canvas.drawCircle(px, py, 14f, pointerPaint)
            if (p.isPointing) {
                val len = 0.4f
                canvas.drawLine(px, py, px + p.dirX * w * len, py + p.dirY * h * len, rayPaint)
            }
        }
    }
}
