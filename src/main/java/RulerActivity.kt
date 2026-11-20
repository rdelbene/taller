package com.rdelbene.taller

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Regla en la pantalla, en centímetros y milímetros, a lo largo del borde izquierdo.
 * La medida sale de la densidad de la pantalla que informa el celular; con "Ajustar con una tarjeta"
 * se corrige si hiciera falta (una tarjeta de crédito o DNI mide 8,56 cm de ancho).
 */
class RulerActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("ruler", Context.MODE_PRIVATE) }
    private lateinit var ruler: RulerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        ruler = RulerView(this, resources.displayMetrics.ydpi * prefs.getFloat("k", 1f))
        root.addView(ruler)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        btns.addView(pill("Ajustar con una tarjeta") { ruler.calibrating = !ruler.calibrating; ruler.invalidate() })
        btns.addView(pill("−") { adjust(0.995f) }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.END })
        btns.addView(pill("+") { adjust(1.005f) }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.END })
        btns.addView(pill("Restablecer") { prefs.edit().remove("k").apply(); ruler.ppi = resources.displayMetrics.ydpi; ruler.invalidate() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); gravity = Gravity.END })
        root.addView(btns, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), dp(24))
        })
        setContentView(root)
    }

    private fun adjust(f: Float) {
        val k = prefs.getFloat("k", 1f) * f
        prefs.edit().putFloat("k", k).apply()
        ruler.ppi = resources.displayMetrics.ydpi * k
        ruler.invalidate()
    }

    class RulerView(ctx: Context, var ppi: Float) : View(ctx) {
        var calibrating = false
        private var marker = -1f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f }
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }

        override fun onDraw(c: Canvas) {
            val pxPerMm = ppi / 25.4f
            val top = 0f
            var mm = 0
            while (top + mm * pxPerMm < height) {
                val y = top + mm * pxPerMm
                val len = when { mm % 10 == 0 -> 70f; mm % 5 == 0 -> 48f; else -> 28f }
                c.drawLine(0f, y, len, y, p)
                if (mm % 10 == 0 && mm > 0) c.drawText("${mm / 10}", len + 10f, y + 12f, t)
                mm++
            }
            if (marker >= 0) {
                p.color = ACCENT; c.drawLine(0f, marker, width.toFloat(), marker, p); p.color = Color.WHITE
                t.color = ACCENT; c.drawText("%.1f cm".format((marker - top) / pxPerMm / 10f), width * 0.45f, marker - 14f, t); t.color = Color.WHITE
            }
            if (calibrating) {
                // tarjeta de 85,6 mm apoyada contra el borde superior
                val h = 85.6f * pxPerMm
                p.style = Paint.Style.STROKE; p.color = ACCENT; p.strokeWidth = 4f
                c.drawRect(90f, top, width * 0.85f, top + h, p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; p.strokeWidth = 2f
                t.textSize = 30f
                c.drawText("Apoyá una tarjeta (8,56 cm) acá, de canto,", 110f, top + h + 50f, t)
                c.drawText("y tocá − o + hasta que el borde coincida.", 110f, top + h + 90f, t)
                t.textSize = 34f
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            marker = e.y; invalidate(); return true
        }
    }
}
