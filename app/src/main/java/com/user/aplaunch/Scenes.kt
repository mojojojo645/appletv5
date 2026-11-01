package com.user.aplaunch

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One hero wallpaper: a monochrome alpine peak, painted procedurally.
 * Two layers so the clock can sit between them:
 *   back  = night sky, stars, moon, glow behind the peak
 *   front = far ranges, the snow-capped hero peak with lit/shaded faces, mist, dark foreground
 */
object Scenes {

    class Layers(val back: Bitmap, val front: Bitmap)

    private class Ridge(
        val base: Float, val amp: Float, val freq: Float,
        val top: Int, val bottom: Int, val seed: Int, val haze: Int,
        val trees: Boolean = false
    )

    private class Cone(val cx: Float, val cy: Float, val s: Float)

    private fun c(s: String) = Color.parseColor(s)
    private fun withAlpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a shl 24)

    // ------------------------------------------------------------------ noise

    private fun hash(i: Int, seed: Int): Float {
        var n = i * 374761393 + seed * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777215f
    }

    private fun noise(t: Float, seed: Int): Float {
        val i = floor(t).toInt()
        val f = t - i
        val s = f * f * (3f - 2f * f)
        val a = hash(i, seed)
        val b = hash(i + 1, seed)
        return a + (b - a) * s
    }

    private fun fbm(t: Float, seed: Int): Float {
        var v = 0f
        var amp = 1f
        var fr = 1f
        for (o in 0 until 5) {
            v += noise(t * fr, seed + o * 17) * amp
            amp *= 0.5f
            fr *= 2f
        }
        return (v / 1.9375f).pow(1.25f)
    }

    private fun ridged(t: Float, seed: Int): Float = 1f - abs(2f * noise(t, seed) - 1f)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mixf(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ------------------------------------------------------------------ render

    fun render(w: Int, h: Int): Layers {
        val back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bc = Canvas(back)
        drawSky(bc, w, h)
        drawFront(front, w, h)
        vignette(bc, w, h, null)
        vignette(Canvas(front), w, h, PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP))
        grain(bc, w, h)
        return Layers(back, front)
    }

    private fun drawSky(cv: Canvas, w: Int, h: Int) {
        val k = h / 900f
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, hf * 0.86f,
            intArrayOf(c("#000000"), c("#050505"), c("#232323"), c("#6E6E6E"), c("#B8B8B8")),
            floatArrayOf(0f, .30f, .58f, .76f, 1f), Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, wf, hf, p)
        p.shader = null

        // stars thin out towards the horizon
        val r = Random(7)
        p.color = Color.WHITE
        for (i in 0 until 520) {
            val y = r.nextFloat().pow(1.7f) * hf * 0.62f
            val fade = 1f - y / (hf * 0.62f)
            p.alpha = (30 + r.nextInt(170) * fade).toInt().coerceIn(0, 255)
            cv.drawCircle(r.nextFloat() * wf, y, (0.5f + r.nextFloat() * 1.2f) * k, p)
        }
        p.alpha = 255

        // glow behind the peak, so its dark silhouette reads like a backlit mountain
        p.shader = RadialGradient(
            wf * 0.66f, hf * 0.72f, hf * 0.62f,
            intArrayOf(withAlpha(Color.WHITE, 120), withAlpha(Color.WHITE, 38), 0),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, wf, hf, p)

        // moon
        val mx = wf * 0.17f; val my = hf * 0.20f; val mr = hf * 0.017f
        p.shader = RadialGradient(
            mx, my, mr * 9f,
            intArrayOf(withAlpha(Color.WHITE, 120), withAlpha(Color.WHITE, 24), 0),
            floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP
        )
        cv.drawCircle(mx, my, mr * 9f, p)
        p.shader = null
        p.color = Color.WHITE
        cv.drawCircle(mx, my, mr, p)
    }

    // hero silhouette: several cones, steep and slightly concave, plus rocky jaggedness
    private val cones = listOf(
        Cone(.66f, .345f, 1.75f), Cone(.41f, .52f, 1.9f),
        Cone(.93f, .48f, 1.8f), Cone(.15f, .62f, 1.6f)
    )

    private fun coneIndexAt(xf: Float): Int {
        var best = 0
        var by = 9f
        for (i in cones.indices) {
            val y = cones[i].cy + cones[i].s * abs(xf - cones[i].cx).pow(0.9f)
            if (y < by) { by = y; best = i }
        }
        return best
    }

    private fun silhouette(xf: Float, ci: Int): Float {
        val cn = cones[ci]
        var y = cn.cy + cn.s * abs(xf - cn.cx).pow(0.9f)
        y += (fbm(xf * 9f, 101) - 0.4f) * 0.05f + (fbm(xf * 34f, 103) - 0.4f) * 0.014f
        return y
    }

    private fun drawFront(front: Bitmap, w: Int, h: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val cv = Canvas(front)

        // far ranges
        drawRidges(cv, w, h, listOf(
            Ridge(.64f, .10f, 3.0f, c("#4A4A4A"), c("#6A6A6A"), 3, c("#66E8E8E8")),
            Ridge(.71f, .09f, 3.6f, c("#363636"), c("#4D4D4D"), 7, c("#55E0E0E0"))
        ))

        // hero peak, shaded per pixel
        val px = IntArray(w * h)
        front.getPixels(px, 0, w, 0, 0, w, h)
        val aspect = wf / hf
        for (x in 0 until w) {
            val xf = x / wf
            val ci = coneIndexAt(xf)
            val cn = cones[ci]
            val ysil = silhouette(xf, ci)
            val ysPx = ysil * hf
            val y0 = max(0, floor(ysPx).toInt() - 1)
            val snowLine = 0.56f + (fbm(xf * 11f, 201) - 0.4f) * 0.10f
            val macro = 0.5f - 0.5f * kotlin.math.tanh((xf - cn.cx) * 6f)
            for (y in y0 until h) {
                val cov = ((y + 1f) - ysPx).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val yf = y / hf
                // rays from the apex: ribs and gullies run straight down the faces
                val dy = yf - cn.cy + 0.06f
                val a = (xf - cn.cx) * aspect / dy
                val rr = ridged(a * 2.2f + cn.cx * 7f, 11 + ci) * 0.65f + ridged(a * 6.5f + yf * 1.4f, 29 + ci) * 0.35f
                val da = 0.012f / dy
                val g = ridged((a + da) * 2.2f + cn.cx * 7f, 11 + ci) - ridged((a - da) * 2.2f + cn.cx * 7f, 11 + ci)
                val light = (0.5f * macro + 0.5f * (0.5f + g * 5f)).coerceIn(0f, 1f)

                var rock = mixf(20f, 150f, light)
                val fog = smooth(0.62f, 0.98f, yf)
                rock = mixf(rock, 78f, fog * 0.5f)

                var snow = smooth(snowLine + 0.05f, snowLine - 0.03f, yf)
                snow *= 1f - 0.5f * rr * rr
                val sv = mixf(100f, 252f, light)
                val v = mixf(rock, sv, snow).toInt().coerceIn(0, 255)
                val src = Color.rgb(v, v, v)

                val idx = y * w + x
                if (cov >= 1f) {
                    px[idx] = src
                } else {
                    val d = px[idx]
                    val da2 = Color.alpha(d) / 255f
                    val oa = cov + da2 * (1f - cov)
                    val r = ((v * cov + Color.red(d) * da2 * (1f - cov)) / oa).toInt()
                    px[idx] = Color.argb((oa * 255f).toInt(), r, r, r)
                }
            }
        }
        front.setPixels(px, 0, w, 0, 0, w, h)

        // drifting mist in the valleys
        val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        val mists = arrayOf(
            floatArrayOf(.28f, .84f, .55f, .075f, 70f),
            floatArrayOf(.74f, .78f, .60f, .080f, 62f),
            floatArrayOf(.50f, .90f, .90f, .070f, 52f)
        )
        for (m in mists) {
            val rx = m[2] * wf
            val ry = m[3] * hf
            cv.save()
            cv.translate(m[0] * wf, m[1] * hf)
            cv.scale(1f, ry / rx)
            mp.shader = RadialGradient(0f, 0f, rx, intArrayOf(withAlpha(Color.WHITE, m[4].toInt()), 0), null, Shader.TileMode.CLAMP)
            cv.drawCircle(0f, 0f, rx, mp)
            cv.restore()
        }

        // dark foreground
        drawRidges(cv, w, h, listOf(
            Ridge(.90f, .06f, 3.2f, c("#0F0F0F"), c("#1A1A1A"), 17, c("#26FFFFFF")),
            Ridge(.965f, .045f, 5.0f, c("#030303"), c("#070707"), 19, c("#00000000"), true)
        ))
    }

    private fun drawRidges(cv: Canvas, w: Int, h: Int, list: List<Ridge>) {
        val k = h / 900f
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (r in list) {
            val yBase = r.base * hf
            val rnd = Random(r.seed.toLong())
            val path = Path()
            path.moveTo(0f, hf)
            var x = 0
            while (x <= w + 8) {
                var y = (r.base - r.amp * fbm(x.toFloat() / wf * r.freq * 3f, r.seed)) * hf
                if (r.trees) y -= if ((x / 6) % 2 == 0) (10f + rnd.nextFloat() * 16f) * k else 0f
                path.lineTo(x.toFloat(), y)
                x += 4
            }
            path.lineTo(wf, hf)
            path.close()
            p.shader = LinearGradient(0f, yBase - r.amp * hf, 0f, min(hf, yBase + 0.12f * hf), r.top, r.bottom, Shader.TileMode.CLAMP)
            cv.drawPath(path, p)
            p.shader = LinearGradient(0f, yBase - r.amp * hf, 0f, hf, 0, r.haze, Shader.TileMode.CLAMP)
            cv.drawRect(0f, yBase - r.amp * hf, wf, hf, p)
            p.shader = null
        }
    }

    private fun vignette(cv: Canvas, w: Int, h: Int, mode: PorterDuffXfermode?) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            wf / 2f, hf * 0.45f, max(wf, hf) * 0.75f,
            intArrayOf(0, withAlpha(Color.BLACK, 120)), floatArrayOf(.55f, 1f), Shader.TileMode.CLAMP
        )
        if (mode != null) p.xfermode = mode
        cv.drawRect(0f, 0f, wf, hf, p)
    }

    /** Fine grain: hides gradient banding on 8-bit panels. */
    private fun grain(cv: Canvas, w: Int, h: Int) {
        val tile = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val nr = Random(3)
        for (yy in 0 until 128) for (xx in 0 until 128) {
            val v = nr.nextInt(256)
            tile.setPixel(xx, yy, Color.argb(12, v, v, v))
        }
        val p = Paint()
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        tile.recycle()
    }
}
