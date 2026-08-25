package com.printserver.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var cols = 0
    private var rows = 0
    private val cell = 12f
    private var rain = FloatArray(0)
    private var logoFloor = FloatArray(0)
    private var colTint = FloatArray(0)
    private var headY = FloatArray(0)
    private var headSpeed = FloatArray(0)
    private var buffer = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()
    private var lastNs = 0L
    private var pendingLogo: Bitmap? = null

    fun setLogoSource(bmp: Bitmap) {
        pendingLogo = bmp
        if (cols > 0) buildLogo(bmp)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        cols = (w / cell).toInt().coerceIn(8, 400)
        rows = (h / cell).toInt().coerceIn(8, 900)
        rain = FloatArray(cols * rows)
        logoFloor = FloatArray(cols * rows)
        colTint = FloatArray(cols) { 0.82f + Math.random().toFloat() * 0.36f }
        headY = FloatArray(cols) { (Math.random() * rows).toFloat() }
        headSpeed = FloatArray(cols) { (5f + Math.random().toFloat() * 13f) }
        buffer = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        pendingLogo?.let { buildLogo(it) }
    }

    private fun buildLogo(src: Bitmap) {
        val s = Bitmap.createScaledBitmap(src, cols, rows, true)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val g = Color.green(s.getPixel(x, y))
                logoFloor[y * cols + x] = if (g > 26) (g / 255f) * 0.5f else 0f
            }
        }
        s.recycle()
    }

    fun step(speedPref: Int) {
        if (cols == 0) return
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.033f else ((now - lastNs) / 1e9f).coerceIn(0.005f, 0.1f)
        lastNs = now
        val mul = when (speedPref) { 0 -> 0.5f; 2 -> 2.4f; else -> 1f }

        for (i in rain.indices) rain[i] *= 0.915f

        for (x in 0 until cols) {
            headY[x] += headSpeed[x] * mul * dt
            val yi = headY[x].toInt()
            if (yi in 0 until rows) {
                val idx = yi * cols + x
                rain[idx] = max(rain[idx], colTint[x])
            }
            if (yi in 1 until rows) {
                val above = (yi - 1) * cols + x
                rain[above] = max(rain[above], colTint[x] * 0.85f)
            }
            if (yi > rows + 2) {
                headY[x] = -(Math.random() * 30).toFloat()
                headSpeed[x] = 5f + Math.random().toFloat() * 13f
            }
        }

        val px = IntArray(cols * rows)
        var i = 0
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val t = colTint[x]
                val rainV = rain[i]
                val logoV = logoFloor[i]
                val v = max(rainV, logoV)
                if (v <= 0.02f) {
                    px[i] = 0xFF050805.toInt()
                } else {
                    val r = (14 * v * t).toInt().coerceAtMost(60)
                    val g = (70 + 185 * v * t).toInt().coerceAtMost(255)
                    val b = (36 * v * t).toInt().coerceAtMost(140)
                    px[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
                i++
            }
        }
        buffer.setPixels(px, 0, cols, 0, 0, cols, rows)
        dst.set(0, 0, width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (cols == 0) return
        canvas.drawBitmap(buffer, null, dst, paint)
    }
}
