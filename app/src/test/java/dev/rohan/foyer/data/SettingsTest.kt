package dev.rohan.foyer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** config.json's parser, checker and diff. Robolectric only for Android's real org.json. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SettingsTest {

    private fun parse(json: String) = Settings.parse(JSONObject(json))

    private fun problems(json: String): String = try {
        parse(json); fail("expected problems in $json"); ""
    } catch (e: IllegalArgumentException) {
        e.message.orEmpty()
    }

    /** The repo's own config.json, found from the module or repo directory. */
    private fun repoConfig(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "config.json") }.first { it.isFile }

    @Test fun theRepoConfigIsValidAndRoundTrips() {
        val s = Settings.parse(JSONObject(repoConfig().readText()))
        assertTrue(s.shelf!!.isNotEmpty())
        // What `homectl pull` writes is the same settings again, comment and all.
        val pulled = s.toJson(Settings.COMMENT)
        assertEquals(s, Settings.parse(pulled))
        assertEquals(Settings.COMMENT, pulled.getString("\$comment"))
    }

    @Test fun pulledJsonHasConfigJsonsOrderAndNoEscapedSlashes() {
        val s = Settings(
            shelf = listOf("com.netflix.ninja"), hidden = setOf("z.z", "a.a"), labels = mapOf("b.b" to "B", "a.a" to "A"),
            seconds = true, date = false, night = NightMode.Auto, nightFrom = 20, nightTo = 6, rotation = Rotation.Never,
        )
        val j = s.toJson(Settings.COMMENT)
        assertEquals(listOf("\$comment", "shelf", "hidden", "labels", "clock", "night", "photos"), j.keys().asSequence().toList())
        assertEquals(listOf("a.a", "z.z"), List(j.getJSONArray("hidden").length()) { j.getJSONArray("hidden").getString(it) })
        assertEquals(listOf("a.a", "b.b"), j.getJSONObject("labels").keys().asSequence().toList())
        assertFalse(j.toString(2).contains("\\/"))
    }

    @Test fun partsLeftOutStayUnset() {
        val s = parse("""{"clock": {"date": true}}""")
        assertEquals(Settings(date = true), s)
        assertEquals(listOf("clock"), s.toJson().keys().asSequence().toList())
    }

    @Test fun valuesAreReadForgivinglyWhereItIsSafe() {
        val s = parse("""{"${'$'}comment": "x", "shelf": [" com.a.b ", "com.a.b", "com.c.d"], "night": {"mode": "always", "from": 21.0}, "photos": {"rotation": "hourly"}}""")
        assertEquals(listOf("com.a.b", "com.c.d"), s.shelf)
        assertEquals(NightMode.Always, s.night)
        assertEquals(21, s.nightFrom)
        assertEquals(Rotation.Hourly, s.rotation)
    }

    @Test fun everyProblemIsReportedAtOnce() {
        val m = problems(
            """{"shelf": ["netflix", 3], "clock": {"second": true, "date": "yes"}, "night": {"from": 25, "mode": "Dusk"},
               "labels": {"com.a.b": ""}, "colour": "red"}"""
        )
        assertTrue(m, m.startsWith("8 problems:"))
        listOf(
            "shelf[0]: 'netflix' isn't a package name",
            "shelf[1]: must be a package name in quotes",
            "clock.second: unknown key (allowed: seconds, date)",
            "clock.date: must be true or false, got 'yes'",
            "night.from: must be an hour 0-23, got 25",
            "night.mode: 'Dusk' isn't one of Auto, Always, Off",
            "labels.com.a.b: is empty",
            "colour: unknown setting",
        ).forEach { assertTrue("missing '$it' in:\n$m", m.contains(it)) }
    }

    @Test fun oneProblemReadsAsOneLine() {
        assertEquals("photos: must be an object with rotation", problems("""{"photos": "Daily"}"""))
        assertEquals("night.to: must be an hour 0-23, got 6.5", problems("""{"night": {"to": 6.5}}"""))
    }

    private val tv = Settings(
        shelf = listOf("com.netflix.ninja", "com.amazon.firebat"), hidden = setOf("com.esaba.downloader"),
        labels = mapOf("com.bskyb.nowtv.beta" to "NOW"), seconds = true, date = false,
        night = NightMode.Auto, nightFrom = 20, nightTo = 6, rotation = Rotation.Never,
    )

    @Test fun inSyncWhenTheFileMatches() {
        assertTrue(tv.diff(tv).isEmpty())
        assertTrue(tv.diff(Settings(seconds = true)).isEmpty()) // parts the file leaves out aren't compared
    }

    @Test fun diffNamesEachDifferingPart() {
        val want = tv.copy(
            shelf = listOf("com.amazon.firebat", "com.netflix.ninja"), hidden = setOf("com.spocky.projengmenu"),
            labels = mapOf("com.bskyb.nowtv.beta" to "NOW TV", "com.stremio.one" to "Stremio"), seconds = false,
        )
        assertEquals(
            listOf(
                "shelf:\n  TV    com.netflix.ninja, com.amazon.firebat\n  file  com.amazon.firebat, com.netflix.ninja",
                "hidden: only on TV com.esaba.downloader · only in file com.spocky.projengmenu",
                "labels.com.bskyb.nowtv.beta: TV \"NOW\" · file \"NOW TV\"",
                "labels.com.stremio.one: TV (none) · file \"Stremio\"",
                "clock.seconds: TV true · file false",
            ),
            tv.diff(want),
        )
    }
}

