package dev.rohan.foyer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.rohan.foyer.data.AndroidPlatform
import dev.rohan.foyer.data.App
import dev.rohan.foyer.data.AppRules
import dev.rohan.foyer.data.Layout
import dev.rohan.foyer.data.LayoutStore
import dev.rohan.foyer.data.NightMode
import dev.rohan.foyer.data.Rotation
import org.json.JSONArray
import org.json.JSONObject

/**
 * adb's way in. The manifest guards it with android.permission.DUMP, which on a retail Fire TV only
 * `adb shell` holds, so no app on the TV can send it commands. Every command answers through the
 * ordered broadcast's result data, which `am broadcast` prints:
 *
 *   adb shell am broadcast -n com.sean.foyer/dev.rohan.foyer.ControlReceiver --es cmd get
 *
 * launcher/homectl wraps this. Commands work whether or not Foyer is on screen: they write
 * LayoutStore, and a running Foyer re-reads it (FoyerModel.reload). Only photo/rescan/ui need it running.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (code, out) = try {
            0 to Control(context.applicationContext).run(intent)
        } catch (e: ControlError) {
            1 to "error: ${e.message}"
        } catch (e: Exception) {
            1 to "error: $e"
        }
        resultCode = code
        resultData = out
    }
}

class ControlError(message: String) : Exception(message)

class Control(private val context: Context) {
    private val store = LayoutStore(context)
    private val platform = AndroidPlatform(context)
    private val apps: List<App> by lazy { AppRules.alphabetical(platform.scanApps()) }

    fun run(i: Intent): String {
        val cmd = i.getStringExtra("cmd") ?: "help"
        val arg = i.getStringExtra("arg")
        val arg2 = i.getStringExtra("arg2")
        return when (cmd) {
            "help" -> HELP
            "get" -> state().toString(2)
            "config" -> config().toString(2)
            "apps" -> apps.joinToString("\n") { "${it.pkg}\t${it.label}" }
            "apply" -> {
                val b64 = i.getStringExtra("b64") ?: throw ControlError("apply needs --es b64 <base64 json>")
                val json = runCatching { JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)) }
                    .getOrElse { throw ControlError("not valid json: ${it.message}") }
                apply(json)
                changed()
                config().toString(2)
            }
            "set" -> {
                val key = need(arg, "set <key> <value>")
                apply(setting(key, need(arg2, "set $key <value>")))
                changed()
                config().toString(2)
            }
            "add" -> shelf(arg) { l, p ->
                val added = l.addToShelf(p)
                arg2?.let { added.moveTo(p, position(it), installed()) } ?: added
            }
            "remove" -> shelf(arg) { l, p -> l.removeFromShelf(p) }
            "hide" -> shelf(arg) { l, p -> l.hide(p) }
            "unhide" -> shelf(arg) { l, p -> l.unhide(p) }
            "move" -> shelf(arg) { l, p ->
                if (!l.isOnShelf(p)) throw ControlError("$p isn't on the home row; add it first")
                l.moveTo(p, position(need(arg2, "move <app> <position>")), installed())
            }
            "label" -> {
                val p = pkg(need(arg, "label <app> <name>"))
                val name = arg2?.trim().orEmpty()
                store.labels = if (name.isEmpty()) store.labels - p else store.labels + (p to name)
                changed()
                "${p}\t${name.ifEmpty { "(label cleared)" }}"
            }
            "launch" -> {
                val p = pkg(need(arg, "launch <app>"))
                if (!platform.launch(p)) throw ControlError("couldn't launch $p")
                "launched $p"
            }
            "photo" -> {
                val m = running()
                when (val q = need(arg, "photo next|<name>")) {
                    "next" -> m.nextPhoto()
                    else -> if (!m.showPhotoNamed(q)) throw ControlError("no photo called $q (see: homectl get)")
                }
                "ok"
            }
            "rescan" -> { running().reload(); "ok" }
            "ui" -> {
                val where = need(arg, "ui home|apps|customize|settings")
                if (!running().showUi(where)) throw ControlError("ui takes home, apps, customize or settings")
                "ok"
            }
            "reset" -> { store.clear(); changed(); "reset to first-run defaults" }
            else -> throw ControlError("unknown command '$cmd'\n\n$HELP")
        }
    }

    // ---- shelf ----------------------------------------------------------------------------------

    private fun layout(): Layout = store.layout() ?: Layout.firstRun(installed())
    private fun installed(): Set<String> = apps.mapTo(HashSet()) { it.pkg }

    /** Applies [op] to the app named by [q], saves, and answers with the new home row. */
    private inline fun shelf(q: String?, op: (Layout, String) -> Layout): String {
        val p = pkg(need(q, "<command> <app>"))
        val next = op(layout(), p)
        store.saveLayout(next)
        changed()
        val byPkg = apps.associateBy { it.pkg }
        return next.visibleShelf(installed()).mapIndexed { i, s -> "${i + 1}. ${byPkg[s]?.label ?: s}\t$s" }
            .joinToString("\n").ifEmpty { "(home row is empty)" }
    }

    /** 1-based position on the visible home row, as people count. */
    private fun position(s: String): Int =
        (s.toIntOrNull() ?: throw ControlError("position must be a number, got '$s'")).coerceAtLeast(1) - 1

    /**
     * An app from what a person would type: package name, exact label, label prefix, then label or
     * package substring, spaces ignored for the package ("netflix", "prime", "channel 4", "com.stremio.one"). Several equally good matches is an
     * error that lists them. Something that looks like a package is accepted even if not installed,
     * so a row can name an app before it's installed.
     */
    fun pkg(q: String): String {
        val s = q.trim()
        apps.firstOrNull { it.pkg == s }?.let { return it.pkg }
        val tiers = listOf<(App) -> Boolean>(
            { it.label.equals(s, ignoreCase = true) },
            { it.label.startsWith(s, ignoreCase = true) },
            { it.label.contains(s, ignoreCase = true) || it.pkg.contains(s.replace(" ", ""), ignoreCase = true) },
        )
        for (t in tiers) {
            val hits = apps.filter(t)
            if (hits.size == 1) return hits[0].pkg
            if (hits.size > 1) throw ControlError("'$s' matches ${hits.size} apps:\n" + hits.joinToString("\n") { "  ${it.pkg}\t${it.label}" })
        }
        if (Regex("^[A-Za-z][\\w]*(\\.[\\w]+)+$").matches(s)) return s
        throw ControlError("no app matches '$s' (see: homectl apps)")
    }

    // ---- config ---------------------------------------------------------------------------------

    /** The same shape as launcher/config.json. */
    fun config(): JSONObject {
        val l = layout()
        return JSONObject()
            .put("shelf", JSONArray(l.shelf))
            .put("hidden", JSONArray(l.hidden.sorted()))
            .put("labels", JSONObject(store.labels.toSortedMap() as Map<*, *>))
            .put("clock", JSONObject().put("seconds", store.clockSeconds).put("date", store.clockDate))
            .put("night", JSONObject().put("mode", store.night.name).put("from", store.nightFrom).put("to", store.nightTo))
            .put("photos", JSONObject().put("rotation", store.rotation.name))
    }

    /** Each top-level key present replaces that part of the settings; absent keys are left alone. */
    fun apply(j: JSONObject) {
        val unknown = j.keys().asSequence().toList() - setOf("shelf", "hidden", "labels", "clock", "night", "photos", "\$comment")
        if (unknown.isNotEmpty()) throw ControlError("unknown key(s) ${unknown.joinToString()}; allowed: shelf hidden labels clock night photos")
        if (j.has("shelf") || j.has("hidden")) {
            val l = layout()
            store.saveLayout(
                Layout(
                    shelf = if (j.has("shelf")) strings(j.getJSONArray("shelf")).distinct() else l.shelf,
                    hidden = if (j.has("hidden")) strings(j.getJSONArray("hidden")).toSet() else l.hidden,
                )
            )
        }
        j.optJSONObject("labels")?.let { o -> store.labels = o.keys().asSequence().associateWith { o.getString(it) } }
        j.optJSONObject("clock")?.let { o ->
            if (o.has("seconds")) store.clockSeconds = o.getBoolean("seconds")
            if (o.has("date")) store.clockDate = o.getBoolean("date")
        }
        j.optJSONObject("night")?.let { o ->
            if (o.has("mode")) store.night = enumOf<NightMode>(o.getString("mode"))
            if (o.has("from")) store.nightFrom = hour(o.getInt("from"))
            if (o.has("to")) store.nightTo = hour(o.getInt("to"))
        }
        j.optJSONObject("photos")?.let { o ->
            if (o.has("rotation")) store.rotation = enumOf<Rotation>(o.getString("rotation"))
        }
    }

    /** `homectl set <key> <value>` as a config fragment. */
    private fun setting(key: String, v: String): JSONObject = when (key) {
        "seconds" -> JSONObject().put("clock", JSONObject().put("seconds", bool(v)))
        "date" -> JSONObject().put("clock", JSONObject().put("date", bool(v)))
        "night" -> JSONObject().put("night", JSONObject().put("mode", v))
        "night_from" -> JSONObject().put("night", JSONObject().put("from", v.toIntOrNull() ?: throw ControlError("hour 0-23")))
        "night_to" -> JSONObject().put("night", JSONObject().put("to", v.toIntOrNull() ?: throw ControlError("hour 0-23")))
        "rotation" -> JSONObject().put("photos", JSONObject().put("rotation", v))
        else -> throw ControlError("set takes seconds, date, night, night_from, night_to or rotation")
    }

    // ---- state ----------------------------------------------------------------------------------

    private fun state(): JSONObject {
        val l = layout()
        val inst = installed()
        val byPkg = apps.associateBy { it.pkg }
        val visible = l.visibleShelf(inst)
        val m = FoyerModel.live
        val out = JSONObject()
            .put("foyer", JSONObject()
                .put("package", context.packageName)
                .put("version", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull())
                .put("running", m != null)
                .put("onScreen", m?.visible == true)
                .put("overlay", when (m?.overlay) { // spelled out: R8 renames the classes
                    null -> null
                    Overlay.None -> "none"
                    Overlay.AllApps -> "apps"
                    Overlay.Customize -> "customize"
                    is Overlay.Menu -> "menu"
                })
                .put("nightOn", m?.nightOn))
            .put("config", config())
            .put("homeRow", JSONArray(visible.map { JSONObject().put("pkg", it).put("label", byPkg[it]?.label) }))
            .put("notInstalled", JSONArray(l.shelf.filter { it !in inst }))
            .put("apps", JSONArray(apps.map { a ->
                JSONObject().put("pkg", a.pkg).put("label", a.label)
                    .put("row", visible.indexOf(a.pkg).let { if (it < 0) JSONObject.NULL else it + 1 })
                    .put("hidden", a.pkg in l.hidden)
            }))
        if (m != null) {
            val (photos, on, current) = m.photoState()
            out.put("photos", JSONArray(photos.map { p ->
                JSONObject().put("id", p.id).put("caption", p.caption).put("on", p.id in on).put("shown", p.id == current)
            }))
        }
        return out
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun changed() { FoyerModel.live?.reload() }

    private fun running(): FoyerModel =
        FoyerModel.live ?: throw ControlError("Foyer isn't running; open it first (homectl open)")

    private fun need(v: String?, usage: String): String = v?.takeIf { it.isNotBlank() } ?: throw ControlError("usage: $usage")

    private fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it).trim() }.filter { it.isNotEmpty() }

    private fun bool(v: String): Boolean = when (v.lowercase()) {
        "on", "true", "yes", "1" -> true
        "off", "false", "no", "0" -> false
        else -> throw ControlError("expected on/off, got '$v'")
    }

    private fun hour(h: Int): Int = h.takeIf { it in 0..23 } ?: throw ControlError("hour must be 0-23, got $h")

    private inline fun <reified E : Enum<E>> enumOf(v: String): E =
        enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) }
            ?: throw ControlError("'$v' isn't one of ${enumValues<E>().joinToString { it.name }}")

    companion object {
        const val HELP = """Foyer over adb (launcher/homectl wraps these):
  get                       everything: settings, home row, apps, photos, what's on screen
  config                    settings only, in launcher/config.json's shape
  apps                      installed apps: package <tab> label
  apply  (b64=<json>)       replace the parts of the settings named in the json
  set <key> <value>         seconds on|off, date on|off, night Auto|Always|Off, night_from 0-23, night_to 0-23, rotation Never|FiveMinutes|Hourly|Daily
  add <app> [position]      put an app on the home row (end, or 1-based position)
  remove <app>              take it off the home row
  move <app> <position>     move it along the home row
  hide <app> / unhide <app> hide from All apps (and the row) / bring back
  label <app> [name]        rename a tile; no name clears it
  launch <app>              open an app
  photo next|<name>         change the wallpaper now            (Foyer must be running)
  rescan                    re-read apps, photos and settings   (Foyer must be running)
  ui home|apps|customize|settings                               (Foyer must be running)
  reset                     forget everything, back to first-run defaults
<app> is a package or any unambiguous part of its name: netflix, prime, stremio."""
    }
}
