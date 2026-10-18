package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.rohan.foyer.FoyerModel
import dev.rohan.foyer.Overlay

/** Foyer's one screen. Layers: photo, scrims, ember, content, sheets/panels/menus, night dim. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FoyerScreen(model: FoyerModel, isDev: Boolean) {
    val targets = remember { Targets() }
    val customizeFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var allSheetH by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var customizeSheetH by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val wall = model.wall
    val frost = wall?.frost
    val backdrop = remember(frost, rootSize) {
        if (frost != null && rootSize != IntSize.Zero) Backdrop(frost, rootSize) else null
    }
    val overlay = model.overlay
    val menu = overlay as? Overlay.Menu
    val allAppsUp = overlay == Overlay.AllApps || (menu != null && !menu.fromShelf)
    val customizeUp = overlay == Overlay.Customize
    val homeEnabled = overlay == Overlay.None

    // Sheet slides (550 ms) and the shelf's fade (350 ms).
    val allSlide = remember { Animatable(0f) }
    val shelfHide = remember { Animatable(0f) }
    val customizeSlide = remember { Animatable(0f) }
    LaunchedEffect(allAppsUp) { allSlide.animateTo(if (allAppsUp) 1f else 0f, tween(550, easing = Motion.Glide)) }
    LaunchedEffect(allAppsUp || customizeUp) {
        shelfHide.animateTo(if (allAppsUp || customizeUp) 1f else 0f, tween(350, easing = Motion.Glide))
    }
    LaunchedEffect(customizeUp) { customizeSlide.animateTo(if (customizeUp) 1f else 0f, tween(550, easing = Motion.Glide)) }
    val customizeComposed by remember { derivedStateOf { customizeSlide.value > 0f } }

    // Night warmth: animated outside composition, read only at draw time.
    LaunchedEffect(model.nightOn) {
        androidx.compose.animation.core.animate(Warmth.amount, if (model.nightOn) 1f else 0f, animationSpec = tween(1200)) { v, _ ->
            Warmth.amount = v
        }
    }

    // Place focus whenever the layer changes or the model asks for it.
    val shelfSize = model.shelf.size
    var lastShelfSize by remember { mutableStateOf(shelfSize) }
    LaunchedEffect(overlay, model.focusEpoch, model.moving, shelfSize) {
        // First run: the shelf fills in after the app scan; focus belongs on its first tile.
        if (lastShelfSize == 0 && shelfSize > 0) model.inTopBar = false
        lastShelfSize = shelfSize
        withFrameNanos { }
        when (overlay) {
            Overlay.None -> {
                val shelf = model.shelf
                if (model.inTopBar || shelf.isEmpty()) targets.top[model.topIndex].tryFocus()
                else targets.tile(shelf[model.shelfIndex.coerceIn(0, shelf.size - 1)].pkg).tryFocus()
            }
            Overlay.AllApps -> {
                val apps = model.allApps
                val pkg = targets.gridPkg?.takeIf { p -> apps.any { it.pkg == p } } ?: apps.firstOrNull()?.pkg
                if (pkg != null) targets.mini(pkg).tryFocus()
            }
            Overlay.Customize -> customizeFocus.tryFocus()
            is Overlay.Menu -> menuFocus.tryFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize() // no background fill: the window is already black, and the photo covers it
            .onSizeChanged { rootSize = it }
            .semantics { testTagsAsResourceId = true }
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) {
                    when {
                        model.moving != null -> model.endMove()
                        overlay == Overlay.AllApps -> model.closeAllApps(targets.gridPkg)
                        else -> model.closeOverlay()
                    }
                }
                true // Back never leaves home.
            }
    ) {
        // An opaque glass sheet hides the photo under it; don't paint what can't be seen.
        PhotoLayer(wall, covered = {
            if (backdrop == null) 0f
            else maxOf(allSheetH * allSlide.value, customizeSheetH * customizeSlide.value)
        })

        ClockBlock(model, isDev)

        TopBar(
            model = model,
            targets = targets,
            backdrop = backdrop,
            enabled = homeEnabled,
            active = if (customizeUp) 0 else null,
            onDown = {
                val shelf = model.shelf
                if (shelf.isEmpty()) { targets.gridPkg = null; model.openAllApps() }
                else targets.tile(shelf[model.shelfIndex.coerceIn(0, shelf.size - 1)].pkg).tryFocus()
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )

        Shelf(
            model = model,
            targets = targets,
            enabled = homeEnabled,
            hide = { shelfHide.value },
            caption = wall?.photo?.caption,
            onUp = { model.inTopBar = true; targets.top[model.topIndex].tryFocus() },
            onDown = {
                targets.gridPkg = model.shelf.getOrNull(model.shelfIndex)?.pkg
                model.openAllApps()
            },
            onPastEnd = {
                // Right off the last favourite: the drawer opens on the next app.
                targets.gridPkg = model.allApps.getOrNull(model.shelf.size)?.pkg
                model.openAllApps()
            },
        )

        // Always composed once there are apps, parked below the screen: Down only slides it up,
        // instead of building 20 tiles in the first frame of the animation.
        if (model.allApps.isNotEmpty()) {
            AllAppsSheet(
                model = model, targets = targets, backdrop = backdrop,
                shown = { allSlide.value }, enabled = overlay == Overlay.AllApps,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { allSheetH = it.height },
            )
        }
        if (customizeComposed || customizeUp) {
            CustomizeSheet(
                model = model, backdrop = backdrop, shown = { customizeSlide.value }, firstFocus = customizeFocus,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { customizeSheetH = it.height },
            )
        }

        Pop(menu) { m, layer ->
            val anchor = targets.bounds[(if (m.fromShelf) "s:" else "a:") + m.pkg]
            AppMenu(model, m, anchor, backdrop, menuFocus, layer)
        }

        Pop(model.toast) { text, layer ->
            Toast(text, backdrop, layer, Modifier.align(Alignment.BottomCenter))
        }
    }
}
