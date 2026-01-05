package com.example.magnetometerwifisignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SimpleGraphView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val dataPoints = FloatArray(100)
    private val paintLine = Paint().apply {
        color = Color.parseColor("#1976D2")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintGrid = Paint().apply { color = Color.parseColor("#E0E0E0"); strokeWidth = 2f }
    private val path = Path()

    fun addPoint(value: Float) {
        System.arraycopy(dataPoints, 1, dataPoints, 0, dataPoints.size - 1)
        dataPoints[dataPoints.size - 1] = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawLine(0f, h/2, w, h/2, paintGrid)

        val maxVal = dataPoints.maxOrNull()?.coerceAtLeast(1f) ?: 100f

        path.reset()
        val stepX = w / (dataPoints.size - 1)
        for (i in dataPoints.indices) {
            val x = i * stepX
            val normalizedY = (dataPoints[i] / (maxVal * 1.2f)) * h
            val y = h - normalizedY.coerceIn(0f, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paintLine)
    }
}