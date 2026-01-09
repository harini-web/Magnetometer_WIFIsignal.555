package com.example.magnetometerwifisignal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SimpleGraphView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val dataPoints = FloatArray(100) // Store last 100 frames

    // Cyan Line for Dark Mode High Contrast
    private val paintLine = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Subtle Grid Lines
    private val paintGrid = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 2f
    }

    private val path = Path()

    fun addPoint(value: Float) {
        // Shift array left
        System.arraycopy(dataPoints, 1, dataPoints, 0, dataPoints.size - 1)
        // Add new point
        dataPoints[dataPoints.size - 1] = value
        invalidate() // Trigger redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#1E1E1E")) // Dark Card Background

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw Center Line
        canvas.drawLine(0f, h/2, w, h/2, paintGrid)

        // Auto-Scale Logic: Find max value to fit graph in view
        val maxVal = dataPoints.maxOrNull()?.coerceAtLeast(1f) ?: 100f

        path.reset()
        val stepX = w / (dataPoints.size - 1)

        for (i in dataPoints.indices) {
            val x = i * stepX
            // Normalize value to height (with some padding)
            val normalizedY = (dataPoints[i] / (maxVal * 1.2f)) * h

            // Invert Y because canvas 0 is at top
            val y = h - normalizedY.coerceIn(0f, h)

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, paintLine)
    }
}