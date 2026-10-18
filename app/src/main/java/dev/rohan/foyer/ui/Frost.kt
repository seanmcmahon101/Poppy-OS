package dev.rohan.foyer.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Frosted glass without live blur (the Mali-G52 can't afford RenderEffect): the wallpaper is
 * shrunk and box-blurred once, then each glass surface draws its own slice of that tiny copy,
 * scaled back up. Costs one ~160x90 software bitmap. Proven in the design-phase renders.
 * Feed it a small software decode of the wallpaper (inSampleSize), never a HARDWARE bitmap.
 */
fun frostedCopy(src: Bitmap, targetWidth: Int = 160, radius: Int = 2, passes: Int = 3): Bitmap {
    val w = targetWidth.coerceAtMost(src.width).coerceAtLeast(1)
    val h = (src.height * w / src.width).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(px.size)
    repeat(passes) {
        blurPass(px, tmp, w, h, radius, horizontal = true)
        blurPass(tmp, px, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) line * w + c else c * w + line
        }
        var a = 0; var red = 0; var g = 0; var b = 0
        for (i in -r..r) {
            val c = src[at(i)]
            a += c ushr 24; red += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        for (i in 0 until len) {
            dst[at(i)] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
            val out = src[at(i - r)]
            val inn = src[at(i + r + 1)]
            a += (inn ushr 24) - (out ushr 24)
            red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

class Backdrop(val frosted: ImageBitmap, val screen: IntSize)

/** Glass when a backdrop exists; a plain dark tint before the first photo has decoded. */
fun Modifier.glass(backdrop: Backdrop?, shape: Shape, tint: Color): Modifier =
    if (backdrop != null) frosted(backdrop, shape, tint)
    else clip(shape).drawBehind { drawRect(Ink.Shadow.copy(alpha = 0.55f)); drawRect(tint) }


/** Paints this element with the frosted wallpaper that sits behind it, then a tint. */
fun Modifier.frosted(backdrop: Backdrop, shape: Shape, tint: Color): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            drawImage(
                image = backdrop.frosted,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(backdrop.frosted.width, backdrop.frosted.height),
                dstOffset = IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()),
                dstSize = backdrop.screen,
                filterQuality = FilterQuality.Low,
                colorFilter = glassFilter(tint), // tone, 10% darkening and the tint, in one pass
            )
        }
}
