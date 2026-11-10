package com.floatsub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** Status yang dibaca layar utama (satu proses, jadi cukup objek biasa). */
object State {
    @Volatile var title = ""
    @Volatile var sub = ""
    @Volatile var error = ""
    @Volatile var playing = false
    @Volatile var pos = 0
    @Volatile var dur = 0
    @Volatile var index = 0
    @Volatile var names: List<String> = emptyList()
    @Volatile var running = false
}

class PlayerService : Service() {

    companion object {
        const val LOAD = "com.floatsub.LOAD"
        const val TOGGLE = "com.floatsub.TOGGLE"
        const val SEEK_BY = "com.floatsub.SEEK_BY"
        const val SEEK_TO = "com.floatsub.SEEK_TO"
        const val SELECT = "com.floatsub.SELECT"
        const val STYLE = "com.floatsub.STYLE"
        const val STOP = "com.floatsub.STOP"
        private const val CH = "floatsub"
        private const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private class Item(val audio: Uri, val name: String, val sub: Uri?, val subName: String, val auto: Boolean)

    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("float", MODE_PRIVATE) }
    private var mp: MediaPlayer? = null
    private var items: List<Item> = emptyList()
    private var idx = 0
    private var cues: List<Cue> = emptyList()
    private var wm: WindowManager? = null
    private var tv: TextView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var lastText = "\u0000"
    private var hintText = ""
    private var hintUntil = 0L
    private var lastPlaying = false
    private var warned = false
    private var dragX = 0f
    private var dragY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Pemutar", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notif())
        }
        State.running = true
        hintText = "Overlay siap. Geser teks ini buat pindahin."
        hintUntil = System.currentTimeMillis() + 6000
        h.post(tick)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) return START_NOT_STICKY
        when (i.action) {
            LOAD -> load(i)
            TOGGLE -> toggle()
            SEEK_BY -> mp?.let { p -> p.seekTo((p.currentPosition + i.getIntExtra("ms", 0)).coerceIn(0, p.duration)) }
            SEEK_TO -> mp?.seekTo(i.getIntExtra("ms", 0))
            SELECT -> {
                val n = i.getIntExtra("i", 0)
                if (n in items.indices) loadItem(n)
            }
            STYLE -> applyStyle()
            STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        mp?.release()
        mp = null
        removeOverlay()
        State.running = false
        super.onDestroy()
    }

    // ---------- muat file
    private fun load(i: Intent) {
        val uris = i.getStringArrayListExtra("uris") ?: return
        val names = i.getStringArrayListExtra("names") ?: return
        State.error = ""
        val isSub = Regex("""\.(srt|lrc|vtt|txt)$""", RegexOption.IGNORE_CASE)
        fun base(n: String) = n.substringBeforeLast('.').lowercase()
        val subs = names.indices.filter { k -> isSub.containsMatchIn(names[k]) }
        val auds = names.indices.filter { k -> !isSub.containsMatchIn(names[k]) }
        if (auds.isEmpty()) {                       // yang dipilih cuma subtitle: pasang ke lagu yang lagi aktif
            if (subs.isNotEmpty()) applySub(Uri.parse(uris[subs[0]]), names[subs[0]], false)
            return
        }
        items = auds.map { a ->
            val same = subs.firstOrNull { s -> base(names[s]) == base(names[a]) }
            val s = same ?: (if (auds.size == 1 && subs.size == 1) subs[0] else null)
            Item(Uri.parse(uris[a]), names[a], s?.let { x -> Uri.parse(uris[x]) }, s?.let { x -> names[x] } ?: "", same != null)
        }
        State.names = items.map { x -> (if (x.sub != null) "✓ " else "• ") + x.name }
        loadItem(0)
    }

    private fun applySub(u: Uri, name: String, auto: Boolean) {
        cues = try {
            val bytes = contentResolver.openInputStream(u)!!.use { s -> s.readBytes() }
            Sub.parse(Sub.decode(bytes), name)
        } catch (e: Exception) {
            fail("Gagal baca subtitle: ${e.message}")
            emptyList()
        }
        State.sub = if (cues.isEmpty()) "gak ada baris subtitle yang terbaca" else
            "$name (${cues.size} baris)" + (if (auto) ", otomatis karena nama sama" else "")
        lastText = "\u0000"
    }

    private fun loadItem(n: Int) {
        idx = n
        val cur = items[n]
        mp?.release()
        mp = null
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(applicationContext, cur.audio)
            p.setOnCompletionListener {
                if (idx + 1 < items.size) {
                    loadItem(idx + 1)
                    mp?.start()
                }
            }
            p.prepare()
            mp = p
        } catch (e: Exception) {
            fail("Gagal memuat audio: ${e.message}")
            return
        }
        State.title = cur.name.substringBeforeLast('.')
        State.index = n
        State.dur = mp?.duration ?: 0
        if (cur.sub != null) {
            applySub(cur.sub, cur.subName, cur.auto)
        } else {
            cues = emptyList()
            State.sub = "gak ada subtitle bernama sama: pilih manual lewat tombol Ganti subtitle"
            lastText = "\u0000"
        }
        refreshNotif()
    }

    private fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) p.pause() else p.start()
        State.playing = p.isPlaying
        refreshNotif()
    }

    private fun fail(msg: String) {
        State.error = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- overlay
    private val drag = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX
                dragY = e.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x += (e.rawX - dragX).toInt()
                lp.y -= (e.rawY - dragY).toInt()
                dragX = e.rawX
                dragY = e.rawY
                tv?.let { v -> wm?.updateViewLayout(v, lp) }
                true
            }
            MotionEvent.ACTION_UP -> {
                prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                true
            }
            else -> false
        }
    }

    private fun ensureOverlay(): Boolean {
        if (tv != null) return true
        if (!Settings.canDrawOverlays(this)) {
            if (!warned) {
                warned = true
                fail("Izin tampil di atas aplikasi lain belum aktif. Buka layar utama lalu tekan tombol izin.")
            }
            return false
        }
        val d = resources.displayMetrics.density
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = TextView(this)
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        v.setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
        v.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        v.maxWidth = (resources.displayMetrics.widthPixels * 0.92).toInt()
        v.visibility = View.GONE
        v.setOnTouchListener(drag)
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = prefs.getInt("x", 0)
        lp.y = prefs.getInt("y", (96 * d).toInt())
        w.addView(v, lp)
        wm = w
        tv = v
        applyStyle()
        return true
    }

    private fun applyStyle() {
        val v = tv ?: return
        val d = resources.displayMetrics.density
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getInt("size", 22).toFloat())
        v.typeface = Typeface.DEFAULT_BOLD
        val bg = GradientDrawable()
        bg.cornerRadius = 14 * d
        bg.setColor(Color.argb(prefs.getInt("alpha", 55) * 255 / 100, 0, 0, 0))
        v.background = bg
        lp.flags = FLAGS or (if (prefs.getBoolean("lock", false)) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        wm?.updateViewLayout(v, lp)
    }

    private fun removeOverlay() {
        val v = tv
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
        }
        tv = null
    }

    private fun showText(t: String) {
        if (t.isNotEmpty() && !ensureOverlay()) return
        val v = tv ?: return
        v.text = t
        v.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- notifikasi
    private fun notif(): Notification {
        fun pi(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlayerService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (State.title.isEmpty()) "Floating Subtitle" else State.title)
            .setContentText(if (State.playing) "Memutar" else "Dijeda")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                if (State.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (State.playing) "Jeda" else "Putar", pi(TOGGLE, 1)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup", pi(STOP, 2))
            .build()
    }

    private fun refreshNotif() {
        getSystemService(NotificationManager::class.java).notify(1, notif())
    }

    private fun shutdown() {
        h.removeCallbacks(tick)
        mp?.release()
        mp = null
        removeOverlay()
        State.running = false
        State.playing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- loop: cocokin posisi audio dengan baris subtitle
    private val tick = object : Runnable {
        override fun run() {
            val p = mp
            var text = ""
            if (p != null) {
                val pos = p.currentPosition
                State.pos = pos
                State.dur = p.duration
                State.playing = p.isPlaying
                var lo = 0
                var hi = cues.size - 1
                var i = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (cues[mid].s <= pos) {
                        i = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                var j = i
                while (j >= 0 && j >= i - 3) {
                    if (pos < cues[j].e) {
                        text = cues[j].t
                        break
                    }
                    j--
                }
            }
            if (text.isEmpty() && System.currentTimeMillis() < hintUntil) text = hintText
            if (text != lastText) {
                lastText = text
                showText(text)
            }
            if (State.playing != lastPlaying) {
                lastPlaying = State.playing
                refreshNotif()
            }
            h.postDelayed(this, 200)
        }
    }
}
