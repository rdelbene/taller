package com.rdelbene.taller

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Medir con dos lentes: el principal y el tele sacan la misma imagen a la vez (cámara lógica multi-lente,
 * como DualCam). Se congela un par de cuadros, se tocan dos puntos sobre la imagen del tele y se calcula
 * a qué distancia está cada uno y cuánto mide lo que hay entre ellos (ver Stereo).
 */
class MeasureActivity : Activity() {

    private lateinit var manager: CameraManager
    private lateinit var view: MeasureView
    private lateinit var info: TextView
    private lateinit var measureBtn: TextView

    private val main = Handler(Looper.getMainLooper())
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val work = Executors.newSingleThreadExecutor()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var teleReader: ImageReader? = null
    private var mainReader: ImageReader? = null

    // lentes elegidos y su geometría
    private var logicalId: String? = null
    private var teleId = ""; private var mainId = ""
    private var teleSize = Size(1920, 1440); private var mainSize = Size(1920, 1440)
    private var fTeleMm = 0.0; private var fMainMm = 0.0
    private var sensorWTele = 0.0; private var sensorWMain = 0.0
    private var poseBase = Double.NaN

    // calibración guardada (por par de lentes)
    private val prefs by lazy { getSharedPreferences("stereo", Context.MODE_PRIVATE) }
    private val key get() = "${mainId}_$teleId"
    private var infX = 0.0; private var infY = 0.0; private var infCal = false
    private var base = 0.018; private var baseCal = false

    // captura de un par de cuadros con la misma marca de tiempo
    private class Frame(val ts: Long, val y: ByteArray, val stride: Int, val w: Int, val h: Int)
    @Volatile private var capturing = false
    private var captureStart = 0L
    private var onPair: ((Frame, Frame, Bitmap) -> Unit)? = null
    private val teleFrames = ArrayDeque<Frame>(); private val mainFrames = ArrayDeque<Frame>()
    private var teleBitmaps = HashMap<Long, Bitmap>()
    private var previewCount = 0

