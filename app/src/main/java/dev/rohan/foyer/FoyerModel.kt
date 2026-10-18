package dev.rohan.foyer

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.rohan.foyer.data.App
import dev.rohan.foyer.data.AppRules
import dev.rohan.foyer.data.Art
import dev.rohan.foyer.data.Images
import dev.rohan.foyer.data.Layout
import dev.rohan.foyer.data.LayoutStore
import dev.rohan.foyer.data.NightMode
import dev.rohan.foyer.data.Photo
import dev.rohan.foyer.data.PhotoRepository
import dev.rohan.foyer.data.Platform
import dev.rohan.foyer.data.Rotation
import dev.rohan.foyer.data.TvInput
import dev.rohan.foyer.ui.frostedCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

sealed interface Overlay {
    data object None : Overlay
    data object AllApps : Overlay
    data object Inputs : Overlay
    data object Customize : Overlay
    data class Menu(val pkg: String, val fromShelf: Boolean) : Overlay
}

/** The photo on screen. [full] is null right after a trim; the frosted copy stands in until it decodes. */
class Wall(val photo: Photo, val full: ImageBitmap?, val frost: ImageBitmap, val fadeMs: Int)

/**
 * All of Foyer's state and behaviour, independent of the UI. Plain snapshot state, owned by
 * MainActivity (singleTask, never recreated). Tests drive it with a fake [Platform] and
 * [Dispatchers.Unconfined], which makes every load synchronous.
 */
