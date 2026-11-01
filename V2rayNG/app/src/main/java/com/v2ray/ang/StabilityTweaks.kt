package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.service.FirewallPrefs
import com.v2ray.ang.util.LogUtil

/**
 * Penyesuaian kecil pada config Xray supaya koneksi WebSocket (terutama lewat CDN seperti
 * CloudFront) tidak mudah putus diam-diam. Dijalankan pada JSON config sesaat sebelum core
 * dimulai, jadi tidak mengubah profil yang tersimpan.
 *
 *  - wsSettings.heartbeatPeriod: Xray mengirim ping WebSocket berkala. Tanpa ini, koneksi yang
 *    diam dianggap idle oleh CDN atau jaringan seluler dan ditutup tanpa pemberitahuan.
 *  - sockopt.tcpUserTimeout: koneksi yang paketnya tidak dibalas lebih dari batas ini diputus
 *    dengan cepat, jadi aplikasi segera membuat koneksi baru, bukan menunggu belasan menit.
 *
 * Hanya outbound proxy berbasis TCP (ws, httpupgrade, tcp) yang disentuh. Nilai yang sudah
 * diisi pengguna di profil tidak ditimpa. Kalau mode stabilitas dimatikan, config dikembalikan apa adanya.
 */
object StabilityTweaks {
    private const val WS_HEARTBEAT_SECONDS = 60
    private const val TCP_USER_TIMEOUT_MS = 30_000
    private val SKIPPED_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")
    private val TCP_BASED = setOf("ws", "httpupgrade", "tcp")

    fun apply(configJson: String): String {
        if (!FirewallPrefs.stability()) return configJson
        return try {
            val root = JsonParser.parseString(configJson).asJsonObject
            val outbounds = root.getAsJsonArray("outbounds") ?: return configJson
            var changed = false
            for (element in outbounds) {
                if (!element.isJsonObject) continue
                if (tweakOutbound(element.asJsonObject)) changed = true
            }
            if (changed) root.toString() else configJson
        } catch (e: Exception) {
            // Config tetap jalan walau penyesuaian gagal.
            LogUtil.w(AppConfig.TAG, "StabilityTweaks: dilewati: ${e.message}")
            configJson
        }
    }

    private fun tweakOutbound(outbound: JsonObject): Boolean {
        val protocol = outbound.get("protocol")?.asString ?: return false
        if (protocol in SKIPPED_PROTOCOLS) return false
        val stream = outbound.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val network = stream.get("network")?.asString ?: return false
        if (network !in TCP_BASED) return false

        var changed = false
        if (network == "ws") {
            val ws = stream.get("wsSettings")?.takeIf { it.isJsonObject }?.asJsonObject
            if (ws != null && !ws.has("heartbeatPeriod")) {
                ws.addProperty("heartbeatPeriod", WS_HEARTBEAT_SECONDS)
                changed = true
            }
        }
        var sockopt = stream.get("sockopt")?.takeIf { it.isJsonObject }?.asJsonObject
        if (sockopt == null) {
            sockopt = JsonObject()
            stream.add("sockopt", sockopt)
            changed = true
        }
        if (!sockopt.has("tcpUserTimeout")) {
            sockopt.addProperty("tcpUserTimeout", TCP_USER_TIMEOUT_MS)
            changed = true
        }
        return changed
    }
}
