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

    // Nombres en castellano (rioplatense) con un color de referencia
    private val NAMES = listOf(
        "Blanco" to 0xFFFFFF, "Blanco hueso" to 0xF2EBDD, "Marfil" to 0xFFF8E1, "Crema" to 0xF5E6C8,
        "Gris muy claro" to 0xDDDDDD, "Gris perla" to 0xC8C8C8, "Gris" to 0x9E9E9E, "Gris topo" to 0x8B8378,
        "Gris oscuro" to 0x5A5A5A, "Gris grafito" to 0x3A3D40, "Negro" to 0x111111,
        "Rojo" to 0xD32F2F, "Rojo oscuro" to 0x8E1B1B, "Bordó" to 0x6D1A2A, "Carmín" to 0xB71C3B, "Coral" to 0xFF7F66,
        "Salmón" to 0xF4A28C, "Rosa" to 0xF48FB1, "Rosa claro" to 0xF8C8D8, "Rosa viejo" to 0xC0868F, "Fucsia" to 0xE91E8C,
        "Magenta" to 0xD81B9A, "Lila" to 0xC8A2D8, "Lavanda" to 0xD6CCF0, "Violeta" to 0x7E57C2, "Púrpura" to 0x5E2A84, "Berenjena" to 0x3E1F47,
        "Azul" to 0x1E63C8, "Azul marino" to 0x1A2A55, "Azul oscuro" to 0x0D2C6B, "Azul francia" to 0x2F5FD0, "Celeste" to 0x7EC8F0,
        "Celeste claro" to 0xC4E4F7, "Azul petróleo" to 0x1F5662, "Turquesa" to 0x26B5B0, "Aguamarina" to 0x7FD8C8, "Cian" to 0x00BCD4,
        "Verde" to 0x2E9E48, "Verde oscuro" to 0x1B5E20, "Verde inglés" to 0x23402E, "Verde oliva" to 0x6B7A2A, "Verde musgo" to 0x5D6B3A,
        "Verde lima" to 0xA5D63A, "Verde manzana" to 0x7CC242, "Verde agua" to 0xA8DCC8, "Verde menta" to 0x98E0B8, "Verde salvia" to 0x9CAF88,
        "Amarillo" to 0xF7D53A, "Amarillo patito" to 0xFFE873, "Amarillo pastel" to 0xFAEDA6, "Mostaza" to 0xC9A227, "Ocre" to 0xC08A2E,
        "Dorado" to 0xC8A04A, "Naranja" to 0xF57C1F, "Naranja claro" to 0xFFB067, "Terracota" to 0xC25B3A, "Ladrillo" to 0x9C4A33,
        "Durazno" to 0xF9C7A1, "Beige" to 0xE3D3B4, "Arena" to 0xD6C29A, "Camel" to 0xB98A55, "Habano" to 0x8F6A44,
        "Marrón" to 0x6D4C33, "Marrón chocolate" to 0x4A2E22, "Caoba" to 0x6E2F22, "Madera clara" to 0xC9A57A, "Madera" to 0x9C6F44,
        "Cobre" to 0xB06A3B, "Plateado" to 0xB8BCC0
    )

    fun name(c: Int): String {
        var best = ""; var bd = Double.MAX_VALUE
        for ((n, v) in NAMES) { val d = deltaE(c, v or (0xFF shl 24)); if (d < bd) { bd = d; best = n } }
        return best
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
