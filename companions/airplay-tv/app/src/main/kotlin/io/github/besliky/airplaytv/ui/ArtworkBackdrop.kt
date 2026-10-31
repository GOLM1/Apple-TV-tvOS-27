package io.github.besliky.airplaytv.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** A static pool of artwork colour. No full-screen bitmap, blur, noise or idle animation. */
class ArtworkBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var top = 0xFF17171A.toInt()
    private var bottom = 0xFF0B0B0D.toInt()

    fun setColors(top: Int, bottom: Int) {
        if (this.top == top && this.bottom == bottom && paint.shader != null) return
        this.top = top
        this.bottom = bottom
        rebuild()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) = rebuild()

    private fun rebuild() {
        if (bounds.isEmpty) return
        paint.shader = RadialGradient(
            bounds.left + bounds.width() * 0.26f,
            bounds.top + bounds.height() * 0.42f,
            bounds.width() * 0.88f,
            intArrayOf(top, bottom), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
