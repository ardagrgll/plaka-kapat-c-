package com.otodegerle.plaka

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Araç listesi: çalışan hesabı, plakalık ayarı, araçlar gün gün bölümlerde. */
class MainActivity : Activity() {

    companion object {
        private const val REQ_COVER = 2
        private const val PREFS = "app"
        private const val KEY_EMPLOYEE = "employee"
    }

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var listBox: LinearLayout
    private lateinit var coverView: ImageView
    private lateinit var emptyText: TextView
    private lateinit var employeeView: TextView
    private lateinit var filterAll: TextView
    private lateinit var filterMine: TextView
    private var onlyMine = false
    private var pendingShared: ArrayList<Uri>? = null

    private val employee: String
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_EMPLOYEE, "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        setContentView(buildUi())
        handleShareIntent(intent)
        if (employee.isEmpty()) askEmployee(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- Çalışan hesabı ----------

    private fun askEmployee(first: Boolean) {
        val input = EditText(this).apply {
            hint = "Adın Soyadın"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(employee)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (first) "Çalışan girişi" else "Çalışanı değiştir")
            .setMessage("Eklediğin araçlar bu isimle kaydedilir.")
            .setView(box)
            .setPositiveButton("Devam") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_EMPLOYEE, name).apply()
                    refresh()
                    pendingShared?.let { createVehicleFromShare(it) }
                } else if (first) {
                    askEmployee(true)
                }
            }
            .setCancelable(!first)
        if (!first) b.setNegativeButton("Vazgeç", null)
        b.show()
    }

    // ---------- Araç oluşturma ----------

    private fun newVehicle() {
        if (employee.isEmpty()) { askEmployee(true); return }
        val input = EditText(this).apply {
            hint = "Örn. 2018 Passat 1.6 TDI"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Yeni araç")
            .setMessage("Araç adı / not (isteğe bağlı)")
            .setView(box)
            .setPositiveButton("Oluştur ve fotoğraf seç") { _, _ ->
                val t = input.text.toString().trim().ifEmpty { "Araç #${Repo.vehicles.size + 1}" }
                val v = Repo.create(t, employee)
                startActivity(
                    Intent(this, VehicleActivity::class.java)
                        .putExtra(VehicleActivity.EXTRA_ID, v.id)
                        .putExtra(VehicleActivity.EXTRA_PICK, true)
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) return
        i.action = Intent.ACTION_MAIN
        if (employee.isEmpty()) {
            pendingShared = uris
        } else {
            createVehicleFromShare(uris)
        }
    }

    private fun createVehicleFromShare(uris: ArrayList<Uri>) {
        pendingShared = null
        val v = Repo.create("Araç #${Repo.vehicles.size + 1}", employee)
        startActivity(
            Intent(this, VehicleActivity::class.java)
                .putExtra(VehicleActivity.EXTRA_ID, v.id)
                .putParcelableArrayListExtra(VehicleActivity.EXTRA_URIS, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    // ---------- Plakalık ----------

    @Suppress("DEPRECATION")
    private fun pickCover() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Plakalık görselini seç"), REQ_COVER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_COVER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        exec.execute {
            val bmp: Bitmap? = try { ImageUtil.load(this, uri, 1600) } catch (e: Throwable) { null }
            if (bmp != null) {
                Repo.setCover(bmp)
                for (v in Repo.vehicles.toList()) {
                    Repo.writeThumb(v)
                    Repo.release(v)
                }
            }
            runOnUiThread {
                if (bmp == null) toast("Plakalık görseli açılamadı")
                else { toast("Plakalık güncellendi"); refresh() }
            }
        }
    }

    private fun resetCover() {
        exec.execute {
            Repo.resetCover()
            for (v in Repo.vehicles.toList()) {
                Repo.writeThumb(v)
                Repo.release(v)
            }
            runOnUiThread { toast("Varsayılan plakalığa dönüldü"); refresh() }
        }
    }

    // ---------- Arayüz ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.BG)
            setPadding(dp(16), dp(20), dp(16), dp(14))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_wordmark)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        titleCol.addView(label("Plaka Kapatıcı", 13f, Brand.SUB))
        head.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        employeeView = label("", 13f, Brand.TEXT, true).apply {
            background = rounded(Brand.CARD, 20, Brand.LINE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(170)
            setOnClickListener { askEmployee(false) }
        }
        head.addView(employeeView)
        root.addView(head)

        // Plakalık
        root.addView(label("PLAKALIK", 11f, Brand.SUB, true).apply { letterSpacing = 0.1f }, full(16))
        val coverBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Brand.CARD, 12, Brand.LINE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        coverView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        coverBox.addView(coverView, LinearLayout.LayoutParams(0, dp(40), 1f))
        coverBox.addView(button("Değiştir", false, 13f) { pickCover() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        coverBox.addView(button("Varsayılan", false, 13f) { resetCover() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        root.addView(coverBox, full(6))

        root.addView(button("＋  Yeni Araç", true, 16f) { newVehicle() }, full(14))

        // Filtre
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterAll = chip("Tüm araçlar") { onlyMine = false; refresh() }
        filterMine = chip("Benim eklediklerim") { onlyMine = true; refresh() }
        filters.addView(filterAll)
        filters.addView(filterMine, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(filters, full(14))

        emptyText = label(
            "Henüz araç yok.\n\n\"Yeni Araç\" ile aracı oluştur, fotoğraflarını seç. Plakalar otomatik kapatılır ve hepsi telefona indirilir.\n\nGaleriden ya da WhatsApp'tan \"Paylaş → otodeğerle Plaka\" ile de fotoğraf gönderebilirsin.",
            14f, Brand.SUB
        ).apply { setLineSpacing(0f, 1.15f) }
        root.addView(emptyText, full(18))

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })
        return root
    }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        label(text, 13f, Brand.TEXT, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }

    private fun styleChip(c: TextView, on: Boolean) {
        c.background = if (on) rounded(Brand.MINT, 20, null) else rounded(Brand.CARD, 20, Brand.LINE)
        c.setTextColor(Brand.TEXT)
    }

    private fun refresh() {
        if (!::listBox.isInitialized) return
        coverView.setImageBitmap(Repo.cover())
        employeeView.text = if (employee.isEmpty()) "Giriş yap" else "👤 $employee"
        styleChip(filterAll, !onlyMine)
        styleChip(filterMine, onlyMine)

        val me = employee
        val list = Repo.vehicles.filter { !onlyMine || it.employee == me }
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        listBox.removeAllViews()

        var lastSection = ""
        for (v in list) {
            val section = sectionName(v.createdAt)
            if (section != lastSection) {
                lastSection = section
                listBox.addView(label(section.uppercase(Locale("tr")), 11f, Brand.SUB, true).apply {
                    letterSpacing = 0.1f
                    setPadding(dp(2), dp(10), 0, dp(8))
                })
            }
            listBox.addView(vehicleCard(v))
        }
    }

    private fun sectionName(t: Long): String {
        val cal = Calendar.getInstance()
        val today = dayKey(cal)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dayKey(cal)
        cal.timeInMillis = t
        return when (dayKey(cal)) {
            today -> "Bugün"
            yesterday -> "Dün"
            else -> SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date(t))
        }
    }

    private fun dayKey(c: Calendar): Int = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)

    private fun vehicleCard(v: Vehicle): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Brand.CARD, 14, Brand.LINE)
            setPadding(dp(8), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, VehicleActivity::class.java).putExtra(VehicleActivity.EXTRA_ID, v.id))
            }
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Brand.LINE, 10, null)
            clipToOutline = true
            val f = Repo.thumbFile(v)
            if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.path))
        }
        row.addView(img, LinearLayout.LayoutParams(dp(110), dp(78)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(label(v.title, 15f, Brand.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val time = SimpleDateFormat("HH:mm", Locale("tr")).format(Date(v.createdAt))
        col.addView(label("${v.photos.size} foto · ${v.employee} · $time", 12f, Brand.SUB).apply {
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val check = v.needsCheck()
        val (st, sc) = when {
            v.photos.isEmpty() -> "Fotoğraf yok" to Brand.SUB
            check > 0 -> "⚠ $check fotoğrafı kontrol et" to Brand.WARN
            v.photos.all { it.saved } -> "✓ Kapatıldı · telefona indirildi" to Brand.TEAL
            else -> "✓ Plakalar kapalı" to Brand.TEAL
        }
        col.addView(label(st, 12f, sc, true).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 22f, Brand.SUB))
        return row
    }
}
