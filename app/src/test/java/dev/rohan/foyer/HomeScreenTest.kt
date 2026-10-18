package dev.rohan.foyer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rohan.foyer.data.Images
import dev.rohan.foyer.ui.FoyerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import java.util.TimeZone

/**
 * Renders every state of the home screen at the TV's 1920x1080 and walks the focus map with the
 * D-pad. PNGs land in app/build/outputs/roborazzi/ (run recordRoborazziDebug).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w960dp-h540dp-land-television-xhdpi-notouch-dpad")
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var fake: FakePlatform
    private lateinit var model: FoyerModel

    @Before fun setUp() {
        Images.hardware = false
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 11, hour, minute, 0)
    }.timeInMillis

    private fun launch(hour: Int = 19, minute: Int = 42, before: (FoyerModel) -> Unit = {}) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        fake = FakePlatform(ctx)
        val t = at(hour, minute)
        model = FoyerModel(ctx, fake, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, clock = { t })
        before(model)
        model.start()
        compose.mainClock.autoAdvance = false
        compose.setContent { FoyerScreen(model, isDev = true) }
        settle(27_000) // photo fade-in and the full 26 s drift
    }

    /** With autoAdvance off, state written in a key handler is only picked up by waitForIdle. */
    private fun settle(ms: Long = 1_300) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>, ms: Long = 1_300) {
        keys.forEach { k ->
            compose.onRoot().performKeyInput { pressKey(k) }
            settle(120)
        }
        settle(ms)
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Suppress("DEPRECATION")
    private val photoDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Foyer")

    /** Writes generated photos (a soft vertical gradient each) into the folder Foyer reads. */
    private fun photos(vararg named: Pair<String, Int>) {
        photoDir.mkdirs()
        for ((name, color) in named) {
            val b = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
            val dark = android.graphics.Color.rgb(
                android.graphics.Color.red(color) / 4, android.graphics.Color.green(color) / 4, android.graphics.Color.blue(color) / 4,
            )
            android.graphics.Canvas(b).drawPaint(android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, 360f, dark, color, android.graphics.Shader.TileMode.CLAMP)
            })
            java.io.File(photoDir, name).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }

    @org.junit.After fun clearPhotos() {
        photoDir.deleteRecursively()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    @Test fun restingHome() {
        launch()
        tag("tile:com.netflix.ninja").assertIsFocused()
        tag("devTag").assertExists()
        shot("home_rest")
    }

    @Test fun focusMovesAlongTheShelfAndScrolls() {
        launch()
        press(listOf(Key.DirectionRight, Key.DirectionRight))
        tag("tile:in.startv.hotstar").assertIsFocused()
        shot("home_focus_jiohotstar")
        press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight))
        tag("tile:com.spotify.tv.android").assertIsFocused()
        shot("home_focus_spotify_scrolled")
    }

    @Test fun rightPastTheLastFavouriteOpensTheDrawer() {
        launch()
        tag("drawerHint").assertExists()
        press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight))
        tag("tile:com.spotify.tv.android").assertIsFocused()
        shot("home_last_favourite")
        press(listOf(Key.DirectionRight))
        // The drawer opens on the first app after the favourites.
        tag("app:" + model.allApps[model.shelf.size].pkg).assertIsFocused()
        shot("allapps_from_right")
    }

    @Test fun upGoesToTheTopBarAndDownComesBack() {
        launch()
        press(listOf(Key.DirectionRight, Key.DirectionUp))
        tag("top:inputs").assertIsFocused()
        shot("topbar_inputs")
        press(listOf(Key.DirectionRight, Key.DirectionRight))
        tag("top:settings").assertIsFocused()
        press(listOf(Key.DirectionCenter))
        assertEquals(1, fake.settingsOpened)
        press(listOf(Key.DirectionDown))
        tag("tile:com.amazon.firebat").assertIsFocused()
    }

    @Test fun allAppsOpensOnTheSameAppAndClosesBack() {
        launch()
        press(listOf(Key.DirectionRight, Key.DirectionDown))
        tag("app:com.amazon.firebat").assertIsFocused()
        shot("allapps")
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        shot("allapps_row2")
        tag("app:com.onemainstream.sonyliv.android").assertIsFocused()
        press(listOf(Key.DirectionUp))
        tag("app:in.startv.hotstar").assertIsFocused()
        press(listOf(Key.DirectionUp)) // Up from the top row closes the sheet, focus on that app's tile.
        tag("tile:in.startv.hotstar").assertIsFocused()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.Back))
        tag("tile:com.amazon.firetv.youtube").assertIsFocused()
    }

    @Test fun okLaunchesAndFailureShowsANote() {
        launch()
        press(listOf(Key.DirectionCenter))
        assertEquals(listOf("com.netflix.ninja"), fake.launched)
        fake.failLaunch = true
        press(listOf(Key.DirectionRight, Key.DirectionCenter), ms = 400)
        tag("toast").assertExists()
        shot("toast")
    }

    @Test fun inputsPanel() {
        launch()
        press(listOf(Key.DirectionUp, Key.DirectionCenter))
        tag("input:hdmi_1").assertIsFocused()
        press(listOf(Key.DirectionDown))
        shot("inputs")
        press(listOf(Key.DirectionCenter))
        assertEquals(listOf("com.mediatek.tis/.HdmiInputService/HW3"), fake.switched)
        press(listOf(Key.Back))
        tag("top:inputs").assertIsFocused()
    }

    @Test fun customizeSheet() {
        launch(before = { photos("Dawn.jpg" to 0xFFB07A5A.toInt(), "Fjord.jpg" to 0xFF4A7AA6.toInt(), "Moss.jpg" to 0xFF5A8A5A.toInt()) })
        press(listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionCenter))
        tag("photo:u:Dawn.jpg").assertIsFocused()
        shot("customize")
        press(listOf(Key.DirectionRight), ms = 200) // preview Fjord (250 ms debounce runs in real time)
        Thread.sleep(600)
        settle(2_500)
        assertEquals("u:Fjord.jpg", model.wall?.photo?.id)
        shot("customize_preview")
        press(listOf(Key.DirectionCenter)) // switch Fjord off
        assertTrue("u:Fjord.jpg" !in model.photosOn)
        press(listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionCenter))
        assertEquals(dev.rohan.foyer.data.Rotation.Never, model.rotation)
        press(listOf(Key.Back), ms = 2_500)
        tag("top:customize").assertIsFocused()
        // Fjord is off, so leaving goes back to a photo that's on.
        assertEquals("u:Dawn.jpg", model.wall?.photo?.id)
    }

    @Test fun menuMoveRearrangesTheShelf() {
        launch()
        press(listOf(Key.DirectionRight, Key.Menu))
        tag("menu:move").assertIsFocused()
        shot("menu")
        press(listOf(Key.DirectionCenter))
        assertEquals("com.amazon.firebat", model.moving)
        press(listOf(Key.DirectionRight))
        shot("moving")
        press(listOf(Key.DirectionCenter))
        assertEquals(null, model.moving)
        assertEquals(
            listOf("com.netflix.ninja", "in.startv.hotstar", "com.amazon.firebat"),
            model.shelf.take(3).map { it.pkg },
        )
        tag("tile:com.amazon.firebat").assertIsFocused()
    }

    @Test fun longPressOkOpensTheMenuAndHideWorks() {
        launch()
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter); advanceEventTime(700); keyUp(Key.DirectionCenter)
        }
        settle()
        assertTrue(fake.launched.isEmpty())
        tag("menu:hide").assertExists()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter))
        tag("tile:com.netflix.ninja").assertDoesNotExist()
        assertTrue("com.netflix.ninja" in model.layout.hidden)
        tag("tile:com.amazon.firebat").assertIsFocused()
    }

    @Test fun addToHomeFromAllApps() {
        launch()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.Menu)) // discovery+, first of row 2
        tag("menu:add").assertIsFocused()
        shot("menu_allapps")
        press(listOf(Key.DirectionCenter))
        assertEquals("com.discoveryplus.tv.fire", model.shelf.last().pkg)
    }

    @Test fun ownPhotosReplaceTheBuiltInsAndNameTheCaption() {
        launch(before = { photos("IMG_2041.jpg" to 0xFF5A3A2E.toInt(), "Pangong Tso, Ladakh.jpg" to 0xFF2E5A88.toInt()) })
        assertTrue(model.photos.all { it is dev.rohan.foyer.data.Photo.Own })
        assertEquals(listOf("u:IMG_2041.jpg", "u:Pangong Tso, Ladakh.jpg"), model.photos.map { it.id })
        assertEquals(setOf("u:IMG_2041.jpg", "u:Pangong Tso, Ladakh.jpg"), model.photosOn)
        assertEquals("u:IMG_2041.jpg", model.wall?.photo?.id)
        assertEquals(null, model.wall?.photo?.caption)
        assertEquals("Pangong Tso, Ladakh", model.photos[1].caption)
    }

    @Test fun everyFiveMinutesIsAnOption() {
        launch()
        press(listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionCenter, Key.DirectionDown))
        press(listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter))
        assertEquals(dev.rohan.foyer.data.Rotation.FiveMinutes, model.rotation)
        shot("customize_five_minutes")
    }

    @Test fun nightWarmth() {
        launch(hour = 21, minute = 47)
        assertTrue(model.nightOn)
        shot("home_night")
    }
}