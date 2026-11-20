package com.rdelbene.taller

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Cuentagotas: muestra el color del centro de la imagen (promedio de una zona chica), su código, nombre,
 * y colores que combinan. Se puede congelar, guardar en una paleta y calibrar con un papel blanco.
 */
class ColorActivity : Activity() {

    private lateinit var manager: CameraManager
    private lateinit var texture: TextureView
    private lateinit var swatch: TextView
    private lateinit var info: TextView
    private lateinit var schemesBox: LinearLayout
    private lateinit var paletteBox: LinearLayout
    private lateinit var freezeBtn: TextView
    private lateinit var whiteBtn: TextView
    private lateinit var torchBtn: TextView

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var cameraId: String? = null
    private var hasTorch = false

    private val main = Handler(Looper.getMainLooper())
    private var frozen = false
    private var torch = false
    private var current = Color.GRAY
    private var smooth: DoubleArray? = null
    // ganancias por canal (en luz lineal) calculadas con un papel blanco; null = sin calibrar
    private var gains: DoubleArray? = null

    private val prefs by lazy { getSharedPreferences("color", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        buildUi()
        loadPalette()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val w = resources.displayMetrics.widthPixels
        val frame = FrameLayout(this)
        texture = TextureView(this)
        frame.addView(texture, FrameLayout.LayoutParams(w, w * 4 / 3))
        frame.addView(Reticle(this), FrameLayout.LayoutParams(w, w * 4 / 3))
        frame.setOnClickListener { toggleFreeze() }
        // la imagen ocupa a lo sumo el 55 % de la pantalla; se recorta arriba y abajo
        val maxH = (resources.displayMetrics.heightPixels * 0.50).toInt()
        val clip = FrameLayout(this).apply { clipChildren = true }
        clip.addView(frame, FrameLayout.LayoutParams(w, w * 4 / 3, Gravity.CENTER))
        root.addView(clip, LinearLayout.LayoutParams(w, minOf(maxH, w * 4 / 3)))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(14)) }
        swatch = TextView(this).apply {
            textSize = 26f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(Color.GRAY, dpf(14f))
            setOnClickListener { copyHex() }
        }
        panel.addView(swatch)
        info = label("", 13f, TEXT2).apply { setPadding(0, dp(6), 0, dp(8)) }
        panel.addView(info)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        freezeBtn = pill("Congelar") { toggleFreeze() }
        whiteBtn = pill("Calibrar blanco") { toggleWhite() }
        torchBtn = pill("Linterna") { toggleTorch() }
        val save = pill("Guardar", ACCENT, Color.BLACK) { savePalette(current) }
        for (b in listOf(freezeBtn, save, whiteBtn, torchBtn)) {
            b.textSize = 13f
            btns.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        panel.addView(btns)

        panel.addView(label("Paleta guardada (tocá para ver, mantené para borrar)", 12f, TEXT2).apply { setPadding(0, dp(12), 0, dp(4)) })
        paletteBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(paletteBox) })

        panel.addView(label("Colores que combinan (tocá uno para copiar su código)", 12f, TEXT2).apply { setPadding(0, dp(12), 0, dp(4)) })
        schemesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(schemesBox)
        panel.addView(label("Consejo: la cámara ajusta sola el color según la luz. Para medir mejor, apuntá primero a un papel blanco con la misma luz y tocá \"Calibrar blanco\".",
            12f, TEXT2).apply { setPadding(0, dp(12), 0, 0) })

        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        show(current)
    }

    /** Círculo en el centro: la zona que se mide. */
    private class Reticle(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun onDraw(c: Canvas) {
            val r = width * SAMPLE_FRAC * 0.5f * 1.15f
            p.strokeWidth = 5f; p.color = 0x99000000.toInt(); c.drawCircle(width / 2f, height / 2f, r, p)
            p.strokeWidth = 2.5f; p.color = Color.WHITE; c.drawCircle(width / 2f, height / 2f, r, p)
        }
    }

    // ------------------------------------------------------------------ cámara

    override fun onResume() {
        super.onResume()
        camThread = HandlerThread("cam").also { it.start(); camHandler = Handler(it.looper) }
        if (texture.isAvailable) openCamera() else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        main.postDelayed(sampler, 300)
    }

    override fun onPause() {
        main.removeCallbacks(sampler)
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
        camThread?.quitSafely(); camThread = null; camHandler = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (texture.isAvailable) openCamera() }
        else toast("Sin permiso de cámara no se puede medir el color")
    }

    private fun openCamera() {
        if (device != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return toast("No hay cámara trasera")
        cameraId = id
        val ch = manager.getCameraCharacteristics(id)
        hasTorch = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        torchBtn.visibility = if (hasTorch) View.VISIBLE else View.GONE
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val size = sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1600 }.maxByOrNull { it.width } ?: Size(1280, 960)
        texture.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startPreview() }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; main.post { toast("Error de cámara ($error)") } }
            }, camHandler)
        } catch (e: Exception) { toast("No se pudo abrir la cámara: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun startPreview() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        val s = Surface(st); previewSurface = s
        d.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cs: CameraCaptureSession) {
                session = cs
                request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(s)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                applyRequest()
            }
            override fun onConfigureFailed(cs: CameraCaptureSession) { main.post { toast("No se pudo iniciar la cámara") } }
        }, camHandler)
    }

    /** Vuelve a mandar el pedido con linterna y bloqueo de balance/exposición según el estado. */
    private fun applyRequest() {
        val r = request ?: return
        val cs = session ?: return
        r.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        val lock = gains != null
        r.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
        r.set(CaptureRequest.CONTROL_AE_LOCK, lock)
        try { cs.setRepeatingRequest(r.build(), null, camHandler) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ medición

    private val sampler = object : Runnable {
        override fun run() {
            if (!frozen) sample()
            main.postDelayed(this, 150)
        }
    }

    /** Promedio (en luz lineal) de la zona del centro. */
    private fun measureCenter(): DoubleArray? {
        if (!texture.isAvailable) return null
        val bw = 120; val bh = 160
        val bmp = texture.getBitmap(bw, bh) ?: return null
        val rad = (bw * SAMPLE_FRAC / 2).roundToInt().coerceAtLeast(2)
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (y in bh / 2 - rad..bh / 2 + rad) for (x in bw / 2 - rad..bw / 2 + rad) {
            if ((x - bw / 2) * (x - bw / 2) + (y - bh / 2) * (y - bh / 2) > rad * rad) continue
            val c = bmp.getPixel(x, y)
            r += ColorMath.lin(ColorMath.r(c) / 255.0); g += ColorMath.lin(ColorMath.g(c) / 255.0); b += ColorMath.lin(ColorMath.b(c) / 255.0); n++
        }
        bmp.recycle()
        return if (n == 0) null else doubleArrayOf(r / n, g / n, b / n)
    }

    private fun sample() {
        val m = measureCenter() ?: return
        // suavizado para que el número no salte con el pulso
        val sm = smooth?.let { o -> DoubleArray(3) { o[it] * 0.6 + m[it] * 0.4 } } ?: m
        smooth = sm
        val gn = gains ?: doubleArrayOf(1.0, 1.0, 1.0)
        val c = ColorMath.rgb(
            (ColorMath.enc(sm[0] * gn[0]) * 255).roundToInt(),
            (ColorMath.enc(sm[1] * gn[1]) * 255).roundToInt(),
            (ColorMath.enc(sm[2] * gn[2]) * 255).roundToInt())
        if (c != current) show(c)
    }

    private fun show(c: Int) {
        current = c
        swatch.text = "${ColorMath.hex(c)}\n${ColorMath.name(c)}"
        swatch.setTextColor(ColorMath.onColor(c))
        swatch.background = rounded(c, dpf(14f), dp(1), 0x33FFFFFF)
        info.text = ColorMath.describe(c) + (if (gains != null) "  ·  blanco calibrado" else "") + "\nTocá el color para copiar el código." +
            (if (gains == null) "\n¿Sale más gris de lo que es? La cámara le quita color a lo que llena la imagen: calibrá el blanco con una hoja, o no te acerques tanto." else "")
        schemesBox.removeAllViews()
        for ((name, cols) in ColorMath.schemes(c)) {
            schemesBox.addView(label(name, 12f, Color.WHITE, true).apply { setPadding(0, dp(6), 0, dp(3)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in cols) row.addView(chip(col), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
            schemesBox.addView(row)
        }
    }

    private fun chip(c: Int, onLong: (() -> Unit)? = null, onTap: (() -> Unit)? = null): TextView = TextView(this).apply {
        text = ColorMath.hex(c); textSize = 11f; gravity = Gravity.CENTER
        setTextColor(ColorMath.onColor(c)); background = rounded(c, dpf(8f), dp(1), 0x33FFFFFF)
        setOnClickListener { if (onTap != null) onTap() else copy(ColorMath.hex(c)) }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

    private fun toggleFreeze() {
        frozen = !frozen
        freezeBtn.text = if (frozen) "Seguir" else "Congelar"
        freezeBtn.background = rounded(if (frozen) OK_GREEN else 0xFF2C3238.toInt(), dpf(22f))
    }

    private fun toggleWhite() {
        if (gains != null) {
            gains = null
            whiteBtn.text = "Calibrar blanco"; whiteBtn.background = rounded(0xFF2C3238.toInt(), dpf(22f))
            applyRequest(); show(current); toast("Calibración quitada"); return
        }
        val m = measureCenter() ?: return
        if (m.any { it < 0.02 }) return toast("Muy oscuro: apuntá a un papel blanco bien iluminado")
        val target = (m[0] + m[1] + m[2]) / 3
        gains = doubleArrayOf(target / m[0], target / m[1], target / m[2])
        whiteBtn.text = "Blanco ✓"; whiteBtn.background = rounded(OK_GREEN, dpf(22f))
        applyRequest() // se bloquean balance y exposición para que la cámara no los vuelva a cambiar
        toast("Listo: blanco calibrado y luz bloqueada. Mantené la misma iluminación.")
    }

    private fun toggleTorch() {
        torch = !torch
        torchBtn.background = rounded(if (torch) ACCENT else 0xFF2C3238.toInt(), dpf(22f))
        torchBtn.setTextColor(if (torch) Color.BLACK else Color.WHITE)
        applyRequest()
    }

    private fun copyHex() = copy(ColorMath.hex(current))

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("color", s))
        toast("Copiado: $s")
    }

    // ------------------------------------------------------------------ paleta

    private fun palette(): MutableList<Int> =
        (prefs.getString("palette", "") ?: "").split(",").mapNotNull { ColorMath.parseHex(it) }.toMutableList()

    private fun savePalette(c: Int) {
        val p = palette(); p.remove(c); p.add(0, c)
        prefs.edit().putString("palette", p.take(40).joinToString(",") { ColorMath.hex(it) }).apply()
        loadPalette(); toast("Guardado ${ColorMath.hex(c)}")
    }

    private fun loadPalette() {
        paletteBox.removeAllViews()
        val p = palette()
        if (p.isEmpty()) { paletteBox.addView(label("Todavía vacía: tocá \"Guardar\".", 13f, TEXT2)); return }
        for (c in p) {
            paletteBox.addView(chip(c, onLong = {
                val q = palette(); q.remove(c)
                prefs.edit().putString("palette", q.joinToString(",") { ColorMath.hex(it) }).apply(); loadPalette()
            }, onTap = { if (!frozen) toggleFreeze(); show(c) }), LinearLayout.LayoutParams(dp(74), dp(44)).apply { marginEnd = dp(6) })
        }
    }

    companion object { const val SAMPLE_FRAC = 0.08f } // ancho de la zona medida, como fracción del ancho de la imagen
}
