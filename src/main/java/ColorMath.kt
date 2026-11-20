package com.rdelbene.taller

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* Cuentas de color: sRGB ↔ lineal, HSL, Lab, nombre más parecido y colores que combinan. Sin nada de Android. */
object ColorMath {

    fun lin(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    fun enc(v: Double): Double { val c = v.coerceIn(0.0, 1.0); return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055 }

    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun hex(c: Int) = "#%02X%02X%02X".format(r(c), g(c), b(c))

    fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toIntOrNull(16)?.let { it or (0xFF shl 24) }
    }

    /** Matiz (0–360), saturación y luminosidad (0–1). */
    fun hsl(c: Int): DoubleArray {
        val r = r(c) / 255.0; val g = g(c) / 255.0; val b = b(c) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        if (h >= 360) h -= 360
        return doubleArrayOf(h, s, l)
    }

    fun fromHsl(h0: Double, s: Double, l: Double): Int {
        val h = ((h0 % 360) + 360) % 360 / 360
        if (s <= 0) { val v = (l * 255).roundToInt(); return rgb(v, v, v) }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
            return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
        }
        return rgb((hue(h + 1.0 / 3) * 255).roundToInt(), (hue(h) * 255).roundToInt(), (hue(h - 1.0 / 3) * 255).roundToInt())
    }

    fun lab(c: Int): DoubleArray {
        val rl = lin(r(c) / 255.0); val gl = lin(g(c) / 255.0); val bl = lin(b(c) / 255.0)
        val x = (0.4124 * rl + 0.3576 * gl + 0.1805 * bl) / 0.95047
        val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
        val z = (0.0193 * rl + 0.1192 * gl + 0.9505 * bl) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun deltaE(a: Int, b: Int): Double {
        val p = lab(a); val q = lab(b)
        return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
    }

    /** Oklab en forma polar: luminosidad (0–100), croma (×100) y tono (0–360°). Más parejo a la vista que Lab. */
    fun oklch(c: Int): DoubleArray {
        val r = lin(r(c) / 255.0); val g = lin(g(c) / 255.0); val b = lin(b(c) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        var h = Math.toDegrees(kotlin.math.atan2(B, A)); if (h < 0) h += 360
        return doubleArrayOf(L * 100, sqrt(A * A + B * B) * 100, h)
    }

    /**
     * Nombre del color: familia según el tono, y "claro/oscuro/intenso/apagado" según luminosidad y croma.
     * Los casi grises se nombran como grises con su tinte ("Gris azulado"), que es lo que son muchas paredes y telas.
     * (Antes se buscaba el más parecido en una lista fija, y como la lista tenía muchos grises, casi todo salía gris.)
     */
    fun name(c: Int): String {
        val (L, C, h) = oklch(c).let { Triple(it[0], it[1], it[2]) }
        if (C < 1.5) return when { L > 95 -> "Blanco"; L > 82 -> "Gris muy claro"; L > 65 -> "Gris claro"; L > 50 -> "Gris"; L > 30 -> "Gris oscuro"; else -> "Negro" }
        val lm = if (L > 75) " claro" else if (L < 40) " oscuro" else ""
        if (C < 4) {
            val t = when { h >= 345 || h < 45 -> "rosado"; h < 105 -> "cálido"; h < 200 -> "verdoso"; h < 285 -> "azulado"; else -> "violáceo" }
            return when {
                L > 92 -> if (t == "cálido") "Blanco roto" else "Blanco $t"
                L < 25 -> "Negro $t"
                t == "cálido" && L > 80 -> "Beige grisáceo"
                else -> "Gris $t$lm"
            }
        }
        // generic = admite "claro/oscuro/intenso/apagado"; los nombres específicos ya lo dicen
        var generic = true
        val base = when {
            h >= 345 || h < 20 -> { generic = false; if (L > 72) "Rosa" else if (L < 45) "Bordó" else if (C > 15) "Fucsia" else "Rosa viejo" }
            h < 45 -> when {
                L < 45 -> { generic = false; "Bordó" }
                L > 75 -> { generic = false; if (C < 12) "Salmón" else "Coral" }
                C < 12 -> { generic = false; "Terracota" }
                else -> "Rojo"
            }
            h < 75 -> when {
                L < 50 || (L < 62 && C < 9) -> "Marrón"
                L < 70 && C < 10 -> { generic = false; "Marrón claro" }
                L > 85 -> { generic = false; "Beige" }
                L > 74 && C < 10 -> { generic = false; "Durazno" }
                else -> "Naranja"
            }
            h < 105 -> when {
                L < 60 -> { generic = false; "Verde oliva" }
                L > 84 && C < 7 -> { generic = false; "Beige" }
                L < 82 && C > 9 -> { generic = false; "Mostaza" }
                else -> "Amarillo"
            }
            h < 135 -> { generic = false; if (L < 60) "Verde oliva" else if (C < 8) "Verde salvia" else "Verde lima" }
            h < 170 -> when {
                C < 8 && L > 65 -> { generic = false; "Verde salvia" }
                C < 8 -> { generic = false; "Verde musgo" }
                else -> "Verde"
            }
            h < 220 -> when {
                L > 80 -> { generic = false; "Verde agua" }
                L < 50 -> { generic = false; "Azul petróleo" }
                else -> "Turquesa"
            }
            h < 245 -> when {
                L > 65 -> "Celeste"
                L < 50 -> { generic = false; "Azul petróleo" }
                else -> { generic = false; "Azul acero" }
            }
            h < 285 -> when {
                L > 72 -> "Celeste"
                L < 35 -> { generic = false; "Azul marino" }
                C < 7 -> { generic = false; "Azul grisáceo" }
                else -> "Azul"
            }
            h < 320 -> if (L > 70) { generic = false; "Lila" } else if (L < 45) { generic = false; "Púrpura" } else "Violeta"
            else -> if (L > 72) { generic = false; "Rosa" } else "Magenta"
        }
        if (!generic) return base
        val cm = if (C > 25) " intenso" else if (C < 7) " apagado" else ""
        return base + cm + (if (base == "Celeste") "" else lm)
    }

    /** Colores que combinan, con su nombre de esquema. */
    fun schemes(c: Int): List<Pair<String, List<Int>>> {
        val (h, s0, l0) = hsl(c).let { Triple(it[0], it[1], it[2]) }
        // con colores casi grises los esquemas por matiz no dicen nada: se usa un acento suave
        val s = if (s0 < 0.08) 0.35 else s0
        val l = l0.coerceIn(0.25, 0.75)
        return listOf(
            "Complementario" to listOf(c, fromHsl(h + 180, s, l)),
            "Análogos" to listOf(fromHsl(h - 30, s, l), c, fromHsl(h + 30, s, l)),
            "Tríada" to listOf(c, fromHsl(h + 120, s, l), fromHsl(h + 240, s, l)),
            "Complementario dividido" to listOf(c, fromHsl(h + 150, s, l), fromHsl(h + 210, s, l)),
            "Tonos del mismo color" to listOf(fromHsl(h, s0, (l0 + 0.30).coerceAtMost(0.95)), fromHsl(h, s0, (l0 + 0.15).coerceAtMost(0.92)), c,
                fromHsl(h, s0, (l0 - 0.15).coerceAtLeast(0.06)), fromHsl(h, s0, (l0 - 0.30).coerceAtLeast(0.04)))
        )
    }

    /** Texto blanco o negro según qué se lea mejor encima del color. */
    fun onColor(c: Int): Int = if (lab(c)[0] > 62) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    fun describe(c: Int): String {
        val h = hsl(c)
        return "RGB ${r(c)}, ${g(c)}, ${b(c)}  ·  HSL ${h[0].roundToInt()}°, ${(h[1] * 100).roundToInt()}%, ${(h[2] * 100).roundToInt()}%"
    }

    @Suppress("unused")
    fun near(a: Double, b: Double, eps: Double) = abs(a - b) < eps
}
