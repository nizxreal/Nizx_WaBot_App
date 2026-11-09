package id.nizx.wabotpoc

import android.content.Context
import java.io.File
import java.security.SecureRandom

// Port acak + kunci rahasia, dibuat sekali. Dipakai UI dan service Node.
object Conf {
    fun load(c: Context): Pair<Int, String> {
        val f = File(c.filesDir, "node.conf")
        if (f.exists()) {
            val l = f.readLines()
            val p = l.getOrNull(0)?.toIntOrNull()
            val s = l.getOrNull(1)
            if (p != null && !s.isNullOrEmpty()) return Pair(p, s)
        }
        val rnd = SecureRandom()
        val port = 20000 + rnd.nextInt(30000)
        val b = ByteArray(16)
        rnd.nextBytes(b)
        val secret = b.joinToString("") { "%02x".format(it) }
        f.writeText("$port\n$secret\n")
        return Pair(port, secret)
    }
}

class NodeBridge {
    external fun start(args: Array<String>, logPath: String): Int

    companion object {
        init {
            System.loadLibrary("node")
            System.loadLibrary("nodeglue")
        }
    }
}
