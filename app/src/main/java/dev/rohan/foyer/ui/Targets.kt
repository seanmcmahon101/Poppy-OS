package dev.rohan.foyer.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

/** Focus requesters and on-screen bounds, shared between the home layer and its overlays. */
class Targets {
    private val tiles = HashMap<String, FocusRequester>()
    private val minis = HashMap<String, FocusRequester>()
    val top = List(2) { FocusRequester() } // Customize, Settings
    /**
     * Tile and mini layout coordinates, for anchoring the app menu. Keys "s:pkg" and "a:pkg".
     * Only the coordinates are kept as things move (the drawer slide re-places every mini on every
     * frame); bounds are worked out when a menu asks for them.
     */
    val placed = HashMap<String, LayoutCoordinates>()
    private val lastBounds = HashMap<String, Rect>()
    /** The app last focused in All apps. */
    var gridPkg: String? = null

    fun tile(pkg: String) = tiles.getOrPut(pkg) { FocusRequester() }
    fun mini(pkg: String) = minis.getOrPut(pkg) { FocusRequester() }

    /** Where [key]'s tile or mini is, in root px; where it last was if it has just left the screen. */
    fun bounds(key: String): Rect? {
        val c = placed[key]
        if (c != null && c.isAttached) return c.boundsInRoot().also { lastBounds[key] = it }
        return lastBounds[key]
    }
}

fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess
