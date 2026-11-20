package com.rdelbene.taller

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * Medición con dos lentes (estéreo), sin nada de Android para poder probarla aparte.
 *
 * Cada lente ve el mismo punto un poco corrido: cuanto más cerca está, más se corre. En coordenadas
 * "normalizadas" (posición en píxeles menos el centro, dividido por la distancia focal en píxeles) ese corrimiento
 * es separación_entre_lentes ÷ distancia, más un corrimiento fijo que existe aunque el objeto esté en el infinito
 * (los lentes nunca están perfectamente paralelos y sus centros no coinciden). Ese fijo se mide una vez apuntando
 * a algo lejano ("calibrar infinito"). La separación se puede corregir con una medida conocida.
 *
 * Referencia: el tele (más detalle por grado). El punto se busca en el principal con correlación normalizada
 * de una ventana chica, en la escala del principal, y se afina por debajo del píxel con una parábola.
 */
object Stereo {

    /** Imagen de luminancia (0–255) de un lente, con su focal y centro en píxeles de esa imagen. */
    class Lens(val w: Int, val h: Int, val y: ByteArray, val rowStride: Int, val f: Double, val cx: Double, val cy: Double) {
        fun at(x: Int, yy: Int): Int = y[yy * rowStride + x].toInt() and 0xFF
        fun bil(x: Double, yy: Double): Double {
            if (x < 0 || yy < 0 || x >= w - 1 || yy >= h - 1) return Double.NaN
            val x0 = x.toInt(); val y0 = yy.toInt(); val fx = x - x0; val fy = yy - y0
            val a = at(x0, y0); val b = at(x0 + 1, y0); val c = at(x0, y0 + 1); val d = at(x0 + 1, y0 + 1)
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }
    }

    class Match(val mx: Double, val my: Double, val score: Double, val texture: Double)

    const val HALF = 7 // ventana de 15×15 píxeles del principal

    /**
     * Busca en el principal el punto (tx, ty) del tele. La búsqueda se centra donde caería si estuviera en el infinito
     * (corrimiento fijo dx∞, dy∞) y abarca ±[radius] píxeles del principal.
     */
    fun match(tele: Lens, mainL: Lens, tx: Double, ty: Double, infX: Double, infY: Double, radius: Int): Match? {
        val k = tele.f / mainL.f // píxeles del tele por píxel del principal (~3)
        val n = 2 * HALF + 1
        val tpl = DoubleArray(n * n)
        // plantilla: el tele llevado a la escala del principal (promedio de 3×3 muestras por celda, para no perder detalle de golpe)
        var sum = 0.0
        for (j in 0 until n) for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (sj in -1..1) for (si in -1..1) {
                val v = tele.bil(tx + (i - HALF + si / 3.0) * k, ty + (j - HALF + sj / 3.0) * k)
                if (!v.isNaN()) { s += v; c++ }
            }
            if (c == 0) return null
            tpl[j * n + i] = s / c; sum += s / c
        }
        val mean = sum / (n * n)
        var vt = 0.0
        for (q in tpl.indices) { tpl[q] -= mean; vt += tpl[q] * tpl[q] }
        val texture = sqrt(vt / (n * n))
        if (texture < 2.0) return Match(Double.NaN, Double.NaN, 0.0, texture) // zona lisa: no hay de dónde agarrarse

        val ux = (tx - tele.cx) / tele.f + infX
        val uy = (ty - tele.cy) / tele.f + infY
        val px = (mainL.cx + ux * mainL.f).toInt()
        val py = (mainL.cy + uy * mainL.f).toInt()
        val w2 = 2 * radius + 1
        val score = DoubleArray(w2 * w2) { -2.0 }
        var best = -2.0; var bi = -1; var bj = -1
        for (dj in -radius..radius) {
            val cyy = py + dj
            if (cyy - HALF < 0 || cyy + HALF >= mainL.h) continue
            for (di in -radius..radius) {
                val cxx = px + di
                if (cxx - HALF < 0 || cxx + HALF >= mainL.w) continue
                var sm = 0.0; var smm = 0.0; var stm = 0.0
                for (j in 0 until n) {
                    val row = (cyy - HALF + j) * mainL.rowStride + cxx - HALF
                    for (i in 0 until n) {
                        val v = (mainL.y[row + i].toInt() and 0xFF).toDouble()
                        sm += v; smm += v * v; stm += tpl[j * n + i] * v
                    }
                }
                val varM = smm - sm * sm / (n * n)
                if (varM <= 1e-6) continue
                val r = stm / sqrt(vt * varM)
                score[(dj + radius) * w2 + di + radius] = r
                if (r > best) { best = r; bi = di; bj = dj }
            }
        }
        if (bi == -1) return null
        // parábola en x y en y alrededor del máximo
        fun sc(i: Int, j: Int): Double {
            if (i < -radius || i > radius || j < -radius || j > radius) return Double.NaN
            return score[(j + radius) * w2 + i + radius]
        }
        fun peak(a: Double, b: Double, c: Double): Double {
            if (a.isNaN() || c.isNaN() || a < -1.5 || c < -1.5) return 0.0
            val den = a - 2 * b + c
            return if (abs(den) < 1e-9) 0.0 else (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        }
        val ox = peak(sc(bi - 1, bj), best, sc(bi + 1, bj))
        val oy = peak(sc(bi, bj - 1), best, sc(bi, bj + 1))
        return Match(px + bi + ox, py + bj + oy, best, texture)
    }

