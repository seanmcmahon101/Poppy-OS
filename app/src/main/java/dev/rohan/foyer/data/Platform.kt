package dev.rohan.foyer.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import dev.rohan.foyer.R

/** What a tile shows. */
sealed interface Art {
    data class Picture(val bitmap: ImageBitmap) : Art
    /** No art or banner: the app icon on a dark colour taken from it, with the name. */
    data class Generated(val icon: ImageBitmap?, val color: Color) : Art
}

/** A physical input (HDMI etc.) from TvInputManager. [label] is the port ("HDMI 1"); [name] is Rohan's. */
data class TvInput(val id: String, val label: String, val connected: Boolean)

/** Everything Foyer needs from the system. Faked in tests. */
interface Platform {
    /** Blocking. Every launchable app that isn't blocklisted, one per package. */
    fun scanApps(): List<App>
    /** Blocking. */
    fun art(app: App): Art
    fun launch(pkg: String): Boolean
    fun openSettings(): Boolean
    fun inputs(): List<TvInput>
    fun switchInput(id: String, label: String): Boolean
}

/** Pending hop from the Settings-app screen to Amazon's main grid (see AndroidPlatform.openSettings). */
object SettingsHandoff {
    @Volatile var armedAt = 0L
    /** Foyer must stop within this long of the press for the hop to happen at all. */
    const val WINDOW_MS = 1_400L
    /**
     * When to hop, after the press. Late enough that Amazon's Settings screen has drawn (measured
     * 350-640 ms) so Home on Fire has seen it; early enough to stay inside its 2 s "recent OK" window.
     */
    const val HOP_AT_MS = 1_050L

    /** Delay until the hop, or -1 if none is due. */
    fun take(): Long {
        val since = android.os.SystemClock.uptimeMillis() - armedAt
        armedAt = 0L
        return if (since < WINDOW_MS) (HOP_AT_MS - since).coerceAtLeast(150L) else -1L
    }
}

class AndroidPlatform(private val context: Context) : Platform {
    private val pm: PackageManager = context.packageManager

    override fun scanApps(): List<App> {
        val byPkg = LinkedHashMap<String, ResolveInfo>()
        fun query(category: String) =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        query(Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { byPkg.putIfAbsent(it.activityInfo.packageName, it) }
        query(Intent.CATEGORY_LAUNCHER).forEach { byPkg.putIfAbsent(it.activityInfo.packageName, it) }
        return byPkg.values
            .filter { !AppRules.isBlocked(it.activityInfo.packageName) }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                App(pkg, AppRules.label(pkg, runCatching { ri.loadLabel(pm).toString() }.getOrNull()))
            }
    }

    override fun art(app: App): Art {
        AppRules.artKey(app.pkg)?.let { _ ->
            val id = bundledArt(context, app.pkg)
            if (id != null) Images.resource(context.resources, id, Images.ART_W, Images.ART_H)?.let { return Art.Picture(it.asImageBitmap()) }
        }
        val banner = runCatching {
            val launch = pm.getLeanbackLaunchIntentForPackage(app.pkg)
            launch?.component?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(app.pkg)
        }.getOrNull()
        if (banner != null) return Art.Picture(Images.fromDrawable(banner, Images.ART_W, Images.ART_H).asImageBitmap())
        val icon = runCatching { pm.getApplicationIcon(app.pkg).toBitmap(80, 80, Bitmap.Config.ARGB_8888) }.getOrNull()
        val color = icon?.let { darkTone(it) } ?: Color(0xFF1A1D24)
        return Art.Generated(icon?.let { Images.toDisplay(it).asImageBitmap() }, color)
    }

    /** The icon's average colour, darkened to about 25% luminance. */
    private fun darkTone(icon: Bitmap): Color {
        val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(one).drawBitmap(Bitmap.createScaledBitmap(icon, 1, 1, true), 0f, 0f, null)
        val avg = one.getPixel(0, 0)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(avg or (0xFF shl 24), hsl)
        hsl[2] = 0.18f
        hsl[1] = hsl[1].coerceAtMost(0.55f)
        return Color(ColorUtils.HSLToColor(hsl) or AColor.BLACK)
    }

