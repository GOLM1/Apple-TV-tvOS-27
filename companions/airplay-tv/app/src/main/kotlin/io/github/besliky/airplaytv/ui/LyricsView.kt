package io.github.besliky.airplaytv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import io.github.besliky.airplaytv.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.max

/**
 * Scrolling lyrics. With time-stamped lyrics the line being sung is large and bright, its
 * neighbours shrink and fade, and the text glides to keep that line in view. Plain lyrics scroll
 * slowly along with the track instead. The lines are laid out once per size, not per frame.
 */
class LyricsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private var lyrics: Lyrics? = null
    private var layouts: List<StaticLayout> = emptyList()
    private var tops = FloatArray(0)
    private var contentHeight = 0f

    private var line = -1
    private var scroll = 0f
    private var following = false
    private var needsPositionSnap = true
    private var emphasis = FloatArray(0)
    private var emphasisAnimation: ValueAnimator? = null
    private val scrollMotion = MotionSpring(0f, omega = 16f, precision = 0.05f) {
        scroll = it * density
        invalidate()
    }

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((56 * density).toInt())
    }

    override fun getTopFadingEdgeStrength(): Float = 1f

    override fun getBottomFadingEdgeStrength(): Float = 1f

    fun setLyrics(value: Lyrics?) {
        if (value === lyrics) return
        lyrics = value
        line = -1
        needsPositionSnap = true
        emphasisAnimation?.cancel()
        emphasis.fill(0f)
        scrollMotion.snapTo(0f)
        layOut()
        invalidate()
    }

    /** Hidden lyrics do no animation work; reopening starts at the actual current line. */
    fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        needsPositionSnap = true
        if (!value) {
            scrollMotion.cancel()
            emphasisAnimation?.cancel()
        }
    }

    /** Moves along with the track: to the line sung at [positionMs], or a share of plain lyrics. */
    fun setPosition(positionMs: Long, durationMs: Long) {
        val l = lyrics ?: return
        if (layouts.isEmpty()) return
        if (l.synced) {
            val next = l.indexAt(positionMs)
            if (next != line || needsPositionSnap) {
                val snap = needsPositionSnap || line < 0 || abs(next - line) > JUMP_LINES
                line = next
                glideTo(next, snap)
                needsPositionSnap = false
            }
        } else if (durationMs > 0) {
            val target = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) * max(0f, contentHeight - height * 0.55f) / density
            if (needsPositionSnap || !following) scrollMotion.snapTo(target) else scrollMotion.animateTo(target)
            needsPositionSnap = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layOut()
        emphasisAnimation?.cancel()
        if (lyrics?.synced == true) scrollMotion.snapTo(scrollFor(line) / density)
        needsPositionSnap = true
    }

    private fun layOut() {
        val l = lyrics
        val width = width - paddingLeft - paddingRight
        if (l == null || width <= 0 || l.lines.isEmpty()) {
            layouts = emptyList()
            emphasis = FloatArray(0)
            tops = FloatArray(0)
            contentHeight = 0f
            return
        }
        val gap = 24f * density
        tops = FloatArray(l.lines.size)
        var y = 0f
        layouts = l.lines.mapIndexed { i, it ->
            val text = it.text.ifBlank { " " }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.06f)
                .build()
            tops[i] = y
            y += layout.height + gap
            layout
        }
        contentHeight = y - gap
        if (emphasis.size != layouts.size) emphasis = FloatArray(layouts.size)
    }

    /** The scroll offset that puts line [i] at about 40% of the height. */
    private fun scrollFor(i: Int): Float =
        if (i < 0 || i >= layouts.size) -height * 0.25f else tops[i] + layouts[i].height / 2f - height * 0.4f

    private fun glideTo(i: Int, snap: Boolean) {
        val target = scrollFor(i) / density
        emphasisAnimation?.cancel()
        if (snap || !following || !ValueAnimator.areAnimatorsEnabled()) {
            scrollMotion.snapTo(target)
            emphasis.fill(0f)
            if (i in emphasis.indices) emphasis[i] = 1f
            invalidate()
            return
        }
        scrollMotion.animateTo(target)
        // Only the real current line gains emphasis. Skipped intermediate lines never light up.
        val before = emphasis.copyOf()
        emphasisAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = PathInterpolator(0.2f, 0f, 0.2f, 1f)
            addUpdateListener {
                val p = it.animatedValue as Float
                for (index in emphasis.indices) {
                    val to = if (index == i) 1f else 0f
                    emphasis[index] = before[index] + (to - before[index]) * p
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val synced = lyrics?.synced ?: return
        for (i in layouts.indices) {
            val layout = layouts[i]
            val top = tops[i] - scroll
            if (top + layout.height < -16f || top > height + 16f) continue
            var alpha = 0.85f
            var scale = 1f
            if (synced) {
                val proximity = (1f - abs(top + layout.height / 2f - height * 0.4f) / (height * 0.7f)).coerceIn(0f, 1f)
                val base = 0.14f + 0.24f * proximity
                val weight = emphasis.getOrElse(i) { 0f }
                alpha = base + (1f - base) * weight
                scale = 0.975f + 0.025f * weight
            }
            paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), top)
            val pivot = if (layout.getParagraphDirection(0) < 0) layout.width.toFloat() else 0f
            canvas.scale(scale, scale, pivot, layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        scrollMotion.cancel()
        emphasisAnimation?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val JUMP_LINES = 6
    }
}