    // estado de la medición
    private var tele: Stereo.Lens? = null
    private var mainL: Stereo.Lens? = null
    private var frozen = false
    private var lastLength = Double.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        val left = FrameLayout(this)
        view = MeasureView(this)
        left.addView(view)
        info = label("", 13f, Color.WHITE).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(0x99000000.toInt()) }
        left.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        measureBtn = pill("Medir", ACCENT, Color.BLACK) { onMeasureButton() }
        side.addView(measureBtn, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        side.addView(pill("Calibrar") { showCalibrate() }, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        side.addView(pill("Ayuda") { showHelp() }, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(side)
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        findLenses()
        loadCal()
        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        camThread = HandlerThread("cam").also { it.start(); camHandler = Handler(it.looper) }
        openCamera()
    }

    override fun onPause() {
        closeCamera()
        camThread?.quitSafely(); camThread = null; camHandler = null
        super.onPause()
    }

    override fun onDestroy() { work.shutdownNow(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else toast("Sin permiso de cámara no se puede medir")
    }

    // ------------------------------------------------------------------ lentes

    private fun equiv(c: CameraCharacteristics): Double {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0.0
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0.0
        return f * 43.27 / sqrt((s.width * s.width + s.height * s.height).toDouble())
    }

    /** Busca la cámara trasera multi-lente y elige el principal (~23–28 mm) y el tele (el de mayor focal). */
    private fun findLenses() {
        var bestId: String? = null; var bestPhys = emptyList<String>()
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue
            if (c.physicalCameraIds.size > bestPhys.size) { bestId = id; bestPhys = c.physicalCameraIds.toList() }
        }
        logicalId = bestId
        if (bestId == null || bestPhys.size < 2) return
        val eq = bestPhys.associateWith { equiv(manager.getCameraCharacteristics(it)) }
        teleId = eq.maxByOrNull { it.value }?.key ?: return
        mainId = eq.filter { it.key != teleId }.minByOrNull { abs(it.value - 25.0) }?.key ?: return
        val ct = manager.getCameraCharacteristics(teleId); val cm = manager.getCameraCharacteristics(mainId)
        fTeleMm = ct.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: 0.0
        fMainMm = cm.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: 0.0
        sensorWTele = ct.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width?.toDouble() ?: 0.0
        sensorWMain = cm.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width?.toDouble() ?: 0.0
        // separación entre lentes informada por el fabricante (si la informa)
        val pt = ct.get(CameraCharacteristics.LENS_POSE_TRANSLATION); val pm = cm.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        if (pt != null && pm != null && pt.size >= 3 && pm.size >= 3) {
            val b = sqrt(((pt[0] - pm[0]) * (pt[0] - pm[0]) + (pt[1] - pm[1]) * (pt[1] - pm[1]) + (pt[2] - pm[2]) * (pt[2] - pm[2])).toDouble())
            if (b in 0.003..0.06) poseBase = b
        }
    }

    private fun yuvSizes(id: String): List<Size> =
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()

    // ------------------------------------------------------------------ cámara

    private fun openCamera() {
        val id = logicalId
        if (id == null || teleId.isEmpty()) { info.text = "Este celular no deja usar dos lentes traseros a la vez desde una app."; return }
        if (device != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; configure() }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; main.post { toast("Error de cámara ($error)") } }
            }, camHandler)
        } catch (e: Exception) { toast("No se pudo abrir la cámara: ${e.message}") }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null
        teleReader?.close(); mainReader?.close(); teleReader = null; mainReader = null
    }

    /** Elige tamaños: el tele a 1920×1440 (o lo más cercano) y el principal lo más grande que acepte junto con él. */
    private fun configure() {
        val d = device ?: return
        val ts = yuvSizes(teleId).filter { it.width * 3 == it.height * 4 }
        val ms = yuvSizes(mainId).filter { it.width * 3 == it.height * 4 }
        val teleCands = ts.filter { it.width <= 1920 }.sortedByDescending { it.width }.ifEmpty { listOf(Size(1280, 960)) }
        val mainCands = ms.filter { it.width <= 4096 }.sortedByDescending { it.width }.ifEmpty { listOf(Size(1280, 960)) }
        val exec = Executors.newSingleThreadExecutor()
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val r = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(teleReader!!.surface); addTarget(mainReader!!.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    s.setRepeatingRequest(r.build(), null, camHandler)
                } catch (e: Exception) { main.post { toast("No arrancó la vista previa: ${e.message}") } }
                main.post { updateInfo() }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { main.post { toast("Estos dos lentes no se pueden usar juntos") } }
        }
        for (tsz in teleCands.take(3)) for (msz in mainCands) {
            if (msz.width < tsz.width) continue
            val tr = ImageReader.newInstance(tsz.width, tsz.height, ImageFormat.YUV_420_888, 3)
            val mr = ImageReader.newInstance(msz.width, msz.height, ImageFormat.YUV_420_888, 3)
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(
                OutputConfiguration(tr.surface).apply { setPhysicalCameraId(teleId) },
                OutputConfiguration(mr.surface).apply { setPhysicalCameraId(mainId) }), exec, cb)
            val ok = try { d.isSessionConfigurationSupported(cfg) } catch (_: Exception) { msz.width <= 1920 }
            if (!ok) { tr.close(); mr.close(); continue }
            teleReader = tr; mainReader = mr; teleSize = tsz; mainSize = msz
            tr.setOnImageAvailableListener({ onTele(it) }, camHandler)
            mr.setOnImageAvailableListener({ onMain(it) }, camHandler)
            try { d.createCaptureSession(cfg) } catch (e: Exception) { main.post { toast("No se pudo crear la sesión: ${e.message}") } }
            return
        }
        main.post { toast("No encontré tamaños que los dos lentes acepten juntos") }
    }

    private fun copyY(img: Image): Frame {
        val p = img.planes[0]; val buf = p.buffer
        val arr = ByteArray(buf.remaining()); buf.get(arr)
        return Frame(img.timestamp, arr, p.rowStride, img.width, img.height)
    }

    private fun onTele(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            previewCount++
            // vista previa a 1/3 (liviana); al capturar, a 1/2 para marcar los puntos con más detalle
            val bmp = if (!frozen && (capturing || previewCount % 2 == 0)) yuvToBitmap(img, if (capturing) 2 else 3) else null
            if (capturing) {
                val f = copyY(img)
                synchronized(teleFrames) { teleFrames.addLast(f); while (teleFrames.size > 4) teleFrames.removeFirst(); if (bmp != null) teleBitmaps[f.ts] = bmp }
                tryPair()
            }
            if (bmp != null && !frozen) main.post { if (!frozen) view.setImage(bmp, teleSize.width, teleSize.height) }
        } finally { img.close() }
    }

    private fun onMain(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (capturing) {
                val f = copyY(img)
                synchronized(teleFrames) { mainFrames.addLast(f); while (mainFrames.size > 3) mainFrames.removeFirst() }
                tryPair()
            }
        } finally { img.close() }
    }

    /** Junta un cuadro de cada lente con la misma marca de tiempo (o la más cercana si tarda). */
    private fun tryPair() {
        val cbk = onPair ?: return
        var pair: Pair<Frame, Frame>? = null
        var bmp: Bitmap? = null
        synchronized(teleFrames) {
            loop@ for (t in teleFrames.reversed()) for (m in mainFrames.reversed()) {
                if (t.ts == m.ts && teleBitmaps[t.ts] != null) { pair = t to m; bmp = teleBitmaps[t.ts]; break@loop }
            }
            if (pair == null && System.currentTimeMillis() - captureStart > 1500) {
                var best = Long.MAX_VALUE
                for (t in teleFrames) for (m in mainFrames) {
                    val dt = abs(t.ts - m.ts)
                    if (dt < best && teleBitmaps[t.ts] != null) { best = dt; pair = t to m; bmp = teleBitmaps[t.ts] }
                }
                if (best > 40_000_000L) pair = null // más de 40 ms de diferencia: no sirve
            }
            if (pair != null) { capturing = false; onPair = null; teleFrames.clear(); mainFrames.clear(); teleBitmaps.clear() }
        }
        val p = pair ?: return
        cbk(p.first, p.second, bmp!!)
    }

    /** Pide un par de cuadros; [done] corre en el hilo principal. */
    private fun capturePair(done: (Frame, Frame, Bitmap) -> Unit) {
        if (session == null) { toast("La cámara todavía no está lista"); return }
        synchronized(teleFrames) { teleFrames.clear(); mainFrames.clear(); teleBitmaps.clear() }
        captureStart = System.currentTimeMillis()
        onPair = { t, m, b -> main.post { done(t, m, b) } }
        capturing = true
        main.postDelayed({
            if (capturing) { capturing = false; onPair = null; toast("No se pudieron sincronizar los dos lentes. Probá de nuevo.") }
        }, 4000)
    }

    /** YUV → imagen en color, reducida [step] veces (para mostrar). */
    private fun yuvToBitmap(img: Image, step: Int): Bitmap {
        val w = img.width / step; val h = img.height / step
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val ys = yP.rowStride; val us = uP.rowStride; val ups = uP.pixelStride; val vs = vP.rowStride; val vps = vP.pixelStride
        val px = IntArray(w * h)
        for (j in 0 until h) {
            val sy = j * step
            for (i in 0 until w) {
                val sx = i * step
                val yv = (yb.get(sy * ys + sx).toInt() and 0xFF)
                val u = (ub.get((sy / 2) * us + (sx / 2) * ups).toInt() and 0xFF) - 128
                val v = (vb.get((sy / 2) * vs + (sx / 2) * vps).toInt() and 0xFF) - 128
                val r = (yv + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (yv - 0.344 * u - 0.714 * v).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * u).toInt().coerceIn(0, 255)
                px[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ geometría

    private fun lensOf(f: Frame, fMm: Double, sensorW: Double): Stereo.Lens {
        // focal en píxeles de esta imagen: focal (mm) ÷ ancho del sensor (mm) × ancho de la imagen
        val fpx = if (fMm > 0 && sensorW > 0) fMm / sensorW * f.w else f.w.toDouble()
        return Stereo.Lens(f.w, f.h, f.y, f.stride, fpx, f.w / 2.0, f.h / 2.0)
    }

    private fun loadCal() {
        infX = prefs.getFloat("infX_$key", 0f).toDouble(); infY = prefs.getFloat("infY_$key", 0f).toDouble()
        infCal = prefs.getBoolean("infCal_$key", false)
        baseCal = prefs.getBoolean("baseCal_$key", false)
        base = if (baseCal) prefs.getFloat("base_$key", 0.018f).toDouble() else if (!poseBase.isNaN()) poseBase else 0.018
    }

    private fun updateInfo() {
        if (logicalId == null || teleId.isEmpty()) return
        val s = StringBuilder()
        s.append("Tele (lente $teleId) ${teleSize.width}×${teleSize.height} · principal (lente $mainId) ${mainSize.width}×${mainSize.height} · separación ${fmt1(base * 100)} cm")
        s.append(if (baseCal) " (calibrada)" else if (!poseBase.isNaN()) " (informada por el celular)" else " (estimada)")
        if (!infCal) s.append("\n⚠ Falta calibrar: tocá Calibrar → \"Infinito\" una vez antes de medir.")
        else if (!frozen) s.append("\nApuntá y tocá Medir (quieto). Después tocá dos puntos con detalle: bordes o esquinas.")
        info.text = s.toString()
    }

    private fun fmt1(v: Double) = "%.1f".format(v)
    private fun fmtLen(m: Double) = if (m < 1) "%.1f cm".format(m * 100) else "%.2f m".format(m)

    // ------------------------------------------------------------------ medir

    private fun onMeasureButton() {
        if (frozen) { frozen = false; view.clearPoints(); measureBtn.text = "Medir"; lastLength = Double.NaN; updateInfo(); return }
        info.text = "Quieto…"
        capturePair { t, m, bmp ->
            tele = lensOf(t, fTeleMm, sensorWTele); mainL = lensOf(m, fMainMm, sensorWMain)
            frozen = true; measureBtn.text = "Nueva"
            view.setImage(bmp, t.w, t.h); view.clearPoints()
            info.text = "Tocá el primer punto (A) y después el segundo (B). Podés arrastrarlos para corregir." +
                if (!infCal) "\n⚠ Sin calibrar el infinito, las medidas pueden estar muy mal." else ""
        }
    }

    /** Lo llama la vista cuando se soltó un punto. */
    fun recompute() {
        val t = tele ?: return; val m = mainL ?: return
        val pts = view.points.toList()
        if (pts.isEmpty()) return
        info.text = "Calculando…"
        val ix = infX; val iy = infY; val b = base
        val radius = ((m.f * b / 0.25).roundToInt() + 16).coerceIn(32, 340) // hasta ~25 cm de cerca
        work.execute {
            val res = pts.map { p ->
                val mt = Stereo.match(t, m, p.x.toDouble(), p.y.toDouble(), ix, iy, radius)
                Triple(p, mt, mt?.let { Stereo.point(t, m, p.x.toDouble(), p.y.toDouble(), it, ix, iy, b) })
            }
            main.post { showResult(res) }
        }
    }

    private fun showResult(res: List<Triple<MeasureView.P, Stereo.Match?, Stereo.Point?>>) {
        val s = StringBuilder()
        val names = listOf("A", "B")
        val ok = ArrayList<Stereo.Point>()
        for ((i, r) in res.withIndex()) {
            val (_, mt, pt) = r
            s.append(names[i]).append(": ")
            when {
                mt == null || mt.mx.isNaN() -> s.append("sin detalle acá, elegí un borde o una esquina")
                pt == null -> s.append("demasiado lejos para medir")
                else -> {
                    s.append("a ${fmtLen(pt.z)} (±${fmtLen(pt.sigmaZ)})")
                    if (mt.score < 0.75) s.append(" ⚠ poco confiable")
                    ok += pt
                }
            }
            s.append("   ")
        }
        lastLength = Double.NaN
        if (ok.size == 2) {
            val l = Stereo.dist(ok[0], ok[1])
            val rel = max(ok[0].sigmaZ / ok[0].z, ok[1].sigmaZ / ok[1].z)
            lastLength = l
            s.append("\nEntre A y B: ${fmtLen(l)} (±${fmtLen(max(l * rel, 0.003))})")
            view.lengthLabel = fmtLen(l)
        } else view.lengthLabel = null
        if (!infCal) s.append("\n⚠ Falta calibrar el infinito.")
        info.text = s.toString()
        view.invalidate()
    }

    // ------------------------------------------------------------------ calibración

    private fun showCalibrate() {
        val items = arrayOf<CharSequence>(
            "1. Infinito (obligatorio, una vez): apuntá a algo a más de 50 m",
            "2. Con una medida conocida (mejora la precisión)",
            "Borrar calibración")
        AlertDialog.Builder(this).setTitle("Calibrar")
            .setItems(items) { _, w -> when (w) { 0 -> calibrateInfinity(); 1 -> calibrateKnown(); 2 -> clearCal() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun calibrateInfinity() {
        AlertDialog.Builder(this).setTitle("Calibrar el infinito")
            .setMessage("Apuntá por una ventana o al aire libre a algo bien lejano (edificios, árboles o cerros a más de 50 m), con detalle y sin nada cerca en el cuadro. Quedate quieto y tocá Listo.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Listo") { _, _ ->
                if (frozen) onMeasureButton()
                info.text = "Calibrando… quieto"
                capturePair { t, m, _ ->
                    val tl = lensOf(t, fTeleMm, sensorWTele); val ml = lensOf(m, fMainMm, sensorWMain)
                    work.execute {
                        val r = Stereo.calibrateInfinity(tl, ml, 0.0, 0.0, (ml.f * 0.06).roundToInt().coerceIn(60, 260))
                        main.post {
                            if (r == null) { toast("No encontré suficientes puntos con detalle. Probá con otra vista lejana."); updateInfo(); return@post }
                            infX = r.first; infY = r.second; infCal = true
                            prefs.edit().putFloat("infX_$key", infX.toFloat()).putFloat("infY_$key", infY.toFloat()).putBoolean("infCal_$key", true).apply()
                            toast("Infinito calibrado con ${r.third} puntos")
                            updateInfo()
                        }
                    }
                }
            }.show()
    }

    private fun calibrateKnown() {
        if (!infCal) { toast("Primero calibrá el infinito"); return }
        if (!frozen || lastLength.isNaN()) {
            AlertDialog.Builder(this).setTitle("Con una medida conocida")
                .setMessage("Medí algo cuyo largo sepas (por ejemplo una hoja A4: 29,7 cm, o una puerta), a 0,5–1,5 m: tocá Medir y marcá sus dos puntas. Después volvé a Calibrar → 2.")
                .setPositiveButton("Entendido", null).show()
            return
        }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; hint = "largo real en cm" }
        AlertDialog.Builder(this).setTitle("¿Cuánto mide de verdad?")
            .setMessage("La app midió ${fmtLen(lastLength)}. Escribí el largo real en centímetros:")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val real = input.text.toString().replace(',', '.').toDoubleOrNull()?.div(100)
                if (real == null || real <= 0) { toast("Número inválido"); return@setPositiveButton }
                // todas las distancias son proporcionales a la separación entre lentes
                base *= real / lastLength; baseCal = true
                prefs.edit().putFloat("base_$key", base.toFloat()).putBoolean("baseCal_$key", true).apply()
                toast("Separación ajustada a ${fmt1(base * 100)} cm")
                recompute(); updateInfo()
            }.show()
    }

    private fun clearCal() {
        prefs.edit().remove("infX_$key").remove("infY_$key").remove("infCal_$key").remove("base_$key").remove("baseCal_$key").apply()
        loadCal(); updateInfo(); toast("Calibración borrada")
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Cómo medir")
            .setMessage("El principal y el tele ven lo mismo desde lugares un poco distintos (separados ~1–2 cm). Cuanto más cerca está algo, más se corre entre las dos imágenes: con eso se calcula la distancia.\n\n" +
                "1. Una sola vez: Calibrar → Infinito, apuntando a algo muy lejano.\n" +
                "2. Opcional: Calibrar → medida conocida (una hoja A4) para afinar.\n" +
                "3. Apuntá, tocá Medir quieto y marcá dos puntos con detalle (bordes, esquinas, dibujos). En zonas lisas no se puede.\n\n" +
                "Funciona mejor de 0,3 a 3 m y en el centro de la imagen. El error crece con la distancia: a 1 m es de pocos milímetros, a 3 m de algunos centímetros. Lo que se ve es el encuadre del tele, así que para cosas grandes hay que alejarse.")
            .setPositiveButton("Entendido", null).show()
    }

    // ------------------------------------------------------------------ vista

    /** Imagen del tele (en vivo o congelada) con los puntos A y B y una lupa al arrastrar. */
    inner class MeasureView(ctx: Context) : View(ctx) {
        inner class P(var x: Float, var y: Float)
        val points = ArrayList<P>()
        var lengthLabel: String? = null
        private var bmp: Bitmap? = null
        private var imgW = 1; private var imgH = 1
        private val dst = RectF()
        private var drag: P? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dpf(16f); isFakeBoldText = true }

        fun setImage(b: Bitmap, w: Int, h: Int) { bmp = b; imgW = w; imgH = h; invalidate() }
        fun clearPoints() { points.clear(); lengthLabel = null; invalidate() }

        private fun layoutDst() {
            val s = min(width.toFloat() / imgW, height.toFloat() / imgH)
            val w = imgW * s; val h = imgH * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }
        private fun toScreen(p: P) = Pair(dst.left + p.x / imgW * dst.width(), dst.top + p.y / imgH * dst.height())
        private fun toImage(x: Float, y: Float) = P(((x - dst.left) / dst.width() * imgW).coerceIn(0f, imgW - 1f), ((y - dst.top) / dst.height() * imgH).coerceIn(0f, imgH - 1f))

        override fun onDraw(c: Canvas) {
            layoutDst()
            bmp?.let { c.drawBitmap(it, null, dst, null) }
            // mira en el centro (la zona más precisa)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(1f); paint.color = 0x66FFFFFF
            val cx = dst.centerX(); val cy = dst.centerY()
            c.drawLine(cx - dpf(14f), cy, cx + dpf(14f), cy, paint); c.drawLine(cx, cy - dpf(14f), cx, cy + dpf(14f), paint)
            if (points.size == 2) {
                val (ax, ay) = toScreen(points[0]); val (bx, by) = toScreen(points[1])
                paint.color = ACCENT; paint.strokeWidth = dpf(2.5f); c.drawLine(ax, ay, bx, by, paint)
                lengthLabel?.let {
                    val mx = (ax + bx) / 2; val my = (ay + by) / 2
                    val w = text.measureText(it)
                    paint.style = Paint.Style.FILL; paint.color = 0xCC000000.toInt()
                    c.drawRoundRect(mx - w / 2 - dpf(8f), my - dpf(30f), mx + w / 2 + dpf(8f), my - dpf(4f), dpf(8f), dpf(8f), paint)
                    text.textAlign = Paint.Align.CENTER; c.drawText(it, mx, my - dpf(11f), text)
                }
            }
            for ((i, p) in points.withIndex()) {
                val (x, y) = toScreen(p)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(2f); paint.color = Color.BLACK; c.drawCircle(x, y, dpf(11f), paint)
                paint.color = ACCENT; paint.strokeWidth = dpf(1.5f); c.drawCircle(x, y, dpf(10f), paint)
                c.drawLine(x - dpf(4f), y, x + dpf(4f), y, paint); c.drawLine(x, y - dpf(4f), x, y + dpf(4f), paint)
                text.textAlign = Paint.Align.LEFT; c.drawText(if (i == 0) "A" else "B", x + dpf(13f), y - dpf(10f), text)
            }
            // lupa mientras se arrastra
            val d = drag; val b = bmp
            if (d != null && b != null) {
                val r = dpf(60f); val zoom = 4f
                val (x, y) = toScreen(d)
                val lx = if (x < width / 2) width - r - dpf(10f) else r + dpf(10f); val ly = r + dpf(10f)
                c.save()
                val path = android.graphics.Path().apply { addCircle(lx, ly, r, android.graphics.Path.Direction.CW) }
                c.clipPath(path)
                val sx = d.x / imgW * b.width; val sy = d.y / imgH * b.height
                val scale = dst.width() / b.width * zoom
                c.translate(lx - sx * scale, ly - sy * scale); c.scale(scale, scale)
                c.drawBitmap(b, 0f, 0f, null)
                c.restore()
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = dpf(2f); c.drawCircle(lx, ly, r, paint)
                paint.color = ACCENT; c.drawLine(lx - dpf(8f), ly, lx + dpf(8f), ly, paint); c.drawLine(lx, ly - dpf(8f), lx, ly + dpf(8f), paint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!frozen) return true
            layoutDst()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val near = points.minByOrNull { val (x, y) = toScreen(it); hypot(x - e.x, y - e.y) }
                    drag = if (near != null && toScreen(near).let { hypot(it.first - e.x, it.second - e.y) } < dpf(40f)) near
                    else if (points.size < 2) toImage(e.x, e.y).also { points += it }
                    else near
                    drag?.let { val q = toImage(e.x, e.y); it.x = q.x; it.y = q.y }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> { drag?.let { val q = toImage(e.x, e.y); it.x = q.x; it.y = q.y; invalidate() } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = null; invalidate(); recompute() }
            }
            return true
        }
    }
}
