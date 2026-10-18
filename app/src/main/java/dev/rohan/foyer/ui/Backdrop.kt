package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.rohan.foyer.Wall

/** Top scrim strength, shared with the baked copy (data/Images.bakeScrims). */
const val TOP_SCRIM = 0.45f

private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** Crossfades in 1/48 steps: ~27 repaints over a 1.8 s fade instead of 90. */
private fun stepAlpha(a: Float): Float = if (a >= 1f) 1f else Math.round(a * 48f) / 48f

/**
 * The photo: crossfades between photos and sharpens from the frosted copy when the full bitmap
 * arrives after a trim. It holds still: upstream's slow drift is gone (Sean wants a solid picture),
 * so between crossfades nothing here ever repaints.
 *
 * Fill rate is the budget on this GPU (~5 ms per full-screen pass at 1080p), so:
 * - the full photo already has the mockup's scrims baked in (data/Images.wall), one pass not four;
 * - [covered] (px from the bottom, screen space) is hidden under an opaque glass sheet, so it isn't drawn;
 * - crossfades fade each draw (ModulateAlpha) rather than going through an offscreen buffer.
 * Animated values are read only in graphicsLayer / draw lambdas: nothing recomposes per frame.
 */
@Composable
fun PhotoLayer(wall: Wall?, covered: () -> Float, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<Wall?>(null) }
    var previous by remember { mutableStateOf<Wall?>(null) }
    val fade = remember { Animatable(1f) }
    val sharpen = remember { Animatable(1f) }
    // Readers subscribe to the stepped values, so a frame where the step didn't change invalidates nothing.
    val shownFade by remember { derivedStateOf { stepAlpha(fade.value) } }
    val shownSharp by remember { derivedStateOf { stepAlpha(sharpen.value) } }

    LaunchedEffect(wall) {
        val w = wall ?: return@LaunchedEffect
        val cur = current
        if (cur == null || cur.photo.id != w.photo.id) {
            previous = cur
            current = w
            sharpen.snapTo(if (w.full != null) 1f else 0f)
            if (w.fadeMs <= 0) fade.snapTo(1f) else {
                fade.snapTo(0f)
                fade.animateTo(1f, tween(w.fadeMs, easing = CssEase))
            }
            previous = null
        } else {
            val hadFull = cur.full != null
            current = w
            when {
                w.full == null -> sharpen.snapTo(0f)
                !hadFull -> { sharpen.snapTo(0f); sharpen.animateTo(1f, tween(w.fadeMs.coerceAtLeast(1), easing = CssEase)) }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        previous?.let { p ->
            Spacer(
                Modifier.fillMaxSize()
                    .graphicsLayer()
                    .drawWithCache {
                        val scrims = scrimBrushes(size, density)
                        onDrawBehind { visiblePart(1f, covered()) { drawWall(p, 1f, scrims) } }
                    }
            )
        }
        current?.let { c ->
            Spacer(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = shownFade
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache {
                        val scrims = scrimBrushes(size, density)
                        onDrawBehind { visiblePart(1f, covered()) { drawWall(c, shownSharp, scrims) } }
                    }
            )
        }
    }
}

/** Clips to the part of the screen above [coveredPx], mapped into this layer's scaled space. */
private inline fun DrawScope.visiblePart(layerScale: Float, coveredPx: Float, block: DrawScope.() -> Unit) {
    if (coveredPx <= 0.5f) { block(); return }
    if (coveredPx >= size.height) return
    val cy = size.height / 2
    val bottomScreen = size.height - coveredPx
    val bottomLocal = cy + (bottomScreen - cy) / layerScale + 2f // a hair of overlap: no seam at the sheet edge
    clipRect(bottom = bottomLocal.coerceAtMost(size.height), block = block)
}

private fun DrawScope.drawWall(w: Wall, sharp: Float, scrims: Scrims) {
    val full = w.full
    if (full == null || sharp < 1f) {
        // The frosted stand-in has no scrims baked in: draw them live (only for the ~300 ms after a trim).
        fillWith(w.frost, 1f)
        scrims.draw(this)
    }
    if (full != null && sharp > 0f) fillWith(full, sharp)
}

private fun DrawScope.fillWith(img: ImageBitmap, alpha: Float) {
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
        colorFilter = tone(1f), // night warmth rides on the photo draw itself
    )
}

/** The two scrims as live brushes (same geometry as data/Images.bakeScrims). */
private class Scrims(val top: Brush, val bottom: Brush) {
    fun draw(d: DrawScope) = with(d) {
        val h = size.height
        drawRect(top, size = size.copy(height = h * 0.24f))
        drawRect(bottom, topLeft = Offset(0f, h * 0.44f), size = size.copy(height = h * 0.56f))
    }
}

private fun scrimBrushes(size: Size, density: Float): Scrims {
    val s = Ink.Shadow
    val h = size.height
    return Scrims(
        top = Brush.verticalGradient(0f to s.copy(alpha = TOP_SCRIM), 1f to s.copy(alpha = 0f), startY = 0f, endY = h * 0.24f),
        bottom = Brush.verticalGradient(
            0f to s.copy(alpha = 0f), (0.26f / 0.56f) to s.copy(alpha = 0.42f), 1f to s.copy(alpha = 0.82f),
            startY = h * 0.44f, endY = h,
        ),
    )
}
