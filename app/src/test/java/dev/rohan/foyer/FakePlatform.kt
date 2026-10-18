package dev.rohan.foyer

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import dev.rohan.foyer.data.AndroidPlatform
import dev.rohan.foyer.data.App
import dev.rohan.foyer.data.AppRules
import dev.rohan.foyer.data.Art
import dev.rohan.foyer.data.Images
import dev.rohan.foyer.data.Platform
import dev.rohan.foyer.data.TvInput

/** The living-room TV's apps, with bundled art; records what Foyer asked the system to do. */
class FakePlatform(private val context: Context) : Platform {
    val launched = mutableListOf<String>()
    val switched = mutableListOf<String>()
    var settingsOpened = 0
    var failLaunch = false

    var apps = listOf(
        "com.netflix.ninja" to "Netflix", "com.amazon.firebat" to "Prime Video",
        "in.startv.hotstar" to "JioHotstar", "com.amazon.firetv.youtube" to "YouTube",
        "com.apple.atve.amazon.appletv" to "Apple TV", "com.spotify.tv.android" to "Spotify",
        "com.onemainstream.sonyliv.android" to "Sony LIV", "com.zee5.amazon" to "ZEE5",
        "com.discoveryplus.tv.fire" to "discovery+", "tv.airtel.xstream.tvapp" to "Xstream Play",
        "com.stremio.one" to "Stremio", "com.fancode.tv" to "FanCode",
        "com.tailscale.ipn" to "Tailscale", "com.esaba.downloader" to "Downloader",
        "com.amazon.tv.launcher" to "Amazon Home",
    ).map { (p, l) -> App(p, AppRules.label(p, l)) }.filter { !AppRules.isBlocked(it.pkg) }

    override fun scanApps(): List<App> = apps

    override fun art(app: App): Art {
        AndroidPlatform.bundledArt(context, app.pkg)?.let { id ->
            Images.resource(context.resources, id, Images.ART_W, Images.ART_H)?.let { return Art.Picture(it.asImageBitmap()) }
        }
        val tone = when (app.pkg) {
            "com.stremio.one" -> Color(0xFF2A1F4A)
            "com.fancode.tv" -> Color(0xFF3A2414)
            else -> Color(0xFF1E2A33)
        }
        return Art.Generated(null, tone)
    }

    override fun launch(pkg: String): Boolean { if (failLaunch) return false; launched += pkg; return true }
    override fun openSettings(): Boolean { settingsOpened++; return true }
    override fun inputs() = listOf(
        TvInput("com.mediatek.tis/.HdmiInputService/HW2", "HDMI 1", connected = true),
        TvInput("com.mediatek.tis/.HdmiInputService/HW3", "HDMI 2", connected = false),
        TvInput("com.mediatek.tis/.HdmiInputService/HW4", "HDMI 3", connected = false),
    )
    override fun switchInput(id: String, label: String): Boolean { switched += id; return true }
}
