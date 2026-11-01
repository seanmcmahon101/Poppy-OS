package dev.rohan.foyer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Foyer's settings in config.json's shape. Every part is optional: a file names only the parts it
 * sets, `apply` writes only those and `diff` compares only those. [parse] checks the whole file
 * before anything is written, so a file with one bad value changes nothing.
 *
 * Only org.json and plain Kotlin, so it's JVM-testable.
 */
data class Settings(
    val shelf: List<String>? = null,
    val hidden: Set<String>? = null,
    val labels: Map<String, String>? = null,
    val seconds: Boolean? = null,
    val date: Boolean? = null,
    val night: NightMode? = null,
    val nightFrom: Int? = null,
    val nightTo: Int? = null,
    val rotation: Rotation? = null,
) {
    /**
     * config.json's shape: parts in a fixed order, hidden and labels sorted. With [comment], the
     * output is exactly what config.json holds, so `homectl pull` after `homectl apply` changes nothing.
     */
    fun toJson(comment: String? = null): JSONObject {
        val j = JSONObject()
        comment?.let { j.put("\$comment", it) }
        shelf?.let { j.put("shelf", JSONArray(it)) }
        hidden?.let { j.put("hidden", JSONArray(it.sorted())) }
        labels?.let { j.put("labels", JSONObject(it.toSortedMap() as Map<*, *>)) }
        if (seconds != null || date != null) {
            j.put("clock", JSONObject().apply { seconds?.let { put("seconds", it) }; date?.let { put("date", it) } })
        }
        if (night != null || nightFrom != null || nightTo != null) {
            j.put("night", JSONObject().apply {
                night?.let { put("mode", it.name) }
                nightFrom?.let { put("from", it) }
                nightTo?.let { put("to", it) }
            })
        }
        rotation?.let { j.put("photos", JSONObject().put("rotation", it.name)) }
        return j
    }

    /**
     * What applying [want] over these settings would change: one entry per differing part, only for
     * the parts [want] names. Empty means in sync. [here] and [there] name the two sides.
     */
    fun diff(want: Settings, here: String = "TV", there: String = "file"): List<String> = buildList {
        fun <T> scalar(name: String, a: T?, b: T?) {
            if (b != null && a != b) add("$name: $here ${a ?: "(unset)"} · $there $b")
        }
        val w = maxOf(here.length, there.length)
        if (want.shelf != null && want.shelf != shelf) {
            add(
                "shelf:\n" +
                    "  ${here.padEnd(w)}  ${shelf.orEmpty().joinToString().ifEmpty { "(empty)" }}\n" +
                    "  ${there.padEnd(w)}  ${want.shelf.joinToString().ifEmpty { "(empty)" }}"
            )
        }
        if (want.hidden != null && want.hidden != hidden) {
            val onlyHere = hidden.orEmpty() - want.hidden
            val onlyThere = want.hidden - hidden.orEmpty()
            add(
                "hidden:" +
                    (if (onlyHere.isNotEmpty()) " only on $here ${onlyHere.sorted().joinToString()}" else "") +
                    (if (onlyHere.isNotEmpty() && onlyThere.isNotEmpty()) " ·" else "") +
                    (if (onlyThere.isNotEmpty()) " only in $there ${onlyThere.sorted().joinToString()}" else "")
            )
        }
        if (want.labels != null && want.labels != labels) {
            val mine = labels.orEmpty()
            for (pkg in (mine.keys + want.labels.keys).sorted()) {
                val a = mine[pkg]
                val b = want.labels[pkg]
                if (a != b) add("labels.$pkg: $here ${a?.let { "\"$it\"" } ?: "(none)"} · $there ${b?.let { "\"$it\"" } ?: "(none)"}")
            }
        }
        scalar("clock.seconds", seconds, want.seconds)
        scalar("clock.date", date, want.date)
        scalar("night.mode", night, want.night)
        scalar("night.from", nightFrom, want.nightFrom)
        scalar("night.to", nightTo, want.nightTo)
        scalar("photos.rotation", rotation, want.rotation)
    }

    companion object {
        /** Kept at the top of config.json; no slashes, because org.json writes "/" as "\/". */
        const val COMMENT = "Foyer settings. Edit, then homectl apply (-t living for the Max, -t all for both). " +
            "homectl diff shows what differs from the TV; homectl pull saves the TV's live settings back here. " +
            "Apps can be named before they're installed."

        private val SECTIONS = listOf("shelf", "hidden", "labels", "clock", "night", "photos")

        /**
         * Reads and checks a whole settings file. Keys starting with "$" are comments. Throws
         * [IllegalArgumentException] listing every problem found, not just the first.
         */
        fun parse(j: JSONObject): Settings {
            val problems = ArrayList<String>()
            var shelf: List<String>? = null
            var hidden: Set<String>? = null
            var labels: Map<String, String>? = null
            var seconds: Boolean? = null
            var date: Boolean? = null
            var night: NightMode? = null
            var nightFrom: Int? = null
            var nightTo: Int? = null
            var rotation: Rotation? = null

            for (key in j.keys().asSequence().toList()) {
                val v = j.get(key)
                when (key) {
                    "shelf" -> shelf = packages(key, v, problems)?.distinct()
                    "hidden" -> hidden = packages(key, v, problems)?.toSet()
                    "labels" -> labels = labels(v, problems)
                    "clock" -> section(key, v, problems, "seconds", "date") { k, x ->
                        when (k) {
                            "seconds" -> seconds = bool("clock.seconds", x, problems)
                            "date" -> date = bool("clock.date", x, problems)
                        }
                    }
                    "night" -> section(key, v, problems, "mode", "from", "to") { k, x ->
                        when (k) {
                            "mode" -> night = enumOf<NightMode>("night.mode", x, problems)
                            "from" -> nightFrom = hour("night.from", x, problems)
                            "to" -> nightTo = hour("night.to", x, problems)
                        }
                    }
                    "photos" -> section(key, v, problems, "rotation") { _, x ->
                        rotation = enumOf<Rotation>("photos.rotation", x, problems)
                    }
                    else -> if (!key.startsWith("$")) problems += "$key: unknown setting (allowed: ${SECTIONS.joinToString()})"
                }
            }
            when (problems.size) {
                0 -> return Settings(shelf, hidden, labels, seconds, date, night, nightFrom, nightTo, rotation)
                1 -> throw IllegalArgumentException(problems[0])
                else -> throw IllegalArgumentException("${problems.size} problems:\n" + problems.joinToString("\n") { "  $it" })
            }
        }

        private fun packages(name: String, v: Any, problems: MutableList<String>): List<String>? {
            if (v !is JSONArray) { problems += "$name: must be a list of package names"; return null }
            val out = ArrayList<String>(v.length())
            for (i in 0 until v.length()) {
                val s = (v.get(i) as? String)?.trim()
                when {
                    s == null -> problems += "$name[$i]: must be a package name in quotes"
                    !AppRules.looksLikePackage(s) -> problems += "$name[$i]: '$s' isn't a package name (homectl apps lists them)"
                    else -> out += s
                }
            }
            return out
        }

        private fun labels(v: Any, problems: MutableList<String>): Map<String, String>? {
            if (v !is JSONObject) { problems += "labels: must be an object of package: name"; return null }
            val out = LinkedHashMap<String, String>()
            for (pkg in v.keys().asSequence().toList()) {
                val name = (v.get(pkg) as? String)?.trim()
                when {
                    !AppRules.looksLikePackage(pkg) -> problems += "labels: '$pkg' isn't a package name (homectl apps lists them)"
                    name == null -> problems += "labels.$pkg: must be a name in quotes"
                    name.isEmpty() -> problems += "labels.$pkg: is empty; leave it out to use the app's own name"
                    else -> out[pkg] = name
                }
            }
            return out
        }

        private inline fun section(
            name: String, v: Any, problems: MutableList<String>, vararg allowed: String, read: (String, Any) -> Unit,
        ) {
            if (v !is JSONObject) { problems += "$name: must be an object with ${allowed.joinToString()}"; return }
            for (k in v.keys().asSequence().toList()) {
                if (k in allowed) read(k, v.get(k))
                else if (!k.startsWith("$")) problems += "$name.$k: unknown key (allowed: ${allowed.joinToString()})"
            }
        }

        private fun bool(name: String, v: Any, problems: MutableList<String>): Boolean? {
            if (v is Boolean) return v
            problems += "$name: must be true or false, got ${show(v)}"
            return null
        }

        private fun hour(name: String, v: Any, problems: MutableList<String>): Int? {
            val d = (v as? Number)?.toDouble()
            if (d != null && d == Math.floor(d) && d in 0.0..23.0) return d.toInt()
            problems += "$name: must be an hour 0-23, got ${show(v)}"
            return null
        }

        private inline fun <reified E : Enum<E>> enumOf(name: String, v: Any, problems: MutableList<String>): E? {
            val hit = (v as? String)?.let { s -> enumValues<E>().firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } }
            if (hit == null) problems += "$name: ${show(v)} isn't one of ${enumValues<E>().joinToString { it.name }}"
            return hit
        }

        private fun show(v: Any): String = if (v is String) "'$v'" else v.toString()
    }
}
