package com.v2ray.ang.service

import com.v2ray.ang.handler.MmkvManager

/**
 * Pengaturan firewall. Disimpan di MMKV karena dibaca dari dua proses
 * (UI di proses utama, CoreVpnService di proses :daemon).
 *
 * Daftar [blocked] berisi aplikasi yang DIBLOKIR internetnya (centang = blokir).
 */
object FirewallPrefs {
    private const val KEY_BLOCKED = "pref_fw_blocked"
    private const val KEY_STANDALONE = "pref_fw_standalone"
    private const val KEY_WITH_V2RAY = "pref_fw_with_v2ray"

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
}
