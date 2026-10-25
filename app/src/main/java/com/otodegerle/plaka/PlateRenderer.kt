package com.otodegerle.plaka

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.roundToInt

object PlateRenderer {

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Plakalık görselini 4 köşeye perspektifli oturtan matris. */
    fun coverMatrix(cover: Bitmap, quad: FloatArray): Matrix? {
        val w = cover.width.toFloat()
        val h = cover.height.toFloat()
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val m = Matrix()
        return if (m.setPolyToPoly(src, 0, quad, 0, 4)) m else null
    }

    fun drawCover(canvas: Canvas, cover: Bitmap, quad: FloatArray) {
        val m = coverMatrix(cover, quad) ?: return
        canvas.drawBitmap(cover, m, bmpPaint)
    }

    /** Fotoğraf + plakalık birleşimi. maxSide = 0 → tam çözünürlük. */
    fun compose(base: Bitmap, cover: Bitmap, quad: FloatArray?, maxSide: Int, blurs: List<FloatArray> = emptyList()): Bitmap {
        val longest = max(base.width, base.height)
        val scale = if (maxSide in 1 until longest) maxSide.toFloat() / longest else 1f
        val w = max(1, (base.width * scale).roundToInt())
        val h = max(1, (base.height * scale).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(base, null, Rect(0, 0, w, h), bmpPaint)
        for (b in blurs) blurRegion(out, c, FloatArray(8) { b[it] * scale })
        if (quad != null) {
            val q = FloatArray(8) { quad[it] * scale }
            drawCover(c, cover, q)
        }
        return out
    }

    /** Dörtgen bölgeyi okunamayacak şekilde bulanıklaştırır (arka plandaki plakalar). */
    private fun blurRegion(img: Bitmap, c: Canvas, q: FloatArray) {
        val xs = floatArrayOf(q[0], q[2], q[4], q[6]); val ys = floatArrayOf(q[1], q[3], q[5], q[7])
        val pad = (ys.max() - ys.min()) * 0.15f
        val l = max(0, (xs.min() - pad).toInt()); val t = max(0, (ys.min() - pad).toInt())
        val r = minOf(img.width, (xs.max() + pad).toInt() + 1); val b = minOf(img.height, (ys.max() + pad).toInt() + 1)
        if (r - l < 2 || b - t < 2) return
        val sub = Bitmap.createBitmap(img, l, t, r - l, b - t)
        val small = Bitmap.createScaledBitmap(sub, max(1, (r - l) / 16), max(1, (b - t) / 16), true)
        val path = Path().apply {
            val cx = xs.average().toFloat(); val cy = ys.average().toFloat()
            // dörtgeni biraz büyüt ki kenarlar da gizlensin
            moveTo(cx + (q[0] - cx) * 1.12f, cy + (q[1] - cy) * 1.25f)
            lineTo(cx + (q[2] - cx) * 1.12f, cy + (q[3] - cy) * 1.25f)
            lineTo(cx + (q[4] - cx) * 1.12f, cy + (q[5] - cy) * 1.25f)
            lineTo(cx + (q[6] - cx) * 1.12f, cy + (q[7] - cy) * 1.25f)
            close()
        }
        c.save()
        c.clipPath(path)
        c.drawBitmap(small, null, Rect(l, t, r, b), bmpPaint)
        c.restore()
        if (small !== sub) small.recycle()
        sub.recycle()
    }

    /** Plaka bulunamazsa elle eklemek için başlangıç konumu (TR plaka oranı 52x11). */
    fun defaultQuad(imgW: Int, imgH: Int): FloatArray {
        val pw = imgW * 0.32f
        val ph = pw * 110f / 520f
        val cx = imgW / 2f
        val cy = imgH * 0.62f
        return floatArrayOf(
            cx - pw / 2, cy - ph / 2,
            cx + pw / 2, cy - ph / 2,
            cx + pw / 2, cy + ph / 2,
            cx - pw / 2, cy + ph / 2
        )
    }

    /** Varsayılan otodeğerle plakalığı (kendi görselin yüklenene kadar). */
    fun defaultCover(): Bitmap {
        val w = 1040
        val h = 220
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = 24f
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Brand.DARK
        c.drawRoundRect(full, r, r, p)

        // Sol turkuaz şerit + » işareti
        val band = 160f
        c.save()
        c.clipRect(0f, 0f, band, h.toFloat())
        p.color = Brand.TURQ
        c.drawRoundRect(full, r, r, p)
        c.restore()

        val chev = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Brand.DARK
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        path.moveTo(42f, 68f); path.lineTo(74f, 110f); path.lineTo(42f, 152f)
        path.moveTo(88f, 68f); path.lineTo(120f, 110f); path.lineTo(88f, 152f)
        c.drawPath(path, chev)

        // "oto" beyaz + "değerle" turkuaz
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 150f
        }
        val a = "oto"
        val b = "değerle"
        val avail = w - band - 70f
        val total = tp.measureText(a) + tp.measureText(b)
        if (total > avail) tp.textSize = tp.textSize * avail / total
        val wa = tp.measureText(a)
        val wb = tp.measureText(b)
        val x0 = band + (w - band - (wa + wb)) / 2f
        val fm = tp.fontMetrics
        val y = h / 2f - (fm.ascent + fm.descent) / 2f
        tp.color = Color.WHITE
        c.drawText(a, x0, y, tp)
        tp.color = Brand.TURQ
        c.drawText(b, x0 + wa, y, tp)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Brand.TURQ
        }
        c.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), r, r, border)
        return bmp
    }
}
