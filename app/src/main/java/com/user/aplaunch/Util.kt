package com.user.aplaunch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun Context.dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number): Int = dp(v).toInt()

fun Context.tv(s: String, size: Float, alpha: Float, family: String): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = size
    t.setTextColor(Color.WHITE)
    t.alpha = alpha
    t.typeface = Typeface.create(family, Typeface.NORMAL)
    return t
}

fun withAlpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a shl 24)

// ---------------------------------------------------------------- settings storage

object K {
    const val CLOCK_MODE = "clock_mode"
    const val CLOCK_SIZE = "clock_size"
    const val CLOCK_LOOK = "clock_look"
    const val CLOCK_FMT = "clock_fmt"
    const val CLOCK_DATE = "clock_date"
    const val MOTION = "motion"
    const val WALL_BRIGHT = "wall_bright"
    const val GLOW = "glow"
    const val GLOW_STR = "glow_str"
    const val SHEEN = "sheen"
    const val ANIM = "anim"
    const val PERF = "perf"
    const val AOD = "aod"
    const val AOD_DELAY = "aod_delay"
    const val AOD_DIM = "aod_dim"
    const val KEEP_ON = "keep_on"
    const val ALL_APPS_TILE = "all_apps_tile"
    const val SHOW_MISSING = "show_missing"
    const val RECS = "recs"
    const val GREETING = "greeting"
    const val RECENT_ON = "recent_on"
    const val NAV_SOUND = "nav_sound"
    const val GLASS = "glass"
    const val FAVS = "favs"
    const val RECENT = "recent"

    val DEF: Map<String, Int> = mapOf(
        CLOCK_MODE to 0, CLOCK_SIZE to 1, CLOCK_LOOK to 0, CLOCK_FMT to 0, CLOCK_DATE to 1,
        MOTION to 1, WALL_BRIGHT to 1,
        GLOW to 1, GLOW_STR to 1, SHEEN to 0, ANIM to 1, PERF to 0,
        AOD to 1, AOD_DELAY to 1, AOD_DIM to 1, KEEP_ON to 1,
        ALL_APPS_TILE to 1, SHOW_MISSING to 1, RECS to 1,
        GREETING to 1, RECENT_ON to 1, NAV_SOUND to 0, GLASS to 1
    )
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("home_v5", Context.MODE_PRIVATE)
    fun i(k: String): Int = p.getInt(k, K.DEF[k] ?: 0)
    fun on(k: String): Boolean = i(k) == 1
    fun put(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun str(k: String): String? = p.getString(k, null)
    fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()
    fun clear() = p.edit().clear().apply()
}

// ---------------------------------------------------------------- clock

/**
 * Big lock-screen style time, drawn between the sky and the mountain layers.
 * Looks: 0 Glass (heavy digits, fading white + glow), 1 Split (solid hours, outlined minutes),
 *        2 Thin, 3 Bold.
 */
class ClockView(ctx: Context) : View(ctx) {
    var sizeFrac = 0.20f
    var look = 0
    var format = 0
    var showDate = true

    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private var hours = ""
    private var mins = ""
    private var date = ""

