package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.rohan.foyer.data.Art
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val Placeholder = Color(0xFF1A1D24)

/**
 * Draws an app's art filling the element: a picture (centre-cropped), a generated icon tile, or a
 * quiet placeholder while it loads. [focus] is read at draw time (0 = rest, 1 = focused).
 */
@Composable
fun ArtFace(art: Art?, name: String, corner: Dp, focus: () -> Float, modifier: Modifier = Modifier) {
    when (art) {
        is Art.Generated -> Box(
            modifier.fillMaxSize().drawWithContent {
                drawRoundRect(art.color, cornerRadius = CornerRadius(corner.toPx()))
                drawContent()
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                art.icon?.let {
                    Image(it, contentDescription = null, modifier = Modifier.size(if (corner < Dims.TileRadius) 22.dp else 28.dp))
                    Spacer(Modifier.height(4.dp))
                }
                BasicText(
                    name, style = Type.TileName.copy(textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        else -> Spacer(
            modifier.fillMaxSize().drawWithContent {
                val p = (art as? Art.Picture)?.bitmap
                if (p == null) {
                    drawRect(Placeholder)
                } else {
                    val f = focus()
                    drawImage(
                        image = p,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(p.width, p.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.Low,
                        colorFilter = tone(0.9f + 0.1f * f),
                    )
                }
            }
        )
    }
}

/** The one-shot diagonal highlight that sweeps a tile when it gains focus. */
class Sheen {
    val t = Animatable(1f)
}

@Composable
fun rememberSheen(focused: Boolean): Sheen {
    val s = remember { Sheen() }
    LaunchedEffect(focused) {
        if (focused) {
            s.t.snapTo(0f)
            delay(60)
            s.t.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        } else {
            s.t.snapTo(1f)
        }
    }
    return s
}

private val sheenDir = Offset(sin(Math.toRadians(115.0)).toFloat(), -cos(Math.toRadians(115.0)).toFloat())

/** Draws the sheen band at progress [t] (0..1); invisible at 1. */
fun DrawScope.drawSheen(t: Float) {
    if (t <= 0f || t >= 1f) return
    val w = size.width
    val cx = -0.62f * w + 2.4f * w * t
    val c = Offset(cx, size.height / 2)
    val hw = 0.39f * w
    val a = 0.28f * (1f - t)
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent, 0.5f to Color.White.copy(alpha = a), 1f to Color.Transparent,
            start = c - sheenDir * hw, end = c + sheenDir * hw,
        )
    )
}

/** Photo thumbnails and small tiles: a clear 2 dp paper ring, since a slight scale alone reads weakly. */
fun DrawScope.drawFocusRing(f: Float, corner: Dp) {
    if (f <= 0.01f) return
    val sw = 2.dp.toPx()
    drawRoundRect(
        color = Ink.Paper.copy(alpha = 0.95f * f),
        topLeft = Offset(sw / 2, sw / 2),
        size = size.copy(width = size.width - sw, height = size.height - sw),
        cornerRadius = CornerRadius(corner.toPx() - sw / 2),
        style = Stroke(sw),
    )
}

/** The focused tile's inner edge: 0.75 dp white at 22%. Drawn only when visible (no hairline at rest). */
fun DrawScope.drawFocusEdge(f: Float, corner: Dp) {
    if (f <= 0.01f) return
    val sw = 0.75.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f * f),
        topLeft = Offset(sw / 2, sw / 2),
        size = size.copy(width = size.width - sw, height = size.height - sw),
        cornerRadius = CornerRadius(corner.toPx() - sw / 2),
        style = Stroke(sw),
    )
}
