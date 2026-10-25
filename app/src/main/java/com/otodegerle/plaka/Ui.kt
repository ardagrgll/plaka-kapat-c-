package com.otodegerle.plaka

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Activity.rounded(color: Int, radiusDp: Int, stroke: Int?): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

fun Activity.button(label: String, primary: Boolean, sizeSp: Float, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Brand.TEXT)
        background = if (primary) rounded(Brand.MINT, 12, null) else rounded(Brand.CARD, 12, Brand.LINE)
        val v = if (sizeSp >= 15f) 14 else 11
        setPadding(dp(12), dp(v), dp(12), dp(v))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Activity.label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Activity.full(topDp: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(topDp) }

fun Activity.weighted(weight: Float, startDp: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { marginStart = dp(startDp) }

fun Activity.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
