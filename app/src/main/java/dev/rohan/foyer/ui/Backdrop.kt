package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.rohan.foyer.Wall
import kotlinx.coroutines.launch

private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * The drift moves the screen edges ~3 px a second, so most frames would change nothing visible yet
 * still repaint all 1080p (~20 ms on this GPU). Quantised so a new value lands only when the edge
 * has moved ~0.4 px: frames in between have no damage and HWUI skips them.
 */
private fun stepScale(s: Float): Float = Math.round(s * 2400f) / 2400f

/** Crossfades in 1/48 steps: ~27 repaints over a 1.8 s fade instead of 90. */
private fun stepAlpha(a: Float): Float = if (a >= 1f) 1f else Math.round(a * 48f) / 48f
private const val DRIFT_FROM = 1.07f
private const val DRIFT_MS = 26_000

/**
 * The photo: crossfades between photos, drifts from 1.07 to 1.0 over 26 s then holds still, and
 * sharpens from the frosted copy when the full bitmap arrives after a trim.
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
    var previousScale by remember { mutableFloatStateOf(1f) }
    val fade = remember { Animatable(1f) }
    val sharpen = remember { Animatable(1f) }
    val drift = remember { Animatable(1f) }
    val driftScope = rememberCoroutineScope()
    // Readers subscribe to the stepped values, so a frame where the step didn't change invalidates nothing.
    val shownScale by remember { derivedStateOf { stepScale(drift.value) } }
    val shownFade by remember { derivedStateOf { stepAlpha(fade.value) } }
    val shownSharp by remember { derivedStateOf { stepAlpha(sharpen.value) } }
    val prevScale by remember { derivedStateOf { stepScale(previousScale) } }

    LaunchedEffect(wall) {
        val w = wall ?: return@LaunchedEffect
        val cur = current
        if (cur == null || cur.photo.id != w.photo.id) {
            previous = cur
            previousScale = drift.value
            current = w
            sharpen.snapTo(if (w.full != null) 1f else 0f)
            driftScope.launch {
                drift.snapTo(DRIFT_FROM)
                drift.animateTo(1f, tween(DRIFT_MS, easing = LinearEasing))
            }
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
                    .graphicsLayer { scaleX = prevScale; scaleY = prevScale }
                    .drawWithCache {
                        val scrims = scrimBrushes(size, density)
                        onDrawBehind { visiblePart(prevScale, covered()) { drawWall(p, 1f, scrims) } }
                    }
            )
        }
        current?.let { c ->
            Spacer(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = shownFade
                        scaleX = shownScale
                        scaleY = shownScale
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache {
                        val scrims = scrimBrushes(size, density)
                        onDrawBehind { visiblePart(shownScale, covered()) { drawWall(c, shownSharp, scrims) } }
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

/** The mockup's three scrims as live brushes (same geometry as data/Images.bakeScrims). */
private class Scrims(val radial: Brush, val rx: Float, val ry: Float, val top: Brush, val bottom: Brush) {
    fun draw(d: DrawScope) = with(d) {
        val h = size.height
        scale(1f, ry / rx, pivot = Offset.Zero) { drawRect(radial, size = Size(rx * 0.7f, rx * 0.7f)) }
        drawRect(top, size = size.copy(height = h * 0.24f))
        drawRect(bottom, topLeft = Offset(0f, h * 0.44f), size = size.copy(height = h * 0.56f))
    }
}

private fun scrimBrushes(size: Size, density: Float): Scrims {
    val s = Ink.Shadow
    val h = size.height
    val rx = 450f * density
    val ry = 260f * density
    return Scrims(
        radial = Brush.radialGradient(0f to s.copy(alpha = 0.62f), 0.7f to s.copy(alpha = 0f), center = Offset.Zero, radius = rx),
        rx = rx, ry = ry,
        top = Brush.verticalGradient(0f to s.copy(alpha = 0.35f), 1f to s.copy(alpha = 0f), startY = 0f, endY = h * 0.24f),
        bottom = Brush.verticalGradient(
            0f to s.copy(alpha = 0f), (0.26f / 0.56f) to s.copy(alpha = 0.42f), 1f to s.copy(alpha = 0.82f),
            startY = h * 0.44f, endY = h,
        ),
    )
}
