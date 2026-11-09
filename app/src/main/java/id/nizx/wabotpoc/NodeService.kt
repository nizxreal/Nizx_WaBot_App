package id.nizx.wabotpoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class NodeService : Service() {
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!started) {
            started = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nizx:node").also { it.acquire() }
            Thread { runNode() }.start()
        }
        return START_STICKY
    }

    private fun goForeground() {
        val chan = "node"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(chan, "Bot", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, chan)
            .setContentTitle("Nizx WaBot PoC")
            .setContentText("Bot berjalan di latar belakang")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (e: Exception) {
            File(filesDir, "native.log").appendText("startForeground gagal: $e\n")
        }
    }

    private fun runNode() {
        val logFile = File(filesDir, "native.log")
        try {
            if (logFile.exists() && logFile.length() > 512 * 1024) logFile.delete()
            val (port, secret) = Conf.load(this)
            val root = File(filesDir, "nodejs-project")
            val marker = File(root, ".version")
            val ver = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
            if (!marker.exists() || marker.readText() != ver) {
                logFile.appendText("Mengekstrak proyek Node...\n")
                root.deleteRecursively()
                unzipAsset("nodejs-project.zip", root)
                marker.writeText(ver)
                logFile.appendText("Ekstraksi selesai.\n")
            }
            val dataDir = File(filesDir, "data").apply { mkdirs() }
            try {
                android.system.Os.setenv("TMPDIR", cacheDir.absolutePath, true)
                android.system.Os.setenv("HOME", filesDir.absolutePath, true)
            } catch (_: Exception) {}
            val args = arrayOf("node", File(root, "main.js").absolutePath, port.toString(), secret, dataDir.absolutePath)
            val rc = NodeBridge().start(args, logFile.absolutePath)
            logFile.appendText("Node berhenti, kode $rc\n")
        } catch (t: Throwable) {
            logFile.appendText("Gagal menjalankan Node: $t\n")
        }
        stopSelf()
    }

    private fun unzipAsset(name: String, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipInputStream(assets.open(name).buffered()).use { zin ->
            val buf = ByteArray(65536)
            var e = zin.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("entri zip tidak valid")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { f ->
                        var n = zin.read(buf)
                        while (n > 0) {
                            f.write(buf, 0, n)
                            n = zin.read(buf)
                        }
                    }
                }
                e = zin.nextEntry
            }
        }
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) {}
        super.onDestroy()
        // Node tidak bisa dihentikan dari dalam proses; matikan proses :node.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
