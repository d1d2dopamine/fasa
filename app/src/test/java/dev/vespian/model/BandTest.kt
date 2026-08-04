package dev.vespian.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uncertainty band shown to the user.
 *
 * A confidence number that does not shrink when the window widens is worse than
 * no number at all, because people believe percentages.
 */
class BandTest {

    @Test
    fun `width is the span of the window`() {
        assertEquals(2.0, Band(median = 1.0, low = 0.0, high = 2.0).width, 1e-9)
    }

    @Test
    fun `a narrow window is more confident than a wide one`() {
        val narrow = Band(median = 1.0, low = 0.5, high = 1.5)
        val wide = Band(median = 1.0, low = -1.5, high = 3.5)
        assertTrue(narrow.confidence > wide.confidence)
    }

    @Test
    fun `confidence stays a percentage`() {
        val absurd = Band(median = 1.0, low = -50.0, high = 50.0)
        val exact = Band(median = 1.0, low = 1.0, high = 1.0)
        assertEquals(0.0, absurd.confidence, 1e-9)
        assertEquals(1.0, exact.confidence, 1e-9)
    }
}