    fun tick() {
        val now = Date()
        val h24 = when (format) {
            1 -> false
            2 -> true
            else -> android.text.format.DateFormat.is24HourFormat(context)
        }
        val t = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        hours = t.substringBefore(':')
        mins = t.substringAfter(':')
        date = SimpleDateFormat("EEEE \u00B7 d MMMM", Locale.getDefault()).format(now)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (height == 0 || hours.isEmpty()) return
        val hf = height.toFloat()
        val wf = width.toFloat()
        val dH = sizeFrac * hf
        val top = 0.165f * hf
        val base = top + dH

        val face = when (look) {
            2 -> "sans-serif-thin"
            3 -> "sans-serif-medium"
            else -> "sans-serif-black"
        }
        tp.typeface = Typeface.create(face, Typeface.NORMAL)
        tp.textAlign = Paint.Align.LEFT
        tp.letterSpacing = -0.03f
        tp.textSize = 100f
        tp.getTextBounds("8", 0, 1, bounds)
        tp.textSize = 100f * dH / max(1f, -bounds.top.toFloat())

        val hw = tp.measureText(hours)
        val mw = tp.measureText(mins)
        val cw = dH * 0.34f
        var x = (wf - (hw + cw + mw)) / 2f
        val mx = x + hw + cw

        tp.clearShadowLayer()
        tp.style = Paint.Style.FILL
        tp.shader = null
        tp.color = Color.WHITE
        when (look) {
            0 -> {
                tp.shader = LinearGradient(0f, top, 0f, base, 0xFFFFFFFF.toInt(), 0x59FFFFFF, Shader.TileMode.CLAMP)
                tp.setShadowLayer(dH * 0.10f, 0f, 0f, 0x40FFFFFF)
            }
            1 -> { }
            3 -> tp.setShadowLayer(dH * 0.05f, 0f, dH * 0.02f, 0x66000000)
        }
        c.drawText(hours, x, base, tp)

        // minutes: outlined in the Split look
        if (look == 1) {
            tp.clearShadowLayer()
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = dH * 0.022f
            tp.strokeJoin = Paint.Join.ROUND
        }
        c.drawText(mins, mx, base, tp)

        // colon: two round dots
        tp.style = Paint.Style.FILL
        val r = dH * 0.046f
        val cx = x + hw + cw / 2f
        c.drawCircle(cx, base - dH * 0.30f, r, tp)
        c.drawCircle(cx, base - dH * 0.70f, r, tp)

        if (showDate) {
            dp2.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            dp2.textAlign = Paint.Align.CENTER
            dp2.color = 0xC8FFFFFF.toInt()
            dp2.letterSpacing = 0.34f
            dp2.textSize = dH * 0.10f
            c.drawText(date.uppercase(Locale.getDefault()), wf / 2f, top - dH * 0.13f, dp2)
        }
    }
}

// ---------------------------------------------------------------- small custom widgets

class GearView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.strokeWidth = s * 0.11f
        c.drawCircle(cx, cy, s * 0.2f, p)
        p.strokeWidth = s * 0.14f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.33f, cy + sa * s * 0.33f, cx + ca * s * 0.43f, cy + sa * s * 0.43f, p)
        }
    }
}

/** 3x3 grid glyph for the "All Apps" icon. */
class DotsView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cell = s / 3f
        val sq = cell * 0.62f
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        for (r in 0 until 3) for (q in 0 until 3) {
            val x = ox + q * cell + (cell - sq) / 2f
            val y = oy + r * cell + (cell - sq) / 2f
            c.drawRoundRect(x, y, x + sq, y + sq, sq * 0.28f, sq * 0.28f, p)
        }
    }
}

