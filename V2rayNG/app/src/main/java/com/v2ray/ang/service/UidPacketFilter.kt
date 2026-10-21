package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.v2ray.ang.R
import java.io.FileInputStream

/**
 * Firewall mandiri (tanpa v2ray), fitur dari BlokirNet.
 *
 * Mode biasa: aplikasi yang DICENTANG diarahkan ke VPN lokal yang paketnya
 * dibuang. Mode "hanya yang dicentang": kebalikannya. Ini bukan VPN sungguhan: tidak ada server.
 *
 * Android hanya mengizinkan satu VPN aktif, jadi mode ini memutus v2ray.
 * Untuk memblokir sambil v2ray aktif, pakai opsi "Terapkan saat v2ray aktif"
 * (lihat UidPacketFilter).
 */
@SuppressLint("VpnServicePolicy")
class FirewallService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopFirewall()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!startFirewall()) {
            setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startFirewall(): Boolean {
        stopFirewall()
        val builder = Builder()
            .setSession("Firewall")
            .addAddress("10.0.0.2", 32).addRoute("0.0.0.0", 0)
            .addAddress("fd00::2", 128).addRoute("::", 0)

        if (FirewallPrefs.allowOnly()) {
            // Aplikasi yang dicentang (dan v2rayNG sendiri) dikeluarkan dari VPN, jadi online normal.
            // Semua aplikasi lain, termasuk yang baru diinstal, masuk VPN lokal dan paketnya dibuang.
            (FirewallPrefs.blocked() + packageName).forEach {
                try {
                    builder.addDisallowedApplication(it)
                } catch (_: Exception) {
                    // paket sudah tidak terinstal
                }
            }
        } else {
            // Hanya aplikasi yang dicentang yang masuk ke VPN lokal (paketnya dibuang).
            var count = 0
            FirewallPrefs.blocked().filter { it != packageName }.forEach {
                try {
                    builder.addAllowedApplication(it)
                    count++
                } catch (_: Exception) {
                    // paket sudah tidak terinstal
                }
            }
            // Tanpa aplikasi terpilih, VPN akan menangkap SEMUA aplikasi. Jangan dinyalakan.
            if (count == 0) return false
        }

        val fd = builder.establish() ?: return false
        tun = fd
        worker = Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (input.read(buf) >= 0) { /* buang paket */ }
            } catch (_: Exception) {
            }
        }.also { it.start() }
        return true
    }

    private fun stopFirewall() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        worker?.interrupt()
        worker = null
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.firewall_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.firewall_title))
            .setContentText(getString(R.string.firewall_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Dipanggil Android saat VPN lain (misalnya v2ray) mengambil alih. */
    override fun onRevoke() {
        setEnabled(this, false)
        stopFirewall()
        stopSelf()
    }

    override fun onDestroy() {
        stopFirewall()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.v2ray.ang.firewall.STOP"
        private const val CHANNEL_ID = "firewall"
        private const val NOTIF_ID = 7002

        fun isEnabled(c: Context) = FirewallPrefs.standalone()

        fun setEnabled(c: Context, on: Boolean) = FirewallPrefs.setStandalone(on)

        fun start(c: Context) {
            setEnabled(c, true)
            c.startForegroundService(Intent(c, FirewallService::class.java))
        }

        fun stop(c: Context) {
            setEnabled(c, false)
            c.startService(Intent(c, FirewallService::class.java).setAction(ACTION_STOP))
        }
    }
}
