package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.rohan.foyer.FoyerModel
import dev.rohan.foyer.R
import dev.rohan.foyer.data.App
import kotlin.math.roundToInt

private const val STEP = 169f // tile 152 + gap 17
private const val VIEW = 848f // between the 56 dp margins
private const val TILE_W = 152f
private val TileShape = RoundedCornerShape(Dims.TileRadius)

/** Keeps the focused tile inside the margins; idempotent, so it's safe to call during composition. */
private class ShelfScroll {
    var value = 0f
    fun update(index: Int): Float {
        val left = index * STEP
        val right = left + TILE_W
        if (right - value > VIEW) value = right - VIEW
        if (left - value < 0f) value = left
        if (value < 0f) value = 0f
        return value
    }
}

/**
 * The shelf: art tiles from the left margin, running off the right edge, the focused label under
 * the focused tile. [hide] (0..1) is the All apps transition: up 20 dp and fade.
 */
@Composable
fun Shelf(
    model: FoyerModel,
    targets: Targets,
    enabled: Boolean,
    hide: () -> Float,
    caption: String?,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPastEnd: () -> Unit,
) {
    val apps = model.shelf
    val moving = model.moving
    val index = model.shelfIndex.coerceIn(0, (apps.size - 1).coerceAtLeast(0))
    val memory = remember { ShelfScroll() }
    val target = memory.update(index)
    val scroll = remember { Animatable(target) }
    LaunchedEffect(target) { scroll.animateTo(target, tween(500, easing = Motion.Glide)) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val h = hide()
                translationY = -20.dp.toPx() * h
                alpha = 1f - h
                // Tiles don't overlap: fade each draw instead of rendering a full-screen offscreen layer.
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
    ) {
        val tileTop = 540.dp - Dims.ShelfBottom - Dims.TileH
        apps.forEachIndexed { i, app ->
            key(app.pkg) {
                val slot = remember { Animatable(i * STEP) }
                LaunchedEffect(i) { slot.animateTo(i * STEP, tween(250, easing = Motion.Glide)) }
                ShelfTile(
                    app = app,
                    model = model,
                    targets = targets,
                    index = i,
                    focused = i == index && !model.inTopBar,
                    isMoving = moving == app.pkg,
                    dimmed = moving != null && moving != app.pkg,
                    enabled = enabled,
                    x = { Dims.Margin.toPx() + (slot.value - scroll.value).dp.toPx() },
                    y = tileTop,
                    onUp = onUp,
                    onDown = onDown,
                    onPastEnd = onPastEnd,
                )
            }
        }

        if (moving != null && index in apps.indices) {
            MoveChevrons(x = { Dims.Margin.toPx() + (index * STEP - scroll.value).dp.toPx() }, y = tileTop)
        }

        if (moving == null) {
            DrawerHint(
                level = when {
                    model.inTopBar -> 0.45f
                    index == apps.size - 1 -> 1f // Right from here opens the drawer too
                    else -> 0.7f
                },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 14.dp),
            )
        }

        val labelX = Dims.Margin.value + index * STEP + TILE_W / 2 - target
        if (caption != null) {
            // The photo caption steps aside while the focused tile's label would run into it.
            val clash = apps.isNotEmpty() && !model.inTopBar && labelX > 600f
            val captionA = remember { Animatable(1f) }
            LaunchedEffect(clash) { captionA.animateTo(if (clash) 0f else 1f, tween(250)) }
            androidx.compose.foundation.text.BasicText(
                caption, style = Type.Caption, maxLines = 1,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = Dims.Margin, bottom = 48.dp)
                    .graphicsLayer { alpha = captionA.value }
                    .testTag("caption"),
            )
        }
        if (apps.isNotEmpty()) {
            val name = apps[index].label
            FocusLabel(
                text = if (moving != null) "Moving $name" else name,
                centerX = Dims.Margin.value + index * STEP + TILE_W / 2 - target,
                visible = !model.inTopBar || moving != null,
            )
        }
    }
}

