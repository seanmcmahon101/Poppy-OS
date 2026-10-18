package dev.rohan.foyer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class Rotation(val millis: Long) {
    Never(0), FiveMinutes(5 * 60 * 1000L), Hourly(60 * 60 * 1000L), Daily(24 * 60 * 60 * 1000L);
}

enum class NightMode { Auto, Always, Off }

/** Everything Foyer remembers, in one SharedPreferences file. Writes are async (apply). */
class LayoutStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("foyer", Context.MODE_PRIVATE)

    /** Null until the first real app scan has stored a layout. */
    fun layout(): Layout? {
        val shelf = prefs.getString(K_SHELF, null) ?: return null
        return Layout(
            shelf = shelf.split(',').filter { it.isNotEmpty() },
            hidden = prefs.getStringSet(K_HIDDEN, emptySet())!!.toSet(),
        )
    }

    fun saveLayout(layout: Layout) = prefs.edit {
        putString(K_SHELF, layout.shelf.joinToString(","))
        putStringSet(K_HIDDEN, layout.hidden.toSet())
    }

    /** Last-known app list (package + label), so the first frame needs no PackageManager query. */
    fun cachedApps(): List<App> =
        prefs.getString(K_APPS, null).orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else App(line.substring(0, tab), line.substring(tab + 1))
        }.toList()

    fun saveApps(apps: List<App>) = prefs.edit {
        putString(K_APPS, apps.joinToString("\n") { "${it.pkg}\t${it.label.replace('\n', ' ').replace('\t', ' ')}" })
    }

    /** Photo ids that are switched on; null means first run (all built-ins on). */
    fun photosOn(): Set<String>? = prefs.getStringSet(K_PHOTOS_ON, null)?.toSet()
    fun savePhotosOn(ids: Set<String>) = prefs.edit { putStringSet(K_PHOTOS_ON, ids.toSet()) }

    /** Photo ids Foyer has already seen, so new user photos can default to on. */
    fun photosSeen(): Set<String> = prefs.getStringSet(K_PHOTOS_SEEN, emptySet())!!.toSet()
    fun savePhotosSeen(ids: Set<String>) = prefs.edit { putStringSet(K_PHOTOS_SEEN, ids.toSet()) }

    var currentPhoto: String?
        get() = prefs.getString(K_PHOTO, null)
        set(v) = prefs.edit { putString(K_PHOTO, v) }

    /** When the current photo was shown (wall clock), for rotation. */
    var photoSince: Long
        get() = prefs.getLong(K_PHOTO_SINCE, 0L)
        set(v) = prefs.edit { putLong(K_PHOTO_SINCE, v) }

    var rotation: Rotation
        get() = prefs.getString(K_ROTATION, null)?.let { runCatching { Rotation.valueOf(it) }.getOrNull() } ?: Rotation.Never
        set(v) = prefs.edit { putString(K_ROTATION, v.name) }

    var night: NightMode
        get() = prefs.getString(K_NIGHT, null)?.let { runCatching { NightMode.valueOf(it) }.getOrNull() } ?: NightMode.Auto
        set(v) = prefs.edit { putString(K_NIGHT, v.name) }

    /** Night.Auto runs from [nightFrom]:00 to [nightTo]:00, local time. */
    var nightFrom: Int
        get() = prefs.getInt(K_NIGHT_FROM, 20)
        set(v) = prefs.edit { putInt(K_NIGHT_FROM, v) }
    var nightTo: Int
        get() = prefs.getInt(K_NIGHT_TO, 6)
        set(v) = prefs.edit { putInt(K_NIGHT_TO, v) }

    var clockSeconds: Boolean
        get() = prefs.getBoolean(K_CLOCK_SECONDS, true)
        set(v) = prefs.edit { putBoolean(K_CLOCK_SECONDS, v) }
    var clockDate: Boolean
        get() = prefs.getBoolean(K_CLOCK_DATE, false)
        set(v) = prefs.edit { putBoolean(K_CLOCK_DATE, v) }

    /** Tile names set over adb, package -> label. */
    var labels: Map<String, String>
        get() = prefs.getString(K_LABELS, null).orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
        set(v) = prefs.edit {
            putString(K_LABELS, v.entries.joinToString("\n") { (p, l) -> "$p\t${l.replace('\n', ' ').replace('\t', ' ')}" })
        }

    /** Back to first-run state (`homectl reset`). */
    fun clear() = prefs.edit(commit = true) { clear() }

    private companion object {
        const val K_SHELF = "shelf"
        const val K_HIDDEN = "hidden"
        const val K_APPS = "apps"
        const val K_PHOTOS_ON = "photos_on"
        const val K_PHOTOS_SEEN = "photos_seen"
        const val K_PHOTO = "photo"
        const val K_PHOTO_SINCE = "photo_since"
        const val K_ROTATION = "rotation"
        const val K_NIGHT = "night"
        const val K_NIGHT_FROM = "night_from"
        const val K_NIGHT_TO = "night_to"
        const val K_CLOCK_SECONDS = "clock_seconds"
        const val K_CLOCK_DATE = "clock_date"
        const val K_LABELS = "labels"
    }
}
