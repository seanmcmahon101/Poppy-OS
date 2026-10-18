package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rohan.foyer.FoyerModel
import dev.rohan.foyer.Overlay
import dev.rohan.foyer.R

/**
 * Keeps [value]'s content on screen through a 120 ms exit after it turns null; fades and scales in
 * from 0.96 over 180 ms. The content gets the last non-null value and an animated layer modifier.
 */
@Composable
fun <T : Any> Pop(value: T?, origin: TransformOrigin = TransformOrigin.Center, content: @Composable (T, Modifier) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    val a = remember { Animatable(0f) }
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(value != null) {
        if (value != null) {
            showing = true
            a.animateTo(1f, tween(180, easing = Motion.Glide))
        } else {
            a.animateTo(0f, tween(120))
            showing = false
        }
    }
    val v = last
    if (v != null && (showing || value != null)) {
        content(v, Modifier.graphicsLayer {
            alpha = a.value
            val s = 0.96f + 0.04f * a.value
            scaleX = s
            scaleY = s
            transformOrigin = origin
        })
    }
}

/** Lifts a floating panel off whatever glass is under it: a soft shadow and a hairline edge. */
private fun Modifier.floating(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .graphicsLayer { shadowElevation = 20.dp.toPx(); this.shape = shape; clip = false }
    .drawWithContent {
        drawContent()
        val o = shape.createOutline(size, layoutDirection, this)
        drawOutline(o, Ink.Paper.copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }

/** Arrow keys move focus inside; nothing escapes. */
private fun Modifier.trapFocus(fm: androidx.compose.ui.focus.FocusManager): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.dpad()) {
        Dpad.Up -> { fm.moveFocus(FocusDirection.Up); true }
        Dpad.Down -> { fm.moveFocus(FocusDirection.Down); true }
        Dpad.Left, Dpad.Right -> true
        null -> false
    }
}

@Composable
private fun PanelRow(icon: Int?, text: String, note: String?, tag: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f = remember { Animatable(0f) }
    LaunchedEffect(focused) { f.animateTo(if (focused) 1f else 0f, tween(140)) }
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .remoteKeys(onClick = onClick)
            .testTag(tag)
            .focusable()
            .drawBehind { if (f.value > 0f) drawRoundRect(Ink.Paper, cornerRadius = CornerRadius(10.dp.toPx()), alpha = f.value) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (focused) Ink.Shadow else Ink.Paper
        if (icon != null) {
            Image(painterResource(icon), null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        BasicText(text, style = Type.Row.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (note != null) {
            BasicText(note, style = Type.RowNote.copy(color = if (focused) Ink.Shadow.copy(alpha = 0.6f) else Ink.Whisper), maxLines = 1)
        }
    }
}

/** The app menu: Move / Add to Home or Remove from Home / Hide, just above the app it's for. */
@Composable
fun AppMenu(
    model: FoyerModel,
    menu: Overlay.Menu,
    anchor: Rect?,
    backdrop: Backdrop?,
    firstFocus: FocusRequester,
    layer: Modifier,
) {
    val fm = LocalFocusManager.current
    val items = model.menuItems(menu)
    Box(
        Modifier.fillMaxSize().layout { m, c ->
            val p = m.measure(Constraints())
            layout(c.maxWidth, c.maxHeight) {
                val margin = Dims.Margin.roundToPx()
                val a = anchor ?: Rect(c.maxWidth / 2f, c.maxHeight / 2f, c.maxWidth / 2f, c.maxHeight / 2f)
                val gap = 14.dp.toPx()
                if (menu.fromShelf) {
                    // Shelf: centred above the lifted tile.
                    val x = (a.center.x - p.width / 2f).toInt().coerceIn(margin, c.maxWidth - margin - p.width)
                    val above = (a.top - gap - p.height).toInt()
                    val y = if (above >= 24.dp.roundToPx()) above else (a.bottom + gap).toInt()
                    p.place(x, y)
                } else {
                    // All apps: beside the mini (right, or left near the edge), top-aligned with it.
                    val right = (a.right + gap).toInt()
                    val x = if (right + p.width <= c.maxWidth - margin) right else (a.left - gap - p.width).toInt()
                    val y = a.top.toInt().coerceAtMost(c.maxHeight - p.height - 16.dp.roundToPx())
                    p.place(x, y)
                }
            }
        }
    ) {
        Column(
            Modifier
                .width(196.dp)
                .then(layer)
                .floating(RoundedCornerShape(14.dp))
                .glass(backdrop, RoundedCornerShape(14.dp), Ink.Shadow.copy(alpha = 0.62f))
                .trapFocus(fm)
                .padding(6.dp)
        ) {
            items.forEachIndexed { i, item ->
                PanelRow(
                    icon = null, text = item.text, note = null,
                    tag = "menu:${item.name.lowercase()}",
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { model.menuAction(menu, item) },
                )
            }
        }
    }
}

/** A quiet note, bottom centre, for 3 s. */
@Composable
fun Toast(text: String, backdrop: Backdrop?, layer: Modifier, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(bottom = 12.dp)
            .then(layer)
            .glass(backdrop, RoundedCornerShape(50), Ink.Shadow.copy(alpha = 0.6f))
            .padding(horizontal = 18.dp, vertical = 9.dp)
            .testTag("toast")
    ) {
        BasicText(text, style = Type.Row.copy(fontSize = Type.Row.fontSize * 0.9f))
    }
}
