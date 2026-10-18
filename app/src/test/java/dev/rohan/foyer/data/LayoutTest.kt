package dev.rohan.foyer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val installed = setOf("a", "b", "c", "d", "e")

    @Test fun firstRunKeepsOnlyInstalledDefaultsInMockupOrder() {
        val l = Layout.firstRun(setOf("com.spotify.tv.android", "com.netflix.ninja", "x.y", "com.amazon.firebat"))
        assertEquals(listOf("com.netflix.ninja", "com.amazon.firebat", "com.spotify.tv.android"), l.shelf)
        assertTrue("com.tailscale.ipn" in l.hidden)
        assertTrue("io.github.toolicious.homeonfire" in l.hidden)
    }

    @Test fun visibleShelfDropsUninstalledHiddenAndBlocked() {
        val l = Layout(listOf("a", "gone", "b", "com.amazon.tv.launcher", "c"), setOf("b"))
        assertEquals(listOf("a", "c"), l.visibleShelf(installed + "com.amazon.tv.launcher"))
    }

    @Test fun addAppendsOnceAndUnhides() {
        val l = Layout(listOf("a"), setOf("b")).addToShelf("b")
        assertEquals(listOf("a", "b"), l.shelf)
        assertFalse("b" in l.hidden)
        assertSame(l, l.addToShelf("a"))
    }

    @Test fun removeKeepsTheAppHiddenStateAlone() {
        val l = Layout(listOf("a", "b"), emptySet()).removeFromShelf("a")
        assertEquals(listOf("b"), l.shelf)
        assertTrue(l.hidden.isEmpty())
    }

    @Test fun hideTakesItOffTheShelfToo() {
        val l = Layout(listOf("a", "b"), emptySet()).hide("a")
        assertEquals(listOf("b"), l.shelf)
        assertEquals(setOf("a"), l.hidden)
        assertEquals(listOf("b"), l.unhide("a").shelf) // unhiding puts it back in All apps, not on the shelf
    }

    @Test fun moveSwapsWithVisibleNeighbourSkippingUninstalled() {
        val l = Layout(listOf("a", "gone", "b", "c"), emptySet())
        assertEquals(listOf("b", "gone", "a", "c"), l.move("a", 1, installed).shelf)
        assertEquals(listOf("a", "gone", "c", "b"), l.move("c", -1, installed).shelf)
    }

    @Test fun moveAtTheEdgesDoesNothing() {
        val l = Layout(listOf("a", "b"), emptySet())
        assertSame(l, l.move("a", -1, installed))
        assertSame(l, l.move("b", 1, installed))
        assertSame(l, l.move("zzz", 1, installed))
    }
}

class PhotoCaptionTest {
    @Test fun captionsComeFromNamesButNotCameraNames() {
        val c = PhotoRepository.Companion::captionFromName
        assertEquals("Pangong Tso, Ladakh", c("Pangong Tso, Ladakh"))
        assertEquals("Swaledale Yorkshire", c("Swaledale_Yorkshire"))
        assertEquals(null, c("IMG_2041"))
        assertEquals(null, c("PXL_20260412_093112"))
        assertEquals(null, c("DSC01234"))
        assertEquals(null, c("WhatsApp Image 2026-10-01 at 09.12.33"))
        assertEquals(null, c("1699999999"))
        assertEquals(null, c("3f2a9c4e-77b1-4c9e"))
    }
}

class AppRulesTest {
    @Test fun blocklist() {
        assertTrue(AppRules.isBlocked("com.vewd.core.tvapp"))
        assertTrue(AppRules.isBlocked("com.amazon.tv.launcher"))
        assertTrue(AppRules.isBlocked("dev.rohan.foyer.dev"))
        assertFalse(AppRules.isBlocked("com.netflix.ninja"))
        assertFalse(AppRules.isBlocked("com.tailscale.ipn")) // hidden by default, not blocked
    }

    @Test fun fixedLabelsWinOverSystemLabels() {
        assertEquals("Prime Video", AppRules.label("com.amazon.firebat", "Amazon Prime Video for Fire TV"))
        assertEquals("ZEE5", AppRules.label("com.zee5.amazon", "Zee5"))
        assertEquals("Stremio", AppRules.label("com.stremio.one", " Stremio "))
        assertEquals("x.y", AppRules.label("x.y", ""))
    }

    @Test fun allAppsPutsShelfFirstThenAlphabetical() {
        val apps = listOf(App("z", "zeta"), App("a", "Alpha"), App("m", "mu"), App("b", "Beta"))
        val order = AppRules.allAppsOrder(apps, listOf("m", "gone", "z"))
        assertEquals(listOf("m", "z", "a", "b"), order.map { it.pkg })
    }
}
