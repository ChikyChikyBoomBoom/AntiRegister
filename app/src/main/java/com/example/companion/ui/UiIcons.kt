package com.example.companion.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Stage 24: the small icon set, drawn with Canvas (no image files, no libraries) so every screen uses the same line style. */
enum class UiIcon { CALLS, RECENT, MESSAGES, DIAL_PAD, SETTINGS, BLUETOOTH, CONTACTS }

/**
 * A simple line icon. It is stateful: inside a disabled button or tile it turns grey by itself, so the icon and the text
 * always agree. [sizePx] is the size it asks the view for.
 */
class IconDrawable(
    private val icon: UiIcon,
    private val color: Int,
    private val sizePx: Int,
    private val disabledColor: Int = UiColors.DISABLED_TEXT
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var enabledLook = true
    private var alphaValue = 255

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun isStateful() = true

    // Stage 16: only the speech bubble has a "direction" (its tail points to the start side), so only it mirrors in RTL. The handset,
    // clock, dial pad, sliders, person and Bluetooth rune are the same picture in both directions, as Android's own icons are.
    override fun isAutoMirrored() = icon == UiIcon.MESSAGES
    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean { invalidateSelf(); return true }

    override fun onStateChange(state: IntArray): Boolean {
        val enabled = state.contains(android.R.attr.state_enabled)
        if (enabled == enabledLook) return false
        enabledLook = enabled
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        val left = b.left + (b.width() - s) / 2f
        val top = b.top + (b.height() - s) / 2f
        fun x(f: Float) = left + f * s
        fun y(f: Float) = top + f * s

        val mirror = isAutoMirrored && layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        if (mirror) { canvas.save(); canvas.scale(-1f, 1f, b.exactCenterX(), b.exactCenterY()) }
        paint.color = if (enabledLook) color else disabledColor
        paint.alpha = alphaValue
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.09f

        when (icon) {
            UiIcon.CALLS -> {                                   // a curved handset
                paint.strokeWidth = s * 0.17f
                val p = Path().apply {
                    moveTo(x(0.27f), y(0.17f))
                    cubicTo(x(0.17f), y(0.58f), x(0.42f), y(0.83f), x(0.83f), y(0.73f))
                }
                canvas.drawPath(p, paint)
            }
            UiIcon.RECENT -> {                                  // a clock
                canvas.drawCircle(x(0.5f), y(0.5f), s * 0.38f, paint)
                canvas.drawLine(x(0.5f), y(0.5f), x(0.5f), y(0.27f), paint)
                canvas.drawLine(x(0.5f), y(0.5f), x(0.67f), y(0.6f), paint)
            }
            UiIcon.MESSAGES -> {                                // a speech bubble
                canvas.drawRoundRect(RectF(x(0.12f), y(0.17f), x(0.88f), y(0.67f)), s * 0.1f, s * 0.1f, paint)
                val tail = Path().apply {
                    moveTo(x(0.30f), y(0.67f)); lineTo(x(0.28f), y(0.87f)); lineTo(x(0.48f), y(0.67f))
                }
                canvas.drawPath(tail, paint)
            }
            UiIcon.DIAL_PAD -> {                                // nine dots
                paint.style = Paint.Style.FILL
                for (r in 0..2) for (c in 0..2) canvas.drawCircle(x(0.22f + c * 0.28f), y(0.22f + r * 0.28f), s * 0.075f, paint)
            }
            UiIcon.SETTINGS -> {                                // three sliders
                val rows = floatArrayOf(0.25f, 0.5f, 0.75f)
                val knobs = floatArrayOf(0.35f, 0.65f, 0.45f)
                rows.forEachIndexed { i, ry ->
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(x(0.14f), y(ry), x(0.86f), y(ry), paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(x(knobs[i]), y(ry), s * 0.09f, paint)
                }
            }
            UiIcon.CONTACTS -> {                                // a head and shoulders
                canvas.drawCircle(x(0.5f), y(0.32f), s * 0.17f, paint)
                canvas.drawArc(RectF(x(0.18f), y(0.58f), x(0.82f), y(1.10f)), 180f, 180f, false, paint)
            }
            UiIcon.BLUETOOTH -> {                               // the Bluetooth rune
                val p = Path().apply {
                    moveTo(x(0.27f), y(0.3f)); lineTo(x(0.73f), y(0.7f)); lineTo(x(0.5f), y(0.9f))
                    lineTo(x(0.5f), y(0.1f)); lineTo(x(0.73f), y(0.3f)); lineTo(x(0.27f), y(0.7f))
                }
                canvas.drawPath(p, paint)
            }
        }
        if (mirror) canvas.restore()
    }
}
