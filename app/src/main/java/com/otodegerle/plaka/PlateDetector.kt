package com.otodegerle.plaka

import android.graphics.Bitmap
import android.graphics.Point
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Türk plakasını (örn. 34 ABC 123) cihaz içi yazı tanıma ile bulur
 * ve plakanın tamamını kapsayacak 4 köşeyi döndürür.
 */
/** main: otodeğerle plakalığıyla kapanacak ana plaka; others: bulanıklaştırılacak arka plan plakaları. */
class DetectResult(val main: FloatArray?, val plate: String?, val others: List<FloatArray>)

object PlateDetector {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // İl kodu (2 hane) + 1-3 harf + 2-4 hane. OCR'ın karıştırdığı O/0, I/1 vb. toleranslı.
    private val loose = Regex("^([0-9OIL]{2})([A-Z]{1,3})([0-9OISBZGL]{2,4})$")

    /** Önce hızlı (1800px) dener; bulamazsa tam çözünürlükte bir kez daha dener. */
    /**
     * [hint]: aynı aracın daha önce okunmuş plakası (örn. 34HGU670). Verilirse, zor açılarda
     * yarım okunan yazı da (en fazla 2 karakter hatayla) plaka kabul edilir.
     * Callback: (köşeler, kesin okunan plaka metni ya da null)
     */
    fun detect(bmp: Bitmap, hint: String?, callback: (DetectResult) -> Unit) {
        detectAt(bmp, 1800, hint) { r ->
            if (r.main != null || max(bmp.width, bmp.height) <= 1800) callback(r)
            else detectAt(bmp, 4000, hint, callback)
        }
    }

    private fun detectAt(bmp: Bitmap, limit: Int, hint: String?, callback: (DetectResult) -> Unit) {
        val longest = max(bmp.width, bmp.height)
        val scale = if (longest > limit) limit.toFloat() / longest else 1f
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                max(1, (bmp.width * scale).roundToInt()),
                max(1, (bmp.height * scale).roundToInt()),
                true
            )
        } else bmp

        recognizer.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { text ->
                val r = try { pick(text, hint) } catch (e: Exception) { null }
                if (r == null) {
                    callback(DetectResult(null, null, emptyList()))
                } else {
                    fun up(q: FloatArray) = FloatArray(8) { q[it] / scale }
                    callback(DetectResult(r.first?.let { up(it.quad) }, r.first?.plate, r.second.map { up(it.quad) }))
                }
            }
            .addOnFailureListener { callback(DetectResult(null, null, emptyList())) }
    }

    private class Cand(val quad: FloatArray, val score: Float, val plate: String?)

    private fun pick(text: Text, hint: String?): Pair<Cand?, List<Cand>> {
        val cands = ArrayList<Cand>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                consider(line.text, line.cornerPoints, null, hint, cands)
                val els = line.elements
                for (i in els.indices) {
                    val sb = StringBuilder()
                    for (j in i until min(els.size, i + 4)) {
                        sb.append(els[j].text)
                        consider(sb.toString(), els[i].cornerPoints, els[j].cornerPoints, hint, cands)
                    }
                }
            }
        }
        // En büyük eşleşme = asıl aracın plakası
        val main = cands.maxByOrNull { it.score } ?: return Pair(null, emptyList())
        // Ana plakayla çakışmayan diğer plakalar (arka plandaki araçlar) → bulanık
        val others = ArrayList<Cand>()
        for (c in cands.sortedByDescending { it.score }) {
            if (c === main) continue
            if (overlaps(c.quad, main.quad)) continue
            if (others.any { overlaps(it.quad, c.quad) }) continue
            others.add(c)
        }
        return Pair(main, others)
    }

    private fun overlaps(a: FloatArray, b: FloatArray): Boolean {
        val ax0 = minOf(a[0], a[6]); val ax1 = maxOf(a[2], a[4]); val ay0 = minOf(a[1], a[3]); val ay1 = maxOf(a[5], a[7])
        val bx0 = minOf(b[0], b[6]); val bx1 = maxOf(b[2], b[4]); val by0 = minOf(b[1], b[3]); val by1 = maxOf(b[5], b[7])
        return ax0 < bx1 && bx0 < ax1 && ay0 < by1 && by0 < ay1
    }

    private fun consider(raw: String, first: Array<Point>?, last: Array<Point>?, hint: String?, out: MutableList<Cand>) {
        if (first == null || first.size != 4) return
        if (last != null && last.size != 4) return

        var n = raw.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }
        var hadTR = false
        if (n.startsWith("TR") && n.length >= 7) {
            n = n.substring(2)
            hadTR = true
        }
        if (n.length < 4 || n.length > 10) return
        val exact = n.length >= 5 && isPlate(n)
        val fuzzy = !exact && hint != null && n.length >= 4 && levenshtein(n, hint) <= 2
        if (!exact && !fuzzy) return

        val pts: Array<Point> = if (last == null) first else arrayOf(first[0], last[1], last[2], first[3])
        val quad = expand(pts, hadTR)
        // Kesin eşleşme her zaman tahmini eşleşmeden önce gelir
        out.add(Cand(quad, area(quad) * (if (exact) 1f else 0.5f), if (exact) n else null))
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun isPlate(n: String): Boolean {
        val m = loose.matchEntire(n) ?: return false
        val province = toDigits(m.groupValues[1])
        val tail = toDigits(m.groupValues[3])
        if (!province.all { it.isDigit() } || !tail.all { it.isDigit() }) return false
        return province.toInt() in 1..81
    }

    private fun toDigits(s: String): String = s.map {
        when (it) {
            'O' -> '0'
            'I', 'L' -> '1'
            'S' -> '5'
            'B' -> '8'
            'Z' -> '2'
            'G' -> '6'
            else -> it
        }
    }.joinToString("")

    /**
     * Yazının çerçevesini plakanın tamamına genişletir
     * (soldaki mavi TR şeridi ve üst/alt boşluklar dahil).
     */
    private fun expand(p: Array<Point>, hadTR: Boolean): FloatArray {
        val x0 = p[0].x.toFloat(); val y0 = p[0].y.toFloat()
        val x1 = p[1].x.toFloat(); val y1 = p[1].y.toFloat()
        val x2 = p[2].x.toFloat(); val y2 = p[2].y.toFloat()
        val x3 = p[3].x.toFloat(); val y3 = p[3].y.toFloat()
        val ux = x1 - x0; val uy = y1 - y0 // genişlik yönü
        val vx = x3 - x0; val vy = y3 - y0 // yükseklik yönü
        val aL = if (hadTR) 0.07f else 0.19f
        val aR = 0.09f
        val aT = 0.38f
        val aB = 0.64f // plaka altındaki bayi çerçevesi de kapansın
        return floatArrayOf(
            x0 - ux * aL - vx * aT, y0 - uy * aL - vy * aT,
            x1 + ux * aR - vx * aT, y1 + uy * aR - vy * aT,
            x2 + ux * aR + vx * aB, y2 + uy * aR + vy * aB,
            x3 - ux * aL + vx * aB, y3 - uy * aL + vy * aB
        )
    }

    private fun area(q: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(s) / 2f
    }
}
