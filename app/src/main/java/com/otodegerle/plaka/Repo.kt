package com.otodegerle.plaka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object Brand {
    // Arayüz (açık tema, otodeğerle renkleri)
    const val BG = 0xFFF4F7F5.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val LINE = 0xFFE2E8E5.toInt()
    const val TEXT = 0xFF0C0E10.toInt()
    const val SUB = 0xFF6B7380.toInt()
    const val MINT = 0xFF74F8C6.toInt()
    const val TEAL = 0xFF0E9C86.toInt()
    const val WARN = 0xFFC77700.toInt()
    // Çizim renkleri
    const val DARK = 0xFF0B0D12.toInt()
    const val TURQ = 0xFF14E0C8.toInt()
    const val PURPLE = 0xFF8B5CF6.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val GRAY = 0xFF8A93A6.toInt()
}

class PhotoRec(val file: String) {
    /** Plakalığın 4 köşesi (görsel koordinatı): sol-üst, sağ-üst, sağ-alt, sol-alt. null = kapatma yok. */
    var quad: FloatArray? = null
    var status: Int = STATUS_SEARCHING
    var saved: Boolean = false
    /** Bulanıklaştırılacak arka plan plakaları */
    var blurs: List<FloatArray> = emptyList()
    var bitmap: Bitmap? = null

    companion object {
        const val STATUS_SEARCHING = 0
        const val STATUS_FOUND = 1
        const val STATUS_NOT_FOUND = 2
        const val STATUS_MANUAL = 3
        const val STATUS_NONE = 4
    }
}

class Vehicle(val id: String, var title: String, val employee: String, val createdAt: Long) {
    val photos = mutableListOf<PhotoRec>()
    /** Bu araçta okunan plaka (sadece uygulama içinde, diğer fotoğraflarda bulmaya yardım eder). */
    var plate: String? = null
    var nextPhoto = 1

    fun needsCheck(): Int = photos.count { it.status == PhotoRec.STATUS_NOT_FOUND }
}

/** Tüm araçlar telefonda saklanır: files/vehicles/<id>/meta.json + fotoğraflar. */
object Repo {
    private const val COVER_FILE = "plate.png"
    private var root: File? = null
    private var filesDir: File? = null
    private var res: android.content.res.Resources? = null
    val vehicles = mutableListOf<Vehicle>()
    private var cover: Bitmap? = null

    fun init(ctx: Context) {
        if (root != null) return
        val fd = ctx.applicationContext.filesDir
        filesDir = fd
        res = ctx.applicationContext.resources
        val r = File(fd, "vehicles")
        r.mkdirs()
        root = r
        r.listFiles()?.forEach { d ->
            if (d.isDirectory) readVehicle(d)?.let { vehicles.add(it) }
        }
        vehicles.sortByDescending { it.createdAt }
    }

    fun find(id: String?): Vehicle? = vehicles.firstOrNull { it.id == id }

    fun dir(v: Vehicle): File = File(root!!, v.id)

    fun create(title: String, employee: String): Vehicle {
        val now = System.currentTimeMillis()
        val v = Vehicle("v$now", title, employee, now)
        dir(v).mkdirs()
        vehicles.add(0, v)
        save(v)
        return v
    }

    fun delete(v: Vehicle) {
        dir(v).deleteRecursively()
        vehicles.remove(v)
    }