class AppRulesNamesTest {
    @Test fun artKeysCoverEveryApp() {
        assertEquals("netflix", AppRules.artKey("com.netflix.ninja"))
        assertEquals("com_stremio_one", AppRules.artKey("com.stremio.one"))
        assertEquals("air_itvmobileplayer", AppRules.artKey("air.ITVMobilePlayer"))
    }

    @Test fun packageShapes() {
        assertTrue(AppRules.looksLikePackage("com.netflix.ninja"))
        assertTrue(AppRules.looksLikePackage("air.ITVMobilePlayer"))
        assertFalse(AppRules.looksLikePackage("netflix"))
        assertFalse(AppRules.looksLikePackage("com.netflix."))
        assertFalse(AppRules.looksLikePackage("prime video"))
    }

    @Test fun systemLabelIsOnlyLoadedWhenNeeded() {
        var asked = 0
        assertEquals("Netflix", AppRules.label("com.netflix.ninja") { asked++; "Netflix for Fire TV" })
        assertEquals(0, asked)
        assertEquals("Stremio", AppRules.label("com.stremio.one") { asked++; " Stremio " })
        assertEquals(1, asked)
    }
}

class ImagesCoverTest {
    private fun cover(w: Int, h: Int) = Images.cover(w, h, Images.ART_W, Images.ART_H).toList()

    @Test fun exactSizeIsUntouched() = assertEquals(listOf(344, 194, 0, 0), cover(344, 194))

    @Test fun widerImagesCropTheSides() {
        val (sw, sh, x, y) = cover(1000, 194)
        assertEquals(194, sh); assertEquals(1000, sw); assertEquals((1000 - 344) / 2, x); assertEquals(0, y)
    }

    @Test fun tallerImagesCropTopAndBottom() {
        val (sw, sh, x, y) = cover(400, 400)
        assertEquals(344, sw); assertEquals(344, sh); assertEquals(0, x); assertEquals(75, y)
    }

    @Test fun neverSmallerThanTheTile() {
        for ((w, h) in listOf(320 to 180, 7 to 3, 4032 to 3024, 1080 to 1920, 641 to 359)) {
            val (sw, sh, x, y) = Images.cover(w, h, 344, 194)
            assertTrue("$w x $h -> $sw x $sh", sw >= 344 && sh >= 194 && x + 344 <= sw && y + 194 <= sh)
        }
    }
}
