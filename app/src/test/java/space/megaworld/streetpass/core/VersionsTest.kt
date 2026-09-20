package space.megaworld.streetpass.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun parsesWithAndWithoutPrefix() {
        assertEquals(listOf(1, 2, 3), Versions.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), Versions.parse("1.2.3"))
        assertEquals(listOf(0, 2), Versions.parse("0.2-beta"))
    }

    @Test
    fun newerVersionIsDetected() {
        assertTrue(Versions.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.9"))
        assertTrue(Versions.isNewer("0.1.1", "0.1.0"))
        assertTrue(Versions.isNewer("0.1.0.1", "0.1.0"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("v0.1", "0.1.0"))
        assertFalse(Versions.isNewer("0.0.9", "0.1.0"))
    }

    @Test
    fun garbageIsTreatedAsZero() {
        assertFalse(Versions.isNewer("latest", "0.1.0"))
        assertEquals(0, Versions.compare("", "0"))
    }
}
