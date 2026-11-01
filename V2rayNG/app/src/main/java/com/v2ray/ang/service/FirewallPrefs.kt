package com.v2ray.ang.service

import com.v2ray.ang.handler.MmkvManager

/**
 * Pengaturan firewall. Disimpan di MMKV karena dibaca dari dua proses
 * (UI di proses utama, CoreVpnService di proses :daemon).
 *
 * Daftar [blocked] adalah aplikasi yang dicentang. Artinya tergantung mode:
 * - mode biasa: centang = internet DIBLOKIR
 * - mode [allowOnly]: centang = satu-satunya yang BOLEH online, sisanya diblokir
 */
object FirewallPrefs {
    private const val KEY_BLOCKED = "pref_fw_blocked"
    private const val KEY_STANDALONE = "pref_fw_standalone"
    private const val KEY_WITH_V2RAY = "pref_fw_with_v2ray"
    private const val KEY_ALLOW_ONLY = "pref_fw_allow_only"
    private const val KEY_STABILITY = "pref_stability_mode"

    fun blocked(): Set<String> =
        MmkvManager.decodeSettingsStringSet(KEY_BLOCKED)?.toSet() ?: emptySet()

    fun setBlocked(set: Set<String>) {
        MmkvManager.encodeSettings(KEY_BLOCKED, set.toMutableSet())
    }

    /** Firewall mandiri (tanpa v2ray) sedang aktif. */
    fun standalone(): Boolean = MmkvManager.decodeSettingsBool(KEY_STANDALONE)
    fun setStandalone(on: Boolean) {
        MmkvManager.encodeSettings(KEY_STANDALONE, on)
    }

    /** Terapkan daftar blokir saat v2ray aktif. */
    fun withV2ray(): Boolean = MmkvManager.decodeSettingsBool(KEY_WITH_V2RAY)
    fun setWithV2ray(on: Boolean) {
        MmkvManager.encodeSettings(KEY_WITH_V2RAY, on)
    }

    /** true = hanya aplikasi yang dicentang boleh online. */
    fun allowOnly(): Boolean = MmkvManager.decodeSettingsBool(KEY_ALLOW_ONLY)
    fun setAllowOnly(on: Boolean) {
        MmkvManager.encodeSettings(KEY_ALLOW_ONLY, on)
    }

    /**
     * Mode stabilitas (bawaan: nyala): keepalive WebSocket, pemutusan cepat koneksi mati, dan
     * pengawas yang menyambung ulang core otomatis. Dibaca saat v2ray dinyalakan.
     */
    fun stability(): Boolean = MmkvManager.decodeSettingsBool(KEY_STABILITY, true)
    fun setStability(on: Boolean) {
        MmkvManager.encodeSettings(KEY_STABILITY, on)
    }
}
