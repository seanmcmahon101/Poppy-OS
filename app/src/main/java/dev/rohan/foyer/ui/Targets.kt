package dev.rohan.foyer.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect

/** Focus requesters and on-screen bounds, shared between the home layer and its overlays. */
class Targets {
    private val tiles = HashMap<String, FocusRequester>()
    private val minis = HashMap<String, FocusRequester>()
    val top = List(2) { FocusRequester() } // Customize, Settings
    /** Tile and mini bounds in root px, for anchoring the app menu. Keys "s:pkg" and "a:pkg". */
    val bounds = HashMap<String, Rect>()
    /** The app last focused in All apps. */
    var gridPkg: String? = null

    fun tile(pkg: String) = tiles.getOrPut(pkg) { FocusRequester() }
    fun mini(pkg: String) = minis.getOrPut(pkg) { FocusRequester() }
}

fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess
