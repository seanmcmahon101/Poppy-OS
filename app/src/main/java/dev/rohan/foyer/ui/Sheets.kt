package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.rohan.foyer.FoyerModel
import dev.rohan.foyer.R
import dev.rohan.foyer.data.App
import dev.rohan.foyer.data.NightMode
import dev.rohan.foyer.data.Photo
import dev.rohan.foyer.data.Rotation
import kotlin.math.roundToInt

private const val COLUMNS = 6

/**
 * A glass sheet rising from the bottom edge. [shown] is the slide progress (0 hidden, 1 up).
 * With [height] null it wraps its content (up to [maxHeight]), so nothing is ever cut off.
 */
@Composable
fun Sheet(
    height: Dp?,
    backdrop: Backdrop?,
    shown: () -> Float,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 480.dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier.heightIn(max = maxHeight))
            .layout { m, c ->
                val p = m.measure(c)
                layout(p.width, p.height) { p.place(0, ((1f - shown()) * p.height).roundToInt()) }
            }
            .glass(backdrop, RectangleShape, Ink.Shadow.copy(alpha = 0.5f))
            .drawBehind { drawLine(Ink.Paper.copy(alpha = 0.08f), Offset.Zero, Offset(size.width, 0f), 1f) }
    ) { content() }
}

