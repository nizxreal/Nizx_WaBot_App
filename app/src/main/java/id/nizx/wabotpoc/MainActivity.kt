package id.nizx.wabotpoc

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.Toast
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

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout

    private lateinit var statusView: TextView
    private lateinit var nativeView: TextView
    private lateinit var codeView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var numberInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var tokenStatusView: TextView
    private lateinit var cmdInput: EditText

    private var clickCount = 0
    private var lastClickTime = 0L

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

        prefs = getSharedPreferences("nizx_prefs", Context.MODE_PRIVATE)

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(12))
        setContentView(root)

        val email = prefs.getString("user_email", null)
        if (email.isNullOrEmpty()) {
            renderLogin()
        } else {
            renderDashboard(email)
        }
    }

    private fun renderLogin() {
        root.removeAllViews()

        val title = TextView(this)
        title.text = "Nizx WaBot"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(24), 0, dp(12))
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Masuk menggunakan Akun Google untuk mengakses bot WhatsApp."
        sub.gravity = Gravity.CENTER
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sub.setPadding(dp(8), 0, dp(8), dp(24))
        root.addView(sub)

        val btnGoogle = button("Masuk dengan Akun Google") {
            pickGoogleAccount()
        }
        btnGoogle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        root.addView(btnGoogle)

        // Akses Admin Tersembunyi (hanya terlihat jika ditekan lama atau lewat dialog tersembunyi)
        title.setOnLongClickListener {
            showAdminAuthDialog()
            true
        }

        val footer = TextView(this)
        footer.text = "\n\nNizx System v0.1"
        footer.gravity = Gravity.CENTER
        footer.setTextColor(Color.GRAY)
        footer.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime < 500) {
                clickCount++
                if (clickCount >= 5) {
                    clickCount = 0
                    showAdminAuthDialog()
                }
            } else {
                clickCount = 1
            }
            lastClickTime = now
        }
        root.addView(footer)
    }

    private fun pickGoogleAccount() {
        try {
            val intent = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), null, null, null, null
            )
            startActivityForResult(intent, 2001)
        } catch (_: Exception) {
            val input = EditText(this)
            input.hint = "nama@gmail.com"
            AlertDialog.Builder(this)
                .setTitle("Input Akun Google")
                .setView(input)
                .setPositiveButton("Lanjut") { _, _ ->
                    val em = input.text.toString().trim()
                    if (em.contains("@")) {
                        onLoginSuccess(em, false)
                    } else {
                        Toast.makeText(this, "Email tidak valid", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val acct = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!acct.isNullOrEmpty()) {
                onLoginSuccess(acct, false)
            }
        }
    }

    private fun showAdminAuthDialog() {
        val input = EditText(this)
        input.hint = "Password / Admin Key"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Khusus Admin Owner")
            .setMessage("Masukkan kunci rahasia Admin:")
            .setView(input)
            .setPositiveButton("Masuk") { _, _ ->
                val key = input.text.toString().trim()
                if (key == "nizxadmin" || key == "nizxadmin2026" || key.length >= 8) {
                    onLoginSuccess("admin@nizx.owner", true)
                } else {
                    Toast.makeText(this, "Kunci salah!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun onLoginSuccess(email: String, isAdmin: Boolean) {
        prefs.edit()
            .putString("user_email", email)
            .putBoolean("is_admin", isAdmin)
            .apply()
        Toast.makeText(this, if (isAdmin) "Selamat datang, Admin!" else "Login berhasil!", Toast.LENGTH_SHORT).show()
        renderDashboard(email)
    }

    private fun renderDashboard(email: String) {
        root.removeAllViews()

        val isAdmin = prefs.getBoolean("is_admin", false) || email.contains("admin")

        // Top bar
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        val title = TextView(this)
        title.text = if (isAdmin) "Nizx WaBot (ADMIN)" else "Nizx WaBot"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        title.setTypeface(null, Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)

        if (isAdmin) {
            val btnAdminMenu = button("Kelola Token") {
                showAdminTokenManager()
            }
            btnAdminMenu.setBackgroundColor(Color.parseColor("#444444"))
            header.addView(btnAdminMenu)
        }

        val btnLogout = button("Keluar") {
            prefs.edit().clear().apply()
            stopService(Intent(this, NodeService::class.java))
            renderLogin()
        }
        header.addView(btnLogout)
        root.addView(header)

        val userLabel = TextView(this)
        userLabel.text = "User: $email | Status: " + (if (isAdmin) "Super Admin (Owner)" else "Member")
        userLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        userLabel.setTextColor(if (isAdmin) Color.MAGENTA else Color.DKGRAY)
        root.addView(userLabel)

        // Bagian Token (Wajib / Cek Token)
        val tokenBox = LinearLayout(this)
        tokenBox.orientation = LinearLayout.VERTICAL
        tokenBox.setPadding(0, dp(6), 0, dp(6))

        tokenStatusView = TextView(this)
        val savedTok = prefs.getString("user_token", "") ?: ""
        tokenStatusView.text = if (savedTok.isNotEmpty()) "Token Aktif: $savedTok" else "Status Token: Belum diisi"
        tokenStatusView.setTextColor(if (savedTok.isNotEmpty()) Color.parseColor("#008800") else Color.RED)
        tokenBox.addView(tokenStatusView)

        val tokenRow = LinearLayout(this)
        tokenRow.orientation = LinearLayout.HORIZONTAL
        tokenInput = EditText(this)
        tokenInput.hint = "Input Token Lisensi dari Admin"
        tokenInput.setText(savedTok)
        tokenInput.setSingleLine()
        tokenInput.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tokenRow.addView(tokenInput)

        val btnApplyToken = button("Verifikasi") {
            val t = tokenInput.text.toString().trim()
            if (t.isNotEmpty()) {
                prefs.edit().putString("user_token", t).apply()
                tokenStatusView.text = "Token Aktif: $t"
                tokenStatusView.setTextColor(Color.parseColor("#008800"))
                Toast.makeText(this, "Token disimpan!", Toast.LENGTH_SHORT).show()
                sendCmd("token $t", false)
            } else {
                Toast.makeText(this, "Token tidak boleh kosong", Toast.LENGTH_SHORT).show()
            }
        }
        tokenRow.addView(btnApplyToken)
        tokenBox.addView(tokenRow)
        root.addView(tokenBox)

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
            val num = numberInput.text.toString().trim()
            if (num.isNotEmpty()) {
                codeView.text = "Meminta kode..."
                sendCmd("pair $num", true)
            } else {
                Toast.makeText(this, "Masukkan nomor WA dulu", Toast.LENGTH_SHORT).show()
            }
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

        poll()
    }

    private fun showAdminTokenManager() {
        val daysInput = EditText(this)
        daysInput.hint = "Masa aktif (hari, default 30)"
        daysInput.inputType = InputType.TYPE_CLASS_NUMBER

        val noteInput = EditText(this)
        noteInput.hint = "Catatan / Nama Member"

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(16), dp(8), dp(16), dp(8))
        layout.addView(daysInput)
        layout.addView(noteInput)

        AlertDialog.Builder(this)
            .setTitle("Admin Panel - Buat Token Baru")
            .setView(layout)
            .setPositiveButton("Generate Token") { _, _ ->
                val days = daysInput.text.toString().toIntOrNull() ?: 30
                val note = noteInput.text.toString().trim()
                createTokenWorker(days, note)
            }
            .setNeutralButton("Format Manual") { _, _ ->
                val rnd = (1000..9999).random()
                val manual = "NIZXBOT-$rnd-${(1000..9999).random()}-${(1000..9999).random()}-${(1000..9999).random()}"
                AlertDialog.Builder(this)
                    .setTitle("Token Dibuat (Manual)")
                    .setMessage(manual)
                    .setPositiveButton("Salin & Pasang") { _, _ ->
                        tokenInput.setText(manual)
                    }
                    .show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun createTokenWorker(days: Int, note: String) {
        Thread {
            try {
                val url = URL("https://botwa-license.nizxreal.workers.dev/admin/create")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-admin-key", "nizxadmin2026")
                conn.doOutput = true
                val payload = JSONObject().put("days", days).put("note", note).toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                ui.post {
                    val j = JSONObject(resp)
                    val tok = j.optString("token")
                    AlertDialog.Builder(this)
                        .setTitle("Token Berhasil Dibuat!")
                        .setMessage("Token: $tok\nMasa Aktif: $days hari\nCatatan: $note")
                        .setPositiveButton("Gunakan di App") { _, _ ->
                            tokenInput.setText(tok)
                        }
                        .show()
                }
            } catch (e: Exception) {
                ui.post {
                    Toast.makeText(this, "Gagal konek worker: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
                    if (pairing) codeView.text = "Gagal: Node belum siap"
                } else {
                    try {
                        val out = JSONObject(res).optString("out")
                        val clean = out.replace("-", "").trim()
                        if (pairing && clean.length == 8) {
                            codeView.text = clean.substring(0, 4) + "-" + clean.substring(4)
                        } else if (pairing) {
                            codeView.text = out
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
            val clean = pc.replace("-", "").trim()
            if (clean.length == 8) codeView.text = clean.substring(0, 4) + "-" + clean.substring(4)
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
