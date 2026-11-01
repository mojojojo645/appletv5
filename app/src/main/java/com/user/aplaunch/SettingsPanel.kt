package com.user.aplaunch

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

interface SettingsHost {
    val apps: List<AppEntry>
    fun isFav(pkg: String): Boolean
    fun toggleFav(pkg: String): Boolean
    fun resetFavs()
    fun clearRecent()
}

/**
 * tvOS / visionOS style settings: a glass sidebar with icons, a glass pane with grouped rows,
 * switches, segmented controls and a volume slider. OK changes a value, Left goes back to the sidebar.
 */
class SettingsPanel(
    private val act: Activity,
    private val s: Store,
    private val host: SettingsHost,
    private val onChange: () -> Unit
) {
    private val ctx: Context = act

    private sealed class It
    private class Ch(val key: String, val title: String, val sub: String, val opts: List<String>) : It()
    private class Ac(val title: String, val sub: String, val icon: Drawable? = null, val run: (TextView) -> Unit) : It()
    private class Info(val title: String, val sub: String, val value: String) : It()
    private class Head(val text: String) : It()
    private class Fav(val app: AppEntry) : It()
    private class Vol : It()
    private class Cat(val name: String, val glyph: Int, val items: () -> List<It>)

    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val cats = ArrayList<Cat>()
    private val side = ArrayList<View>()
    private val sideText = ArrayList<TextView>()
    private val sideGlyph = ArrayList<GlyphView>()
    private val sideCircle = ArrayList<GradientDrawable>()
    private lateinit var pane: LinearLayout
    private lateinit var paneTitle: TextView
    private lateinit var paneScroll: ScrollView
    private var cur = 0
    private var armed = false

    private fun d(ms: Long): Long = if (s.i(K.ANIM) == 2) 0L else (ms * (if (s.i(K.ANIM) == 1) 0.6 else 1.0)).toLong()

    private fun icon(a: AppEntry): Drawable = a.icon.constantState?.newDrawable(ctx.resources)?.mutate() ?: a.icon

    private fun sys(action: String, title: String) = Ac(title, "Opens the system page") {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- pages built from real system data

    private fun btKind(c: BluetoothClass?): String = when (c?.majorDeviceClass) {
        0x0400 -> "Audio"
        0x0500 -> "Remote or keyboard"
        0x0200 -> "Phone"
        else -> "Device"
    }

    private fun btItems(): List<It> {
        val out = ArrayList<It>()
        try {
            val ad = BluetoothAdapter.getDefaultAdapter()
            if (ad == null) {
                out.add(Info("Bluetooth", "Not available on this TV", ""))
            } else {
                out.add(Info("Bluetooth", "Remotes, keyboards, headphones", if (ad.isEnabled) "On" else "Off"))
                val needs = Build.VERSION.SDK_INT >= 31 &&
                    ctx.checkCallingOrSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                if (needs) {
                    out.add(Ac("Show paired devices", "Allow Bluetooth access first") {
                        if (Build.VERSION.SDK_INT >= 23) act.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 11)
                    })
                } else {
                    val bonded = ad.bondedDevices
                    if (bonded == null || bonded.isEmpty()) {
                        out.add(Info("No paired devices", "", ""))
                    } else {
                        for (dv in bonded.sortedBy { it.name ?: "" }) {
                            out.add(Info(dv.name ?: dv.address, btKind(dv.bluetoothClass), "Paired"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out.add(Info("Bluetooth unavailable", "", ""))
        }
        out.add(sys(Settings.ACTION_BLUETOOTH_SETTINGS, "Pair a new device"))
        return out
    }

    @Suppress("DEPRECATION")
    private fun netItems(): List<It> {
        val out = ArrayList<It>()
        var status = "Not connected"
        var ip = ""
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            if (ni != null && ni.isConnected) {
                status = when (ni.type) {
                    ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
                    ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                    else -> "Connected"
                }
            }
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val n = wm.connectionInfo?.ipAddress ?: 0
            if (n != 0) ip = "${n and 255}.${(n shr 8) and 255}.${(n shr 16) and 255}.${(n shr 24) and 255}"
        } catch (e: Exception) { }
        out.add(Info("Connection", "", status))
        if (ip.isNotEmpty()) out.add(Info("IP address", "Use it for adb connect", ip))
        out.add(sys(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings"))
        return out
    }

    private fun gb(bytes: Long): String = String.format("%.1f GB", bytes / 1073741824.0)

    private fun sysInfo(): List<It> {
        val out = ArrayList<It>()
        out.add(Info("Model", "", (Build.MANUFACTURER + " " + Build.MODEL).trim()))
        out.add(Info("Android", "", "Android " + Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")"))
        try {
            val m = ctx.resources.displayMetrics
            out.add(Info("Display", "", "${m.widthPixels} \u00D7 ${m.heightPixels}  \u00B7  ${m.densityDpi} dpi"))
        } catch (e: Exception) { }
        try {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            out.add(Info("Memory", "Free right now: " + gb(mi.availMem), gb(mi.totalMem)))
        } catch (e: Exception) { }
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            out.add(Info("Storage", "Free: " + gb(st.availableBytes), gb(st.totalBytes)))
        } catch (e: Exception) { }
        out.add(Info("Build", "", Build.DISPLAY ?: ""))
        out.add(Head("More system settings"))
        out.add(sys(Settings.ACTION_DATE_SETTINGS, "Date & time"))
        out.add(sys(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage"))
        out.add(sys(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility"))
        out.add(sys(Settings.ACTION_INPUT_METHOD_SETTINGS, "Keyboard"))
        out.add(sys(Settings.ACTION_LOCALE_SETTINGS, "Language"))
        out.add(sys(Settings.ACTION_SETTINGS, "All Android settings"))
        return out
    }

    private fun recItems(): List<It> {
        val granted = Recs.granted(ctx)
        val out = ArrayList<It>()
        out.add(Ch(K.RECS, "Show recommendations", "Hover an app to see what it suggests", listOf("Off", "On")))
        out.add(Info("Access", "Lets the launcher read what apps publish to the TV home screen", if (granted) "Granted" else "Not granted"))
        if (!granted) {
            out.add(Ac("Allow access", "Opens the permission prompt") {
                if (Build.VERSION.SDK_INT >= 23) act.requestPermissions(arrayOf(Recs.PERM), 12)
            })
            out.add(Info("No prompt? Run this on your PC", "adb shell pm grant com.user.aplaunch " + Recs.PERM, ""))
        }
        out.add(Info("Works with", "YouTube, Netflix, Disney+, Prime Video and others that publish to the TV home screen", ""))
        return out
    }

    init {
        val onOff = listOf("Off", "On")
        cats.add(Cat("Display & Appearance", 13) {
            listOf(
                Head("Clock"),
                Ch(K.CLOCK_LOOK, "Look", "Glass fades into the mist, Split outlines the minutes", listOf("Glass", "Split", "Thin", "Bold")),
                Ch(K.CLOCK_SIZE, "Size", "", listOf("Small", "Medium", "Large")),
                Ch(K.CLOCK_MODE, "Depth", "Behind lets the peak overlap the digits", listOf("Behind", "In front", "Hidden")),
                Ch(K.CLOCK_FMT, "Time format", "", listOf("Follow TV", "12-hour", "24-hour")),
                Ch(K.CLOCK_DATE, "Show date", "", onOff),
                Head("Interface"),
                Ch(K.GLASS, "Glass style", "How strongly panels frost the wallpaper", listOf("Clear", "Frosted", "Dense")),
                Ch(K.GREETING, "Greeting", "\u201CGood evening\u201D on the home screen", onOff),
                sys(Settings.ACTION_DISPLAY_SETTINGS, "TV display & picture")
            )
        })
        cats.add(Cat("Wallpaper & Background", 1) {
            listOf(
                Info("Scene", "One painted alpine peak, drawn on the TV and cached", "Midnight Peak"),
                Ch(K.MOTION, "Slow motion", "Gentle parallax between sky and mountain", onOff),
                Ch(K.WALL_BRIGHT, "Brightness", "", listOf("Bright", "Normal", "Dim"))
            )
        })
        cats.add(Cat("Sound", 10) {
            listOf(
                Vol(),
                Ch(K.NAV_SOUND, "Navigation sounds", "Soft click when the focus moves", onOff),
                sys(Settings.ACTION_SOUND_SETTINGS, "TV sound settings")
            )
        })
        cats.add(Cat("Home Screen", 4) {
            listOf(
                Ch(K.RECENT_ON, "Recently used", "Row of apps you opened from this launcher", onOff),
                Ch(K.ALL_APPS_TILE, "All Apps icon", "Last icon in your favorites", onOff),
                Ac("Reset favorites", "Back to the default apps. Press OK, then OK again") { sub ->
                    if (!armed) { armed = true; sub.text = "Press OK again to reset your favorites" }
                    else { armed = false; host.resetFavs(); sub.text = "Done"; onChange() }
                },
                Ac("Clear recently used", "Empties the Recently used row") { sub -> host.clearRecent(); sub.text = "Cleared"; onChange() }
            )
        })
        cats.add(Cat("Favorites", 12) {
            val l = ArrayList<It>()
            l.add(Info("Choose the apps on your home screen", "You can also hold OK on any app to add or remove it", ""))
            for (a in host.apps) l.add(Fav(a))
            l
        })
        cats.add(Cat("App Management", 11) {
            val l = ArrayList<It>()
            l.add(Info("All installed apps", "Select one for storage, permissions and notifications", host.apps.size.toString()))
            for (a in host.apps) {
                l.add(Ac(a.label, "App info", icon(a)) {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Not available on this TV", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            l
        })
        cats.add(Cat("Animations", 2) {
            listOf(
                Ch(K.ANIM, "Animation speed", "", listOf("Normal", "Fast", "Off")),
                Ch(K.GLOW, "Focus glow", "White halo behind the selected icon", onOff),
                Ch(K.GLOW_STR, "Glow strength", "", listOf("Soft", "Normal", "Intense")),
                Ch(K.SHEEN, "Shine sweep", "Light streak across the selected icon", onOff),
                Ch(K.PERF, "Performance mode", "Lighter effects, smaller wallpaper. Restart the launcher to resize", onOff)
            )
        })
        cats.add(Cat("Always-on Display", 3) {
            listOf(
                Ch(K.AOD, "Always-on display", "Dims the wallpaper and keeps only the clock when idle", onOff),
                Ch(K.AOD_DELAY, "Start after", "", listOf("1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes")),
                Ch(K.AOD_DIM, "Dimming", "", listOf("Light", "Medium", "Dark")),
                Ch(K.KEEP_ON, "Keep display on", "Stops the TV sleeping or starting the screensaver on Home", onOff)
            )
        })
        cats.add(Cat("Recommendations", 5) { recItems() })
        cats.add(Cat("Connections", 7) {
            val l = ArrayList<It>()
            l.add(Head("Network"))
            l.addAll(netItems())
            l.add(Head("Bluetooth"))
            l.addAll(btItems())
            l
        })
        cats.add(Cat("System Information", 8) { sysInfo() })
        cats.add(Cat("About", 9) {
            val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
            listOf(
                Info("Launcher", "Glass home screen for Android TV", "Home $ver"),
                sys(Settings.ACTION_HOME_SETTINGS, "Default home app"),
                Ac("Reset launcher settings", "Press OK, then OK again to confirm") { sub ->
                    if (!armed) {
                        armed = true
                        sub.text = "Press OK again to reset everything"
                    } else {
                        armed = false
                        s.clear()
                        onChange()
                        show(cur)
                    }
                }
            )
        })
    }

    // ---------------------------------------------------------------- layout

    fun build(): FrameLayout {
        val root = GlassPanel(ctx, 0f)
        root.extraDark = 0.34f
        root.isClickable = true

        val h = LinearLayout(ctx)
        h.orientation = LinearLayout.HORIZONTAL
        h.setPadding(ctx.dpi(40), ctx.dpi(32), ctx.dpi(40), ctx.dpi(32))
        root.addView(h, FrameLayout.LayoutParams(-1, -1))

        // sidebar glass
        val sidePanel = GlassPanel(ctx, 30f)
        val left = LinearLayout(ctx)
        left.orientation = LinearLayout.VERTICAL
        left.setPadding(ctx.dpi(14), ctx.dpi(22), ctx.dpi(14), ctx.dpi(14))
        left.addView(ctx.tv("Settings", 30f, 1f, "sans-serif-light").apply { setPadding(ctx.dpi(12), 0, 0, ctx.dpi(12)) })
        cats.forEachIndexed { i, cat ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dpi(10), ctx.dpi(7), ctx.dpi(12), ctx.dpi(7))
            val g = GradientDrawable()
            g.cornerRadius = ctx.dp(14)
            g.setColor(Color.TRANSPARENT)
            row.background = g

            val circle = FrameLayout(ctx)
            val cg = GradientDrawable()
            cg.shape = GradientDrawable.OVAL
            cg.setColor(0x2EFFFFFF)
            circle.background = cg
            val gv = GlyphView(ctx, cat.glyph)
            circle.addView(gv, FrameLayout.LayoutParams(ctx.dpi(18), ctx.dpi(18), Gravity.CENTER))
            row.addView(circle, LinearLayout.LayoutParams(ctx.dpi(30), ctx.dpi(30)))
            val t = ctx.tv(cat.name, 15.5f, 0.65f, "sans-serif-medium")
            t.setPadding(ctx.dpi(12), 0, 0, 0)
            row.addView(t)

            row.isFocusable = true
            row.isClickable = true
            sideText.add(t); sideGlyph.add(gv); sideCircle.add(cg)
            row.setOnFocusChangeListener { v, has ->
                if (has) { cur = i; show(i) }
                g.setColor(if (has) 0x3DFFFFFF else Color.TRANSPARENT)
                v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(d(160)).start()
            }
            row.setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    for (k in 0 until pane.childCount) {
                        val v = pane.getChildAt(k)
                        if (v.isFocusable) { v.requestFocus(); break }
                    }
                    true
                } else false
            }
            side.add(row)
            left.addView(row, LinearLayout.LayoutParams(ctx.dpi(268), -2))
        }
        val lsv = ScrollView(ctx)
        lsv.isVerticalScrollBarEnabled = false
        lsv.clipToPadding = false
        lsv.addView(left)
        sidePanel.addView(lsv, FrameLayout.LayoutParams(-1, -1))
        h.addView(sidePanel, LinearLayout.LayoutParams(ctx.dpi(300), -1))
        h.addView(View(ctx), LinearLayout.LayoutParams(ctx.dpi(18), 1))

        // content glass
        val panePanel = GlassPanel(ctx, 30f)
        paneScroll = ScrollView(ctx)
        paneScroll.isVerticalScrollBarEnabled = false
        paneScroll.clipToPadding = false
        paneScroll.setPadding(ctx.dpi(24), ctx.dpi(22), ctx.dpi(24), ctx.dpi(24))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        paneTitle = ctx.tv("", 28f, 1f, "sans-serif-light")
        paneTitle.setPadding(ctx.dpi(6), 0, 0, ctx.dpi(14))
        col.addView(paneTitle)
        pane = LinearLayout(ctx)
        pane.orientation = LinearLayout.VERTICAL
        col.addView(pane, LinearLayout.LayoutParams(-1, -2))
        paneScroll.addView(col)
        panePanel.addView(paneScroll, FrameLayout.LayoutParams(-1, -1))
        h.addView(panePanel, LinearLayout.LayoutParams(0, -1, 1f))

        show(0)
        root.post { side[0].requestFocus() }
        return root
    }

    private fun show(i: Int) {
        val cat = cats[i]
        armed = false
        paneTitle.text = cat.name
        for (j in sideText.indices) {
            val on = j == i
            sideText[j].alpha = if (on) 1f else 0.65f
            sideCircle[j].setColor(if (on) 0xF2FFFFFF.toInt() else 0x2EFFFFFF)
            sideGlyph[j].tint = if (on) 0xFF0A0A0A.toInt() else Color.WHITE
        }
        pane.removeAllViews()
        for (item in cat.items()) pane.addView(rowView(item))
        paneScroll.scrollTo(0, 0)
    }

    private fun rowView(item: It): View {
        if (item is Head) {
            val t = ctx.tv(item.text.uppercase(), 11.5f, 0.55f, "sans-serif-medium")
            t.letterSpacing = 0.16f
            t.setPadding(ctx.dpi(8), ctx.dpi(14), 0, ctx.dpi(8))
            return t
        }
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val g = GradientDrawable()
        g.cornerRadius = ctx.dp(16)
        g.setColor(0x14FFFFFF)
        row.background = g
        row.isFocusable = !(item is Info && item.title.isEmpty())
        row.isClickable = true
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = ctx.dpi(8)
        row.layoutParams = lp
        row.setPadding(ctx.dpi(18), ctx.dpi(12), ctx.dpi(18), ctx.dpi(12))

        var ic: Drawable? = null
        val title: String
        val sub: String
        when (item) {
            is Ch -> { title = item.title; sub = item.sub }
            is Ac -> { title = item.title; sub = item.sub; ic = item.icon }
            is Info -> { title = item.title; sub = item.sub }
            is Fav -> { title = item.app.label; sub = ""; ic = icon(item.app) }
            is Vol -> { title = "Volume"; sub = "Left and right to adjust. Works when the TV allows app volume control" }
            is Head -> { title = ""; sub = "" }
        }
        if (ic != null) {
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageDrawable(ic)
            row.addView(iv, LinearLayout.LayoutParams(ctx.dpi(34), ctx.dpi(34)).apply { rightMargin = ctx.dpi(14) })
        }
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(ctx.tv(title, 16.5f, 1f, "sans-serif-medium"))
        val subTv = ctx.tv(sub, 12f, 0.55f, "sans-serif")
        if (sub.isNotEmpty()) texts.addView(subTv)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        var sw: SwitchView? = null
        var seg: SegmentView? = null
        var slider: SliderView? = null
        val value = ctx.tv("", 15f, 0.9f, "sans-serif-medium")
        val ctrl = LinearLayout.LayoutParams(-2, -2)
        ctrl.leftMargin = ctx.dpi(16)
        when (item) {
            is Ch -> {
                val c0 = s.i(item.key).coerceIn(0, item.opts.size - 1)
                if (item.opts.size == 2 && item.opts[0] == "Off") {
                    val v = SwitchView(ctx); v.set(c0 == 1, false); sw = v
                    row.addView(v, LinearLayout.LayoutParams(ctx.dpi(50), ctx.dpi(30)).apply { leftMargin = ctx.dpi(16) })
                } else if (item.opts.size in 2..4) {
                    val v = SegmentView(ctx, item.opts); v.sel = c0; seg = v
                    row.addView(v, ctrl)
                } else {
                    value.text = item.opts[c0] + "  \u203A"
                    row.addView(value, ctrl)
                }
            }
            is Ac -> { value.text = "\u203A"; value.textSize = 22f; row.addView(value, ctrl) }
            is Info -> if (item.value.isNotEmpty()) { value.text = item.value; row.addView(value, ctrl) }
            is Fav -> {
                val v = SwitchView(ctx); v.set(host.isFav(item.app.pkg), false); sw = v
                row.addView(v, LinearLayout.LayoutParams(ctx.dpi(50), ctx.dpi(30)).apply { leftMargin = ctx.dpi(16) })
            }
            is Vol -> {
                val v = SliderView(ctx)
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    v.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                } catch (e: Exception) { }
                slider = v
                row.addView(v, ctrl)
            }
            is Head -> { }
        }

        val swf = sw
        val segf = seg
        val slf = slider
        row.setOnFocusChangeListener { v, has ->
            g.setColor(if (has) 0x3DFFFFFF else 0x14FFFFFF)
            v.animate().scaleX(if (has) 1.012f else 1f).scaleY(if (has) 1.012f else 1f)
                .setDuration(d(160)).setInterpolator(ease).start()
        }
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (slf != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val mx = maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT && now == 0) { side[cur].requestFocus(); return@setOnKeyListener true }
                    val nv = (now + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, mx)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                    slf.progress = nv.toFloat() / mx
                } catch (e: Exception) { }
                return@setOnKeyListener true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { side[cur].requestFocus(); true } else false
        }
        row.setOnClickListener {
            when (item) {
                is Ch -> {
                    val next = (s.i(item.key) + 1) % item.opts.size
                    s.put(item.key, next)
                    if (swf != null) swf.set(next == 1, true)
                    else if (segf != null) segf.sel = next
                    else value.text = item.opts[next] + "  \u203A"
                    onChange()
                }
                is Ac -> item.run(subTv)
                is Fav -> { val on = host.toggleFav(item.app.pkg); swf?.set(on, true); onChange() }
                is Info, is Vol, is Head -> { }
            }
        }
        return row
    }
}
