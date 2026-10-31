package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.view.Choreographer

/** Runs only until settled, on display frames; no timer, dependency or work at rest. Main thread. */
internal class MotionSpring(
    initial: Float,
    omega: Float = 18f,
    precision: Float = 0.001f,
    private val update: (Float) -> Unit,
) : Choreographer.FrameCallback {
    private val state = SpringValue(initial, omega, precision)
    private var running = false
    private var lastFrame = 0L

    val value: Float get() = state.value

    fun animateTo(target: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snapTo(target)
            return
        }
        state.target = target
        if (running || state.settled) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun snapTo(value: Float) {
        cancel()
        state.snapTo(value)
        update(value)
    }

    fun cancel() {
        Choreographer.getInstance().removeFrameCallback(this)
        running = false
        lastFrame = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val seconds = if (lastFrame == 0L) 1f / 60f else ((frameTimeNanos - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = frameTimeNanos
        state.advance(seconds)
        update(state.value)
        if (state.settled) {
            running = false
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
