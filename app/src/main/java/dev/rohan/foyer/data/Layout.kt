package dev.rohan.foyer.data

/**
 * The user's arrangement: which apps sit on the shelf (in order) and which are hidden.
 * Immutable; every operation returns a new Layout. Stored shelf entries survive uninstalls
 * so a reinstalled app returns to its slot; [visibleShelf] filters to what's installed.
 */
data class Layout(val shelf: List<String>, val hidden: Set<String>) {

    fun visibleShelf(installed: Set<String>): List<String> =
        shelf.filter { it in installed && it !in hidden && !AppRules.isBlocked(it) }

    fun isOnShelf(pkg: String) = pkg in shelf

    fun addToShelf(pkg: String): Layout =
        if (pkg in shelf) this else copy(shelf = shelf + pkg, hidden = hidden - pkg)

    fun removeFromShelf(pkg: String): Layout = copy(shelf = shelf - pkg)

    fun hide(pkg: String): Layout = copy(shelf = shelf - pkg, hidden = hidden + pkg)

    fun unhide(pkg: String): Layout = copy(hidden = hidden - pkg)

    /**
     * Swap [pkg] with its visible neighbour ([delta] = -1 left, +1 right). Neighbours are counted
     * among [installed] shelf entries, so uninstalled placeholders never make a press do nothing.
     */
    fun move(pkg: String, delta: Int, installed: Set<String>): Layout {
        val visible = visibleShelf(installed)
        val i = visible.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in visible.indices) return this
        val other = visible[j]
        val a = shelf.indexOf(pkg)
        val b = shelf.indexOf(other)
        val out = shelf.toMutableList()
        out[a] = other
        out[b] = pkg
        return copy(shelf = out)
    }

    companion object {
        fun firstRun(installed: Set<String>) = Layout(
            shelf = AppRules.defaultShelf.filter { it in installed },
            hidden = AppRules.defaultHidden,
        )
    }
}
