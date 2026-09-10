package com.rio.gamaentity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = 0xFFCEBAA2.toInt()
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val bars = FloatArray(30) { 0f }
    private var currentAmplitude = 0f

    fun updateAmplitude(amplitude: Float) {
        currentAmplitude = min(amplitude / 3000f, 1f)
        bars.copyInto(bars, 0, 1)
        bars[bars.size - 1] = currentAmplitude
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.all { it == 0f }) return
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / bars.size
        val centerY = h / 2f

        bars.forEachIndexed { i, amp ->
            val x = i * barWidth + barWidth / 2
            val barHeight = (amp * h * 0.8f).coerceAtLeast(2f)
            canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, paint)
        }
    }
}
