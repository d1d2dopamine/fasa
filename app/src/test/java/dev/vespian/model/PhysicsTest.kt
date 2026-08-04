package dev.vespian.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the arithmetic the whole forecast rests on.
 *
 * Nothing here touches Android, a database or a clock, which is the point: these
 * are the parts that can be checked without a phone, and they are also the parts
 * where a wrong sign or a swapped argument would not crash anything. It would
 * just quietly move somebody's bedtime, and nobody would ever find out.
 *
 * Every case below is a statement the model makes about a body, written so that
 * it fails if the maths stops meaning what the comments claim it means.
 */
class PhysicsTest {

    private val eps = 1e-9

    // ---- sleep pressure ---------------------------------------------------

    @Test
    fun `pressure does not move over no time`() {
        assertEquals(0.4, Physics.rise(0.4, 0.0, 4.0), eps)
        assertEquals(0.4, Physics.fall(0.4, 0.0, 2.0), eps)
    }

    @Test
    fun `staying awake raises pressure towards the ceiling`() {
        val short = Physics.rise(0.2, 4.0, 4.0)
        val long = Physics.rise(0.2, 16.0, 4.0)
        assertTrue(short > 0.2)
        assertTrue(long > short)
        assertTrue(long < 1.0)
    }

    @Test
    fun `sleeping lowers pressure and never below zero`() {
        val napped = Physics.fall(0.9, 1.0, 2.0)
        val slept = Physics.fall(0.9, 8.0, 2.0)
        assertTrue(napped < 0.9)
        assertTrue(slept < napped)
        assertTrue(slept >= 0.0)
    }

    // ---- caffeine ---------------------------------------------------------

    @Test
    fun `one half life leaves half the dose`() {
        assertEquals(50.0, Physics.caffeine(100.0, 5.0, 5.0), 1e-6)
        assertEquals(25.0, Physics.caffeine(100.0, 10.0, 5.0), 1e-6)
    }

    @Test
    fun `a drink not yet drunk does nothing`() {
        // The forecast walks forward through the evening, so it constantly asks
        // about hours before a dose happened. Those must contribute nothing
        // rather than a negative decay, which would read as caffeine helping
        // someone fall asleep.
        assertEquals(0.0, Physics.caffeine(100.0, -1.0, 5.0), eps)
    }

    @Test
    fun `a precisely timed drink is the plain curve`() {
        assertEquals(
            Physics.caffeine(130.0, 3.0, 5.0),
            Physics.caffeineSpread(130.0, 3.0, 0.0, 5.0),
            eps,
        )
    }

    @Test
    fun `a vaguely timed drink errs towards caffeine still being present`() {
        // Decay is a curve, so the average over a window is above the value at
        // the middle of it. This is deliberate: for a forecast about falling
        // asleep, admitting caffeine might still be there is the safe error.
        val pinned = Physics.caffeine(130.0, 6.0, 5.0)
        val vague = Physics.caffeineSpread(130.0, 6.0, 2.0, 5.0)
        assertTrue(vague >= pinned)
    }

    @Test
    fun `two drinks add up and later drinks matter more at bedtime`() {
        val morning = Physics.Dose(hour = 9.0, mg = 130.0)
        val evening = Physics.Dose(hour = 21.0, mg = 130.0)
        val atNight = 23.0

        val both = Physics.caffeineFrom(listOf(morning, evening), atNight, 5.0)
        val onlyMorning = Physics.caffeineFrom(listOf(morning), atNight, 5.0)
        val onlyEvening = Physics.caffeineFrom(listOf(evening), atNight, 5.0)

        assertEquals(both, onlyMorning + onlyEvening, 1e-6)
        assertTrue(onlyEvening > onlyMorning)
    }

    @Test
    fun `the same milligrams drunk early and late are not the same night`() {
        // This is the whole reason drinks are stored with their times. If this
        // test ever passes with the two sides equal, the model has gone back to
        // treating a day as one lump of caffeine.
        val early = Physics.caffeineFrom(listOf(Physics.Dose(8.0, 260.0)), 23.0, 5.0)
        val late = Physics.caffeineFrom(listOf(Physics.Dose(20.0, 260.0)), 23.0, 5.0)
        assertTrue(late > early * 4.0)
    }

    @Test
    fun `a drink logged for later tonight does not haunt this hour`() {
        val doses = listOf(Physics.Dose(22.0, 130.0))
        assertEquals(0.0, Physics.caffeineFrom(doses, 18.0, 5.0), eps)
    }

    // ---- light ------------------------------------------------------------

    @Test
    fun `darkness is no light dose and brightness saturates`() {
        assertEquals(0.0, Physics.dose(0.0), eps)
        assertEquals(0.5, Physics.dose(Physics.LUX_HALF), 1e-9)
        assertTrue(Physics.dose(10_000.0) > Physics.dose(1_000.0))
        assertTrue(Physics.dose(100_000.0) < 1.0)
    }

    @Test
    fun `hour differences fold to the nearest day`() {
        assertEquals(1.0, Physics.wrapHalf(25.0, 24.0), eps)
        assertEquals(-1.0, Physics.wrapHalf(23.0, 24.0), eps)
        assertEquals(0.0, Physics.wrapHalf(48.0, 24.0), eps)
    }

    @Test
    fun `light before the low delays and light after it advances`() {
        // Evening light pushes the clock later, morning light pulls it earlier.
        // The signs being the wrong way round would turn every piece of advice
        // this app gives into the opposite advice.
        assertTrue(Physics.prc(Physics.PRC_DELAY_CENTRE) > 0.0)
        assertTrue(Physics.prc(Physics.PRC_ADVANCE_CENTRE) < 0.0)
    }
}
