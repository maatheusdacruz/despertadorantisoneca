package br.com.despertador

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Helpers mínimos para montar as telas em código (sem XML de layout). */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.label(text: String = "", sizeSp: Float = 16f, color: Int = Color.BLACK, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.button(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    textSize = 18f
    isAllCaps = false
    minimumHeight = dp(64)
    setOnClickListener { onClick() }
}

fun LinearLayout.addSpaced(view: View, topDp: Int = 12) {
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(topDp)
    })
}
