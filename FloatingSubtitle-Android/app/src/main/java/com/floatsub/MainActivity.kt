package com.floatsub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("float", MODE_PRIVATE) }
    private lateinit var tvStatus: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPerm: TextView
    private lateinit var btnPlay: Button
    private lateinit var sbPos: SeekBar
    private lateinit var spTracks: Spinner
    private var dragging = false
    private var lastNames: List<String> = emptyList()

    private fun send(action: String, extra: Intent.() -> Unit = {}) {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        i.extra()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun restyle() {
        if (State.running) send(PlayerService.STYLE)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding((16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun label(t: String, size: Float = 14f): TextView {
            val x = TextView(this)
            x.text = t
            x.textSize = size
            root.addView(x)
            return x
        }

        fun button(t: String, f: () -> Unit): Button {
            val x = Button(this)
            x.text = t
            x.isAllCaps = false
            x.setOnClickListener { f() }
            root.addView(x)
            return x
        }

        fun bar(max: Int, value: Int, onChange: (Int) -> Unit, onStop: (Int) -> Unit, track: Boolean): SeekBar {
            val s = SeekBar(this)
            s.max = max
            s.progress = value
            s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    if (track) dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (track) dragging = false
                    onStop(sb?.progress ?: 0)
                }
            })
            root.addView(s)
            return s
        }

        label("Floating Subtitle", 22f)
        button("📂 Pilih audio + subtitle") { pick(false) }
        button("📝 Ganti subtitle") { pick(true) }
        label("Tip: pilih audio dan subtitle-nya sekaligus (tahan lama satu file, lalu tap file lainnya). Yang namanya sama otomatis dipasangkan.", 12f)

        spTracks = Spinner(this)
        spTracks.visibility = View.GONE
        root.addView(spTracks)
        spTracks.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != State.index) send(PlayerService.SELECT) { putExtra("i", pos) }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        tvStatus = label("Belum ada audio")
        sbPos = bar(1, 0, { }, { p -> send(PlayerService.SEEK_TO) { putExtra("ms", p) } }, true)
        tvTime = label("0:00 / 0:00", 12f)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        root.addView(row)
        fun rowButton(t: String, f: () -> Unit): Button {
            val x = Button(this)
            x.text = t
            x.isAllCaps = false
            x.setOnClickListener { f() }
            row.addView(x, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return x
        }
        rowButton("⏪ 5s") { send(PlayerService.SEEK_BY) { putExtra("ms", -5000) } }
        btnPlay = rowButton("▶ Putar") { send(PlayerService.TOGGLE) }
        rowButton("5s ⏩") { send(PlayerService.SEEK_BY) { putExtra("ms", 5000) } }

        label("Tampilan overlay", 16f)
        label("Ukuran teks", 12f)
        bar(36, prefs.getInt("size", 22) - 12, { p -> prefs.edit().putInt("size", p + 12).apply(); restyle() }, { }, false)
        label("Kepekatan latar (0 = transparan penuh)", 12f)
        bar(100, prefs.getInt("alpha", 55), { p -> prefs.edit().putInt("alpha", p).apply(); restyle() }, { }, false)

        val lock = CheckBox(this)
        lock.text = "Kunci overlay (sentuhan tembus ke aplikasi lain)"
        lock.isChecked = prefs.getBoolean("lock", false)
        lock.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("lock", c).apply()
            restyle()
        }
        root.addView(lock)

        tvPerm = label("", 12f)
        button("🔓 Izinkan tampil di atas aplikasi lain") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("⏹ Tutup semua") { if (State.running) send(PlayerService.STOP) }

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        h.post(poll)
    }

    override fun onDestroy() {
        h.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun pick(onlySub: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !onlySub)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 1)
    }

    private fun nameOf(u: Uri): String {
        var n: String? = null
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ix >= 0) n = c.getString(ix)
            }
        }
        return n ?: (u.lastPathSegment ?: "file")
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        } else {
            data.data?.let { u -> uris.add(u) }
        }
        if (uris.isEmpty()) return
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
        }
        val names = ArrayList(uris.map { u -> nameOf(u) })
        val strs = ArrayList(uris.map { u -> u.toString() })
        send(PlayerService.LOAD) {
            putStringArrayListExtra("uris", strs)
            putStringArrayListExtra("names", names)
        }
    }

    // ---------- refresh tampilan tiap 300 ms dari State yang diisi service
    private val poll = object : Runnable {
        override fun run() {
            val sb = StringBuilder(if (State.title.isEmpty()) "Belum ada audio" else "🎵 " + State.title)
            if (State.sub.isNotEmpty()) sb.append("\n💬 ").append(State.sub)
            if (State.error.isNotEmpty()) sb.append("\n⚠ ").append(State.error)
            tvStatus.text = sb.toString()
            btnPlay.text = if (State.playing) "⏸ Jeda" else "▶ Putar"
            if (!dragging) {
                sbPos.max = maxOf(State.dur, 1)
                sbPos.progress = State.pos
            }
            tvTime.text = fmt(State.pos) + " / " + fmt(State.dur)
            tvPerm.text = if (Settings.canDrawOverlays(this@MainActivity)) "✅ Izin tampil di atas aplikasi lain aktif"
            else "⚠ Izin tampil di atas aplikasi lain belum aktif: tekan tombol di bawah ini"
            if (State.names != lastNames) {
                lastNames = State.names
                spTracks.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, State.names)
                spTracks.visibility = if (State.names.size > 1) View.VISIBLE else View.GONE
            }
            if (State.names.size > 1 && spTracks.selectedItemPosition != State.index) spTracks.setSelection(State.index)
            h.postDelayed(this, 300)
        }
    }
}
