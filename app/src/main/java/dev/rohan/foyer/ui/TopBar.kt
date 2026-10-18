package dev.rohan.foyer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rohan.foyer.FoyerModel
import dev.rohan.foyer.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centred at the top: HH:mm:ss (or HH:mm with `homectl set seconds off`) in Google Sans Flex Light
 * with tabular figures, so the digits don't shuffle as they change; the date under it is optional
 * (`homectl set date on`). The dev build adds an amber "dev" tag.
 *
 * The seconds tick only while Foyer is on screen ([FoyerModel.visible]), each one landing on the
 * second boundary. The clock sits in its own layer, so a tick repaints only the clock's rectangle.
 */
@Composable
fun ClockBlock(model: FoyerModel, isDev: Boolean, modifier: Modifier = Modifier) {
    val seconds = model.clockSeconds
    val time = remember(seconds) { SimpleDateFormat(if (seconds) "HH:mm:ss" else "HH:mm", Locale.getDefault()) }
    val day = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
    var now by remember { mutableLongStateOf(model.now) }
    LaunchedEffect(model.visible, seconds, model.now) {
        // Off screen (and in tests) the model's clock is the truth; on screen, tick on real seconds.
        if (!model.visible) { now = model.now; return@LaunchedEffect }
        now = System.currentTimeMillis()
        val step = if (seconds) 1_000L else 60_000L
        while (true) {
            delay(step - System.currentTimeMillis() % step + 5)
            now = System.currentTimeMillis()
        }
    }
    val date = Date(now)
    // The formatters keep the zone they were made in; follow the TV's current zone (ACTION_TIMEZONE_CHANGED ticks us).
    val zone = java.util.TimeZone.getDefault()
    time.timeZone = zone
    day.timeZone = zone
    Column(
        modifier.fillMaxWidth().padding(top = Dims.ClockTop).graphicsLayer(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(time.format(date), style = Type.Clock, modifier = Modifier.testTag("clock"))
        if (model.clockDate || isDev) {
            Row(Modifier.padding(top = 4.dp)) {
                if (model.clockDate) BasicText(day.format(date), style = Type.Date, modifier = Modifier.alignByBaseline().testTag("date"))
                if (isDev) {
                    Spacer(Modifier.width(6.dp))
                    BasicText("dev", style = Type.DevTag, modifier = Modifier.alignByBaseline().testTag("devTag"))
                }
            }
        }
    }
}

private data class TopAction(val icon: Int, val label: String, val tag: String)

private val actions = listOf(
    TopAction(R.drawable.ic_tune, "Customize", "top:customize"),
    TopAction(R.drawable.ic_settings, "Settings", "top:settings"),
)

/** Customize, Settings: round glass buttons, right-aligned to the margin. */
@Composable
fun TopBar(
    model: FoyerModel,
    targets: Targets,
    backdrop: Backdrop?,
    enabled: Boolean,
    active: Int?,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(top = Dims.ButtonTop, end = Dims.Margin),
        horizontalArrangement = Arrangement.spacedBy(Dims.ButtonGap),
    ) {
        actions.forEachIndexed { i, a ->
            GlassButton(
                action = a,
                backdrop = backdrop,
                active = active == i,
                enabled = enabled,
                modifier = Modifier.focusRequester(targets.top[i]),
                // Only remember which button; whether the user is "in" the top bar is set by the
                // Up press itself. Android drops initial focus on the first focusable (Customize)
                // when the window gains focus, and that must not count as the user going up.
                onFocus = { model.topIndex = i },
                onClick = {
                    when (i) {
                        0 -> model.openCustomize()
                        else -> model.openSettings()
                    }
                },
                onDpad = { d ->
                    when (d) {
                        Dpad.Left -> if (i > 0) targets.top[i - 1].tryFocus()
                        Dpad.Right -> if (i < actions.size - 1) targets.top[i + 1].tryFocus()
                        Dpad.Down -> onDown()
                        Dpad.Up -> Unit
                    }
                    true
                },
            )
        }
    }
}

@Composable
private fun GlassButton(
    action: TopAction,
    backdrop: Backdrop?,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onDpad: (Dpad) -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused || active
    val fill = remember { Animatable(0f) }
    LaunchedEffect(lit) { fill.animateTo(if (lit) 1f else 0f, tween(160)) }
    val label = remember { Animatable(0f) }
    LaunchedEffect(focused) { label.animateTo(if (focused) 1f else 0f, tween(if (focused) 200 else 120)) }

    Box(
        modifier
            .size(Dims.ButtonSize)
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .remoteKeys(onClick = onClick, onDpad = onDpad)
            .testTag(action.tag)
            .focusable()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .glass(backdrop, CircleShape, Ink.Paper.copy(alpha = 0.12f))
                .drawBehind { if (fill.value > 0f) drawCircle(Ink.Paper, alpha = fill.value) },
            contentAlignment = Alignment.Center,
        ) {
            val tintLit = ColorFilter.tint(Ink.Shadow)
            val tintRest = ColorFilter.tint(Ink.Paper)
            Image(
                painterResource(action.icon), contentDescription = action.label,
                colorFilter = if (lit) tintLit else tintRest,
                modifier = Modifier.size(Dims.Glyph),
            )
        }
        // Label 8 dp under the button, centred, without affecting layout.
        BasicText(
            action.label, style = Type.ButtonLabel, maxLines = 1,
            modifier = Modifier
                .graphicsLayer { alpha = label.value; translationY = (1f - label.value) * -3.dp.toPx() }
                .layout { m, c ->
                    val p = m.measure(Constraints())
                    layout(0, 0) { p.place((Dims.ButtonSize.roundToPx() - p.width) / 2, (Dims.ButtonSize + 8.dp).roundToPx()) }
                },
        )
    }
}