    override fun launch(pkg: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Lands on Amazon's main Settings grid, even with Home on Fire redirecting Home.
     *
     * The grid lives inside Amazon's home app: ACTION_SETTINGS resolves to
     * com.amazon.tv.launcher/.ui.SettingsActivity, a NoDisplay trampoline into HomeActivity_vNext.
     * Started straight from Foyer, Home on Fire reads that as a Home press and bounces back.
     * Home on Fire leaves Amazon's home alone when it arrives from one of Amazon's own shell
     * screens within 2 s of an OK/Back press (HijackService.handleAmazonHomeArrival: "deliberate
     * move inside the Amazon shell"). So: open a Settings-app screen first (the OK on Foyer's
     * button is the recent key), and once it's in front (MainActivity.onStop) hop to the grid.
     * Without Home on Fire the same two steps just land on the grid.
     */
    override fun openSettings(): Boolean {
        for (action in SETTINGS_ACTIONS) {
            val ri = pm.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY) ?: continue
            val pkg = ri.activityInfo.packageName
            if (pkg == "com.amazon.tv.launcher" || pkg == "android") continue // trampoline or chooser
            val intent = Intent(action).setClassName(pkg, ri.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (start(intent)) {
                // The hop needs the background-launch exemption; without it the user stays on
                // this Settings screen, which still reaches Network, Storage, About and so on.
                if (Settings.canDrawOverlays(context)) SettingsHandoff.armedAt = android.os.SystemClock.uptimeMillis()
                return true
            }
        }
        return start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Second step of [openSettings]: the main grid. Named explicitly: from an app, the implicit
     * ACTION_SETTINGS can resolve to Amazon's do-nothing CTSDummySettingsHandler instead.
     */
    fun openSettingsGrid(): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        return start(Intent(Settings.ACTION_SETTINGS).setClassName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.SettingsActivity").addFlags(flags)) ||
            start(Intent(Settings.ACTION_SETTINGS).addFlags(flags))
    }

    override fun inputs(): List<TvInput> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val physical = setOf(TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_COMPOSITE,
            TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART, TvInputInfo.TYPE_VGA, TvInputInfo.TYPE_DVI, TvInputInfo.TYPE_DISPLAY_PORT)
        return runCatching {
            tim.tvInputList
                .filter { it.type in physical && it.parentId == null }
                .map { info ->
                    val label = (info.loadCustomLabel(context) ?: info.loadLabel(context))?.toString()?.trim().orEmpty()
                    TvInput(info.id, label.ifEmpty { "Input" }, tim.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED)
                }
                .sortedWith(compareBy({ inputOrder(it.label) }, { it.label }))
        }.getOrElse { Log.w(TAG, "inputs", it); emptyList() }
    }

    private fun inputOrder(label: String): Int = Regex("(\\d+)").find(label)?.value?.toIntOrNull() ?: 99

    /**
     * Foyer plays the input itself (InputActivity + TvView). On Fire OS 8 the passthrough URI only
     * resolves to Live TV's alias, which opens Live TV rather than the input (Rohan, 2026-10-12), and
     * Amazon's PassthroughPlayerActivity needs a signature permission.
     */
    override fun switchInput(id: String, label: String): Boolean = start(
        Intent(context, dev.rohan.foyer.InputActivity::class.java)
            .putExtra(dev.rohan.foyer.InputActivity.EXTRA_INPUT_ID, id)
            .putExtra(dev.rohan.foyer.InputActivity.EXTRA_LABEL, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "start $intent", e)
        false
    }

    companion object {
        private const val TAG = "Foyer"
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        /** Ordered by how close to the top of Settings each lands ("My Fire TV" first). */
        private val SETTINGS_ACTIONS = listOf(
            Settings.ACTION_DEVICE_INFO_SETTINGS,
            Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_APPLICATION_SETTINGS,
            Settings.ACTION_DISPLAY_SETTINGS,
            Settings.ACTION_DATE_SETTINGS,
        )
        private val artIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /**
         * Optional brand art: an art_<key> drawable from app/local/res, which stays out of git because
         * the logos belong to their owners. Found by name, since a checkout may have none of it;
         * res/raw/keep.xml stops the resource shrinker from dropping it. Null means "use the banner".
         */
        @android.annotation.SuppressLint("DiscouragedApi")
        fun bundledArt(context: Context, pkg: String): Int? {
            val key = AppRules.artKey(pkg) ?: return null
            return artIds.getOrPut(key) { context.resources.getIdentifier("art_$key", "drawable", context.packageName) }
                .takeIf { it != 0 }
        }
    }
}
