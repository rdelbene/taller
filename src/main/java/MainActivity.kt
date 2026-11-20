package com.rdelbene.taller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** Pantalla de inicio: las cuatro herramientas. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(18))
            setBackgroundColor(BG)
        }
        box.addView(label("Taller", 30f, Color.WHITE, true))
        box.addView(label("Herramientas con la cámara y los sensores", 14f, TEXT2).apply { setPadding(0, dp(2), 0, dp(18)) })

        card(box, "🎨", "Color", "Apuntá a una pared, una tela o un objeto: color, código y colores que combinan.") {
            startActivity(Intent(this, ColorActivity::class.java))
        }
        card(box, "🫧", "Nivel", "Para cuadros, estantes y muebles: apoyá el celular de canto o acostado.") {
            startActivity(Intent(this, LevelActivity::class.java))
        }
        card(box, "📐", "Medir con 2 lentes", "Usa el lente principal y el tele a la vez para calcular distancias y medidas (de 0,3 a 3 m).") {
            startActivity(Intent(this, MeasureActivity::class.java))
        }
        card(box, "📏", "Regla", "Regla en la pantalla, en centímetros, para cosas chicas.") {
            startActivity(Intent(this, RulerActivity::class.java))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(box) })
    }

    private fun card(parent: LinearLayout, icon: String, title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(CARD, dpf(16f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(label(icon, 30f).apply { setPadding(0, 0, dp(14), 0) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 19f, Color.WHITE, true))
        texts.addView(label(sub, 13f, TEXT2))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }
}
