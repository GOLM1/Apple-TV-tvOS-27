package io.github.besliky.airplaytv.ui

import org.junit.Assert.*
import org.junit.Test

class SpringValueTest {
    @Test fun settlesWithoutOvershootingAtThirtyAndSixtyHz() {
        for (hz in listOf(30, 60)) {
            val spring = SpringValue(0f)
            spring.target = 1f
            var previous = 0f
            repeat(hz) {
                spring.advance(1f / hz)
                assertTrue(spring.value >= previous)
                assertTrue(spring.value <= 1f)
                previous = spring.value
            }
            assertTrue(spring.settled)
        }
    }

    @Test fun reversalPreservesPositionAndVelocity() {
        val spring = SpringValue(0f)
        spring.target = 1f
        repeat(5) { spring.advance(1f / 60f) }
        val position = spring.value
        val velocity = spring.velocity
        spring.target = 0f
        assertEquals(position, spring.value, 0f)
        assertEquals(velocity, spring.velocity, 0f)
        repeat(60) { spring.advance(1f / 60f) }
        assertEquals(0f, spring.value, 0f)
        assertTrue(spring.settled)
    }

    @Test fun integrationDoesNotDependOnFrameRate() {
        val coarse = SpringValue(0f)
        val fine = SpringValue(0f)
        coarse.target = 100f
        fine.target = 100f
        coarse.advance(0.1f)
        repeat(6) { fine.advance(1f / 60f) }
        assertEquals(coarse.value, fine.value, 0.0001f)
        assertEquals(coarse.velocity, fine.velocity, 0.001f)
    }

    @Test fun snapDiscardsOldVelocityAndTarget() {
        val spring = SpringValue(0f)
        spring.target = 1f
        spring.advance(0.1f)
        spring.snapTo(0.4f)
        spring.advance(1f)
        assertEquals(0.4f, spring.value, 0f)
        assertEquals(0f, spring.velocity, 0f)
        assertTrue(spring.settled)
    }
}
