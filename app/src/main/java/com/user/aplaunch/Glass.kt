package com.user.aplaunch

import android.content.Context
import android.graphics.Bitmap
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
import android.widget.FrameLayout
import java.util.WeakHashMap

/**
 * Cheap "frosted glass": the wallpaper never changes, so one tiny, heavily blurred copy of it is built once
 * and every glass panel just draws the matching slice behind itself. No GPU blur, no per-frame cost.
 */
object Glass {
    @Volatile var backdrop: Bitmap? = null
    var level = 1 // 0 clear, 1 frosted, 2 dense
    val panels = WeakHashMap<GlassPanel, Boolean>()

    fun refresh() { for (p in panels.keys.toList()) p.invalidate() }

    fun set(b: Bitmap) { backdrop = b; refresh() }

    fun build(back: Bitmap, front: Bitmap): Bitmap {
        val w = 192; val h = 108
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect(0, 0, w, h)
        c.drawBitmap(back, null, dst, p)
        c.drawBitmap(front, null, dst, p)
        return blur(small, 5, 3)
    }

    private fun blur(bmp: Bitmap, r: Int, passes: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        var a = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        var b = IntArray(w * h)
        val n = 2 * r + 1
        repeat(passes) {
            for (y in 0 until h) for (x in 0 until w) {
                var rr = 0; var gg = 0; var bb = 0
                for (k in -r..r) {
                    val c = a[y * w + (x + k).coerceIn(0, w - 1)]
                    rr += Color.red(c); gg += Color.green(c); bb += Color.blue(c)
                }
                b[y * w + x] = Color.rgb(rr / n, gg / n, bb / n)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var rr = 0; var gg = 0; var bb = 0
                for (k in -r..r) {
                    val c = b[(y + k).coerceIn(0, h - 1) * w + x]
                    rr += Color.red(c); gg += Color.green(c); bb += Color.blue(c)
                }
                a[y * w + x] = Color.rgb(rr / n, gg / n, bb / n)
            }
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
        return bmp
    }
}

/** A glass surface: blurred backdrop slice, tint, diagonal reflection and a lit border. */
class GlassPanel(ctx: Context, private val radiusDp: Float = 28f) : FrameLayout(ctx) {
    var extraDark = 0f
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val loc = IntArray(2)
    private val src = Rect()
    private val dst = RectF()
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        Glass.panels[this] = true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val r = minOf(radiusDp * d, h / 2f, w / 2f)
        path.reset()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)

        val bd = Glass.backdrop
        if (bd != null) {
            getLocationOnScreen(loc)
            val sw = resources.displayMetrics.widthPixels.toFloat()
            val sh = resources.displayMetrics.heightPixels.toFloat()
            val l = (loc[0] / sw * bd.width).toInt().coerceIn(0, bd.width - 1)
            val t = (loc[1] / sh * bd.height).toInt().coerceIn(0, bd.height - 1)
            val rr = ((loc[0] + w) / sw * bd.width).toInt().coerceIn(l + 1, bd.width)
            val bb = ((loc[1] + h) / sh * bd.height).toInt().coerceIn(t + 1, bd.height)
            src.set(l, t, rr, bb)
            dst.set(0f, 0f, w, h)
            c.drawBitmap(bd, src, dst, bgPaint)
        }
        val dark = floatArrayOf(0f, 0.18f, 0.42f)[Glass.level] + extraDark
        val tint = intArrayOf(0x14FFFFFF, 0x1FFFFFFF, 0x1AFFFFFF)[Glass.level]
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.argb((dark.coerceIn(0f, 0.9f) * 255).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        p.color = tint
        c.drawRect(0f, 0f, w, h, p)
        // soft diagonal reflection
        p.shader = LinearGradient(0f, 0f, w * 0.65f, h * 0.95f, 0x2CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        c.restore()

        // lit border: bright at the top-left, fading to the bottom-right
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * 1.2f
        p.shader = LinearGradient(0f, 0f, w, h, 0x99FFFFFF.toInt(), 0x1AFFFFFF, Shader.TileMode.CLAMP)
        rect.set(0.6f, 0.6f, w - 0.6f, h - 0.6f)
        c.drawRoundRect(rect, r, r, p)
        p.shader = null
    }
}

/** Segmented pill (like the Years / Months / Days control): the selected segment is a lit pill. */
class SegmentView(ctx: Context, private val opts: List<String>) : View(ctx) {
    var sel = 0
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val widths = FloatArray(opts.size)
    private val rf = RectF()

    init {
        text.textSize = 13f * d
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        for (i in opts.indices) widths[i] = text.measureText(opts[i]) + 26f * d
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(widths.sum().toInt() + (4 * d).toInt(), (34 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        track.color = 0x24FFFFFF
        rf.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rf, h / 2f, h / 2f, track)
        var x = 2f * d
        for (i in opts.indices) {
            if (i == sel) {
                track.color = 0xF2FFFFFF.toInt()
                rf.set(x, 2f * d, x + widths[i], h - 2f * d)
                c.drawRoundRect(rf, h / 2f, h / 2f, track)
            }
            text.color = if (i == sel) 0xFF0A0A0A.toInt() else 0xCCFFFFFF.toInt()
            c.drawText(opts[i], x + widths[i] / 2f, h / 2f + 4.6f * d, text)
            x += widths[i]
        }
    }
}

/** Control-Center style slider: a pill that fills with white. */
class SliderView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val clip = Path()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension((210 * d).toInt(), (28 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, h / 2f, h / 2f, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        p.color = 0x30FFFFFF
        c.drawRect(0f, 0f, w, h, p)
        p.color = 0xF2FFFFFF.toInt()
        rf.set(0f, 0f, w * progress, h)
        c.drawRect(rf, p)
        c.restore()
    }
}
