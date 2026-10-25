package com.otodegerle.plaka

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class EditorActivity : Activity() {

    companion object {
        const val EXTRA_VID = "vid"
        const val EXTRA_FILE = "file"
    }

    private lateinit var editor: QuadEditorView
    private lateinit var toggleBtn: TextView
    private lateinit var vehicle: Vehicle
    private lateinit var photo: PhotoRec
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        val v = Repo.find(intent.getStringExtra(EXTRA_VID))
        val p = v?.photos?.firstOrNull { it.file == intent.getStringExtra(EXTRA_FILE) }
        val bmp = if (v != null && p != null) Repo.bitmap(v, p) else null
        if (v == null || p == null || bmp == null) {
            finish()
            return
        }
        vehicle = v
        photo = p

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.BG)
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }
        root.addView(
            label(
                "Otomatik bulunamayan plaka için: köşeleri sürükleyerek plakalığı oturt. Tamam'a basınca telefona tekrar indirilir.",
                13f, Brand.SUB
            ).apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        )

        editor = QuadEditorView(this)
        editor.setData(bmp, Repo.cover(), p.quad?.copyOf())
        editor.onChange = {
            changed = true
            updateToggle()
        }
        root.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggleBtn = button("", false, 14f) { togglePlate() }
        row.addView(button("Otomatik Bul", false, 14f) { autoDetect() }, weighted(1f, 0))
        row.addView(toggleBtn, weighted(1f, 8))
        row.addView(button("Tamam", true, 14f) { finishEdit() }, weighted(1f, 8))
        root.addView(row, full(10))

        setContentView(root)
        updateToggle()
    }

    private fun updateToggle() {
        toggleBtn.text = if (editor.quad == null) "Plaka Ekle" else "Kaldır"
    }

    private fun togglePlate() {
        val b = photo.bitmap ?: return
        if (editor.quad == null) editor.setQuad(PlateRenderer.defaultQuad(b.width, b.height))
        else editor.setQuad(null)
        changed = true
        updateToggle()
    }

    private fun autoDetect() {
        val b = photo.bitmap ?: return
        toast("Plaka aranıyor…")
        PlateDetector.detect(b, vehicle.plate) { r ->
            val q = r.main
            if (isFinishing) return@detect
            if (q != null) {
                editor.setQuad(q)
                changed = true
                updateToggle()
            } else {
                toast("Plaka bulunamadı, elle ekleyebilirsin")
            }
        }
    }

    private fun finishEdit() {
        if (changed) {
            photo.quad = editor.quad?.copyOf()
            photo.status = if (photo.quad == null) PhotoRec.STATUS_NONE else PhotoRec.STATUS_MANUAL
            photo.saved = false // düzeltilen hali tekrar indirilsin
            Repo.save(vehicle)
        }
        finish()
    }
}
