package id.nizx.wabotpoc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private var port = 0
    private var secret = ""
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    private var nextLog = 0
    private val logBuf = StringBuilder()

    private lateinit var statusView: TextView
    private lateinit var nativeView: TextView
    private lateinit var codeView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var numberInput: EditText
    private lateinit var cmdInput: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setAllCaps(false)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun weighted(b: Button): Button {
        b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conf = Conf.load(this)
        port = conf.first
        secret = conf.second

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(12))

        val title = TextView(this)
        title.text = "Nizx WaBot PoC"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        statusView = TextView(this)
        statusView.text = "Node: belum dijalankan"
        root.addView(statusView)

        nativeView = TextView(this)
        nativeView.typeface = Typeface.MONOSPACE
        nativeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        nativeView.visibility = View.GONE
        root.addView(nativeView)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(weighted(button("Start Node") { startNode() }))
        row.addView(weighted(button("Stop") {
            stopService(Intent(this, NodeService::class.java))
            statusView.text = "Node: dihentikan"
        }))
        row.addView(weighted(button("Baterai") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }))
        root.addView(row)

        numberInput = EditText(this)
        numberInput.hint = "Nomor WA, contoh 6281234567890"
        numberInput.inputType = InputType.TYPE_CLASS_PHONE
        numberInput.setSingleLine()
        root.addView(numberInput)
        root.addView(button("Minta kode pairing") {
            sendCmd("pair " + numberInput.text.toString().trim(), true)
        })

        codeView = TextView(this)
        codeView.typeface = Typeface.MONOSPACE
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        codeView.gravity = Gravity.CENTER
        codeView.setTextIsSelectable(true)
        root.addView(codeView)

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        logView.setTextIsSelectable(true)
        scroll = ScrollView(this)
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val cmdRow = LinearLayout(this)
        cmdRow.orientation = LinearLayout.HORIZONTAL
        cmdInput = EditText(this)
        cmdInput.hint = "perintah (ketik help)"
        cmdInput.setSingleLine()
        cmdInput.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        cmdRow.addView(cmdInput)
        cmdRow.addView(button("Kirim") {
            val c = cmdInput.text.toString().trim()
            if (c.isNotEmpty()) {
                cmdInput.setText("")
                sendCmd(c, false)
            }
        })
        root.addView(cmdRow)

        setContentView(root)
        poll()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    private fun startNode() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, NodeService::class.java))
        statusView.text = "Node: memulai (pertama kali mengekstrak, tunggu 30-90 detik)"
    }

    private fun http(method: String, path: String, body: String?, readMs: Int): String? {
        return try {
            val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 1500
            c.readTimeout = readMs
            c.setRequestProperty("x-key", secret)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun append(s: String) {
        logBuf.append(s).append('\n')
        if (logBuf.length > 30000) logBuf.delete(0, logBuf.length - 24000)
        logView.text = logBuf.toString()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendCmd(cmd: String, pairing: Boolean) {
        append("> $cmd")
        Thread {
            val body = JSONObject().put("cmd", cmd).toString()
            val res = http("POST", "/cmd", body, 45000)
            ui.post {
                if (res == null) {
                    append("(Node tidak merespons. Tekan Start Node dulu.)")
                } else {
                    try {
                        val out = JSONObject(res).optString("out")
                        if (pairing && Regex("[A-Z0-9]{8}").matches(out)) {
                            codeView.text = out.substring(0, 4) + "-" + out.substring(4)
                        }
                        append(out)
                    } catch (e: Exception) {
                        append(res)
                    }
                }
            }
        }.start()
    }

    private fun poll() {
        Thread {
            while (alive) {
                val st = http("GET", "/status", null, 4000)
                val lg = if (st != null) http("GET", "/logs?after=$nextLog", null, 4000) else null
                ui.post { render(st, lg) }
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    private fun render(st: String?, lg: String?) {
        if (st == null) {
            statusView.text = "Node: tidak merespons"
            val t = tail(File(filesDir, "native.log"), 3000)
            nativeView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            nativeView.text = t
            return
        }
        nativeView.visibility = View.GONE
        try {
            val s = JSONObject(st)
            statusView.text = "WA: ${s.optString("status")} | nomor: ${s.optString("number")} | up ${s.optInt("uptimeMin")} m | rss ${s.optInt("rssMB")} MB\n" +
                "Node ${s.optString("node")} ${s.optString("arch")} | Baileys ${s.optString("baileys")} | pesan ${s.optInt("msgs")}"
            val pc = s.optString("pairingCode")
            if (pc.length == 8) codeView.text = pc.substring(0, 4) + "-" + pc.substring(4)
            else if (s.optString("status") == "open") codeView.text = ""
        } catch (_: Exception) {}
        if (lg != null) {
            try {
                val j = JSONObject(lg)
                val arr = j.getJSONArray("lines")
                if (arr.length() > 0) {
                    for (i in 0 until arr.length()) logBuf.append(arr.getString(i)).append('\n')
                    if (logBuf.length > 30000) logBuf.delete(0, logBuf.length - 24000)
                    logView.text = logBuf.toString()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                nextLog = j.optInt("next", nextLog)
            } catch (_: Exception) {}
        }
    }

    private fun tail(f: File, max: Int): String {
        if (!f.exists()) return ""
        return try {
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val start = if (len > max) len - max else 0L
                r.seek(start)
                val b = ByteArray((len - start).toInt())
                r.readFully(b)
                String(b)
            }
        } catch (e: Exception) {
            ""
        }
    }
}
