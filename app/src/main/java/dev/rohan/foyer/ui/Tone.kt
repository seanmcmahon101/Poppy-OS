package dev.rohan.foyer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * Night warmth, folded into the colour filters the photo, glass and tiles already draw with,
 * instead of full-screen Multiply and dim layers: on a Mali-G52 every full-screen pass at 1080p
 * costs frames. Same look on the photo (ember multiply, then brightness .8), zero extra fill.
 */
object Warmth {
    /** 0 = day, 1 = full night warmth. Animated by FoyerScreen; read only in draw lambdas. */
    var amount by mutableFloatStateOf(0f)
}

private val toneCache = HashMap<Int, ColorFilter?>()
private val glassCache = HashMap<Long, ColorFilter>()

/** Saturation [si]/100 with the night warmth step [wi]/40 applied to the output channels. 4x5, offsets in 0..255. */
private fun toneValues(si: Int, wi: Int): FloatArray {
    val v = ColorMatrix().apply { setToSaturation(si / 100f) }.values.copyOf()
    if (wi > 0) {
        val t = wi / 40f
        val e = floatArrayOf(Ink.Ember.red, Ink.Ember.green, Ink.Ember.blue)
        for (row in 0..2) {
            val k = 1f + (e[row] * 0.8f - 1f) * t // ember multiply, then brightness .8
            for (col in 0..4) v[row * 5 + col] *= k
        }
    }
    return v
}

private fun steps(s: Float, warmth: Float) =
    (s * 100).roundToInt().coerceIn(0, 200) to (warmth * 40).roundToInt().coerceIn(0, 40)

/** Saturation [s] (1 = unchanged) plus the current night warmth. Cached in small steps: no per-frame allocation. */
fun tone(s: Float, warmth: Float = Warmth.amount): ColorFilter? {
    val (si, wi) = steps(s, warmth)
    if (si == 100 && wi == 0) return null
    return toneCache.getOrPut(si * 100 + wi) { ColorFilter.colorMatrix(ColorMatrix(toneValues(si, wi))) }
}

/**
 * Glass in one pass: the frosted image toned (saturation 1.1 + warmth), darkened 10% for legibility,
 * then [tint] laid over it. Both overlays are linear, so they fold into the colour matrix instead
 * of costing two more fills.
 */
fun glassFilter(tint: Color, warmth: Float = Warmth.amount): ColorFilter {
    val (si, wi) = steps(1.1f, warmth)
    val key = (tint.toArgb().toLong() shl 8) or wi.toLong()
    return glassCache.getOrPut(key) {
        val v = toneValues(si, wi)
        val a = tint.alpha
        val k = 0.9f * (1f - a)
        val c = floatArrayOf(tint.red, tint.green, tint.blue)
        for (row in 0..2) {
            for (col in 0..4) v[row * 5 + col] *= k
            v[row * 5 + 4] += c[row] * a * 255f
        }
        ColorFilter.colorMatrix(ColorMatrix(v))
    }
}