/** All apps: a 6-column grid of minis. Shelf apps first, then the rest alphabetically. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AllAppsSheet(
    model: FoyerModel,
    targets: Targets,
    backdrop: Backdrop?,
    shown: () -> Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val apps = model.allApps
    val fm = LocalFocusManager.current
    // Exactly whole rows, up to 3, in a viewport that scrolls one row at a time: never a cut-off row.
    val rows = ((apps.size + COLUMNS - 1) / COLUMNS).coerceIn(1, 3)
    val pad = 8.dp // room for the focused mini's 1.1x growth inside the viewport
    val gridH = Dims.MiniH * rows + Dims.MiniGapY * (rows - 1)
    val height = 28.dp + 18.dp + 4.dp + pad * 2 + gridH + 22.dp
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val padPx = with(density) { pad.toPx() }
    val rowSnap = remember(padPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = tween(320, easing = Motion.Glide)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < padPx -> offset - padPx
                offset + size > containerSize - padPx -> offset + size - (containerSize - padPx)
                else -> 0f
            }
        }
    }
    Sheet(height, backdrop, shown, modifier) {
        Column(Modifier.padding(top = 28.dp, start = Dims.Margin, end = Dims.Margin)) {
            BasicText("All apps", style = Type.SheetTitle)
            Spacer(Modifier.height(4.dp))
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowSnap) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(gridH + pad * 2)
                    .verticalScroll(scroll)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val d = e.dpad() ?: return@onPreviewKeyEvent false
                        val i = apps.indexOfFirst { it.pkg == targets.gridPkg }
                        when (d) {
                            Dpad.Up -> if (i in 0 until COLUMNS) model.closeAllApps(targets.gridPkg) else fm.moveFocus(FocusDirection.Up)
                            Dpad.Down -> if (!fm.moveFocus(FocusDirection.Down) && i >= 0) {
                                // Partial last row: drop to the last app.
                                if (i / COLUMNS < (apps.size - 1) / COLUMNS) targets.mini(apps.last().pkg).tryFocus()
                            }
                            Dpad.Left -> if (i % COLUMNS != 0) fm.moveFocus(FocusDirection.Left)
                            Dpad.Right -> if (i % COLUMNS != COLUMNS - 1) fm.moveFocus(FocusDirection.Right)
                        }
                        true
                    }
                    .padding(vertical = pad),
                verticalArrangement = Arrangement.spacedBy(Dims.MiniGapY),
            ) {
                apps.chunked(COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dims.MiniGapX)) {
                        row.forEach { app -> Mini(app, model, targets, enabled) }
                    }
                }
            }
            }
        }
        // More rows below: the same soft chevron as the shelf's drawer hint.
        if (scroll.maxValue > 0) {
            DrawerHint(
                level = if (scroll.value < scroll.maxValue - 1) 0.7f else 0f,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
            )
        }
    }
}

@Composable
private fun Mini(app: App, model: FoyerModel, targets: Targets, enabled: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val f = remember { Animatable(0f) }
    LaunchedEffect(focused) { f.animateTo(if (focused) 1f else 0f, tween(300, easing = Motion.Lift)) }
    val sheen = rememberSheen(focused)
    val clipShape = RoundedCornerShape(Dims.MiniRadius)
    Box(
        Modifier
            .size(Dims.MiniW, Dims.MiniH)
            .onGloballyPositioned { targets.bounds["a:${app.pkg}"] = it.boundsInRoot() }
            .graphicsLayer {
                val p = f.value
                scaleX = 1f + 0.1f * p
                scaleY = 1f + 0.1f * p
                alpha = (0.9f + 0.1f * p).coerceIn(0f, 1f)
                shadowElevation = (18.dp * p.coerceIn(0f, 1f)).toPx() // only the focused one casts a shadow
                shape = clipShape
                clip = true
                // Fade each draw instead of an offscreen buffer per tile: on the Mali-G52 every
                // render-target switch costs a tile flush (52 ms frames with the drawer open).
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .focusProperties { canFocus = enabled }
            .focusRequester(targets.mini(app.pkg))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) targets.gridPkg = app.pkg
            }
            .remoteKeys(
                onClick = { model.launch(app) },
                onLongClick = { model.openMenu(app, fromShelf = false) },
                onMenu = { model.openMenu(app, fromShelf = false) },
            )
            .testTag("app:${app.pkg}")
            .focusable()
    ) {
        ArtFace(model.art[app.pkg], app.label, Dims.MiniRadius, focus = { f.value })
        Spacer(Modifier.fillMaxSize().drawBehind { drawSheen(sheen.t.value); drawFocusEdge(f.value.coerceIn(0f, 1f), Dims.MiniRadius) })
    }
}

// =============================================================================================
// Customize

private val ThumbW = 120.dp
private val ThumbH = 67.5.dp

@Composable
fun CustomizeSheet(
    model: FoyerModel,
    backdrop: Backdrop?,
    shown: () -> Float,
    firstFocus: androidx.compose.ui.focus.FocusRequester,
    modifier: Modifier = Modifier,
) {
    val fm = LocalFocusManager.current
    Sheet(null, backdrop, shown, modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.dpad()) {
                        Dpad.Up -> { fm.moveFocus(FocusDirection.Up); true }
                        Dpad.Down -> { fm.moveFocus(FocusDirection.Down); true }
                        Dpad.Left -> { fm.moveFocus(FocusDirection.Left); true }
                        Dpad.Right -> { fm.moveFocus(FocusDirection.Right); true }
                        null -> false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(top = 28.dp, start = Dims.Margin, end = Dims.Margin, bottom = 20.dp),
        ) {
            BasicText("Customize", style = Type.SheetTitle)
            Spacer(Modifier.height(12.dp))

            RowTitle("Photos")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = model.wall?.photo?.id
                model.photos.forEach { p ->
                    Thumb(
                        photo = p, model = model,
                        modifier = if (p.id == current) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (model.photos.none { it is Photo.Own }) AddYourOwn()
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    RowTitle("Change photo")
                    Segmented(
                        options = listOf(
                            Rotation.Never to "Never", Rotation.FiveMinutes to "Every 5 minutes",
                            Rotation.Hourly to "Every hour", Rotation.Daily to "Every day",
                        ),
                        selected = model.rotation, tag = "rotation", onSelect = model::changeRotation,
                    )
                }
                Column {
                    RowTitle("Night warmth")
                    Segmented(
                        options = listOf(NightMode.Auto to "Auto", NightMode.Always to "Always", NightMode.Off to "Off"),
                        selected = model.night, tag = "night", onSelect = model::changeNight,
                    )
                }
            }

            val hidden = model.hiddenApps
            if (hidden.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                RowTitle("Hidden apps")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    hidden.forEach { app -> HiddenApp(app, model) }
                }
            }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    BasicText(text, style = Type.RowNote.copy(color = Ink.Mist), modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Thumb(photo: Photo, model: FoyerModel, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val f = remember { Animatable(0f) }
    LaunchedEffect(focused) { f.animateTo(if (focused) 1f else 0f, tween(260, easing = Motion.Lift)) }
    val on = photo.id in model.photosOn
    val onA = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { onA.animateTo(if (on) 1f else 0f, tween(200)) }
    val clipShape = RoundedCornerShape(8.dp)
    val img = model.thumbs[photo.id]
    Box(
        modifier
            .size(ThumbW, ThumbH)
            .graphicsLayer {
                val p = f.value
                scaleX = 1f + 0.08f * p
                scaleY = 1f + 0.08f * p
                shadowElevation = (2.dp + 14.dp * p.coerceIn(0f, 1f)).toPx()
                shape = clipShape
                clip = true
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) model.preview(photo.id)
            }
            .remoteKeys(onClick = { model.togglePhoto(photo.id) })
            .testTag("photo:${photo.id}")
            .focusable()
            .drawBehind {
                if (img == null) drawRect(Ink.Shadow.copy(alpha = 0.6f)) else drawImage(
                    img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = 0.45f + 0.55f * onA.value, filterQuality = FilterQuality.Low,
                )
                drawFocusRing(f.value.coerceIn(0f, 1f), 8.dp)
            }
    ) {
        if (on) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp)
                    .graphicsLayer { alpha = onA.value }
                    .drawBehind { drawCircle(Ink.Paper) },
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_check), null, colorFilter = ColorFilter.tint(Ink.Shadow), modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun AddYourOwn() {
    Column(
        Modifier
            .size(width = 176.dp, height = ThumbH)
            .drawBehind {
                drawRoundRect(
                    Ink.Paper.copy(alpha = 0.18f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                )
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Image(
            painterResource(R.drawable.ic_add_photo_alternate), null,
            colorFilter = ColorFilter.tint(Ink.Whisper), modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(4.dp))
        BasicText("Add your own: ask Iris to send photos to the TV", style = Type.RowNote.copy(fontSize = Type.RowNote.fontSize * 0.92f, lineHeight = Type.RowNote.lineHeight * 0.92f))
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .drawBehind {
                drawRoundRect(Ink.Paper.copy(alpha = 0.08f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            }
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            var focused by remember { mutableStateOf(false) }
            val f = remember { Animatable(0f) }
            LaunchedEffect(focused) { f.animateTo(if (focused) 1f else 0f, tween(160)) }
            val isSel = value == selected
            Box(
                Modifier
                    .fillMaxHeight()
                    .onFocusChanged { focused = it.isFocused }
                    .remoteKeys(onClick = { onSelect(value) })
                    .testTag("$tag:${label.lowercase().replace(' ', '_')}")
                    .focusable()
                    .drawBehind {
                        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                        if (isSel) drawRoundRect(Ink.Paper.copy(alpha = 0.20f), cornerRadius = r)
                        if (f.value > 0f) drawRoundRect(Ink.Paper, cornerRadius = r, alpha = f.value)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = Type.Row.copy(fontSize = Type.Row.fontSize * 0.93f, color = if (focused) Ink.Shadow else if (isSel) Ink.Paper else Ink.Mist),
                )
            }
        }
    }
}

@Composable
private fun HiddenApp(app: App, model: FoyerModel) {
    var focused by remember { mutableStateOf(false) }
    val f = remember { Animatable(0f) }
    LaunchedEffect(focused) { f.animateTo(if (focused) 1f else 0f, tween(260, easing = Motion.Lift)) }
    val clipShape = RoundedCornerShape(Dims.MiniRadius)
    Box(
        Modifier
            .size(Dims.MiniW, Dims.MiniH)
            .graphicsLayer {
                val p = f.value
                scaleX = 1f + 0.08f * p
                scaleY = 1f + 0.08f * p
                alpha = (0.8f + 0.2f * p).coerceIn(0f, 1f)
                shape = clipShape
                clip = true
                // Fade each draw instead of an offscreen buffer per tile: on the Mali-G52 every
                // render-target switch costs a tile flush (52 ms frames with the drawer open).
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .onFocusChanged { focused = it.isFocused }
            .remoteKeys(onClick = { model.unhide(app) })
            .testTag("hidden:${app.pkg}")
            .focusable()
    ) {
        ArtFace(model.art[app.pkg], app.label, Dims.MiniRadius, focus = { f.value })
        Spacer(Modifier.fillMaxSize().drawBehind { drawFocusRing(f.value.coerceIn(0f, 1f), Dims.MiniRadius) })
    }
}
