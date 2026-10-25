package com.otodegerle.plaka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Fotoğraf üzerinde plakalığın 4 köşesini parmakla ayarlamaya yarayan görünüm. */
class QuadEditorView(context: Context) : View(context) {

    private var bmp: Bitmap? = null
    private var cover: Bitmap? = null
    var quad: FloatArray? = null
        private set
    var onChange: (() -> Unit)? = null

    private val toView = Matrix()
    private val density = resources.displayMetrics.density

    private var dragIndex = -1 // 0..3 köşe, 4 = tamamı
    private var lastX = 0f
    private var lastY = 0f
    private var touching = false

    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.TURQ
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.TURQ
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Brand.AMBER
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    fun setData(b: Bitmap, c: Bitmap, q: FloatArray?) {
        bmp = b
        cover = c
        quad = q
        updateMatrix()
        invalidate()
    }

    fun setQuad(q: FloatArray?) {
        quad = q
        invalidate()
        onChange?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val pad = 12 * density
        toView.setRectToRect(
            RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()),
            RectF(pad, pad, width - pad, height - pad),
            Matrix.ScaleToFit.CENTER
        )
    }

    private fun viewScale(): Float {
        val v = FloatArray(9)
        toView.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun viewPoints(): FloatArray? {
        val q = quad ?: return null
        val out = q.copyOf()
        toView.mapPoints(out)
        return out
    }

    private fun drawScene(canvas: Canvas) {
        val b = bmp ?: return
        canvas.save()
        canvas.concat(toView)
        canvas.drawBitmap(b, 0f, 0f, imgPaint)
        val q = quad
        val c = cover
        if (q != null && c != null) PlateRenderer.drawCover(canvas, c, q)
        canvas.restore()
    }

    private fun drawOutline(canvas: Canvas, p: FloatArray) {
        val path = Path()
        path.moveTo(p[0], p[1])
        path.lineTo(p[2], p[3])
        path.lineTo(p[4], p[5])
        path.lineTo(p[6], p[7])
        path.close()
        canvas.drawPath(path, linePaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawScene(canvas)
        val p = viewPoints() ?: return
        drawOutline(canvas, p)
        val hr = 11 * density
        for (i in 0 until 4) {
            canvas.drawCircle(p[2 * i], p[2 * i + 1], hr, handleFill)
            canvas.drawCircle(p[2 * i], p[2 * i + 1], hr, handleStroke)
        }

        // Köşe sürüklenirken büyüteç
        if (touching && dragIndex in 0..3) {
            val hx = p[2 * dragIndex]
            val hy = p[2 * dragIndex + 1]
            val r = 64 * density
            val margin = 16 * density
            val cx = if (hx < width / 2f) width - r - margin else r + margin
            val cy = r + margin
            val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawColor(Color.BLACK)
            canvas.translate(cx, cy)
            canvas.scale(3f, 3f)
            canvas.translate(-hx, -hy)
            drawScene(canvas)
            drawOutline(canvas, p)
            canvas.restore()
            canvas.drawCircle(cx, cy, r, loupeBorder)
            val k = 10 * density
            canvas.drawLine(cx - k, cy, cx + k, cy, crossPaint)
            canvas.drawLine(cx, cy - k, cx, cy + k, crossPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val q = quad ?: return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = viewPoints() ?: return false
                var best = -1
                var bestD = 40 * density
                for (i in 0 until 4) {
                    val d = hypot(p[2 * i] - x, p[2 * i + 1] - y)
                    if (d < bestD) { bestD = d; best = i }
                }
                dragIndex = when {
                    best >= 0 -> best
                    inside(p, x, y) -> 4
                    else -> -1
                }
                if (dragIndex == -1) return false
                lastX = x
                lastY = y
                touching = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex == -1) return false
                val s = viewScale()
                val dx = (x - lastX) / s
                val dy = (y - lastY) / s
                lastX = x
                lastY = y
                if (dragIndex == 4) {
                    for (i in 0 until 4) {
                        q[2 * i] += dx
                        q[2 * i + 1] += dy
                    }
                } else {
                    q[2 * dragIndex] += dx
                    q[2 * dragIndex + 1] += dy
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                dragIndex = -1
                invalidate()
                onChange?.invoke()
                return true
            }
        }
        return true
    }

    private fun inside(p: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        var j = 3
        for (i in 0 until 4) {
            val xi = p[2 * i]; val yi = p[2 * i + 1]
            val xj = p[2 * j]; val yj = p[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
}