@Composable
private fun ShelfTile(
    app: App,
    model: FoyerModel,
    targets: Targets,
    index: Int,
    focused: Boolean,
    isMoving: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    x: androidx.compose.ui.unit.Density.() -> Float,
    y: androidx.compose.ui.unit.Dp,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPastEnd: () -> Unit,
) {
    val f = remember { Animatable(0f) }
    LaunchedEffect(focused) { f.animateTo(if (focused) 1f else 0f, tween(380, easing = Motion.Lift)) }
    val dim = remember { Animatable(1f) }
    LaunchedEffect(dimmed) { dim.animateTo(if (dimmed) 0.6f else 1f, tween(250)) }
    val sheen = rememberSheen(focused && !isMoving)
    val art = model.art[app.pkg]

    Box(
        Modifier
            .offset { IntOffset(x().roundToInt(), y.roundToPx()) }
            .size(Dims.TileW, Dims.TileH)
            .onGloballyPositioned { targets.bounds["s:${app.pkg}"] = it.boundsInRoot() }
            .graphicsLayer {
                val p = f.value
                val s = 1f + 0.13f * p
                scaleX = s
                scaleY = s
                translationY = -5.dp.toPx() * p
                alpha = ((0.86f + 0.14f * p) * dim.value).coerceIn(0f, 1f)
                // Only the focused (or moving) tile casts a shadow: six resting shadows cost ~3-4 ms on
                // every full-screen frame and are invisible over the dark bottom scrim anyway.
                shadowElevation = (if (isMoving) 32.dp else 24.dp * p.coerceIn(0f, 1f)).toPx()
                shape = TileShape
                clip = true
                // Fade each draw instead of an offscreen buffer per tile: on the Mali-G52 every
                // render-target switch costs a tile flush (52 ms frames with the drawer open).
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .focusProperties { canFocus = enabled }
            .focusRequester(targets.tile(app.pkg))
            .onFocusChanged {
                if (it.isFocused) {
                    model.shelfIndex = index
                    model.inTopBar = false
                }
            }
            .remoteKeys(
                onClick = { if (model.moving != null) model.endMove() else model.launch(app) },
                onLongClick = { if (model.moving == null) model.openMenu(app, fromShelf = true) },
                onMenu = { if (model.moving == null) model.openMenu(app, fromShelf = true) },
                onDpad = { d ->
                    val moving = model.moving != null
                    when (d) {
                        Dpad.Left -> if (moving) model.moveBy(-1) else targets.focusShelf(model, index - 1)
                        Dpad.Right -> when {
                            moving -> model.moveBy(1)
                            index == model.shelf.size - 1 -> onPastEnd() // the shelf continues into the drawer
                            else -> targets.focusShelf(model, index + 1)
                        }
                        Dpad.Up -> if (!moving) onUp()
                        Dpad.Down -> if (!moving) onDown()
                    }
                    true
                },
            )
            .testTag("tile:${app.pkg}")
            .focusable()
    ) {
        ArtFace(art, app.label, Dims.TileRadius, focus = { f.value })
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawSheen(sheen.t.value)
                drawFocusEdge(f.value.coerceIn(0f, 1f), Dims.TileRadius)
            }
        )
    }
}

private fun Targets.focusShelf(model: FoyerModel, i: Int) {
    val apps = model.shelf
    if (i !in apps.indices) return
    tile(apps[i].pkg).tryFocus()
}

/**
 * Move-mode handles: small paper discs sitting on the lifted tile's left and right edges
 * (the tile grows 1.13x, so its edges sit ~9.9 dp outside the slot), with shadow-ink chevrons.
 */
@Composable
private fun MoveChevrons(x: androidx.compose.ui.unit.Density.() -> Float, y: androidx.compose.ui.unit.Dp) {
    val disc = 24.dp
    val grow = Dims.TileW * 0.065f
    val top = y + (Dims.TileH - disc) / 2 - 5.dp
    listOf(
        R.drawable.ic_chevron_left to { d: androidx.compose.ui.unit.Density -> with(d) { x() - grow.toPx() - disc.toPx() / 2 } },
        R.drawable.ic_chevron_right to { d: androidx.compose.ui.unit.Density -> with(d) { x() + (Dims.TileW + grow).toPx() - disc.toPx() / 2 } },
    ).forEach { (icon, left) ->
        Box(
            Modifier
                .offset { IntOffset(left(this).roundToInt(), top.roundToPx()) }
                .size(disc)
                .graphicsLayer { shadowElevation = 6.dp.toPx(); shape = androidx.compose.foundation.shape.CircleShape; clip = true }
                .drawBehind { drawCircle(Ink.Paper) },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(Ink.Shadow), modifier = Modifier.size(18.dp))
        }
    }
}

/** The label under the focused tile: glides to its new centre, the new text fades in. */
@Composable
private fun FocusLabel(text: String, centerX: Float, visible: Boolean) {
    val cx = remember { Animatable(centerX) }
    LaunchedEffect(centerX) { cx.animateTo(centerX, tween(380, easing = Motion.Glide)) }
    val a = remember { Animatable(1f) }
    LaunchedEffect(text, visible) {
        if (!visible) a.animateTo(0f, tween(150)) else { a.snapTo(0f); a.animateTo(1f, tween(150)) }
    }
    Box(Modifier.fillMaxWidth().offset(y = 474.dp).height(20.dp)) {
        BasicText(
            text, style = Type.Label, maxLines = 1,
            modifier = Modifier
                .graphicsLayer { alpha = a.value }
                .layout { m, c ->
                    val p = m.measure(Constraints())
                    layout(c.maxWidth, p.height) {
                        p.place((cx.value.dp.roundToPx() - p.width / 2), 0)
                    }
                },
        )
    }
}

/** The quiet hint that All apps lives below: a soft, round-capped chevron at the bottom centre. */
@Composable
internal fun DrawerHint(level: Float, modifier: Modifier = Modifier) {
    val a = remember { Animatable(level) }
    LaunchedEffect(level) { a.animateTo(level, tween(250)) }
    Spacer(
        modifier
            .size(26.dp, 12.dp)
            .testTag("drawerHint")
            .drawWithCache {
                val w = size.width
                val sw = 2.2.dp.toPx()
                val inset = sw
                val depth = 7.dp.toPx()
                val r = 3.dp.toPx() // rounded apex
                val top = (size.height - depth) / 2
                val mid = w / 2
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(inset, top)
                    lineTo(mid - r, top + depth - r * 0.55f)
                    quadraticTo(mid, top + depth + r * 0.15f, mid + r, top + depth - r * 0.55f)
                    lineTo(w - inset, top)
                }
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = sw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                )
                onDrawBehind { drawPath(path, Ink.Paper, alpha = 0.85f * a.value, style = stroke) }
            }
    )
}
