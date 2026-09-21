package com.synclab.airlens.telemetry

import com.synclab.airlens.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Turns a [HudSnapshot] into localised, colour-toned [HudRow]s for the glance card and
 * the four telemetry tabs.
 *
 * Pure Kotlin: the only Android touch-point is the generated `R` constants, resolved
 * through [HudStrings], so the class runs under plain JUnit. All numbers are formatted
 * with [Locale.US]; negative EV and dBFS values use U+2212 (true minus) as in the design.
 * Colour appears only when a value is out of range — see the tone functions in the
 * companion for the exact band edges.
 */
class HudFormatter(private val strings: HudStrings) {

    /**
     * The five glance-card rows. Selection follows state:
     * thermal → Temp, Bitrate, FPS, Wi-Fi, Battery ·
     * degraded → Bitrate, Loss, Wi-Fi, Battery, Temp ·
     * otherwise → Bitrate, FPS, RTT, Wi-Fi, Battery.
     */
    fun glance(s: HudSnapshot, degraded: Boolean, thermalAlert: Boolean): List<HudRow> {
        val bitrate = HudRow(
            key = strings.get(R.string.hud_g_bitrate),
            value = bitrateValue(s),
            tone = bitrateTone(s.actualBitrateMbps, s.targetBitrateMbps, degraded),
        )
        val fps = HudRow(
            key = strings.get(R.string.hud_g_fps),
            value = s.actualFps?.let { "${wholeNumber(it)} / ${s.targetFps}" } ?: na(),
            tone = fpsTone(s.actualFps, s.targetFps, degraded),
        )
        val rtt = HudRow(
            key = strings.get(R.string.hud_g_rtt),
            value = rttValue(s),
            tone = rttTone(s.srtRttMs, degraded),
        )
        val wifi = HudRow(
            key = strings.get(R.string.hud_g_wifi),
            value = rssiValue(s),
            tone = rssiTone(s.wifiRssi),
        )
        val battery = HudRow(
            key = strings.get(R.string.hud_g_battery),
            value = percentValue(s.batteryPercent),
            tone = batteryTone(s.batteryPercent),
        )
        val temp = HudRow(
            key = strings.get(R.string.hud_g_temp),
            value = celsiusValue(s.temperatureC),
            tone = temperatureTone(s.temperatureC),
        )
        val loss = HudRow(
            key = strings.get(R.string.hud_g_loss),
            value = lossValue(s),
            tone = lossTone(s.srtLossPercent),
        )
        return when {
            thermalAlert -> listOf(temp, bitrate, fps, wifi, battery)
            degraded -> listOf(bitrate, loss, wifi, battery, temp)
            else -> listOf(bitrate, fps, rtt, wifi, battery)
        }
    }

    /** Rows for one sheet tab in display order; `debug=false` drops diagnostic rows. */
    fun rows(tab: HudTab, s: HudSnapshot, degraded: Boolean, debug: Boolean): List<HudRow> {
        val all = when (tab) {
            HudTab.Stream -> streamRows(s, degraded)
            HudTab.Camera -> cameraRows(s)
            HudTab.Network -> networkRows(s, degraded)
            HudTab.Device -> deviceRows(s)
        }
        return if (debug) all else all.filter { !it.debug }
    }

    // ---- Stream --------------------------------------------------------------------