    /** Punto 3D (en metros, desde el tele) y su error aproximado de distancia. */
    class Point(val x: Double, val y: Double, val z: Double, val sigmaZ: Double, val score: Double)

    /**
     * Distancia de un punto del tele a partir de su pareja en el principal.
     * [base] = separación entre lentes (m). [pixErr] = error típico de la búsqueda, en píxeles del principal.
     */
    fun point(tele: Lens, mainL: Lens, tx: Double, ty: Double, m: Match, infX: Double, infY: Double, base: Double, pixErr: Double = 0.3): Point? {
        if (m.mx.isNaN()) return null
        val ux = (tx - tele.cx) / tele.f; val uy = (ty - tele.cy) / tele.f
        val vx = (m.mx - mainL.cx) / mainL.f; val vy = (m.my - mainL.cy) / mainL.f
        val d = hypot(vx - ux - infX, vy - uy - infY) // corrimiento por estar cerca
        if (d < 1e-6) return null
        val z = base / d
        val sigma = z * z * (pixErr / mainL.f) / base
        return Point(ux * z, uy * z, z, sigma, m.score)
    }

    fun dist(a: Point, b: Point) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    /**
     * Corrimiento fijo (infinito): se buscan puntos con textura en una grilla del tele, cada uno con una búsqueda amplia,
     * y se toma la mediana. Devuelve (dx∞, dy∞, puntos usados) o null.
     */
    fun calibrateInfinity(tele: Lens, mainL: Lens, startX: Double, startY: Double, radius: Int): Triple<Double, Double, Int>? {
        // 1) un punto con textura cerca del centro, con búsqueda amplia: da el corrimiento aproximado
        var sx = Double.NaN; var sy = Double.NaN
        loop@ for (r in listOf(0.0, 0.08, 0.16)) for ((ax, ay) in listOf(0.0 to 0.0, 1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)) {
            if (r == 0.0 && (ax != 0.0 || ay != 0.0)) continue
            val tx = tele.w * (0.5 + ax * r); val ty = tele.h * (0.5 + ay * r)
            val m = match(tele, mainL, tx, ty, startX, startY, radius) ?: continue
            if (m.mx.isNaN() || m.score < 0.85) continue
            sx = (m.mx - mainL.cx) / mainL.f - (tx - tele.cx) / tele.f
            sy = (m.my - mainL.cy) / mainL.f - (ty - tele.cy) / tele.f
            break@loop
        }
        if (sx.isNaN()) return null
        // 2) una grilla de puntos con búsqueda corta alrededor de ese corrimiento; se toma la mediana
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (gy in 1..5) for (gx in 1..7) {
            val tx = tele.w * (0.15 + 0.7 * (gx - 1) / 6.0)
            val ty = tele.h * (0.15 + 0.7 * (gy - 1) / 4.0)
            val m = match(tele, mainL, tx, ty, sx, sy, 24) ?: continue
            if (m.mx.isNaN() || m.score < 0.8) continue
            xs += (m.mx - mainL.cx) / mainL.f - (tx - tele.cx) / tele.f
            ys += (m.my - mainL.cy) / mainL.f - (ty - tele.cy) / tele.f
        }
        if (xs.size < 5) return null
        xs.sort(); ys.sort()
        return Triple(xs[xs.size / 2], ys[ys.size / 2], xs.size)
    }
}