/** Line icons for the settings sidebar, drawn in code. */
class GlyphView(ctx: Context, private val kind: Int) : View(ctx) {
    var tint = Color.WHITE
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        p.color = tint
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.085f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        val path = Path()
        when (kind) {
            0 -> { // clock
                c.drawCircle(x(.5f), y(.5f), s * .38f, p)
                c.drawLine(x(.5f), y(.5f), x(.5f), y(.26f), p)
                c.drawLine(x(.5f), y(.5f), x(.68f), y(.6f), p)
            }
            1 -> { // mountains
                path.moveTo(x(.06f), y(.8f)); path.lineTo(x(.36f), y(.26f)); path.lineTo(x(.56f), y(.6f))
                path.lineTo(x(.7f), y(.42f)); path.lineTo(x(.94f), y(.8f)); path.close()
                c.drawPath(path, p)
            }
            2 -> { // sparkle
                path.moveTo(x(.5f), y(.08f)); path.lineTo(x(.59f), y(.41f)); path.lineTo(x(.92f), y(.5f))
                path.lineTo(x(.59f), y(.59f)); path.lineTo(x(.5f), y(.92f)); path.lineTo(x(.41f), y(.59f))
                path.lineTo(x(.08f), y(.5f)); path.lineTo(x(.41f), y(.41f)); path.close()
                c.drawPath(path, p)
            }
            3 -> { // moon
                val a = Path(); a.addCircle(x(.5f), y(.5f), s * .36f, Path.Direction.CW)
                val b = Path(); b.addCircle(x(.68f), y(.38f), s * .3f, Path.Direction.CW)
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, p)
            }
            4 -> { // dock
                c.drawRoundRect(RectF(x(.08f), y(.58f), x(.92f), y(.86f)), s * .12f, s * .12f, p)
                for (i in 0 until 3) c.drawCircle(x(.3f + i * .2f), y(.34f), s * .07f, p)
            }
            5 -> { // recommendations: play
                c.drawRoundRect(RectF(x(.08f), y(.2f), x(.92f), y(.8f)), s * .14f, s * .14f, p)
                path.moveTo(x(.42f), y(.36f)); path.lineTo(x(.42f), y(.64f)); path.lineTo(x(.66f), y(.5f)); path.close()
                c.drawPath(path, p)
            }
            6 -> { // bluetooth
                path.moveTo(x(.3f), y(.32f)); path.lineTo(x(.7f), y(.68f)); path.lineTo(x(.5f), y(.86f))
                path.lineTo(x(.5f), y(.14f)); path.lineTo(x(.7f), y(.32f)); path.lineTo(x(.3f), y(.68f))
                c.drawPath(path, p)
            }
            7 -> { // wifi
                for (r in floatArrayOf(.52f, .34f, .16f)) {
                    c.drawArc(RectF(x(.5f - r), y(.8f - r), x(.5f + r), y(.8f + r)), 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(x(.5f), y(.8f), s * .05f, p)
            }
            8 -> { // gear
                c.drawCircle(x(.5f), y(.5f), s * .2f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                    c.drawLine(x(.5f + ca * .33f), y(.5f + sa * .33f), x(.5f + ca * .43f), y(.5f + sa * .43f), p)
                }
            }
            10 -> { // speaker
                path.moveTo(x(.12f), y(.4f)); path.lineTo(x(.3f), y(.4f)); path.lineTo(x(.52f), y(.2f))
                path.lineTo(x(.52f), y(.8f)); path.lineTo(x(.3f), y(.6f)); path.lineTo(x(.12f), y(.6f)); path.close()
                c.drawPath(path, p)
                c.drawArc(RectF(x(.4f), y(.3f), x(.8f), y(.7f)), -45f, 90f, false, p)
                c.drawArc(RectF(x(.3f), y(.16f), x(.94f), y(.84f)), -45f, 90f, false, p)
            }
            11 -> { // app grid
                for (r in 0 until 2) for (q in 0 until 2) {
                    val l = .14f + q * .38f; val t = .14f + r * .38f
                    c.drawRoundRect(RectF(x(l), y(t), x(l + .3f), y(t + .3f)), s * .07f, s * .07f, p)
                }
            }
            12 -> { // star
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val rr = if (i % 2 == 0) .42f else .18f
                    val px = x(.5f + cos(a).toFloat() * rr); val py = y(.54f + sin(a).toFloat() * rr)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, p)
            }
            13 -> { // display
                c.drawRoundRect(RectF(x(.08f), y(.18f), x(.92f), y(.68f)), s * .08f, s * .08f, p)
                c.drawLine(x(.5f), y(.68f), x(.5f), y(.84f), p)
                c.drawLine(x(.3f), y(.84f), x(.7f), y(.84f), p)
            }
            else -> { // info
                c.drawCircle(x(.5f), y(.5f), s * .38f, p)
                c.drawLine(x(.5f), y(.46f), x(.5f), y(.68f), p)
                p.style = Paint.Style.FILL
                c.drawCircle(x(.5f), y(.33f), s * .045f, p)
            }
        }
    }
}

/** iOS-style switch. */
class SwitchView(ctx: Context) : View(ctx) {
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(on: Boolean, animate: Boolean) {
        anim?.cancel()
        val to = if (on) 1f else 0f
        if (!animate) { pos = to; invalidate(); return }
        val a = ValueAnimator.ofFloat(pos, to)
        a.duration = 160
        a.addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        anim = a
        a.start()
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = lerp(0x48FFFFFF, Color.WHITE, pos)
        c.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, p)
        val r = h / 2f - h * 0.1f
        val cx = h / 2f + pos * (w - h)
        p.color = lerp(Color.WHITE, 0xFF0A0A0A.toInt(), pos)
        c.drawCircle(cx, h / 2f, r, p)
    }
}
