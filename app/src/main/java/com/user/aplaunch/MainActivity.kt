package com.user.aplaunch

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.Executors

class AppEntry(val pkg: String, val label: String, val intent: Intent, val icon: Drawable)

/** One of the default favorites. Matched by package first, then by label. "=x" = exact label. */
class Fixed(val name: String, val pkgs: List<String>, val hints: List<String>)

class MainActivity : Activity(), SettingsHost {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val FIXED = listOf(
            Fixed("Rad TV", emptyList(), listOf("radtv")),
            Fixed("YouTube", listOf("com.google.android.youtube.tv"), listOf("=youtube")),
            Fixed("Netflix", listOf("com.netflix.ninja"), listOf("=netflix")),
            Fixed("HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max"), listOf("hbomax", "=max")),
            Fixed("Disney+", listOf("com.disney.disneyplus"), listOf("disney")),
            Fixed("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), listOf("primevideo")),
            Fixed("Crunchyroll", listOf("com.crunchyroll.crunchyroid"), listOf("crunchyroll")),
            Fixed("Stremio", listOf("com.stremio.one"), listOf("stremio")),
            Fixed("7reels", emptyList(), listOf("7reels"))
        )
        val GLOW_A = floatArrayOf(0.55f, 0.85f, 1f)
        val DIM = floatArrayOf(0.6f, 0.4f, 0.25f)
        val WALL = floatArrayOf(1f, 0.85f, 0.65f)
        val AOD_MS = longArrayOf(60_000, 120_000, 300_000, 600_000, 1_800_000)
        val CLOCK_SIZE = floatArrayOf(0.16f, 0.20f, 0.25f)
    }

    /** kind: 0 favorites tray, 1 recently used, 2 recommendation card, 3 top navigation */
    private class Tag(val kind: Int, val app: AppEntry?, val name: String)

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val io = Executors.newFixedThreadPool(2)
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private lateinit var store: Store

    private lateinit var root: FrameLayout
    private lateinit var wallBack: ImageView
    private lateinit var wallFront: ImageView
    private lateinit var clock: ClockView
    private lateinit var content: FrameLayout
    private lateinit var greeting: TextView
    private lateinit var wifiGlyph: GlyphView
    private lateinit var btGlyph: GlyphView
    private lateinit var homeTab: TextView
    private lateinit var rows: FrameLayout
    private lateinit var trayScroll: HorizontalScrollView
    private lateinit var trayRow: LinearLayout
    private lateinit var recentsTitle: TextView
    private lateinit var recentsScroll: HorizontalScrollView
    private lateinit var recentsRow: LinearLayout
    private lateinit var shelf: LinearLayout
    private lateinit var shelfTitle: TextView
    private lateinit var shelfScroll: HorizontalScrollView
    private lateinit var shelfRow: LinearLayout

    private var overlay: View? = null
    private var appList: List<AppEntry> = emptyList()
    private var sig = ""
    private var lastRefresh = 0L
    private var drifts = ArrayList<ObjectAnimator>()
    private var wallReady = false
    private var idle = false
    private var dim = 0f
    private var dimAnim: ValueAnimator? = null
    private var pendingTag: Tag? = null
    private var hintShown = false
    private var recentsMode = false
    private val appCache = HashMap<String, AppEntry>()

    override val apps: List<AppEntry> get() = appList

    private val idleRun = Runnable { enterIdle() }
    private val shelfRun = Runnable { pendingTag?.let { showShelfFor(it) } }
    private val clockTick = object : Runnable {
        override fun run() {
            clock.tick()
            updateGreeting()
            ui.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        root = FrameLayout(this)
        wallBack = wallView()
        wallFront = wallView()
        clock = ClockView(this)
        // z-order: sky layer, clock, mountain layer, scrim, UI
        root.addView(wallBack, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(clock, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(wallFront, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x33000000, 0x00000000, 0x00000000, 0x99000000.toInt())
        )
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        content = FrameLayout(this)
        content.clipChildren = false
        buildGreeting()
        buildShelf()
        buildRows()
        buildNav()
        buildStatus()
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, nv -> onFocusChanged(nv) }

        loadWallpaper()
        applySettings()
        refreshApps(true)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(clockTick)
        ui.post(clockTick)
        drifts.forEach { it.resume() }
        if (idle) wake(true)
        resetIdle()
        refreshStatus()
        if (overlay == null && appList.isNotEmpty()) populateRecents()
        if (System.currentTimeMillis() - lastRefresh > 15_000) refreshApps(false)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockTick)
        ui.removeCallbacks(idleRun)
        ui.removeCallbacks(shelfRun)
        drifts.forEach { it.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        io.shutdownNow()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (idle) wake(true)
        if (overlay != null) closeOverlay()
        trayScroll.smoothScrollTo(0, 0)
        firstCard()?.requestFocus()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (overlay != null) closeOverlay()
        else if (recentsMode) firstCard()?.requestFocus()
        // a launcher never goes "back"
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (idle) {
            if (e.action == KeyEvent.ACTION_DOWN) wake(false)
            return true
        }
        resetIdle()
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_MENU && overlay == null) {
            openSettings()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- settings -> live effects

    private fun d(ms: Long): Long {
        var f = when (store.i(K.ANIM)) { 0 -> 1.0; 1 -> 0.6; else -> 0.0 }
        if (store.on(K.PERF) && f > 0.5) f = 0.5
        return (ms * f).toLong()
    }

    private fun motionOn() = store.on(K.MOTION) && !store.on(K.PERF)

    private fun applySettings() {
        clock.sizeFrac = CLOCK_SIZE[store.i(K.CLOCK_SIZE)]
        clock.look = store.i(K.CLOCK_LOOK)
        clock.format = store.i(K.CLOCK_FMT)
        clock.showDate = store.on(K.CLOCK_DATE)
        val mode = store.i(K.CLOCK_MODE)
        clock.visibility = if (mode == 2) View.GONE else View.VISIBLE
        val below = root.indexOfChild(clock) < root.indexOfChild(wallFront)
        if (mode == 1 && below) {
            root.removeView(clock)
            root.addView(clock, root.indexOfChild(wallFront) + 1, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (mode != 1 && !below) {
            root.removeView(clock)
            root.addView(clock, root.indexOfChild(wallFront), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        clock.tick()
        updateGreeting()

        Glass.level = store.i(K.GLASS)
        Glass.refresh()

        if (store.on(K.KEEP_ON)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (wallReady) {
            if (!motionOn()) {
                stopDrifts()
                for (v in listOf(wallBack, wallFront)) { v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f }
            } else if (drifts.isEmpty()) {
                startDrifts()
            }
        }
        if (!store.on(K.RECS)) hideShelf()
        if (!store.on(K.AOD) && idle) wake(true)
        if (overlay == null && appList.isNotEmpty()) { populateTray(false); populateRecents() }
        resetIdle()
        applyDim()
    }

    private fun updateGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = when {
            h < 5 -> "Good night"
            h < 12 -> "Good morning"
            h < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        greeting.visibility = if (store.on(K.GREETING)) View.VISIBLE else View.INVISIBLE
    }

    private fun navSound() {
        if (!store.on(K.NAV_SOUND)) return
        try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT, 0.5f) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- always-on display

    private fun resetIdle() {
        ui.removeCallbacks(idleRun)
        if (store.on(K.AOD) && overlay == null) ui.postDelayed(idleRun, AOD_MS[store.i(K.AOD_DELAY)])
    }

    private fun enterIdle() {
        if (idle || overlay != null) return
        idle = true
        hideShelf()
        content.animate().alpha(0f).setDuration(d(700)).start()
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dimTo(1f)
    }

    private fun wake(instant: Boolean) {
        idle = false
        content.animate().cancel()
        if (instant) content.alpha = 1f else content.animate().alpha(1f).setDuration(d(350)).start()
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (instant) { dimAnim?.cancel(); dim = 0f; applyDim() } else dimTo(0f)
        resetIdle()
        firstCard()?.requestFocus()
    }

    private fun dimTo(target: Float) {
        dimAnim?.cancel()
        val a = ValueAnimator.ofFloat(dim, target)
        a.duration = d(1200).coerceAtLeast(1)
        a.addUpdateListener { dim = it.animatedValue as Float; applyDim() }
        dimAnim = a
        a.start()
    }

    private fun applyDim() {
        val wm = WALL[store.i(K.WALL_BRIGHT)]
        val m = DIM[store.i(K.AOD_DIM)]
        val g = (255 * wm * (1f - (1f - m) * dim)).toInt().coerceIn(0, 255)
        for (v in listOf(wallBack, wallFront)) {
            if (g >= 255) v.clearColorFilter() else v.colorFilter = LightingColorFilter(Color.rgb(g, g, g), 0)
        }
    }

    // ---------------------------------------------------------------- wallpaper (one hero mountain, cached)

    private fun wallView(): ImageView {
        val v = ImageView(this)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null) // animate as GPU layers: cheap on slow TVs
        v.alpha = 0f
        return v
    }

    private fun loadWallpaper() {
        val big = !store.on(K.PERF)
        val w = if (big) 1600 else 1280
        val h = if (big) 900 else 720
        bg.execute {
            val fb = File(cacheDir, "mtn5_${w}_back.png")
            val ff = File(cacheDir, "mtn5_${w}_front.png")
            var back: Bitmap? = null
            var front: Bitmap? = null
            try {
                if (fb.exists() && ff.exists()) {
                    back = BitmapFactory.decodeFile(fb.path)
                    front = BitmapFactory.decodeFile(ff.path)
                }
            } catch (e: Throwable) { }
            if (back == null || front == null) {
                try {
                    val l = Scenes.render(w, h)
                    back = l.back
                    front = l.front
                    try {
                        FileOutputStream(fb).use { l.back.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        FileOutputStream(ff).use { l.front.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } catch (e: Throwable) { fb.delete(); ff.delete() }
                } catch (e: Throwable) { }
            }
            val b = back
            val f = front
            val backdrop = if (b != null && f != null) try { Glass.build(b, f) } catch (e: Throwable) { null } else null
            ui.post {
                if (!isDestroyed && b != null && f != null) {
                    showWall(b, f)
                    if (backdrop != null) Glass.set(backdrop)
                }
            }
        }
    }

    private fun showWall(b: Bitmap, f: Bitmap) {
        wallBack.setImageBitmap(b)
        wallFront.setImageBitmap(f)
        wallReady = true
        wallBack.animate().alpha(1f).setDuration(d(1200)).start()
        wallFront.animate().alpha(1f).setDuration(d(1200)).start()
        if (motionOn()) startDrifts()
        applyDim()
    }

    private fun driftAnim(v: View, from: Float, to: Float, dx: Float): ObjectAnimator {
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx)
        )
        a.duration = 60_000
        a.interpolator = LinearInterpolator()
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        return a
    }

    private fun startDrifts() {
        // the near layer moves more than the far layer: that's the parallax
        drifts.add(driftAnim(wallBack, 1.02f, 1.045f, -dp(12)))
        drifts.add(driftAnim(wallFront, 1.03f, 1.075f, -dp(28)))
        drifts.forEach { it.start() }
    }

    private fun stopDrifts() {
        drifts.forEach { it.cancel() }
        drifts.clear()
    }

    // ---------------------------------------------------------------- small helpers

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
    }

    private fun fresh(dr: Drawable): Drawable = dr.constantState?.newDrawable(resources)?.mutate() ?: dr

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun resolve(f: Fixed): AppEntry? {
        appList.firstOrNull { it.pkg in f.pkgs }?.let { return it }
        return appList.firstOrNull { a ->
            val n = norm(a.label)
            f.hints.any { h -> if (h.startsWith("=")) n == h.substring(1) else n.contains(h) }
        }
    }

    private fun firstCard(): View? = trayRow.getChildAt(0)?.tag as? View

    // ---------------------------------------------------------------- top navigation + status

    private fun buildNav() {
        val nav = GlassPanel(this, 40f)
        nav.setPadding(dpi(6), dpi(5), dpi(6), dpi(5))
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        val tabs = listOf("Home", "Apps", "Settings")
        for (name in tabs) {
            val t = tv(name, 15f, 0.92f, "sans-serif-medium")
            t.setPadding(dpi(22), dpi(9), dpi(22), dpi(9))
            val g = GradientDrawable()
            g.cornerRadius = dp(40)
            g.setColor(if (name == "Home") 0x33FFFFFF else Color.TRANSPARENT)
            t.background = g
            t.isFocusable = true
            t.isClickable = true
            t.tag = Tag(3, null, name)
            t.setOnFocusChangeListener { v, has ->
                g.setColor(if (has) 0xF2FFFFFF.toInt() else if (name == "Home") 0x33FFFFFF else Color.TRANSPARENT)
                t.setTextColor(if (has) 0xFF0A0A0A.toInt() else Color.WHITE)
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(d(180)).setInterpolator(ease).start()
                if (has) navSound()
            }
            t.setOnClickListener {
                when (name) {
                    "Apps" -> openAllApps()
                    "Settings" -> openSettings()
                    else -> { trayScroll.smoothScrollTo(0, 0); firstCard()?.requestFocus() }
                }
            }
            if (name == "Home") homeTab = t
            l.addView(t)
        }
        nav.addView(l)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = dpi(24)
        content.addView(nav, lp)
    }

    private fun buildStatus() {
        val pill = GlassPanel(this, 40f)
        pill.setPadding(dpi(18), dpi(11), dpi(18), dpi(11))
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        wifiGlyph = GlyphView(this, 7)
        btGlyph = GlyphView(this, 6)
        l.addView(wifiGlyph, LinearLayout.LayoutParams(dpi(20), dpi(20)))
        l.addView(btGlyph, LinearLayout.LayoutParams(dpi(20), dpi(20)).apply { leftMargin = dpi(10) })
        val t = TextClock(this)
        t.format12Hour = "h:mm"
        t.format24Hour = "HH:mm"
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        l.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dpi(14) })
        pill.addView(l)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        lp.topMargin = dpi(24); lp.rightMargin = dpi(64)
        content.addView(pill, lp)
    }

    @Suppress("DEPRECATION")
    private fun refreshStatus() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            wifiGlyph.alpha = if (ni != null && ni.isConnected) 1f else 0.35f
        } catch (e: Exception) { }
        try {
            val ad = BluetoothAdapter.getDefaultAdapter()
            btGlyph.alpha = if (ad != null && ad.isEnabled) 1f else 0.35f
        } catch (e: Exception) { btGlyph.alpha = 0.35f }
    }

    private fun buildGreeting() {
        greeting = tv("", 21f, 0.92f, "sans-serif-light")
        greeting.setShadowLayer(dp(8), 0f, dp(1), 0x66000000)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        lp.leftMargin = dpi(64); lp.topMargin = dpi(88)
        content.addView(greeting, lp)
    }

    // ---------------------------------------------------------------- home rows

    private fun buildRows() {
        rows = FrameLayout(this)
        rows.clipChildren = false

        val tray = GlassPanel(this, 30f)
        trayScroll = HorizontalScrollView(this)
        trayScroll.isHorizontalScrollBarEnabled = false
        trayScroll.overScrollMode = View.OVER_SCROLL_NEVER
        trayScroll.clipToPadding = false
        trayScroll.clipChildren = false
        trayScroll.setPadding(dpi(26), dpi(18), dpi(26), dpi(14))
        trayRow = LinearLayout(this)
        trayRow.orientation = LinearLayout.HORIZONTAL
        trayRow.clipChildren = false
        trayScroll.addView(trayRow, FrameLayout.LayoutParams(WRAP, WRAP))
        tray.addView(trayScroll, FrameLayout.LayoutParams(MATCH, WRAP))
        val tlp = FrameLayout.LayoutParams(MATCH, WRAP)
        tlp.leftMargin = dpi(64); tlp.rightMargin = dpi(64)
        rows.addView(tray, tlp)

        recentsTitle = tv("RECENTLY USED", 12f, 0.7f, "sans-serif-medium")
        recentsTitle.letterSpacing = 0.2f
        val rlp = FrameLayout.LayoutParams(WRAP, WRAP)
        rlp.leftMargin = dpi(74); rlp.topMargin = dpi(164)
        rows.addView(recentsTitle, rlp)

        recentsScroll = HorizontalScrollView(this)
        recentsScroll.isHorizontalScrollBarEnabled = false
        recentsScroll.overScrollMode = View.OVER_SCROLL_NEVER
        recentsScroll.clipToPadding = false
        recentsScroll.clipChildren = false
        recentsScroll.setPadding(dpi(64), dpi(14), dpi(64), dpi(14))
        recentsRow = LinearLayout(this)
        recentsRow.orientation = LinearLayout.HORIZONTAL
        recentsRow.clipChildren = false
        recentsScroll.addView(recentsRow, FrameLayout.LayoutParams(WRAP, WRAP))
        val slp = FrameLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = dpi(184)
        rows.addView(recentsScroll, slp)

        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
        lp.topMargin = dpi(318)
        content.addView(rows, lp)
    }

    private fun setRecentsMode(on: Boolean) {
        if (on == recentsMode) return
        recentsMode = on
        rows.animate().translationY(if (on) -dp(200) else 0f).setDuration(d(380)).setInterpolator(ease).start()
        val a = if (on) 0f else 1f
        clock.animate().alpha(a).setDuration(d(300)).start()
        greeting.animate().alpha(if (on) 0f else 0.92f).setDuration(d(300)).start()
    }

    private fun favList(): MutableList<String> =
        (store.str(K.FAVS) ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()

    private fun saveFavs(l: List<String>) = store.putStr(K.FAVS, l.joinToString("\n"))

    private fun favorites(): List<AppEntry> {
        if (store.str(K.FAVS) == null && appList.isNotEmpty()) saveFavs(FIXED.mapNotNull { resolve(it)?.pkg }.distinct())
        val by = appList.associateBy { it.pkg }
        return favList().mapNotNull { by[it] }
    }

    override fun isFav(pkg: String) = pkg in favList()

    override fun toggleFav(pkg: String): Boolean {
        val l = favList()
        val on = if (l.remove(pkg)) false else { l.add(pkg); true }
        saveFavs(l)
        return on
    }

    override fun resetFavs() { saveFavs(FIXED.mapNotNull { resolve(it)?.pkg }.distinct()) }

    override fun clearRecent() { store.putStr(K.RECENT, "") }

    private fun recentList(): MutableList<String> =
        (store.str(K.RECENT) ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()

    private fun recordRecent(pkg: String) {
        val l = recentList()
        l.remove(pkg)
        l.add(0, pkg)
        store.putStr(K.RECENT, l.take(14).joinToString("\n"))
    }

    private fun iconFill(card: FrameLayout, a: AppEntry) {
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setImageDrawable(fresh(a.icon))
        card.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun populateTray(animateIn: Boolean) {
        trayRow.removeAllViews()
        val s = dpi(92)
        var i = 0
        for (a in favorites()) {
            trayRow.addView(makeTile(0, a, a.label, s, false, trayScroll, i++, animateIn,
                { v -> launch(a, v) },
                { Toast.makeText(this, a.label + " removed from favorites", Toast.LENGTH_SHORT).show(); toggleFav(a.pkg); populateTray(false); populateRecents() },
                { card -> iconFill(card, a) }))
        }
        if (store.on(K.ALL_APPS_TILE)) {
            trayRow.addView(makeTile(0, null, "All Apps", s, false, trayScroll, i, animateIn,
                { openAllApps() }, null,
                { card ->
                    val g = GradientDrawable()
                    g.cornerRadius = s * 0.26f
                    g.setColor(0x33FFFFFF)
                    card.background = g
                    card.addView(DotsView(this), FrameLayout.LayoutParams(s / 2, s / 2, Gravity.CENTER))
                }))
        }
        trayRow.post { if (overlay == null && !recentsMode) firstCard()?.requestFocus() }
    }

    private fun populateRecents() {
        recentsRow.removeAllViews()
        val by = appList.associateBy { it.pkg }
        val rec = if (store.on(K.RECENT_ON)) recentList().mapNotNull { by[it] } else emptyList()
        val vis = if (rec.isEmpty()) View.GONE else View.VISIBLE
        recentsTitle.visibility = vis
        recentsScroll.visibility = vis
        val s = dpi(58)
        for ((j, a) in rec.withIndex()) {
            recentsRow.addView(makeTile(1, a, a.label, s, true, recentsScroll, j, false,
                { v -> launch(a, v) },
                { val on = toggleFav(a.pkg); Toast.makeText(this, a.label + if (on) " added to favorites" else " removed from favorites", Toast.LENGTH_SHORT).show(); populateTray(false) },
                { card -> iconFill(card, a) }))
        }
        if (rec.isEmpty() && recentsMode) firstCard()?.requestFocus()
    }

    /** One glass-tray icon: squircle app icon, white halo on focus, label, long-press action. */
    private fun makeTile(
        kind: Int, app: AppEntry?, name: String, s: Int, labelAlways: Boolean,
        scroll: HorizontalScrollView, index: Int, animateIn: Boolean,
        onClick: (View) -> Unit, onLong: (() -> Unit)?, fill: (FrameLayout) -> Unit
    ): View {
        val r = s * 0.26f
        val gap = (s * 0.22f).toInt()
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.clipChildren = false
        val clp = LinearLayout.LayoutParams(s, WRAP)
        clp.marginEnd = gap
        cell.layoutParams = clp

        val holder = FrameLayout(this)
        holder.clipChildren = false
        val gs = (s * 2.1f).toInt()
        val glow = View(this)
        val gd = GradientDrawable()
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        gd.setGradientRadius(gs / 2f)
        gd.setColors(intArrayOf(0xCCFFFFFF.toInt(), 0x50FFFFFF, 0x00FFFFFF))
        glow.background = gd
        glow.alpha = 0f
        glow.scaleX = 0.8f
        glow.scaleY = 0.8f
        holder.addView(glow, FrameLayout.LayoutParams(gs, gs, Gravity.CENTER))

        val card = FrameLayout(this)
        val bgd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2C2C2E.toInt(), 0xFF111112.toInt()))
        bgd.cornerRadius = r
        card.background = bgd
        card.outlineProvider = roundOutline(r)
        card.clipToOutline = true
        card.elevation = dp(2)
        holder.addView(card, FrameLayout.LayoutParams(s, s))
        fill(card)

        val sheen = View(this)
        sheen.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF))
        sheen.alpha = 0f
        card.addView(sheen, FrameLayout.LayoutParams(s, MATCH))
        val ring = View(this)
        val rg = GradientDrawable()
        rg.setColor(Color.TRANSPARENT)
        rg.setStroke(dpi(1), Color.WHITE)
        rg.cornerRadius = r
        ring.background = rg
        ring.alpha = 0.22f
        card.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))

        val lab = tv(name, if (kind == 0) 14f else 11f, if (labelAlways) 0.75f else 0f, "sans-serif-medium")
        lab.gravity = Gravity.CENTER
        lab.maxLines = 1
        lab.ellipsize = TextUtils.TruncateAt.END
        lab.setPadding(0, dpi(if (kind == 0) 9 else 6), 0, 0)
        lab.setShadowLayer(dp(8), 0f, dp(1), 0x99000000.toInt())

        cell.addView(holder, LinearLayout.LayoutParams(s, s))
        val llp = LinearLayout.LayoutParams(s + gap, WRAP)
        llp.marginStart = -gap / 2
        cell.addView(lab, llp)

        card.isFocusable = true
        card.isClickable = true
        card.tag = Tag(kind, app, name)
        card.setOnFocusChangeListener { _, has ->
            card.animate().scaleX(if (has) 1.14f else 1f).scaleY(if (has) 1.14f else 1f)
                .translationZ(if (has) dp(20) else 0f).setDuration(d(260)).setInterpolator(ease).start()
            val ga = if (has && store.on(K.GLOW)) GLOW_A[store.i(K.GLOW_STR)] * 0.8f else 0f
            glow.animate().alpha(ga).scaleX(if (has) 1f else 0.8f).scaleY(if (has) 1f else 0.8f)
                .setDuration(d(300)).setInterpolator(ease).start()
            ring.animate().alpha(if (has) 0.95f else 0.22f).setDuration(d(180)).start()
            lab.animate().alpha(if (has) 1f else if (labelAlways) 0.75f else 0f)
                .translationY(if (has) s * 0.07f else 0f).setDuration(d(220)).start()
            sheen.animate().cancel()
            sheen.alpha = 0f
            if (has) {
                navSound()
                if (store.on(K.SHEEN) && !store.on(K.PERF)) {
                    sheen.translationX = -s.toFloat()
                    sheen.alpha = 1f
                    sheen.animate().translationX(s.toFloat()).setDuration(700).setInterpolator(LinearInterpolator())
                        .withEndAction { sheen.alpha = 0f }.start()
                }
                val target = scroll.paddingLeft + cell.left + cell.width / 2 - scroll.width / 2
                scroll.smoothScrollTo(maxOf(0, target), 0)
            }
        }
        card.setOnClickListener { onClick(it) }
        if (onLong != null) card.setOnLongClickListener { onLong(); true }
        cell.tag = card
        if (animateIn && d(1) != 0L) {
            cell.alpha = 0f
            cell.translationY = dp(28)
            cell.animate().alpha(1f).translationY(0f).setStartDelay(120L + index * 50L)
                .setDuration(d(500)).setInterpolator(ease).start()
        }
        return cell
    }

    private fun launch(a: AppEntry, from: View) {
        try {
            recordRecent(a.pkg)
            val o = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            val i = Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i, o.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${a.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- recommendations shelf

    private fun buildShelf() {
        shelf = LinearLayout(this)
        shelf.orientation = LinearLayout.VERTICAL
        shelf.clipChildren = false
        shelf.visibility = View.INVISIBLE
        shelf.alpha = 0f
        shelfTitle = tv("", 12f, 0.8f, "sans-serif-medium")
        shelfTitle.letterSpacing = 0.2f
        shelfTitle.setPadding(dpi(74), 0, 0, 0)
        shelf.addView(shelfTitle)
        shelfScroll = HorizontalScrollView(this)
        shelfScroll.isHorizontalScrollBarEnabled = false
        shelfScroll.overScrollMode = View.OVER_SCROLL_NEVER
        shelfScroll.clipToPadding = false
        shelfScroll.clipChildren = false
        shelfScroll.setPadding(dpi(64), dpi(8), dpi(64), dpi(8))
        shelfRow = LinearLayout(this)
        shelfRow.orientation = LinearLayout.HORIZONTAL
        shelfRow.clipChildren = false
        shelfScroll.addView(shelfRow, FrameLayout.LayoutParams(WRAP, WRAP))
        shelf.addView(shelfScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        lp.bottomMargin = resources.displayMetrics.heightPixels - dpi(312)
        content.addView(shelf, lp)
    }

    private fun onFocusChanged(nv: View?) {
        val t = nv?.tag as? Tag
        ui.removeCallbacks(shelfRun)
        when (t?.kind) {
            0 -> { setRecentsMode(false); pendingTag = t; ui.postDelayed(shelfRun, d(260)) }
            1 -> { setRecentsMode(true); hideShelf() }
            2 -> { }
            3 -> { setRecentsMode(false); pendingTag = null; hideShelf() }
            else -> { pendingTag = null; hideShelf() }
        }
    }

    private fun showShelfFor(t: Tag) {
        val app = t.app
        if (!store.on(K.RECS) || app == null || overlay != null || idle) { hideShelf(); return }
        if (!Recs.granted(this)) {
            if (!hintShown) {
                hintShown = true
                fillShelf(t, emptyList(), "Turn on recommendations: Settings \u203A Recommendations")
            } else hideShelf()
            return
        }
        val cached = Recs.cached(app.pkg)
        if (cached != null) {
            fillShelf(t, cached, null)
        } else {
            io.execute {
                val l = Recs.fetch(applicationContext, app.pkg)
                ui.post { if (pendingTag === t && !isDestroyed) fillShelf(t, l, null) }
            }
        }
    }

    private fun fillShelf(t: Tag, list: List<Rec>, hint: String?) {
        if (list.isEmpty() && hint == null) { hideShelf(); return }
        shelfTitle.text = hint ?: ("FROM " + t.name.uppercase())
        shelfRow.removeAllViews()
        for (r in list) shelfRow.addView(recCard(r, t))
        shelfScroll.scrollTo(0, 0)
        shelf.animate().cancel()
        shelf.visibility = View.VISIBLE
        shelf.animate().alpha(1f).setDuration(d(260)).start()
    }

    private fun hideShelf() {
        if (shelf.visibility != View.VISIBLE) return
        shelf.animate().cancel()
        shelf.animate().alpha(0f).setDuration(d(160)).withEndAction {
            if (shelf.alpha == 0f) { shelf.visibility = View.INVISIBLE; shelfRow.removeAllViews() }
        }.start()
    }

    private fun recCard(r: Rec, t: Tag): View {
        val w = dpi(150); val h = dpi(84); val rad = dp(16)
        val card = FrameLayout(this)
        val bgd = GradientDrawable()
        bgd.setColor(0xFF1C1C1E.toInt())
        bgd.cornerRadius = rad
        card.background = bgd
        card.outlineProvider = roundOutline(rad)
        card.clipToOutline = true
        card.elevation = dp(2)
        val lp = LinearLayout.LayoutParams(w, h)
        lp.marginEnd = dpi(14)
        card.layoutParams = lp

        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.alpha = 0f
        card.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        val url = r.image
        if (url != null) {
            Img.load(this, url, w) { bmp ->
                if (bmp != null) { img.setImageBitmap(bmp); img.animate().alpha(1f).setDuration(d(250)).start() }
            }
        }
        val shade = View(this)
        shade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xD0000000.toInt()))
        card.addView(shade, FrameLayout.LayoutParams(MATCH, (h * 0.62f).toInt(), Gravity.BOTTOM))
        val title = tv(r.title, 11f, 1f, "sans-serif-medium")
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(dpi(10), 0, dpi(10), dpi(8))
        card.addView(title, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        val ring = View(this)
        val rg = GradientDrawable()
        rg.setColor(Color.TRANSPARENT)
        rg.setStroke(dpi(1), Color.WHITE)
        rg.cornerRadius = rad
        ring.background = rg
        ring.alpha = 0.22f
        card.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))

        card.isFocusable = true
        card.isClickable = true
        card.tag = Tag(2, null, "")
        card.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f)
                .translationZ(if (has) dp(14) else 0f).setDuration(d(220)).setInterpolator(ease).start()
            ring.animate().alpha(if (has) 0.95f else 0.22f).setDuration(d(160)).start()
            if (has) { navSound(); shelfScroll.smoothScrollTo(maxOf(0, v.left - dpi(40)), 0) }
        }
        card.setOnClickListener { v ->
            try {
                val i = Intent.parseUri(r.intentUri ?: "", Intent.URI_INTENT_SCHEME)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val o = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height)
                startActivity(i, o.toBundle())
            } catch (e: Exception) {
                val a = t.app
                if (a != null) launch(a, v)
            }
        }
        return card
    }

    // ---------------------------------------------------------------- app list (cached, never blocks the UI)

    private fun refreshApps(first: Boolean) {
        lastRefresh = System.currentTimeMillis()
        bg.execute {
            val list = try { loadApps() } catch (e: Throwable) { emptyList() }
            ui.post {
                if (!isDestroyed) {
                    val s = list.joinToString("|") { it.pkg }
                    appList = list
                    if (first || s != sig) {
                        sig = s
                        if (overlay == null) { populateTray(first); populateRecents() }
                    }
                }
            }
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val seen = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                if (appCache.containsKey(pkg)) continue // icons are only loaded once per app
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { continue }
                appCache[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), intent, icon)
            }
        }
        appCache.keys.retainAll(seen)
        return appCache.values.sortedBy { it.label.lowercase() }
    }

    // ---------------------------------------------------------------- overlays (glass screens)

    private fun showOverlay(v: View) {
        if (overlay != null) return
        ui.removeCallbacks(idleRun)
        hideShelf()
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        v.alpha = 0f
        v.scaleX = 1.04f
        v.scaleY = 1.04f
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d(300)).setInterpolator(ease).start()
        content.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(d(260)).start()
        overlay = v
    }

    private fun closeOverlay() {
        val v = overlay ?: return
        overlay = null
        v.animate().alpha(0f).scaleX(1.03f).scaleY(1.03f).setDuration(d(220)).withEndAction { root.removeView(v) }.start()
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d(280)).setInterpolator(ease).start()
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        populateTray(false)
        populateRecents()
        resetIdle()
    }

    private fun openSettings() {
        showOverlay(SettingsPanel(this, store, this) { applySettings() }.build())
    }

    // ---------------------------------------------------------------- All Apps

    private fun openAllApps() {
        val p = GlassPanel(this, 0f)
        p.extraDark = 0.34f
        p.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dpi(64), dpi(34), dpi(64), 0)

        val head = FrameLayout(this)
        head.addView(tv("All Apps", 34f, 1f, "sans-serif-light"), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        head.addView(tv(appList.size.toString() + " apps  \u00B7  hold OK to add or remove a favorite", 13f, 0.6f, "sans-serif-medium"),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val panel = GlassPanel(this, 30f)
        val grid = GridLayout(this)
        grid.columnCount = 7
        grid.clipChildren = false
        grid.clipToPadding = false
        for (a in appList) {
            val lp = GridLayout.LayoutParams()
            lp.width = dpi(112); lp.height = dpi(126)
            grid.addView(appCell(a), lp)
        }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.clipChildren = false
        scroll.setPadding(dpi(20), dpi(18), dpi(20), dpi(24))
        scroll.addView(grid)
        panel.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        val plp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        plp.topMargin = dpi(16); plp.bottomMargin = dpi(28)
        col.addView(panel, plp)
        p.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        showOverlay(p)
        grid.getChildAt(0)?.requestFocus()
    }

    private fun appCell(a: AppEntry): View {
        val s = dpi(70)
        val r = s * 0.26f
        val cell = FrameLayout(this)
        cell.clipChildren = false
        cell.isFocusable = true
        cell.isClickable = true

        val glow = View(this)
        val gd = GradientDrawable()
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        gd.setGradientRadius(s * 1.1f)
        gd.setColors(intArrayOf(0x99FFFFFF.toInt(), 0x33FFFFFF, 0x00FFFFFF))
        glow.background = gd
        glow.alpha = 0f
        val gs = (s * 2.2f).toInt()
        val glp = FrameLayout.LayoutParams(gs, gs, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        glp.topMargin = dpi(6) + s / 2 - gs / 2
        cell.addView(glow, glp)

        val box = FrameLayout(this)
        val bgd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2C2C2E.toInt(), 0xFF111112.toInt()))
        bgd.cornerRadius = r
        box.background = bgd
        box.outlineProvider = roundOutline(r)
        box.clipToOutline = true
        box.elevation = dp(2)
        iconFill(box, a)
        val ring = View(this)
        val rg = GradientDrawable()
        rg.setColor(Color.TRANSPARENT)
        rg.setStroke(dpi(1), Color.WHITE)
        rg.cornerRadius = r
        ring.background = rg
        ring.alpha = 0.2f
        box.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        val blp = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        blp.topMargin = dpi(6)
        cell.addView(box, blp)

        // star badge on favorites
        val badge = FrameLayout(this)
        val bg2 = GradientDrawable()
        bg2.shape = GradientDrawable.OVAL
        bg2.setColor(0xCC000000.toInt())
        badge.background = bg2
        badge.addView(GlyphView(this, 12), FrameLayout.LayoutParams(dpi(12), dpi(12), Gravity.CENTER))
        val badgeLp = FrameLayout.LayoutParams(dpi(20), dpi(20), Gravity.TOP or Gravity.END)
        badgeLp.topMargin = dpi(2); badgeLp.rightMargin = dpi(8)
        badge.visibility = if (isFav(a.pkg)) View.VISIBLE else View.INVISIBLE
        cell.addView(badge, badgeLp)

        val lab = tv(a.label, 12f, 0.7f, "sans-serif-medium")
        lab.gravity = Gravity.CENTER
        lab.maxLines = 1
        lab.ellipsize = TextUtils.TruncateAt.END
        lab.setPadding(dpi(4), 0, dpi(4), 0)
        cell.addView(lab, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dpi(8) })

        box.pivotX = s / 2f
        box.pivotY = s / 2f
        cell.setOnFocusChangeListener { _, has ->
            box.animate().scaleX(if (has) 1.2f else 1f).scaleY(if (has) 1.2f else 1f)
                .translationZ(if (has) dp(16) else 0f).setDuration(d(200)).setInterpolator(ease).start()
            glow.animate().alpha(if (has && store.on(K.GLOW)) 0.9f else 0f).setDuration(d(240)).start()
            ring.animate().alpha(if (has) 0.95f else 0.2f).setDuration(d(160)).start()
            lab.animate().alpha(if (has) 1f else 0.7f).translationY(if (has) dp(6) else 0f).setDuration(d(200)).start()
            if (has) navSound()
        }
        cell.setOnClickListener { launch(a, box) }
        cell.setOnLongClickListener {
            val on = toggleFav(a.pkg)
            badge.visibility = if (on) View.VISIBLE else View.INVISIBLE
            Toast.makeText(this, a.label + if (on) " added to favorites" else " removed from favorites", Toast.LENGTH_SHORT).show()
            true
        }
        return cell
    }
}
