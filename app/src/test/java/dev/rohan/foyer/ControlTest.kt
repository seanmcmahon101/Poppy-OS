package dev.rohan.foyer

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rohan.foyer.data.AppRules
import dev.rohan.foyer.data.LayoutStore
import dev.rohan.foyer.data.NightMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The adb control channel, as homectl drives it (Foyer not running). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ControlTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val store = LayoutStore(ctx)

    @After fun forgetLabels() { AppRules.userLabels = emptyMap() }

    private fun run(cmd: String, arg: String? = null, arg2: String? = null, json: String? = null): String {
        val i = Intent().putExtra("cmd", cmd)
        arg?.let { i.putExtra("arg", it) }
        arg2?.let { i.putExtra("arg2", it) }
        json?.let { i.putExtra("b64", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)) }
        return Control(ctx).run(i)
    }

    private fun error(cmd: String, arg: String? = null, arg2: String? = null, json: String? = null): String = try {
        run(cmd, arg, arg2, json); fail("expected $cmd to fail"); ""
    } catch (e: ControlError) {
        e.message.orEmpty()
    }

    private val good = """{"shelf": ["com.netflix.ninja", "com.stremio.one"], "hidden": ["com.esaba.downloader"],
        "labels": {"com.bskyb.nowtv.beta": "NOW"}, "clock": {"seconds": false, "date": true},
        "night": {"mode": "Always", "from": 21, "to": 7}, "photos": {"rotation": "Daily"}}"""

    @Test fun applyWritesEveryPartAndConfigReadsItBack() {
        val out = JSONObject(run("apply", json = good))
        assertEquals("\$comment", out.keys().next())
        assertEquals(listOf("com.netflix.ninja", "com.stremio.one"), store.layout()!!.shelf)
        assertEquals(mapOf("com.bskyb.nowtv.beta" to "NOW"), store.labels)
        assertEquals(false, store.clockSeconds)
        assertEquals(NightMode.Always, store.night)
        assertEquals(21, store.nightFrom)
        assertEquals("in sync", run("diff", json = good))
    }

    @Test fun aBadFileChangesNothing() {
        run("apply", json = good)
        val before = run("config")
        val m = error("apply", json = """{"shelf": ["com.netflix.ninja"], "clock": {"seconds": true}, "night": {"from": 25}}""")
        assertTrue(m, m.contains("night.from: must be an hour 0-23, got 25"))
        assertTrue(m, m.contains("nothing was changed"))
        assertEquals(before, run("config"))
    }

    @Test fun diffListsWhatApplyWouldChange() {
        run("apply", json = good)
        run("set", "seconds", "on")
        val d = run("diff", json = good)
        assertTrue(d, d.startsWith("1 difference (TV vs file):"))
        assertTrue(d, d.contains("clock.seconds: TV true · file false"))
    }

    @Test fun setChecksItsValue() {
        assertTrue(error("set", "night_from", "25").contains("night.from: must be an hour 0-23, got 25"))
        assertTrue(error("set", "rotation", "weekly").contains("isn't one of Never, FiveMinutes, Hourly, Daily"))
        run("set", "night", "off")
        assertEquals(NightMode.Off, store.night)
    }

    @Test fun whichResolvesPackagesAndRefusesNonsense() {
        assertEquals("com.example.notyet", run("which", "com.example.notyet"))
        assertTrue(error("which", "no such app").startsWith("no app matches"))
    }

    @Test fun aColdProcessStillKnowsTheTileNames() {
        store.labels = mapOf("com.bskyb.nowtv.beta" to "NOW")
        AppRules.userLabels = emptyMap()
        Control(ctx)
        assertEquals("NOW", AppRules.label("com.bskyb.nowtv.beta", "NOW TV"))
    }
}
