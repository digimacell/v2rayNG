package com.v2ray.ang.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Filter paket di antara TUN Android dan hev-socks5-tunnel.
 *
 *   aplikasi -> TUN -> [filter ini] -> socketpair -> hev-socks5-tunnel -> Xray
 *
 * Untuk setiap alur baru (TCP/UDP) pemilik alurnya dicari lewat
 * ConnectivityManager.getConnectionOwnerUid (Android 10+). Kalau pemiliknya
 * ada di daftar blokir, paketnya dibuang, jadi aplikasi itu tidak bisa online
 * walaupun v2ray aktif. Paket arah sebaliknya diteruskan apa adanya.
 */
class UidPacketFilter(
    private val context: Context,
    private val tun: ParcelFileDescriptor
) {
    private class Verdict(val blocked: Boolean, var expiry: Long)

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val a = FileDescriptor()
    private val b = FileDescriptor()
    private var hevSide: ParcelFileDescriptor? = null

    @Volatile private var running = false
    private var up: Thread? = null
    private var down: Thread? = null

    // Hanya diakses dari thread "up".
    private val cache = HashMap<Int, Verdict>()
    private var blockedUids: Set<Int> = emptySet()
    private var blockedPkgs: Set<String> = emptySet()
    private var lastRefresh = 0L

    /** @return ujung socketpair yang diberikan ke hev sebagai "TUN", atau null kalau gagal. */
    fun start(): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, a, b)
            for (fd in arrayOf(a, b)) {
                try {
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 1 shl 20)
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1 shl 20)
                } catch (_: Exception) {
                }
            }
            val dup = ParcelFileDescriptor.dup(b)
            Os.close(b)
            hevSide = dup
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "UidPacketFilter: socketpair gagal", e)
            return null
        }
        running = true
        up = Thread({ pumpUp() }, "fw-up").also { it.isDaemon = true; it.start() }
        down = Thread({ pumpDown() }, "fw-down").also { it.isDaemon = true; it.start() }
        return hevSide
    }

    fun stop() {
        running = false
        try { Os.shutdown(a, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
        try { up?.join(1500) } catch (_: Exception) {}
        try { down?.join(1500) } catch (_: Exception) {}
        try { Os.close(a) } catch (_: Exception) {}
        try { hevSide?.close() } catch (_: Exception) {}
        up = null; down = null; hevSide = null
    }

    /** TUN -> filter -> hev */
    private fun pumpUp() {
        val buf = ByteArray(65536)
        val pfd = StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val pfds = arrayOf(pfd)
        try {
            while (running) {
                // poll dengan timeout supaya thread ini pasti berhenti saat stop(),
                // dan tidak "mencuri" paket dari filter berikutnya.
                if (Os.poll(pfds, 500) <= 0) continue
                val n = Os.read(tun.fileDescriptor, buf, 0, buf.size)
                if (n <= 0) continue
                if (!isBlocked(buf, n)) Os.write(a, buf, 0, n)
            }
        } catch (e: Exception) {
            if (running) LogUtil.e(AppConfig.TAG, "UidPacketFilter up: ${e.message}")
        }
    }

    /** hev -> TUN (tanpa filter) */
    private fun pumpDown() {
        val buf = ByteArray(65536)
        try {
            while (running) {
                val n = Os.read(a, buf, 0, buf.size)
                if (n <= 0) { if (!running) break else continue }
                Os.write(tun.fileDescriptor, buf, 0, n)
            }
        } catch (e: Exception) {
            if (running) LogUtil.e(AppConfig.TAG, "UidPacketFilter down: ${e.message}")
        }
    }

    private fun refreshBlockList(now: Long) {
        if (now - lastRefresh < 2000) return
        lastRefresh = now
        val pkgs = FirewallPrefs.blocked()
        if (pkgs == blockedPkgs) return
        blockedPkgs = pkgs
        val pm = context.packageManager
        val uids = HashSet<Int>()
        for (p in pkgs) {
            try {
                uids.add(pm.getApplicationInfo(p, 0).uid)
            } catch (_: Exception) {
            }
        }
        uids.remove(context.applicationInfo.uid) // v2rayNG sendiri jangan pernah diblokir
        blockedUids = uids
        cache.clear()
        LogUtil.i(AppConfig.TAG, "UidPacketFilter: ${uids.size} uid diblokir")
    }

    private fun isBlocked(p: ByteArray, n: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        refreshBlockList(now)
        if (blockedUids.isEmpty() || n < 20) return false

        val ver = (p[0].toInt() ushr 4) and 0xF
        val proto: Int
        val hdr: Int
        val srcOff: Int
        val dstOff: Int
        val alen: Int
        when (ver) {
            4 -> {
                hdr = (p[0].toInt() and 0xF) * 4
                proto = p[9].toInt() and 0xFF
                val fragOff = ((p[6].toInt() and 0x1F) shl 8) or (p[7].toInt() and 0xFF)
                if (fragOff != 0) return false
                srcOff = 12; dstOff = 16; alen = 4
            }
            6 -> {
                if (n < 44) return false
                hdr = 40
                proto = p[6].toInt() and 0xFF
                srcOff = 8; dstOff = 24; alen = 16
            }
            else -> return false
        }
        if (proto != OsConstants.IPPROTO_TCP && proto != OsConstants.IPPROTO_UDP) return false
        if (n < hdr + 4) return false

        val sport = ((p[hdr].toInt() and 0xFF) shl 8) or (p[hdr + 1].toInt() and 0xFF)
        val key = (proto shl 24) or (ver shl 16) or sport

        val cached = cache[key]
        if (cached != null && cached.expiry > now) {
            if (cached.blocked) cached.expiry = now + KNOWN_TTL
            return cached.blocked
        }

        val dport = ((p[hdr + 2].toInt() and 0xFF) shl 8) or (p[hdr + 3].toInt() and 0xFF)
        val uid = lookupUid(proto, p, srcOff, dstOff, alen, sport, dport)
        val blocked = uid >= 0 && uid in blockedUids
        if (cache.size > 8192) cache.entries.removeAll { it.value.expiry <= now }
        cache[key] = Verdict(blocked, now + if (uid < 0) UNKNOWN_TTL else KNOWN_TTL)
        return blocked
    }

    private fun lookupUid(
        proto: Int, p: ByteArray, srcOff: Int, dstOff: Int, alen: Int, sport: Int, dport: Int
    ): Int {
        return try {
            val src = InetAddress.getByAddress(p.copyOfRange(srcOff, srcOff + alen))
            val dst = InetAddress.getByAddress(p.copyOfRange(dstOff, dstOff + alen))
            val local = InetSocketAddress(src, sport)
            var uid = cm.getConnectionOwnerUid(proto, local, InetSocketAddress(dst, dport))
            if (uid < 0 && proto == OsConstants.IPPROTO_UDP) {
                // UDP tanpa connect(): coba dengan tujuan wildcard
                val any = if (alen == 4) InetAddress.getByAddress(ByteArray(4)) else InetAddress.getByAddress(ByteArray(16))
                uid = cm.getConnectionOwnerUid(proto, local, InetSocketAddress(any, 0))
            }
            uid
        } catch (e: Exception) {
            -1
        }
    }

    companion object {
        private const val KNOWN_TTL = 120_000L
        private const val UNKNOWN_TTL = 2_000L
    }
}