    private fun streamRows(s: HudSnapshot, degraded: Boolean): List<HudRow> = listOf(
        HudRow(
            key = strings.get(R.string.hud_k_bitrate),
            value = bitrateValue(s),
            note = strings.get(R.string.hud_n_target, s.targetBitrateMbps),
            tone = bitrateTone(s.actualBitrateMbps, s.targetBitrateMbps, degraded),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_framerate),
            value = s.actualFps
                ?.let { strings.get(R.string.hud_f_fps_pair, wholeNumber(it), s.targetFps) }
                ?: na(),
            note = strings.get(R.string.hud_n_actual_target),
            tone = fpsTone(s.actualFps, s.targetFps, degraded),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_resolution),
            value = strings.get(R.string.hud_f_resolution, s.width, s.height),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_codec),
            value = s.codecLabel.ifBlank { na() },
        ),
        HudRow(
            key = strings.get(R.string.hud_k_duration),
            value = if (s.live) formatDuration(s.durationSeconds) else na(),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_video_frames),
            value = grouped(s.videoFrames),
            debug = true,
        ),
        HudRow(
            key = strings.get(R.string.hud_k_keyframes),
            value = grouped(s.keyframes),
            note = strings.get(R.string.hud_n_every, s.keyframeIntervalSeconds),
            debug = true,
        ),
        HudRow(
            key = strings.get(R.string.hud_k_audio_frames),
            value = grouped(s.audioFrames),
            debug = true,
        ),
        encoderStateRow(s.encoderState),
    )

    private fun encoderStateRow(state: String): HudRow {
        val (resId, tone) = when (state) {
            ENCODER_STREAMING -> R.string.hud_v_streaming to HudTone.Ok
            ENCODER_STALLED -> R.string.hud_v_stalled to HudTone.Err
            ENCODER_DISCONNECTED -> R.string.hud_v_disconnected to HudTone.Err
            else -> R.string.hud_v_idle to HudTone.Neutral
        }
        return HudRow(
            key = strings.get(R.string.hud_k_encoder_state),
            value = strings.get(resId),
            tone = tone,
            debug = true,
        )
    }

    // ---- Camera --------------------------------------------------------------------

    private fun cameraRows(s: HudSnapshot): List<HudRow> = listOf(
        HudRow(
            key = strings.get(R.string.hud_k_lens),
            value = s.lensSub.lowercase(Locale.ROOT).ifBlank { na() },
            note = s.cameraId?.let { strings.get(R.string.hud_n_camera_id, it) },
        ),
        HudRow(
            key = strings.get(R.string.hud_k_zoom),
            value = strings.get(R.string.hud_f_zoom, decimal(s.zoom.toDouble(), 1)),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_iso),
            value = s.iso?.toString() ?: na(),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_shutter),
            value = shutterValue(s.exposureTimeNs),
            note = strings.get(R.string.hud_n_exposure_time),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_aperture),
            value = s.aperture?.let { strings.get(R.string.hud_f_aperture, decimal(it.toDouble(), 1)) } ?: na(),
        ),
        focusRow(s.afState, s.focusDistanceDiopters),
        exposureStateRow(s.aeState),
        whiteBalanceRow(s.awbState),
        HudRow(
            key = strings.get(R.string.hud_k_ev),
            value = evValue(s.evSteps, s.evStepNumerator, s.evStepDenominator),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_focal_length),
            value = s.focalLength35mmEq?.let { strings.get(R.string.hud_f_mm_eq, it) } ?: na(),
            debug = true,
        ),
        HudRow(
            key = strings.get(R.string.hud_k_frame_timing),
            value = s.frameIntervalMsAvg?.let { strings.get(R.string.hud_f_ms_avg, decimal(it, 1)) } ?: na(),
            note = s.frameJitterMs?.let { strings.get(R.string.hud_n_jitter, decimal(it, 1)) },
            debug = true,
        ),
    )

    private fun focusRow(afState: Int?, diopters: Float?): HudRow {
        val (resId, tone) = when (afState) {
            null -> null to HudTone.Neutral
            AF_FOCUSED_LOCKED -> R.string.hud_v_af_locked to HudTone.Ok
            AF_PASSIVE_FOCUSED -> R.string.hud_v_af_focused to HudTone.Ok
            AF_PASSIVE_SCAN, AF_ACTIVE_SCAN -> R.string.hud_v_af_scanning to HudTone.Neutral
            AF_NOT_FOCUSED_LOCKED, AF_PASSIVE_UNFOCUSED -> R.string.hud_v_af_unfocused to HudTone.Warn
            else -> R.string.hud_v_af_inactive to HudTone.Neutral
        }
        return HudRow(
            key = strings.get(R.string.hud_k_focus),
            value = resId?.let { strings.get(it) } ?: na(),
            note = focusDistanceNote(diopters),
            tone = tone,
        )
    }

    private fun focusDistanceNote(diopters: Float?): String? {
        diopters ?: return null
        if (diopters <= 0f) return strings.get(R.string.hud_v_infinity)
        return strings.get(R.string.hud_f_metres, decimal(1.0 / diopters, 1))
    }

    private fun exposureStateRow(aeState: Int?): HudRow {
        val (resId, tone) = when (aeState) {
            null -> null to HudTone.Neutral
            AE_CONVERGED -> R.string.hud_v_ae_converged to HudTone.Ok
            AE_LOCKED -> R.string.hud_v_ae_locked to HudTone.Ok
            AE_SEARCHING, AE_PRECAPTURE -> R.string.hud_v_ae_searching to HudTone.Neutral
            AE_FLASH_REQUIRED -> R.string.hud_v_ae_flash to HudTone.Warn
            else -> R.string.hud_v_ae_inactive to HudTone.Neutral
        }
        return HudRow(
            key = strings.get(R.string.hud_k_exposure_state),
            value = resId?.let { strings.get(it) } ?: na(),
            tone = tone,
        )
    }

    private fun whiteBalanceRow(awbState: Int?): HudRow {
        val resId = when (awbState) {
            null -> null
            AWB_CONVERGED -> R.string.hud_v_awb_converged
            AWB_LOCKED -> R.string.hud_v_awb_locked
            AWB_SEARCHING -> R.string.hud_v_awb_searching
            else -> R.string.hud_v_awb_inactive
        }
        return HudRow(
            key = strings.get(R.string.hud_k_white_balance),
            value = resId?.let { strings.get(it) } ?: na(),
        )
    }

    private fun shutterValue(exposureTimeNs: Long?): String {
        if (exposureTimeNs == null || exposureTimeNs <= 0L) return na()
        if (exposureTimeNs >= NANOS_PER_SECOND) {
            val seconds = exposureTimeNs / NANOS_PER_SECOND.toDouble()
            val text = if (seconds == floor(seconds)) wholeNumber(seconds) else decimal(seconds, 1)
            return strings.get(R.string.hud_f_seconds, text)
        }
        val denominator = (NANOS_PER_SECOND.toDouble() / exposureTimeNs).roundToLong()
        return strings.get(R.string.hud_f_shutter, denominator)
    }

    private fun evValue(steps: Int?, numerator: Int?, denominator: Int?): String {
        if (steps == null || numerator == null || denominator == null || denominator == 0) return na()
        val ev = steps * numerator.toDouble() / denominator
        return signedDecimal(ev, 1, explicitPlus = true)
    }

    // ---- Network -------------------------------------------------------------------

    private fun networkRows(s: HudSnapshot, degraded: Boolean): List<HudRow> {
        val rssiTone = rssiTone(s.wifiRssi)
        return listOf(
            HudRow(
                key = strings.get(R.string.hud_k_wifi),
                value = rssiValue(s),
                note = wifiNote(s.wifiRssi, s.wifiBandLabel, rssiTone),
                tone = rssiTone,
            ),
            HudRow(
                key = strings.get(R.string.hud_k_link_quality),
                value = if (s.wifiRssi == null) na() else strings.get(linkQualityLabel(rssiTone)),
                tone = rssiTone,
            ),
            HudRow(
                key = strings.get(R.string.hud_k_srt_rtt),
                value = rttValue(s),
                tone = rttTone(s.srtRttMs, degraded),
            ),
            HudRow(
                key = strings.get(R.string.hud_k_packet_loss),
                value = lossValue(s),
                tone = lossTone(s.srtLossPercent),
            ),
            HudRow(
                key = strings.get(R.string.hud_k_network_type),
                value = s.networkTypeLabel.ifBlank { na() },
            ),
            HudRow(
                key = strings.get(R.string.hud_k_reconnects),
                value = grouped(s.reconnects),
                debug = true,
            ),
            HudRow(
                key = strings.get(R.string.hud_k_connection_losses),
                value = grouped(s.connectionLosses),
                debug = true,
            ),
            HudRow(
                key = strings.get(R.string.hud_k_target),
                value = s.targetUrl.ifBlank { na() },
                debug = true,
            ),
        )
    }

    private fun wifiNote(rssi: Int?, band: String?, tone: HudTone): String? {
        rssi ?: return null
        if (tone == HudTone.Err) return strings.get(R.string.hud_n_wifi_weak)
        band ?: return null
        val resId = when (tone) {
            HudTone.Ok -> R.string.hud_n_wifi_excellent
            HudTone.Neutral -> R.string.hud_n_wifi_good
            else -> R.string.hud_n_wifi_fair
        }
        return strings.get(resId, band)
    }

    private fun linkQualityLabel(tone: HudTone): Int = when (tone) {
        HudTone.Ok, HudTone.Neutral -> R.string.hud_v_good
        HudTone.Warn -> R.string.hud_v_fair
        HudTone.Err -> R.string.hud_v_poor
    }

    // ---- Device --------------------------------------------------------------------

    private fun deviceRows(s: HudSnapshot): List<HudRow> = listOf(
        HudRow(
            key = strings.get(R.string.hud_k_battery),
            value = percentValue(s.batteryPercent),
            note = batteryNote(s),
            tone = batteryTone(s.batteryPercent),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_temperature),
            value = celsiusValue(s.temperatureC),
            tone = temperatureTone(s.temperatureC),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_thermal),
            value = if (s.thermalStatus == null) na() else thermalLabel(s.thermalStatus),
            note = if (isThermalSevere(s.thermalStatus)) strings.get(R.string.hud_n_thermal_severe) else null,
            tone = thermalTone(s.thermalStatus),
        ),
        microphoneRow(s.micState, s.micSummary),
        audioLevelRow(s.audioRmsDbfs, s.audioPeakDbfs),
        HudRow(
            key = strings.get(R.string.hud_k_keep_screen_on),
            value = strings.get(if (s.keepScreenOn) R.string.hud_v_on else R.string.hud_v_off),
        ),
        HudRow(
            key = strings.get(R.string.hud_k_device),
            value = s.deviceModel.ifBlank { na() },
            debug = true,
        ),
    )

    private fun batteryNote(s: HudSnapshot): String {
        if (s.batteryCharging) return strings.get(R.string.hud_v_charging)
        val minutes = s.batteryMinutesRemaining ?: return strings.get(R.string.hud_v_discharging)
        return strings.get(
            R.string.hud_n_battery_estimate,
            strings.get(R.string.hud_v_discharging),
            minutes,
            modeLabel(s.width, s.height, s.targetFps),
        )
    }

    private fun microphoneRow(state: MicState, summary: String): HudRow {
        val (value, tone) = when (state) {
            MicState.Active -> strings.get(R.string.hud_v_mic_active, summary) to HudTone.Ok
            MicState.Off -> strings.get(R.string.hud_v_mic_off) to HudTone.Neutral
            MicState.NoPermission -> strings.get(R.string.hud_v_mic_no_permission) to HudTone.Warn
        }
        return HudRow(key = strings.get(R.string.hud_k_microphone), value = value, tone = tone)
    }

    private fun audioLevelRow(rms: Float?, peak: Float?): HudRow = HudRow(
        key = strings.get(R.string.hud_k_audio_level),
        value = rms?.let { strings.get(R.string.hud_f_dbfs, signedDecimal(it.toDouble(), 0)) } ?: na(),
        note = peak?.let { strings.get(R.string.hud_n_peak, signedDecimal(it.toDouble(), 0)) },
        tone = audioTone(rms, peak),
    )

    // ---- Shared value formatters -----------------------------------------------------

    private fun na(): String = strings.get(R.string.hud_v_na)

    private fun bitrateValue(s: HudSnapshot): String =
        s.actualBitrateMbps?.let { strings.get(R.string.hud_f_mbps, decimal(it, 1)) } ?: na()

    private fun rttValue(s: HudSnapshot): String =
        s.srtRttMs?.let { strings.get(R.string.hud_f_ms, wholeNumber(it)) } ?: na()

    private fun rssiValue(s: HudSnapshot): String =
        s.wifiRssi?.let { strings.get(R.string.hud_f_dbm, it) } ?: na()

    private fun lossValue(s: HudSnapshot): String =
        s.srtLossPercent?.let { strings.get(R.string.hud_f_percent, decimal(it, 2)) } ?: na()

    private fun percentValue(percent: Int): String =
        strings.get(R.string.hud_f_percent, percent.toString())

    private fun celsiusValue(celsius: Float?): String =
        celsius?.let { strings.get(R.string.hud_f_celsius, decimal(it.toDouble(), 1)) } ?: na()

    companion object {
        /** Camera2 `CONTROL_AF_STATE_*` raw values (mirrored to avoid an android.* import). */
        private const val AF_PASSIVE_SCAN = 1
        private const val AF_PASSIVE_FOCUSED = 2
        private const val AF_ACTIVE_SCAN = 3
        private const val AF_FOCUSED_LOCKED = 4
        private const val AF_NOT_FOCUSED_LOCKED = 5
        private const val AF_PASSIVE_UNFOCUSED = 6

        /** Camera2 `CONTROL_AE_STATE_*` raw values. */
        private const val AE_SEARCHING = 1
        private const val AE_CONVERGED = 2
        private const val AE_LOCKED = 3
        private const val AE_FLASH_REQUIRED = 4
        private const val AE_PRECAPTURE = 5

        /** Camera2 `CONTROL_AWB_STATE_*` raw values. */
        private const val AWB_SEARCHING = 1
        private const val AWB_CONVERGED = 2
        private const val AWB_LOCKED = 3

        /** `PowerManager.THERMAL_STATUS_*` raw values. */
        private const val THERMAL_NONE = 0
        private const val THERMAL_SEVERE = 3
        private const val THERMAL_SHUTDOWN = 6
        private val THERMAL_LABELS = arrayOf(
            "NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN",
        )

        /** Temperature at or above which the thermal alert fires, in °C. */
        const val THERMAL_ALERT_CELSIUS = 42.0f

        const val ENCODER_STREAMING = "streaming"
        const val ENCODER_STALLED = "stalled"
        const val ENCODER_DISCONNECTED = "disconnected"
        const val ENCODER_IDLE = "idle"

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MINUS_SIGN = '−'
        private const val EM_DASH = "—"

        /** 3840×2160 → "4K{fps}", otherwise "{shortSide}p{fps}" (e.g. "1080p60", "720p30"). */
        fun modeLabel(width: Int, height: Int, fps: Int): String {
            val shortSide = minOf(width, height)
            val longSide = maxOf(width, height)
            return if (longSide == 3840 && shortSide == 2160) "4K$fps" else "${shortSide}p$fps"
        }

        /** `mm:ss`, or `h:mm:ss` once the duration reaches one hour. Negative input clamps to 0. */
        fun formatDuration(seconds: Long): String {
            val total = seconds.coerceAtLeast(0L)
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val secs = total % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, secs)
            }
        }

        /** "NONE".."SHUTDOWN" for the raw `PowerManager` status, "—" when null or unknown. */
        fun thermalLabel(status: Int?): String =
            if (status != null && status in THERMAL_NONE..THERMAL_SHUTDOWN) THERMAL_LABELS[status] else EM_DASH

        /** True when status ≥ SEVERE or temperature ≥ [THERMAL_ALERT_CELSIUS]. */
        fun isThermalAlert(status: Int?, temperatureC: Float?): Boolean =
            isThermalSevere(status) || (temperatureC != null && temperatureC >= THERMAL_ALERT_CELSIUS)

        /** Degraded → Err; unknown → Neutral; ratio ≥ 0.85 Ok · 0.60–0.85 Warn · < 0.60 Err. */
        fun bitrateTone(actual: Double?, target: Int, degraded: Boolean): HudTone {
            if (degraded) return HudTone.Err
            if (actual == null || target <= 0) return HudTone.Neutral
            val ratio = actual / target
            return when {
                ratio >= 0.85 -> HudTone.Ok
                ratio >= 0.60 -> HudTone.Warn
                else -> HudTone.Err
            }
        }

        /** Ratio ≥ 0.95 Neutral · 0.80–0.95 Warn · < 0.80 Err; unknown → Err while degraded, else Neutral. */
        fun fpsTone(actual: Double?, target: Int, degraded: Boolean): HudTone {
            if (actual == null || target <= 0) return if (degraded) HudTone.Err else HudTone.Neutral
            val ratio = actual / target
            return when {
                ratio >= 0.95 -> HudTone.Neutral
                ratio >= 0.80 -> HudTone.Warn
                else -> HudTone.Err
            }
        }

        /** ≥ −60 Ok · −60…−70 Neutral · −70…−80 Warn · < −80 Err; unknown Neutral. */
        fun rssiTone(rssi: Int?): HudTone = when {
            rssi == null -> HudTone.Neutral
            rssi >= -60 -> HudTone.Ok
            rssi >= -70 -> HudTone.Neutral
            rssi >= -80 -> HudTone.Warn
            else -> HudTone.Err
        }

        /** < 50 ms Neutral · 50–150 Warn · > 150 Err; unknown → Err while degraded, else Neutral. */
        fun rttTone(rtt: Double?, degraded: Boolean): HudTone = when {
            rtt == null -> if (degraded) HudTone.Err else HudTone.Neutral
            rtt < 50.0 -> HudTone.Neutral
            rtt <= 150.0 -> HudTone.Warn
            else -> HudTone.Err
        }

        /** 0 % Ok · < 1 Neutral · 1–5 Warn · > 5 Err; unknown Neutral. */
        fun lossTone(loss: Double?): HudTone = when {
            loss == null -> HudTone.Neutral
            loss <= 0.0 -> HudTone.Ok
            loss < 1.0 -> HudTone.Neutral
            loss <= 5.0 -> HudTone.Warn
            else -> HudTone.Err
        }

        /** ≤ 15 % Err · ≤ 30 % Warn · else Neutral. */
        fun batteryTone(p: Int): HudTone = when {
            p <= 15 -> HudTone.Err
            p <= 30 -> HudTone.Warn
            else -> HudTone.Neutral
        }

        /** ≥ 42 °C Err · ≥ 39 °C Warn · else Neutral; unknown Neutral. */
        fun temperatureTone(c: Float?): HudTone = when {
            c == null -> HudTone.Neutral
            c >= 42.0f -> HudTone.Err
            c >= 39.0f -> HudTone.Warn
            else -> HudTone.Neutral
        }

        /** NONE Ok · LIGHT/MODERATE Warn · ≥ SEVERE Err; unknown Neutral. */
        fun thermalTone(status: Int?): HudTone = when {
            status == null -> HudTone.Neutral
            status <= THERMAL_NONE -> HudTone.Ok
            status < THERMAL_SEVERE -> HudTone.Warn
            else -> HudTone.Err
        }

        /** Peak (or RMS when peak is unknown) above −3 dBFS → Warn (clipping); known → Ok; unknown Neutral. */
        fun audioTone(rms: Float?, peak: Float?): HudTone {
            val loudest = peak ?: rms ?: return HudTone.Neutral
            return if (loudest > -3.0f) HudTone.Warn else HudTone.Ok
        }

        private fun isThermalSevere(status: Int?): Boolean = status != null && status >= THERMAL_SEVERE

        /** Fixed-point decimal in [Locale.US] (ASCII hyphen for negatives — used where the sign is never shown). */
        internal fun decimal(value: Double, decimals: Int): String =
            String.format(Locale.US, "%.${decimals}f", value)

        /** Rounded integer text in [Locale.US]. */
        internal fun wholeNumber(value: Double): String = String.format(Locale.US, "%.0f", value)

        /** Thousands-grouped counter, e.g. 43920 → "43,920". */
        internal fun grouped(count: Long): String = String.format(Locale.US, "%,d", count)

        /**
         * Decimal with a typographic sign: U+2212 for negatives, "+" for positives when
         * [explicitPlus], no sign for zero. Rounds before choosing the sign so −0.04 → "0.0".
         */
        internal fun signedDecimal(value: Double, decimals: Int, explicitPlus: Boolean = false): String {
            val magnitude = decimal(abs(value), decimals)
            val isZero = magnitude.all { it == '0' || it == '.' }
            return when {
                isZero -> magnitude
                value < 0 -> "$MINUS_SIGN$magnitude"
                explicitPlus -> "+$magnitude"
                else -> magnitude
            }
        }
    }
}
