package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pengawas koneksi: memeriksa secara berkala apakah jalur proxy masih hidup, dan kalau mati
 * menyambungkan ulang core secara otomatis (tanpa memutus VPN).
 *
 * Cara kerja:
 *  - Hanya memeriksa saat layar menyala, supaya hemat kuota dan baterai.
 *  - Begitu layar menyala, langsung diperiksa (setelah jeda singkat), karena saat itulah pengguna
 *    biasanya baru sadar koneksi mati.
 *  - Satu kegagalan belum dianggap mati: diperiksa lagi beberapa detik kemudian.
 *  - Kalau gagal dua kali berturut-turut, [recover] dipanggil. Kalau pemulihan tidak membuat
 *    koneksi sehat, jeda antar percobaan makin panjang (maksimal 5 menit) supaya tidak
 *    menyambung ulang terus-menerus saat server memang sedang mati atau kuota habis.
 *
 * @param probe true bila koneksi sehat. Boleh memblokir (dipanggil di coroutine).
 * @param recover memulihkan core, true bila berhasil dijalankan. Boleh memblokir.
 */
class ConnectionWatchdog(
    private val scope: CoroutineScope,
    private val probe: () -> Boolean,
    private val recover: () -> Boolean,
) {
    private companion object {
        const val FIRST_CHECK_DELAY_MS = 20_000L
        const val CHECK_INTERVAL_MS = 120_000L
        const val CONFIRM_DELAY_MS = 4_000L
        const val WAKE_SETTLE_MS = 3_000L
        val BACKOFF_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }

    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var screenOn = true

    @Synchronized
    fun start(screenOn: Boolean) {
        stop()
        this.screenOn = screenOn
        // buang sinyal bangun lama
        while (wake.tryReceive().isSuccess) { /* kosongkan */ }
        job = scope.launch { loop() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Dipanggil saat layar menyala atau mati. */
    fun onScreenChanged(on: Boolean) {
        screenOn = on
        if (on) wake.trySend(Unit)
    }

    private suspend fun loop() {
        var failedRecoveries = 0
        var waitMs = FIRST_CHECK_DELAY_MS
        try {
            while (true) {
                if (!screenOn) {
                    // layar mati: tidur sampai layar menyala
                    wake.receive()
                    delay(WAKE_SETTLE_MS)
                } else {
                    val woken = withTimeoutOrNull(waitMs) { wake.receive() } != null
                    if (woken) delay(WAKE_SETTLE_MS)
                }
                if (!screenOn) continue

                if (check()) {
                    failedRecoveries = 0
                    waitMs = CHECK_INTERVAL_MS
                    continue
                }

                LogUtil.w(AppConfig.TAG, "Watchdog: koneksi tidak merespons, menyambung ulang core")
                recover()
                val index = if (failedRecoveries < BACKOFF_MS.size) failedRecoveries else BACKOFF_MS.size - 1
                waitMs = BACKOFF_MS[index]
                failedRecoveries++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Watchdog: berhenti karena error: ${e.message}", e)
        }
    }

    /** Dua pemeriksaan berurutan; sehat bila salah satunya lolos. */
    private suspend fun check(): Boolean {
        if (safeProbe()) return true
        delay(CONFIRM_DELAY_MS)
        return safeProbe()
    }

    private fun safeProbe(): Boolean = try {
        probe()
    } catch (e: Exception) {
        false
    }
}
