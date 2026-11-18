package com.lanvideo.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

internal object Theme {
    val background = 0xFF101B1B.toInt()
    val surface = 0xFF192727.toInt()
    val field = 0xFF111F20.toInt()
    val border = 0xFF344B48.toInt()
    val text = 0xFFF1F8F3.toInt()
    val muted = 0xFFACC2BB.toInt()
    val accent = 0xFF88EDC3.toInt()
    val ink = 0xFF082F25.toInt()
    val error = 0xFFFFB5AA.toInt()
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

internal fun Context.panel(color: Int, radius: Int, stroke: Int? = null) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

internal fun Context.ripple(color: Int, radius: Int, stroke: Int? = null) =
    RippleDrawable(ColorStateList.valueOf(0x2588EDC3), panel(color, radius, stroke), panel(Color.WHITE, radius))

internal fun Context.label(value: CharSequence, size: Float, color: Int = Theme.text, bold: Boolean = false) =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

internal fun LinearLayout.block(view: View, top: Int = 0) {
    addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(top) })
}

internal enum class Glyph { PLAY, PAUSE, BACK, NEXT, FOLDER, FILM, REFRESH, SETTINGS, EYE, EYE_OFF, COMPUTER }

internal class Icon(context: Context, var glyph: Glyph, private val color: Int, private val size: Int = 22) : View(context) {
    private val shape = Path()
    private val eye = Path().apply {
        moveTo(2f, 12f)
        quadTo(12f, 0f, 22f, 12f)
        quadTo(12f, 24f, 2f, 12f)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@Icon.color
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val pixels = min(context.dp(size).toFloat(), min(width, height).toFloat())
        val saved = canvas.save()
        canvas.translate((width - pixels) / 2, (height - pixels) / 2)
        canvas.scale(pixels / 24, pixels / 24)
        paint.style = Paint.Style.STROKE
        fun path(vararg points: Float, close: Boolean = false) {
            shape.rewind()
            shape.moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) shape.lineTo(points[index], points[index + 1])
            if (close) shape.close()
            canvas.drawPath(shape, paint)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) =
            canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
        when (glyph) {
            Glyph.PLAY -> {
                paint.style = Paint.Style.FILL
                path(8f, 4f, 20f, 12f, 8f, 20f, close = true)
            }
            Glyph.PAUSE -> {
                paint.style = Paint.Style.FILL
                rect(6f, 5f, 10f, 19f, 1f)
                rect(14f, 5f, 18f, 19f, 1f)
            }
            Glyph.BACK -> path(14f, 5f, 7f, 12f, 14f, 19f)
            Glyph.NEXT -> path(9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.FOLDER -> path(3f, 19f, 3f, 6f, 10f, 6f, 12f, 9f, 21f, 9f, 21f, 19f, close = true)
            Glyph.FILM -> {
                rect(3f, 5f, 21f, 19f)
                path(10f, 9f, 15f, 12f, 10f, 15f, close = true)
            }
            Glyph.REFRESH -> {
                canvas.drawArc(4f, 4f, 20f, 20f, -45f, 302f, false, paint)
                path(15f, 4f, 19f, 4f, 19f, 8f)
            }
            Glyph.SETTINGS -> {
                path(4f, 7f, 20f, 7f)
                path(4f, 17f, 20f, 17f)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(9f, 7f, 3f, paint)
                canvas.drawCircle(15f, 17f, 3f, paint)
            }
            Glyph.EYE, Glyph.EYE_OFF -> {
                canvas.drawPath(eye, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
                if (glyph == Glyph.EYE_OFF) path(4f, 4f, 20f, 20f)
            }
            Glyph.COMPUTER -> {
                rect(3f, 4f, 21f, 16f)
                path(12f, 16f, 12f, 20f)
                path(8f, 20f, 16f, 20f)
            }
        }
        canvas.restoreToCount(saved)
    }
}

internal class NetworkArtwork(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tile = Path().apply {
        moveTo(79f, 61f); lineTo(79f, 86f); lineTo(100f, 73.5f); close()
    }
    private val glow = LinearGradient(36f, 39f, 135f, 116f, 0xFF267157.toInt(), 0xFF173B34.toInt(), Shader.TileMode.CLAMP)

    override fun onDraw(canvas: Canvas) {
        val scale = min(width / 160f, height / 148f)
        val saved = canvas.save()
        canvas.translate((width - 160 * scale) / 2, (height - 148 * scale) / 2)
        canvas.scale(scale, scale)
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        paint.color = 0x4488EDC3
        for (radius in floatArrayOf(50f, 63f, 76f)) canvas.drawCircle(94f, 73f, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x99A7F8D8.toInt()
        canvas.drawCircle(27f, 96f, 2.6f, paint)
        canvas.drawCircle(138f, 28f, 2.7f, paint)
        canvas.drawCircle(123f, 132f, 2f, paint)
        paint.shader = glow
        canvas.drawRoundRect(38f, 39f, 134f, 108f, 12f, 12f, paint)
        paint.shader = null
        paint.color = 0xFFA0ECCC.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(38f, 39f, 134f, 108f, 12f, 12f, paint)
        canvas.drawLine(86f, 108f, 86f, 119f, paint)
        canvas.drawLine(69f, 119f, 103f, 119f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Theme.accent
        canvas.drawPath(tile, paint)
        canvas.restoreToCount(saved)
    }
}
