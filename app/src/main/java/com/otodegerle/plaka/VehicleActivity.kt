package com.otodegerle.plaka

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Bir aracın bölümü: fotoğrafları, plaka kapatma, kaydet/paylaş. */
class VehicleActivity : Activity() {

    companion object {
        const val EXTRA_ID = "vid"
        const val EXTRA_PICK = "pick"
        const val EXTRA_URIS = "uris"
        private const val REQ_PHOTOS = 1
        private const val REQ_PERM = 3
        private const val MAX_SIDE = 2400
    }

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var v: Vehicle
    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        val found = Repo.find(intent.getStringExtra(EXTRA_ID))
        if (found == null) { finish(); return }
        v = found
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
        }

        // Kayıtlı fotoğrafları arka planda yükle
        exec.execute {
            for (p in v.photos.toList()) Repo.bitmap(v, p)
            runOnUiThread { refresh() }
        }

        @Suppress("DEPRECATION")
        val shared = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        if (savedInstanceState == null) {
            if (!shared.isNullOrEmpty()) addPhotos(shared)
            else if (intent.getBooleanExtra(EXTRA_PICK, false)) pickPhotos()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) {
            refresh()
            persist()
            maybeAutoSave()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::v.isInitialized) Repo.release(v)
        exec.shutdown()
    }

    private fun persist() {
        Repo.save(v)
        exec.execute { Repo.writeThumb(v) }
    }

    // ---------- Fotoğraf ekleme ----------

    @Suppress("DEPRECATION")
    private fun pickPhotos() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(i, "Araç fotoğraflarını seç"), REQ_PHOTOS)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTOS || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) addPhotos(uris)
    }

    private fun addPhotos(uris: List<Uri>) {
        toast("${uris.size} fotoğraf yükleniyor…")
        for (uri in uris) {
            exec.execute {
                val rec: PhotoRec? = try {
                    val bmp = ImageUtil.load(this, uri, MAX_SIDE)
                    if (bmp != null) Repo.storePhoto(v, bmp) else null
                } catch (e: Throwable) { null }
                runOnUiThread {
                    if (rec == null) {
                        toast("Bir fotoğraf açılamadı")
                    } else {
                        v.photos.add(rec)
                        refresh()
                        persist()
                        detect(rec)
                    }
                }
            }
        }
    }

    private val retried = HashSet<String>()

    private fun detect(p: PhotoRec) {
        val b = p.bitmap ?: return
        p.status = PhotoRec.STATUS_SEARCHING
        PlateDetector.detect(b, v.plate) { r ->
            val q = r.main
            val plateText = r.plate
            if (p.status == PhotoRec.STATUS_SEARCHING) {
                p.quad = q
                p.blurs = r.others
                p.status = if (q != null) PhotoRec.STATUS_FOUND else PhotoRec.STATUS_NOT_FOUND
            }
            if (plateText != null && v.plate == null) v.plate = plateText
            // Plaka öğrenildiyse, bulunamayanları bir kez daha tara
            if (v.plate != null && v.photos.none { it.status == PhotoRec.STATUS_SEARCHING }) {
                for (o in v.photos.toList()) {
                    if (o.status == PhotoRec.STATUS_NOT_FOUND && retried.add(o.file)) detect(o)
                }
            }
            if (!isDestroyed) {
                refresh()
                persist()
                maybeAutoSave()
            } else {
                Repo.save(v)
            }
        }
    }

    // ---------- Kaydet / Paylaş ----------

    /** Tüm fotoğraflar işlenince, henüz indirilmemişleri otomatik telefona kaydeder. */
    private var autoSaving = false

    private fun maybeAutoSave() {
        if (autoSaving || v.photos.isEmpty()) return
        if (v.photos.any { it.status == PhotoRec.STATUS_SEARCHING }) return
        val pending = v.photos.filter { !it.saved }
        if (pending.isEmpty()) return
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) return
        autoSaving = true
        val cv = Repo.cover()
        val folder = safeName(v.title)
        val all = v.photos.toList()
        exec.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            var ok = 0
            for (p in pending) {
                try {
                    val b = Repo.bitmap(v, p) ?: continue
                    val out = PlateRenderer.compose(b, cv, p.quad, 0, p.blurs)
                    val n = all.indexOf(p) + 1
                    if (ImageUtil.saveToGallery(this, out, "${folder}_${n}_$stamp.jpg", folder)) {
                        p.saved = true
                        ok++
                    }
                    out.recycle()
                } catch (e: Throwable) {
                    // atla
                }
            }
            Repo.save(v)
            runOnUiThread {
                autoSaving = false
                if (!isDestroyed) {
                    refresh()
                    toast("✓ $ok fotoğraf telefona indirildi (Resimler › otodegerle › $folder)")
                }
            }
        }
    }

    private fun saveAll() {
        if (v.photos.isEmpty()) { toast("Önce fotoğraf ekle"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
            return
        }
        v.photos.forEach { it.saved = false }
        maybeAutoSave()
    }

    @Suppress("unused")
    private fun saveAllLegacy() {
        val photos = v.photos.toList()
        val cv = Repo.cover()
        val prefix = safeName(v.title)
        toast("Kaydediliyor…")
        exec.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            var ok = 0
            photos.forEachIndexed { idx, p ->
                try {
                    val b = Repo.bitmap(v, p)
                    if (b != null) {
                        val out = PlateRenderer.compose(b, cv, p.quad, 0, p.blurs)
                        if (ImageUtil.saveToGallery(this, out, "${prefix}_${stamp}_${idx + 1}.jpg")) ok++
                        out.recycle()
                    }
                } catch (e: Throwable) {
                    // atla
                }
            }
            runOnUiThread { toast("$ok fotoğraf galeriye kaydedildi (Resimler › otodegerle)") }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) maybeAutoSave()
            else toast("Telefona indirmek için depolama izni gerekli")
        }
    }

    private fun shareAll() {
        if (v.photos.isEmpty()) { toast("Önce fotoğraf ekle"); return }
        val photos = v.photos.toList()
        val cv = Repo.cover()
        toast("Hazırlanıyor…")
        exec.execute {
            val dir = File(cacheDir, "share")
            dir.deleteRecursively()
            dir.mkdirs()
            val uris = ArrayList<Uri>()
            photos.forEachIndexed { idx, p ->
                try {
                    val b = Repo.bitmap(v, p)
                    if (b != null) {
                        val out = PlateRenderer.compose(b, cv, p.quad, 0, p.blurs)
                        val f = File(dir, "otodegerle_${idx + 1}.jpg")
                        FileOutputStream(f).use { s -> out.compress(Bitmap.CompressFormat.JPEG, 92, s) }
                        out.recycle()
                        uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
                    }
                } catch (e: Throwable) {
                    // atla
                }
            }
            runOnUiThread {
                if (uris.isEmpty()) {
                    toast("Paylaşılacak fotoğraf hazırlanamadı")
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val cd = ClipData.newUri(contentResolver, "foto", uris[0])
                    for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k]))
                    clipData = cd
                }
                startActivity(Intent.createChooser(send, "Paylaş"))
            }
        }
    }

    private fun safeName(s: String): String {
        val tr = mapOf('ç' to 'c', 'Ç' to 'C', 'ğ' to 'g', 'Ğ' to 'G', 'ı' to 'i', 'İ' to 'I',
            'ö' to 'o', 'Ö' to 'O', 'ş' to 's', 'Ş' to 'S', 'ü' to 'u', 'Ü' to 'U')
        val out = s.map { tr[it] ?: it }.joinToString("")
            .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        return if (out.isEmpty()) "otodegerle" else out.take(30)
    }

    private fun renameVehicle() {
        val input = android.widget.EditText(this).apply {
            setText(v.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Araç adı")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    v.title = t
                    titleView.text = t
                    Repo.save(v)
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun deleteVehicle() {
        AlertDialog.Builder(this)
            .setTitle("Araç silinsin mi?")
            .setMessage("\"${v.title}\" ve ${v.photos.size} fotoğrafı bu uygulamadan silinecek. Galeriye kaydedilenler silinmez.")
            .setPositiveButton("Sil") { _, _ ->
                Repo.delete(v)
                finish()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- Arayüz ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.BG)
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = label("‹ Araçlar", 15f, Brand.TEAL, true).apply {
            setPadding(0, dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }
        top.addView(back)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(label("Sil", 14f, Brand.SUB, true).apply {
            setPadding(dp(12), dp(6), 0, dp(6))
            setOnClickListener { deleteVehicle() }
        })
        root.addView(top)

        titleView = label(v.title, 24f, Brand.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { renameVehicle() }
        }
        root.addView(titleView, full(6))
        val date = SimpleDateFormat("d MMM yyyy HH:mm", Locale("tr")).format(Date(v.createdAt))
        root.addView(label("${v.employee} · $date  ·  adı değiştirmek için dokun", 12f, Brand.SUB))

        root.addView(button("＋  Fotoğraf Ekle", true, 16f) { pickPhotos() }, full(14))

        emptyText = label(
            "Aracın fotoğraflarını seç (ön, arka, çaprazlar…).\n\nPlakalar otomatik bulunup otodeğerle plakalığıyla kapatılır, bitince hepsi telefona indirilir.",
            14f, Brand.SUB
        ).apply { setLineSpacing(0f, 1.15f) }
        root.addView(emptyText, full(18))

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(button("Tekrar İndir", true, 14f) { saveAll() }, weighted(1.3f, 0))
        bottom.addView(button("Paylaş", false, 14f) { shareAll() }, weighted(1f, 8))
        root.addView(bottom, full(10))
        return root
    }

    private fun refresh() {
        emptyText.visibility = if (v.photos.isEmpty()) View.VISIBLE else View.GONE
        listBox.removeAllViews()
        v.photos.forEachIndexed { idx, p -> listBox.addView(photoRow(idx, p)) }
    }

    private fun photoRow(idx: Int, p: PhotoRec): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Brand.CARD, 14, Brand.LINE)
            setPadding(dp(8), dp(8), dp(4), dp(8))
            isClickable = true
            setOnClickListener {
                if (p.bitmap == null) { toast("Fotoğraf yükleniyor…"); return@setOnClickListener }
                startActivity(
                    Intent(this@VehicleActivity, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_VID, v.id)
                        .putExtra(EditorActivity.EXTRA_FILE, p.file)
                )
            }
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Brand.LINE, 10, null)
            clipToOutline = true
            p.bitmap?.let { setImageBitmap(PlateRenderer.compose(it, Repo.cover(), p.quad, 480, p.blurs)) }
        }
        row.addView(img, LinearLayout.LayoutParams(dp(124), dp(88)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        col.addView(label("Fotoğraf ${idx + 1}", 14f, Brand.TEXT, true))
        val (statusText, statusColor) = when (p.status) {
            PhotoRec.STATUS_SEARCHING -> "⏳ Plaka aranıyor…" to Brand.SUB
            PhotoRec.STATUS_FOUND -> "✓ Plaka kapatıldı" to Brand.TEAL
            PhotoRec.STATUS_MANUAL -> "✓ Elle ayarlandı" to Brand.TEAL
            PhotoRec.STATUS_NONE -> "— Kapatma yok" to Brand.SUB
            else -> "⚠ Plaka bulunamadı — dokun, düzelt" to Brand.WARN
        }
        col.addView(label(statusText, 13f, statusColor).apply { setPadding(0, dp(4), 0, 0) })
        if (p.blurs.isNotEmpty()) col.addView(label("◌ Arka planda ${p.blurs.size} plaka bulanıklaştırıldı", 12f, Brand.SUB).apply { setPadding(0, dp(2), 0, 0) })
        if (p.saved) col.addView(label("⬇ Telefona indirildi", 12f, Brand.SUB).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val del = label("✕", 18f, Brand.SUB).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener {
                Repo.removePhoto(v, p)
                refresh()
                exec.execute { Repo.writeThumb(v) }
            }
        }
        row.addView(del)
        return row
    }
}
