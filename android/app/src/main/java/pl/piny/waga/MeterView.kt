package pl.piny.waga

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Pasek surowego sygnału ze znacznikiem wartości szczytowej. */
class MeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var trackColor = 0
    var startColor = 0
    var endColor = 0
    var markColor = 0

    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var peak = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        bgPaint.color = trackColor
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bgPaint)

        if (value > 0f) {
            fillPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f, startColor, endColor, Shader.TileMode.CLAMP
            )
            rect.set(0f, 0f, width * value, height.toFloat())
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
        if (peak > 0f) {
            peakPaint.color = markColor
            peakPaint.alpha = 150
            val x = (width * peak).coerceIn(1f, width - 2f)
            canvas.drawRect(x - 1f, 0f, x + 1f, height.toFloat(), peakPaint)
        }
    }
}
