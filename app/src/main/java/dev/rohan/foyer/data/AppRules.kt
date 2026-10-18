package dev.rohan.foyer.data

/** What shows where: shelf, All apps, hidden, blocked. No Android types, so JVM-testable. */
object AppRules {

    /** Never shown anywhere. */
    private val blocked = setOf(
        "dev.rohan.foyer", "dev.rohan.foyer.dev", "dev.rohan.foyer.debug",
        "com.sean.foyer", "com.sean.foyer.dev", "com.sean.foyer.debug",
        "com.amazon.tv.launcher",
        "com.mediatek.opapp",
        "com.apple.airplay.amazon.airplay", "com.amazon.tv.earlyaccess",
        "com.amazon.tv.quicksettings.ui", "com.amazon.venezia",
        "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.ssm",
        "com.android.documentsui",
    )
    private val blockedPrefixes = listOf("com.vewd.core.")

    fun isBlocked(pkg: String): Boolean = pkg in blocked || blockedPrefixes.any { pkg.startsWith(it) }

    /** Hidden on first run; unhide in Customize or with `homectl unhide`. */
    val defaultHidden: Set<String> = setOf(
        "io.github.toolicious.homeonfire", "com.spocky.projengmenu", "com.esaba.downloader",
    )

    /** First-run shelf. After that, launcher/config.json (`homectl apply`) is the source of truth. */
    val defaultShelf: List<String> = listOf(
        "com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube",
        "com.stremio.one", "com.surfshark.vpnclient.android",
    )

    /** Names set over adb (`homectl label`); win over the built-in and system labels. */
    @Volatile var userLabels: Map<String, String> = emptyMap()

    private val fixedLabels = mapOf(
        "com.netflix.ninja" to "Netflix",
        "com.amazon.firebat" to "Prime Video",
        "in.startv.hotstar" to "JioHotstar",
        "com.amazon.firetv.youtube" to "YouTube",
        "com.apple.atve.amazon.appletv" to "Apple TV",
        "com.spotify.tv.android" to "Spotify",
        "com.onemainstream.sonyliv.android" to "Sony LIV",
        "com.zee5.amazon" to "ZEE5",
        "com.discoveryplus.tv.fire" to "discovery+",
        "tv.airtel.xstream.tvapp" to "Xstream Play",
    )

    private val artKeys = mapOf(
        "com.netflix.ninja" to "netflix",
        "com.amazon.firebat" to "primevideo",
        "in.startv.hotstar" to "jiohotstar",
        "com.amazon.firetv.youtube" to "youtube",
        "com.apple.atve.amazon.appletv" to "appletv",
        "com.spotify.tv.android" to "spotify",
        "com.onemainstream.sonyliv.android" to "sonyliv",
        "com.zee5.amazon" to "zee5",
        "com.discoveryplus.tv.fire" to "discoveryplus",
        "tv.airtel.xstream.tvapp" to "xstream",
    )

    fun label(pkg: String, systemLabel: String?): String =
        userLabels[pkg] ?: fixedLabels[pkg] ?: systemLabel?.trim()?.takeIf { it.isNotEmpty() } ?: pkg

    fun artKey(pkg: String): String? = artKeys[pkg]

    /** Alphabetical, case-insensitive, stable on package name for ties. */
    fun alphabetical(apps: List<App>): List<App> =
        apps.sortedWith(compareBy<App, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.pkg })

    /**
     * All apps order: shelf apps first in shelf order, then the rest alphabetically.
     * [visible] is every launchable app that isn't blocked or hidden.
     */
    fun allAppsOrder(visible: List<App>, shelf: List<String>): List<App> {
        val byPkg = visible.associateBy { it.pkg }
        val first = shelf.mapNotNull { byPkg[it] }
        val firstSet = first.mapTo(HashSet()) { it.pkg }
        return first + alphabetical(visible.filter { it.pkg !in firstSet })
    }
}

/** One launchable app. [banner] is true when the app ships a leanback banner. */
data class App(val pkg: String, val label: String)
