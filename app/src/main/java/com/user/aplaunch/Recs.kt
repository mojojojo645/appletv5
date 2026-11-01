package com.user.aplaunch

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class Rec(val title: String, val image: String?, val intentUri: String?)

/**
 * Reads what apps publish to the Android TV home screen ("Watch Next" and preview channels).
 * YouTube, Netflix, Disney+, Prime Video and others publish there; apps that don't simply show nothing.
 */
object Recs {
    const val PERM = "com.android.providers.tv.permission.READ_EPG_DATA"
    private val cache = HashMap<String, Pair<Long, List<Rec>>>()

    fun granted(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun cached(pkg: String): List<Rec>? {
        val e = cache[pkg] ?: return null
        return if (System.currentTimeMillis() - e.first < 120_000) e.second else null
    }

    fun fetch(ctx: Context, pkg: String): List<Rec> {
        val out = ArrayList<Rec>()
        val seen = HashSet<String>()
        fun read(uri: String, byWeight: Boolean) {
            try {
                ctx.contentResolver.query(Uri.parse(uri), null, "package_name=?", arrayOf(pkg), null)?.use { c ->
                    val iT = c.getColumnIndex("title")
                    val iTh = c.getColumnIndex("thumbnail_uri")
                    val iP = c.getColumnIndex("poster_art_uri")
                    val iI = c.getColumnIndex("intent_uri")
                    val iW = c.getColumnIndex("weight")
                    val rows = ArrayList<Pair<Int, Rec>>()
                    var n = 0
                    while (c.moveToNext() && n++ < 80) {
                        val t = if (iT >= 0) c.getString(iT) else null
                        if (t.isNullOrBlank() || !seen.add(t)) continue
                        val img = (if (iTh >= 0) c.getString(iTh) else null) ?: (if (iP >= 0) c.getString(iP) else null)
                        val iu = if (iI >= 0) c.getString(iI) else null
                        val wt = if (iW >= 0 && !c.isNull(iW)) c.getInt(iW) else 0
                        rows.add(Pair(wt, Rec(t, img, iu)))
                    }
                    if (byWeight) rows.sortByDescending { it.first }
                    for (r in rows) out.add(r.second)
                }
            } catch (e: Exception) {
                // no access or no such table: nothing to show
            }
        }
        read("content://android.media.tv/watch_next_program", false)
        read("content://android.media.tv/preview_program", true)
        val list = out.take(8)
        synchronized(this) { cache[pkg] = Pair(System.currentTimeMillis(), list) }
        return list
    }
}

/** Tiny image loader: memory cache, http(s) and content:// sources, downsampled. */
object Img {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, url: String, targetW: Int, cb: (Bitmap?) -> Unit) {
        val hit = cache.get(url)
        if (hit != null) { cb(hit); return }
        val app = ctx.applicationContext
        pool.execute {
            var bmp: Bitmap? = null
            try { bmp = decode(app, url, targetW) } catch (e: Throwable) { }
            val out = bmp
            if (out != null) cache.put(url, out)
            main.post { cb(out) }
        }
    }

    private fun open(ctx: Context, url: String): InputStream? {
        if (url.startsWith("http")) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 6000
            return c.inputStream
        }
        return ctx.contentResolver.openInputStream(Uri.parse(url))
    }

    private fun decode(ctx: Context, url: String, tw: Int): Bitmap? {
        val bytes = open(ctx, url)?.use { it.readBytes() } ?: return null
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= tw) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}
