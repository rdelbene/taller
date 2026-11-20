package com.rdelbene.taller

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Nivel con el sensor de gravedad. Dos modos automáticos:
 *  - acostado (pantalla hacia arriba): burbuja en dos ejes, para mesas y estantes;
 *  - de canto (apoyado en el borde de un cuadro o una pared): cuántos grados le falta para quedar derecho.
 * "Poner en cero" corrige si el celular tiene la carcasa o la cámara que lo inclinan.
 */
class LevelActivity : Activity(), SensorEventListener {

    private lateinit var sm: SensorManager
    private lateinit var view: LevelView
    private val prefs by lazy { getSharedPreferences("level", Context.MODE_PRIVATE) }
    private var g = FloatArray(3)
    private var have = false
    private var wasLevel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        view = LevelView(this)
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        btns.addView(pill("Poner en cero") { zero() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        btns.addView(pill("Quitar cero") { clearZero() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)
        root.addView(label("Para cuadros: apoyá el borde largo o corto del celular sobre el marco. Para estantes: acostalo.\n" +
            "\"Poner en cero\": apoyalo en una superficie que sepas que está derecha (o en el mismo lugar dado vuelta) y tocalo.",
            12f, TEXT2).apply { setPadding(dp(16), 0, dp(16), dp(16)) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) toast("Este celular no tiene sensor de gravedad") else sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() { sm.unregisterListener(this); super.onPause() }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        // filtro suave: el número queda quieto sin tardar en reaccionar
        val a = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.08f else 0.25f
        for (i in 0..2) g[i] = if (have) g[i] + a * (e.values[i] - g[i]) else e.values[i]
        have = true
        val st = state()
        view.set(st)
        val level = st.level
        if (level && !wasLevel) vibrate()
        wasLevel = level
    }

    /** Estado actual: modo, ángulos en grados (con el cero aplicado). */
    class State(val flat: Boolean, val x: Float, val y: Float, val edge: Float, val ref: Int) {
        val level get() = if (flat) hypot(x, y) < 0.3f else abs(edge) < 0.3f
    }

    private fun state(): State {
        val (x, y, z) = Triple(g[0], g[1], g[2])
        val n = sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
        val flat = abs(z) / n > 0.85f
        return if (flat) {
            // inclinación en cada eje (grados): hacia dónde se va la burbuja
            val ax = Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat() - prefs.getFloat("fx", 0f)
            val ay = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat() - prefs.getFloat("fy", 0f)
            State(true, ax, ay, 0f, 0)
        } else {
            // ángulo del celular respecto de la vertical, en la pantalla; se busca el borde más cercano a horizontal
            val ang = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            val ref = (Math.round(ang / 90f) * 90).let { if (it == -180) 180 else it }
            var dev = ang - ref
            if (dev > 180) dev -= 360; if (dev < -180) dev += 360
            dev -= prefs.getFloat("e$ref", 0f)
            State(false, 0f, 0f, dev, ref)
        }
    }

    private fun zero() {
        if (!have) return
        // se guarda el ángulo actual como cero (sin el cero anterior)
        clearZeroFor(state())
        val s = state()
        if (s.flat) prefs.edit().putFloat("fx", s.x).putFloat("fy", s.y).apply()
        else prefs.edit().putFloat("e${s.ref}", s.edge).apply()
        toast("Cero guardado")
    }

    private fun clearZeroFor(s: State) {
        if (s.flat) prefs.edit().remove("fx").remove("fy").apply() else prefs.edit().remove("e${s.ref}").apply()
    }

    private fun clearZero() { prefs.edit().clear().apply(); toast("Ceros borrados") }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /** Dibujo del nivel. */
    class LevelView(ctx: Context) : View(ctx) {
        private var s = State(true, 0f, 0f, 0f, 0)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE; isFakeBoldText = true }
        fun set(v: State) { s = v; invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h * 0.45f
            val col = if (s.level) OK_GREEN else if ((if (s.flat) hypot(s.x, s.y) else abs(s.edge)) < 2f) ACCENT else 0xFFE53935.toInt()
            t.textSize = w * 0.16f
            if (s.flat) {
                val r = min(w, h) * 0.36f
                p.style = Paint.Style.FILL; p.color = 0xFF1E2A22.toInt(); c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0x66FFFFFF
                c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r * 0.12f, p)
                c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
                // la burbuja va hacia arriba de la pendiente; 10° = borde del círculo
                val k = r / 10f
                var bx = s.x * k; var by = -s.y * k
                val d = hypot(bx, by); if (d > r * 0.88f) { bx *= r * 0.88f / d; by *= r * 0.88f / d }
                p.style = Paint.Style.FILL; p.color = col; c.drawCircle(cx + bx, cy + by, r * 0.11f, p)
                t.textSize = w * 0.09f
                c.drawText("%.1f°   %.1f°".format(abs(s.x), abs(s.y)), cx, cy + r + w * 0.14f, t)
                t.textSize = w * 0.04f; t.color = TEXT2
                c.drawText("acostado · izquierda-derecha   adelante-atrás", cx, cy + r + w * 0.21f, t); t.color = Color.WHITE
            } else {
                // línea de horizonte real, girada con el celular
                // referencia: el borde del celular que tiene que quedar horizontal
                c.save(); c.rotate(s.ref.toFloat(), cx, cy)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x55FFFFFF
                c.drawLine(cx - w, cy, cx + w, cy, p)
                c.restore()
                // horizonte real
                c.save(); c.rotate(s.ref + s.edge, cx, cy)
                p.strokeWidth = 8f; p.color = col
                c.drawLine(cx - w, cy, cx + w, cy, p)
                c.restore()
                t.textSize = w * 0.22f
                c.drawText("%.1f°".format(abs(s.edge)), cx, cy - w * 0.12f, t)
                t.textSize = w * 0.045f; t.color = TEXT2
                val side = when (s.ref) { 0 -> "parado"; 180 -> "parado, dado vuelta"; else -> "de costado" }
                c.drawText("$side · la línea de color es el horizonte real", cx, cy + w * 0.18f, t); t.color = Color.WHITE
            }
            if (s.level) { t.textSize = w * 0.06f; t.color = OK_GREEN; c.drawText("✓ derecho", cx, h * 0.93f, t); t.color = Color.WHITE }
        }
    }

    @Suppress("unused") private fun r1(v: Float) = (v * 10).roundToInt() / 10f
}
