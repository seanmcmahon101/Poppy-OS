package dev.rohan.foyer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Remote keys, as Foyer reads them. */
enum class Dpad { Left, Right, Up, Down }

val KeyEvent.isOk: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

fun KeyEvent.dpad(): Dpad? = when (key) {
    Key.DirectionLeft -> Dpad.Left
    Key.DirectionRight -> Dpad.Right
    Key.DirectionUp -> Dpad.Up
    Key.DirectionDown -> Dpad.Down
    else -> null
}

private class PressState {
    var downAt = -1L
    var longFired = false
}

const val LONG_PRESS_MS = 600L

/**
 * D-pad activation for a focusable element.
 * - OK activates on key-up, and only if this element saw the key-down (so the key-up that ends
 *   a long press never activates whatever the menu put focus on).
 * - Holding OK for [LONG_PRESS_MS] fires [onLongClick] once, from the key repeats the remote sends;
 *   a release after that long without repeats (tests, odd remotes) counts too.
 * - A short Menu press fires [onMenu]. Home on Fire owns the long Menu press.
 * - Arrow keys go to [onDpad]; return true to consume.
 */
fun Modifier.remoteKeys(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onDpad: ((Dpad) -> Boolean)? = null,
): Modifier = composed {
    val ok = remember { PressState() }
    val menu = remember { PressState() }
    onPreviewKeyEvent { e ->
        val native = e.nativeKeyEvent
        when {
            e.isOk && e.type == KeyEventType.KeyDown -> {
                if (native.repeatCount == 0) {
                    ok.downAt = native.eventTime
                    ok.longFired = false
                } else if (ok.downAt >= 0 && !ok.longFired && onLongClick != null &&
                    native.eventTime - ok.downAt >= LONG_PRESS_MS
                ) {
                    ok.longFired = true
                    ok.downAt = -1
                    onLongClick()
                }
                true
            }
            e.isOk && e.type == KeyEventType.KeyUp -> {
                if (ok.downAt >= 0 && !ok.longFired) {
                    val held = native.eventTime - ok.downAt
                    ok.downAt = -1
                    if (onLongClick != null && held >= LONG_PRESS_MS) onLongClick() else onClick()
                }
                ok.downAt = -1
                true
            }
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> {
                if (native.repeatCount == 0) menu.downAt = native.eventTime
                onMenu != null
            }
            e.key == Key.Menu && e.type == KeyEventType.KeyUp -> {
                val short = menu.downAt >= 0 && native.eventTime - menu.downAt < 500
                menu.downAt = -1
                if (short && onMenu != null) { onMenu(); true } else onMenu != null
            }
            e.type == KeyEventType.KeyDown -> {
                val d = e.dpad()
                if (d != null && onDpad != null) onDpad(d) else false
            }
            else -> false
        }
    }
}
