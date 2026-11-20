package com.rdelbene.taller

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/* Ayudas de interfaz compartidas: todo se arma por código, sin XML de pantallas. */

const val BG = 0xFF121417.toInt()
const val CARD = 0xFF1E2226.toInt()
const val ACCENT = 0xFFFFC107.toInt()
const val OK_GREEN = 0xFF4CAF50.toInt()
const val TEXT2 = 0xFFB0B4B8.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

/** Botón chato con fondo redondeado. */
fun Context.pill(text: String, bg: Int = 0xFF2C3238.toInt(), fg: Int = Color.WHITE, onClick: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(fg)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(bg, dpf(22f))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.label(text: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