@Stable
class FoyerModel(
    context: Context,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = LayoutStore(context)
    private val photoRepo = PhotoRepository(context)

    // ---- apps -------------------------------------------------------------------------------
    /** Every launchable, non-blocklisted app (hidden ones included). */
    var installed by mutableStateOf(store.cachedApps())
        private set
    var layout by mutableStateOf(store.layout() ?: Layout(emptyList(), AppRules.defaultHidden))
        private set
    private var layoutStored = store.layout() != null

    val installedPkgs: Set<String> by derivedStateOf { installed.mapTo(HashSet()) { it.pkg } }
    val shelf: List<App> by derivedStateOf {
        val byPkg = installed.associateBy { it.pkg }
        layout.visibleShelf(byPkg.keys).mapNotNull { byPkg[it] }
    }
    val allApps: List<App> by derivedStateOf {
        AppRules.allAppsOrder(installed.filter { it.pkg !in layout.hidden }, layout.shelf)
    }
    val hiddenApps: List<App> by derivedStateOf { AppRules.alphabetical(installed.filter { it.pkg in layout.hidden }) }

    val art = mutableStateMapOf<String, Art>()

    // ---- UI state ---------------------------------------------------------------------------
    var overlay by mutableStateOf<Overlay>(Overlay.None)
        private set
    var moving by mutableStateOf<String?>(null)
        private set
    /** Focused shelf index (also the one focus returns to). */
    var shelfIndex by mutableIntStateOf(0)
    /** Last-focused top-bar button: 0 Inputs, 1 Customize, 2 Settings. */
    var topIndex by mutableIntStateOf(0)
    /** Focus zone on the home layer: true while a top-bar button has focus. */
    var inTopBar by mutableStateOf(false)
    /** Bumped whenever focus must be re-placed (overlay closed, returned to Foyer). */
    var focusEpoch by mutableIntStateOf(0)
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null

    var inputs by mutableStateOf<List<TvInput>>(emptyList())
        private set

    // ---- time, night ------------------------------------------------------------------------
    var now by mutableLongStateOf(clock())
        private set
    var night by mutableStateOf(store.night)
        private set
    val nightOn: Boolean
        get() = when (night) {
            NightMode.Always -> true
            NightMode.Off -> false
            NightMode.Auto -> Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY).let { it >= 20 || it < 6 }
        }

    // ---- photos -----------------------------------------------------------------------------
    var photos by mutableStateOf<List<Photo>>(photoRepo.builtins)
        private set
    var photosOn by mutableStateOf(store.photosOn() ?: photoRepo.builtins.mapTo(HashSet()) { it.id })
        private set
    var rotation by mutableStateOf(store.rotation)
        private set
    var wall by mutableStateOf<Wall?>(null)
        private set
    /** Customize thumbnails, decoded while the sheet is open. */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    /** The photo being previewed in Customize, if any. */
    var previewId by mutableStateOf<String?>(null)
        private set
    private var currentId: String = store.currentPhoto ?: photoRepo.builtins.first().id
    private var wallJob: Job? = null
    private var previewJob: Job? = null
    private val frostCache = HashMap<String, ImageBitmap>()

    // =========================================================================================
    // Lifecycle

    /** Called once. Shows whatever is cached immediately, then refreshes. */
    fun start() {
        // Rohan's own photos are known only after a scan; built-ins can show right away.
        if (photo(currentId) != null) showPhoto(currentId, fadeMs = FADE_PHOTO)
        loadArt(shelf + allApps)
        refreshApps()
        refreshPhotos()
        refreshInputs()
    }

    /** Back in front (onStart): put back what a trim released, then refresh in the background. */
    fun resume() {
        now = clock()
        closeEverything()
        val w = wall
        if (w != null && w.full == null) {
            wallJob?.cancel()
            wallJob = scope.launch {
                val p = w.photo
                val bmp = withContext(io) { photoRepo.full(p) }?.asImageBitmap() ?: return@launch
                if (wall?.photo?.id == p.id) publish(Wall(p, bmp, w.frost, FADE_SHARPEN)) else bmp.asAndroidBitmap().recycle()
            }
        }
        restoreShelfArt()
        loadArt(allApps)
        rotateIfDue(visible = false)
        refreshApps()
        refreshPhotos()
        refreshInputs()
    }

    /** onTrimMemory(UI_HIDDEN): keep only the tiny frosted copy. */
    fun releaseImages() {
        wallJob?.cancel()
        previewJob?.cancel()
        wall?.let { publish(Wall(it.photo, null, it.frost, 0)) }
        // Art and thumbnails: nothing is drawn while hidden, so free them once this frame is done.
        val oldArt = art.values.toList()
        val oldThumbs = thumbs.values.toList()
        art.clear()
        thumbs.clear()
        scope.launch {
            delay(RETIRE_AFTER_MS)
            oldArt.forEach { a ->
                when (a) {
                    is Art.Picture -> if (art.values.none { it === a }) runCatching { a.bitmap.asAndroidBitmap().recycle() }
                    is Art.Generated -> a.icon?.let { i -> if (art.values.none { it === a }) runCatching { i.asAndroidBitmap().recycle() } }
                }
            }
            oldThumbs.forEach { t -> if (thumbs.values.none { it === t }) runCatching { t.asAndroidBitmap().recycle() } }
        }
    }

    /** The window just got focus: put focus back where Foyer wants it (Android may have moved it). */
    fun onWindowFocused() {
        if (overlay == Overlay.None && moving == null) focusEpoch++
    }

    /** Home pressed while Foyer is in front, or returned via Home on Fire. */
    fun onHomeIntent() = closeEverything()

    /** Called on ACTION_TIME_TICK and on resume. */
    fun tick() {
        now = clock()
        rotateIfDue(visible = true)
        // Photos Iris sends show up within a minute, even while Foyer stays in front.
        refreshPhotos()
    }

    // =========================================================================================
    // Apps

    fun refreshApps() {
        scope.launch {
            val scanned = withContext(io) { runCatching { platform.scanApps() }.getOrNull() } ?: return@launch
            val sorted = AppRules.alphabetical(scanned)
            if (sorted != installed) {
                installed = sorted
                store.saveApps(sorted)
            }
            if (!layoutStored) {
                layout = Layout.firstRun(sorted.mapTo(HashSet()) { it.pkg })
                store.saveLayout(layout)
                layoutStored = true
            }
            shelfIndex = shelfIndex.coerceIn(0, (shelf.size - 1).coerceAtLeast(0))
            loadArt(shelf + allApps)
        }
    }

    private fun loadArt(apps: List<App>) {
        val missing = apps.filter { it.pkg !in art }.distinctBy { it.pkg }
        if (missing.isEmpty()) return
        scope.launch {
            for (app in missing) {
                if (app.pkg in art) continue
                val a = withContext(io) { runCatching { platform.art(app) }.getOrNull() } ?: continue
                art[app.pkg] = a
            }
        }
    }

    /** Decodes shelf art in parallel and waits briefly, so a warm return shows complete tiles. */
    private fun restoreShelfArt() {
        val missing = shelf.filter { it.pkg !in art }
        if (missing.isEmpty()) return
        val loaded = runBlocking {
            withTimeoutOrNull(ART_WAIT_MS) {
                missing.map { app -> async(io) { app.pkg to runCatching { platform.art(app) }.getOrNull() } }.awaitAll()
            }
        }
        loaded?.forEach { (pkg, a) -> if (a != null) art[pkg] = a }
    }

    fun launch(app: App) {
        if (moving != null) return
        val i = shelf.indexOfFirst { it.pkg == app.pkg }
        if (i >= 0) shelfIndex = i
        if (!platform.launch(app.pkg)) showToast("Couldn't open ${app.label}")
    }

    // ---- rearranging ------------------------------------------------------------------------

    fun openMenu(app: App, fromShelf: Boolean) {
        if (moving != null) return
        overlay = Overlay.Menu(app.pkg, fromShelf)
    }

    fun menuItems(menu: Overlay.Menu): List<MenuItem> = buildList {
        if (menu.fromShelf) add(MenuItem.Move)
        add(if (shelf.any { it.pkg == menu.pkg }) MenuItem.Remove else MenuItem.Add)
        add(MenuItem.Hide)
    }

    fun menuAction(menu: Overlay.Menu, item: MenuItem) {
        when (item) {
            MenuItem.Move -> {
                overlay = Overlay.None
                moving = menu.pkg
                return
            }
            MenuItem.Add -> applyLayout(layout.addToShelf(menu.pkg))
            MenuItem.Remove -> applyLayout(layout.removeFromShelf(menu.pkg))
            MenuItem.Hide -> applyLayout(layout.hide(menu.pkg))
        }
        overlay = if (menu.fromShelf) Overlay.None else Overlay.AllApps
        shelfIndex = shelfIndex.coerceIn(0, (shelf.size - 1).coerceAtLeast(0))
        if (menu.fromShelf) focusEpoch++
    }

    fun closeMenu() {
        val m = overlay as? Overlay.Menu ?: return
        overlay = if (m.fromShelf) Overlay.None else Overlay.AllApps
    }

    /** Move mode: Left/Right swaps with the neighbour. Returns the new shelf index. */
    fun moveBy(delta: Int) {
        val pkg = moving ?: return
        val next = layout.move(pkg, delta, installedPkgs)
        if (next != layout) applyLayout(next)
        shelfIndex = shelf.indexOfFirst { it.pkg == pkg }.coerceAtLeast(0)
    }

    fun endMove() {
        moving = null
        focusEpoch++
    }

    fun unhide(app: App) {
        applyLayout(layout.unhide(app.pkg))
        loadArt(listOf(app))
    }

    private fun applyLayout(l: Layout) {
        layout = l
        layoutStored = true
        store.saveLayout(l)
    }

    // =========================================================================================
    // Overlays

    fun openAllApps() { if (moving == null) overlay = Overlay.AllApps }

    fun openInputs() {
        if (moving != null) return
        refreshInputs()
        overlay = Overlay.Inputs
    }

    fun openCustomize() {
        if (moving != null) return
        refreshPhotos()
        loadThumbs()
        loadArt(hiddenApps)
        previewId = null
        overlay = Overlay.Customize
    }

    fun openSettings() {
        if (!platform.openSettings()) showToast("Couldn't open Settings")
    }

    /** Back from any overlay: returns to the home layer, focus where it was. */
    fun closeOverlay() {
        when (overlay) {
            is Overlay.Menu -> closeMenu()
            Overlay.Customize -> { commitPreview(); dropThumbs(); overlay = Overlay.None; focusEpoch++ }
            Overlay.None -> Unit
            else -> { overlay = Overlay.None; focusEpoch++ }
        }
    }

    /** Closes All apps, putting focus on [pkg]'s shelf tile if it has one. */
    fun closeAllApps(pkg: String?) {
        val i = shelf.indexOfFirst { it.pkg == pkg }
        if (i >= 0) shelfIndex = i
        inTopBar = false
        overlay = Overlay.None
        focusEpoch++
    }

    private fun closeEverything() {
        if (overlay == Overlay.Customize) { commitPreview(); dropThumbs() }
        overlay = Overlay.None
        if (moving != null) moving = null
        inTopBar = false
        focusEpoch++
    }

    private fun refreshInputs() {
        scope.launch {
            inputs = withContext(io) { runCatching { platform.inputs() }.getOrDefault(emptyList()) }
        }
    }

    fun switchInput(input: TvInput) {
        if (!platform.switchInput(input.id, input.label)) showToast("Couldn't switch to ${input.label}")
    }

    private fun showToast(text: String) {
        toastJob?.cancel()
        toast = text
        toastJob = scope.launch { delay(3000); toast = null }
    }

    // =========================================================================================
    // Photos

    private fun refreshPhotos() {
        scope.launch {
            val own = withContext(io) { runCatching { photoRepo.scanOwn() }.getOrDefault(emptyList()) }
            // Rohan's photos replace the built-in set entirely; the built-ins are only a fallback
            // for an empty folder, so the screen is never blank.
            val all: List<Photo> = own.ifEmpty { photoRepo.builtins }
            // New own photos start switched on.
            val seen = store.photosSeen()
            val fresh = own.map { it.id }.filter { it !in seen }
            if (fresh.isNotEmpty()) {
                photosOn = photosOn + fresh
                store.savePhotosOn(photosOn)
            }
            val nowSeen = seen + all.map { it.id }
            if (nowSeen != seen) store.savePhotosSeen(nowSeen)
            val ids = all.mapTo(HashSet()) { it.id }
            val on = photosOn.filterTo(HashSet()) { it in ids }
            if (on.isEmpty()) on += all.first().id
            if (on != photosOn) { photosOn = on; store.savePhotosOn(on) }
            if (all != photos) photos = all
            if (overlay == Overlay.Customize) loadThumbs()
            if (currentId !in ids) {
                currentId = nextOn(currentId)
                store.currentPhoto = currentId
            }
            if (wall?.photo?.id != currentId && overlay != Overlay.Customize && wallJob?.isActive != true) {
                showPhoto(currentId, FADE_PHOTO)
            }
        }
    }

    private fun photo(id: String): Photo? = photos.firstOrNull { it.id == id }

    /** Decodes [id] (small copy first for the glass, then full) and publishes it as the wall. */
    private fun showPhoto(id: String, fadeMs: Int, onPreview: Boolean = false) {
        val p = photo(id) ?: photos.first()
        val job = scope.launch {
            val frost = frostCache[p.id] ?: withContext(io) {
                photoRepo.small(p)?.let { small -> frostedCopy(small).also { small.recycle() }.asImageBitmap() }
            }?.also { remember(p.id, it) } ?: return@launch
            val full = withContext(io) { photoRepo.full(p) }?.asImageBitmap()
            publish(Wall(p, full, frost, fadeMs))
        }
        if (onPreview) { previewJob?.cancel(); previewJob = job } else { wallJob?.cancel(); wallJob = job }
    }

    /**
     * Swaps in a new wall and frees the old full-size photo (8 MB of graphics memory) once nothing
     * can still be drawing it: after the longest crossfade. Hardware bitmaps are otherwise only freed
     * when the Java object is collected, and this app's tiny heap means that can take a long time.
     */
    private fun publish(next: Wall) {
        val old = wall?.full
        wall = next
        if (old != null && old !== next.full) retire(old)
    }

    private fun retire(bitmap: ImageBitmap) {
        scope.launch {
            delay(RETIRE_AFTER_MS)
            if (wall?.full !== bitmap) runCatching { bitmap.asAndroidBitmap().recycle() }
        }
    }

    private fun remember(id: String, frost: ImageBitmap) {
        if (frostCache.size >= 8) frostCache.remove(frostCache.keys.first())
        frostCache[id] = frost
    }

    private fun nextOn(fromId: String): String {
        val order = photos.map { it.id }
        val on = order.filter { it in photosOn }
        if (on.isEmpty()) return order.first()
        val i = order.indexOf(fromId)
        for (k in 1..order.size) {
            val c = order[(i + k).mod(order.size)]
            if (c in photosOn) return c
        }
        return on.first()
    }

    private fun rotateIfDue(visible: Boolean) {
        val r = rotation
        if (r == Rotation.Never || overlay == Overlay.Customize) return
        val since = store.photoSince
        val t = clock()
        if (since == 0L) { store.photoSince = t; return }
        if (t - since < r.millis) return
        val next = nextOn(currentId)
        store.photoSince = t
        if (next == currentId) return
        currentId = next
        store.currentPhoto = next
        showPhoto(next, if (visible) FADE_PHOTO else 0)
    }

    private fun loadThumbs() {
        val missing = photos.filter { it.id !in thumbs }
        if (missing.isEmpty()) return
        scope.launch {
            for (p in missing) {
                val b = withContext(io) { photoRepo.small(p) } ?: continue
                thumbs[p.id] = Images.toDisplay(b).asImageBitmap()
            }
        }
    }

    private fun dropThumbs() {
        val old = thumbs.values.toList()
        thumbs.clear()
        scope.launch { delay(RETIRE_AFTER_MS); old.forEach { runCatching { it.asAndroidBitmap().recycle() } } }
    }

    /** Customize: focus landed on a thumbnail. Previews it after a short debounce. */
    fun preview(id: String) {
        if (previewId == id || (previewId == null && id == wall?.photo?.id)) { previewId = id; previewJob?.cancel(); return }
        previewId = id
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            showPhoto(id, FADE_PREVIEW, onPreview = true)
        }
    }

    fun togglePhoto(id: String) {
        val on = photosOn
        photosOn = if (id in on) { if (on.size == 1) return; on - id } else on + id
        store.savePhotosOn(photosOn)
    }

    fun changeRotation(r: Rotation) { rotation = r; store.rotation = r }
    fun changeNight(n: NightMode) { night = n; store.night = n }

    /** Leaving Customize: keep the previewed photo if it's on, else go back to one that is. */
    private fun commitPreview() {
        val shown = wall?.photo?.id ?: currentId
        val target = when {
            shown in photosOn -> shown
            currentId in photosOn -> currentId
            else -> nextOn(currentId)
        }
        previewId = null
        previewJob?.cancel()
        if (target != currentId) {
            currentId = target
            store.currentPhoto = target
            store.photoSince = clock()
        }
        if (target != shown) showPhoto(target, FADE_PREVIEW)
    }

    enum class MenuItem(val text: String) { Move("Move"), Add("Add to Home"), Remove("Remove from Home"), Hide("Hide") }

    companion object {
        const val FADE_PHOTO = 1800
        const val FADE_PREVIEW = 700
        const val FADE_SHARPEN = 320
        const val PREVIEW_DEBOUNCE_MS = 250L
        const val ART_WAIT_MS = 150L
        /** Longer than any crossfade (1.8 s), so a retired bitmap is never drawn again. */
        const val RETIRE_AFTER_MS = 2_600L
    }
}
