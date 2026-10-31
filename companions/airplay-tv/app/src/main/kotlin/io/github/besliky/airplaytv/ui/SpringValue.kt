package io.github.besliky.airplaytv.ui

import kotlin.math.abs
import kotlin.math.exp

/** Exact critically damped motion: changing direction retains position and velocity. */
internal class SpringValue(initial: Float, private val omega: Float = 18f, private val precision: Float = 0.001f) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial

    val settled: Boolean get() = value == target && velocity == 0f

    fun snapTo(value: Float) {
        this.value = value
        target = value
        velocity = 0f
    }

    fun advance(seconds: Float) {
        if (seconds <= 0f || settled) return
        val offset = value - target
        val term = velocity + omega * offset
        val decay = exp(-omega * seconds)
        value = target + (offset + term * seconds) * decay
        velocity = (velocity - omega * term * seconds) * decay
        if (abs(value - target) < precision && abs(velocity) < precision * omega) snapTo(target)
    }
}