    /** Arka planda çağrılır: fotoğrafı aracın klasörüne yazar. */
    fun storePhoto(v: Vehicle, bmp: Bitmap): PhotoRec {
        val name = synchronized(v) { "photo_${v.nextPhoto++}.jpg" }
        FileOutputStream(File(dir(v), name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        val p = PhotoRec(name)
        p.bitmap = bmp
        return p
    }

    fun removePhoto(v: Vehicle, p: PhotoRec) {
        File(dir(v), p.file).delete()
        v.photos.remove(p)
        save(v)
    }

    fun bitmap(v: Vehicle, p: PhotoRec): Bitmap? {
        p.bitmap?.let { return it }
        val b = BitmapFactory.decodeFile(File(dir(v), p.file).path)
        p.bitmap = b
        return b
    }

    fun release(v: Vehicle) {
        v.photos.forEach { it.bitmap = null }
    }

    @Synchronized
    fun save(v: Vehicle) {
        if (!vehicles.contains(v)) return // silinmiş araç
        try {
            val arr = JSONArray()
            for (p in v.photos.toList()) {
                val o = JSONObject()
                o.put("file", p.file)
                o.put("status", p.status)
                o.put("saved", p.saved)
                if (p.blurs.isNotEmpty()) {
                    val ba = JSONArray()
                    for (b in p.blurs) { val x = JSONArray(); for (f in b) x.put(f.toDouble()); ba.put(x) }
                    o.put("blurs", ba)
                }
                val q = p.quad
                if (q != null) {
                    val qa = JSONArray()
                    for (f in q) qa.put(f.toDouble())
                    o.put("quad", qa)
                }
                arr.put(o)
            }
            val j = JSONObject()
            j.put("title", v.title)
            j.put("employee", v.employee)
            j.put("createdAt", v.createdAt)
            j.put("nextPhoto", v.nextPhoto)
            v.plate?.let { j.put("plate", it) }
            j.put("photos", arr)
            val d = dir(v)
            d.mkdirs()
            File(d, "meta.json").writeText(j.toString())
        } catch (e: Exception) {
            // yok say
        }
    }

    private fun readVehicle(d: File): Vehicle? {
        return try {
            val j = JSONObject(File(d, "meta.json").readText())
            val v = Vehicle(d.name, j.optString("title", "Araç"), j.optString("employee", ""), j.optLong("createdAt", d.lastModified()))
            v.nextPhoto = j.optInt("nextPhoto", 1)
            v.plate = j.optString("plate", "").ifEmpty { null }
            val arr = j.optJSONArray("photos") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = PhotoRec(o.getString("file"))
                if (!File(d, p.file).exists()) continue
                p.status = o.optInt("status", PhotoRec.STATUS_NOT_FOUND)
                p.saved = o.optBoolean("saved", false)
                val ba = o.optJSONArray("blurs")
                if (ba != null) {
                    val l = ArrayList<FloatArray>()
                    for (k in 0 until ba.length()) {
                        val x = ba.getJSONArray(k)
                        if (x.length() == 8) l.add(FloatArray(8) { x.getDouble(it).toFloat() })
                    }
                    p.blurs = l
                }
                if (p.status == PhotoRec.STATUS_SEARCHING) p.status = PhotoRec.STATUS_NOT_FOUND
                val qa = o.optJSONArray("quad")
                if (qa != null && qa.length() == 8) p.quad = FloatArray(8) { qa.getDouble(it).toFloat() }
                v.photos.add(p)
            }
            v
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Önizleme (liste kartı) ----------

    fun thumbFile(v: Vehicle): File = File(dir(v), "thumb.jpg")

    /** Arka planda çağrılır. */
    fun writeThumb(v: Vehicle) {
        val p = v.photos.firstOrNull()
        val f = thumbFile(v)
        if (p == null) { f.delete(); return }
        val b = bitmap(v, p) ?: return
        val t = PlateRenderer.compose(b, cover(), p.quad, 420, p.blurs)
        try {
            FileOutputStream(f).use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
            // yok say
        }
        t.recycle()
    }

    // ---------- Plakalık ----------

    fun cover(): Bitmap {
        cover?.let { return it }
        val f = filesDir?.let { File(it, COVER_FILE) }
        val loaded = if (f != null && f.exists()) BitmapFactory.decodeFile(f.path) else null
        val c = loaded ?: defaultCover()
        cover = c
        return c
    }

    fun setCover(bmp: Bitmap) {
        filesDir?.let { fd ->
            try {
                FileOutputStream(File(fd, COVER_FILE)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                // bu oturumda yine de kullan
            }
        }
        cover = bmp
    }

    fun resetCover() {
        filesDir?.let { File(it, COVER_FILE).delete() }
        cover = defaultCover()
    }

    /** Uygulamaya gömülü otodeğerle plakalığı. */
    fun defaultCover(): Bitmap {
        val r = res
        if (r != null) {
            val o = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(r, R.drawable.plate_default, o)?.let { return it }
        }
        return PlateRenderer.defaultCover()
    }
}
