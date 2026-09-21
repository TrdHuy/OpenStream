package com.synclab.airlens.telemetry

import com.synclab.airlens.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resource-free [HudStrings]: returns the id as a stable token ("@1234") plus the
 * arguments joined with "|", so tests assert on *which* string was chosen and *what*
 * was formatted into it without needing Android resources.
 */
class FakeHudStrings : HudStrings {
    override fun get(resId: Int): String = "@$resId"

    override fun get(resId: Int, vararg args: Any): String = "@$resId|" + args.joinToString("|")
}

/** Token for a plain resource, matching [FakeHudStrings]. */
fun res(resId: Int): String = "@$resId"

/** Token for a formatted resource, matching [FakeHudStrings]. */
fun res(resId: Int, vararg args: Any): String = "@$resId|" + args.joinToString("|")

class HudFormatterTest {
    private val formatter = HudFormatter(FakeHudStrings())
    private val na = res(R.string.hud_v_na)

    /** Healthy 4K30 session mirroring the design prototype's "live" scenario. */
    private val live = HudSnapshot(
        live = true,
        targetBitrateMbps = 30,
        actualBitrateMbps = 29.8,
        targetFps = 30,
        actualFps = 30.0,
        width = 3840,
        height = 2160,
        codecLabel = "H.264 High · CBR",
        durationSeconds = 1_462,
        videoFrames = 43_920,
        keyframes = 732,
        keyframeIntervalSeconds = 2,
        audioFrames = 68_610,
        sessionMegabits = 1_234.5,
        encoderState = HudFormatter.ENCODER_STREAMING,
        lensSub = "MAIN",
        lensName = "Back main",
        cameraId = "0",
        zoom = 1.0f,
        iso = 640,
        exposureTimeNs = 20_000_000L,
        aperture = 1.8f,
        afState = 4,
        aeState = 2,
        awbState = 2,
        focusDistanceDiopters = 0.714f,
        evSteps = -1,
        evStepNumerator = 1,
        evStepDenominator = 3,
        focalLength35mmEq = 24,
        frameIntervalMsAvg = 33.1,
        frameJitterMs = 1.2,
        wifiRssi = -52,
        wifiBandLabel = "5 GHz",
        networkTypeLabel = "Wi-Fi 6 · 5 GHz",
        srtRttMs = 8.0,
        srtLossPercent = 0.0,
        reconnects = 0,
        connectionLosses = 0,
        targetUrl = "srt://192.168.1.5:9000",
        batteryPercent = 62,
        batteryCharging = false,
        batteryMinutesRemaining = 48,
        temperatureC = 36.8f,
        thermalStatus = 0,
        micState = MicState.Active,
        micSummary = "48 kHz mono",
        audioRmsDbfs = -18f,
        audioPeakDbfs = -6f,
        keepScreenOn = true,
        deviceModel = "Samsung SM-S916B",
    )

    /** Transport dropped: stream values unknown, Wi-Fi weak, loss high. */
    private val degradedSnapshot = live.copy(
        actualBitrateMbps = null,
        actualFps = null,
        srtRttMs = null,
        srtLossPercent = 12.4,
        wifiRssi = -78,
        encoderState = HudFormatter.ENCODER_STALLED,
    )

    private val idle = live.copy(
        live = false,
        actualBitrateMbps = null,
        actualFps = null,
        srtRttMs = null,
        srtLossPercent = null,
        durationSeconds = 0,
        videoFrames = 0,
        keyframes = 0,
        audioFrames = 0,
        encoderState = HudFormatter.ENCODER_IDLE,
    )

    // ---- Glance card ------------------------------------------------------------------

    @Test
    fun glanceNormalShowsBitrateFpsRttWifiBattery() {
        val rows = formatter.glance(live, degraded = false, thermalAlert = false)

        assertEquals(
            listOf(R.string.hud_g_bitrate, R.string.hud_g_fps, R.string.hud_g_rtt, R.string.hud_g_wifi, R.string.hud_g_battery)
                .map(::res),
            rows.map { it.key },
        )
        assertEquals(
            listOf(
                res(R.string.hud_f_mbps, "29.8"),
                "30 / 30",
                res(R.string.hud_f_ms, "8"),
                res(R.string.hud_f_dbm, -52),
                res(R.string.hud_f_percent, "62"),
            ),
            rows.map { it.value },
        )
        assertEquals(
            listOf(HudTone.Ok, HudTone.Neutral, HudTone.Neutral, HudTone.Ok, HudTone.Neutral),
            rows.map { it.tone },
        )
        assertTrue(rows.none { it.debug })
    }

    @Test
    fun glanceDegradedShowsBitrateLossWifiBatteryTempInErrTones() {
        val rows = formatter.glance(degradedSnapshot, degraded = true, thermalAlert = false)

        assertEquals(
            listOf(R.string.hud_g_bitrate, R.string.hud_g_loss, R.string.hud_g_wifi, R.string.hud_g_battery, R.string.hud_g_temp)
                .map(::res),
            rows.map { it.key },
        )
        assertEquals(na, rows[0].value)
        assertEquals(HudTone.Err, rows[0].tone)
        assertEquals(res(R.string.hud_f_percent, "12.40"), rows[1].value)
        assertEquals(HudTone.Err, rows[1].tone)
        assertEquals(res(R.string.hud_f_dbm, -78), rows[2].value)
        assertEquals(HudTone.Warn, rows[2].tone)
        assertEquals(HudTone.Neutral, rows[3].tone)
        assertEquals(res(R.string.hud_f_celsius, "36.8"), rows[4].value)
        assertEquals(HudTone.Neutral, rows[4].tone)
    }

    @Test
    fun glanceThermalLeadsWithTemperatureAndKeepsBitrateFpsWifiBattery() {
        val hot = live.copy(temperatureC = 43.2f, thermalStatus = 3, actualBitrateMbps = 27.4, actualFps = 26.0)
        val rows = formatter.glance(hot, degraded = false, thermalAlert = true)

        assertEquals(
            listOf(R.string.hud_g_temp, R.string.hud_g_bitrate, R.string.hud_g_fps, R.string.hud_g_wifi, R.string.hud_g_battery)
                .map(::res),
            rows.map { it.key },
        )
        assertEquals(res(R.string.hud_f_celsius, "43.2"), rows[0].value)
        assertEquals(HudTone.Err, rows[0].tone)
        assertEquals(HudTone.Ok, rows[1].tone)
        assertEquals("26 / 30", rows[2].value)
        assertEquals(HudTone.Warn, rows[2].tone)
    }

    @Test
    fun glanceThermalWinsOverDegradedSelection() {
        val rows = formatter.glance(degradedSnapshot, degraded = true, thermalAlert = true)
        assertEquals(res(R.string.hud_g_temp), rows.first().key)
        assertEquals(5, rows.size)
    }

    @Test
    fun glanceShowsDashForStreamValuesWhenNotLive() {
        val rows = formatter.glance(idle, degraded = false, thermalAlert = false)

        assertEquals(na, rows[0].value)
        assertEquals(na, rows[1].value)
        assertEquals(na, rows[2].value)
        assertEquals(HudTone.Neutral, rows[0].tone)
        assertEquals(HudTone.Neutral, rows[1].tone)
        assertEquals(HudTone.Neutral, rows[2].tone)
        assertEquals(res(R.string.hud_f_dbm, -52), rows[3].value)
        assertEquals(res(R.string.hud_f_percent, "62"), rows[4].value)
    }

    @Test
    fun glanceAlwaysHasExactlyFiveRows() {
        for (snapshot in listOf(live, degradedSnapshot, idle)) {
            for (degraded in listOf(false, true)) {
                for (thermal in listOf(false, true)) {
                    assertEquals(5, formatter.glance(snapshot, degraded, thermal).size)
                }
            }
        }
    }

    // ---- Stream tab -------------------------------------------------------------------

    @Test
    fun streamTabRowOrderAndDebugFiltering() {
        val visible = formatter.rows(HudTab.Stream, live, degraded = false, debug = false)
        val all = formatter.rows(HudTab.Stream, live, degraded = false, debug = true)

        assertEquals(
            listOf(
                R.string.hud_k_bitrate, R.string.hud_k_framerate, R.string.hud_k_resolution,
                R.string.hud_k_codec, R.string.hud_k_duration,
            ).map(::res),
            visible.map { it.key },
        )
        assertEquals(
            listOf(
                R.string.hud_k_bitrate, R.string.hud_k_framerate, R.string.hud_k_resolution,
                R.string.hud_k_codec, R.string.hud_k_duration, R.string.hud_k_video_frames,
                R.string.hud_k_keyframes, R.string.hud_k_audio_frames, R.string.hud_k_encoder_state,
            ).map(::res),
            all.map { it.key },
        )
        assertTrue(visible.none { it.debug })
        assertEquals(4, all.count { it.debug })
    }

    @Test
    fun streamTabValuesAndNotes() {
        val rows = formatter.rows(HudTab.Stream, live, degraded = false, debug = true)

        assertEquals(res(R.string.hud_f_mbps, "29.8"), rows[0].value)
        assertEquals(res(R.string.hud_n_target, 30), rows[0].note)
        assertEquals(HudTone.Ok, rows[0].tone)

        assertEquals(res(R.string.hud_f_fps_pair, "30", 30), rows[1].value)
        assertEquals(res(R.string.hud_n_actual_target), rows[1].note)
        assertEquals(HudTone.Neutral, rows[1].tone)

        assertEquals(res(R.string.hud_f_resolution, 3840, 2160), rows[2].value)
        assertEquals("H.264 High · CBR", rows[3].value)
        assertEquals("24:22", rows[4].value)

        assertEquals("43,920", rows[5].value)
        assertEquals("732", rows[6].value)
        assertEquals(res(R.string.hud_n_every, 2), rows[6].note)
        assertEquals("68,610", rows[7].value)
        assertEquals(res(R.string.hud_v_streaming), rows[8].value)
        assertEquals(HudTone.Ok, rows[8].tone)
    }

    @Test
    fun streamTabDegradedAndIdleValues() {
        val degraded = formatter.rows(HudTab.Stream, degradedSnapshot, degraded = true, debug = true)
        assertEquals(na, degraded[0].value)
        assertEquals(HudTone.Err, degraded[0].tone)
        assertEquals(na, degraded[1].value)
        assertEquals(HudTone.Err, degraded[1].tone)
        assertEquals(res(R.string.hud_v_stalled), degraded[8].value)
        assertEquals(HudTone.Err, degraded[8].tone)

        val idleRows = formatter.rows(HudTab.Stream, idle, degraded = false, debug = true)
        assertEquals(na, idleRows[4].value)
        assertEquals(res(R.string.hud_v_idle), idleRows[8].value)
        assertEquals(HudTone.Neutral, idleRows[8].tone)

        val disconnected = formatter.rows(
            HudTab.Stream,
            live.copy(encoderState = HudFormatter.ENCODER_DISCONNECTED),
            degraded = false,
            debug = true,
        )
        assertEquals(res(R.string.hud_v_disconnected), disconnected[8].value)
        assertEquals(HudTone.Err, disconnected[8].tone)
    }

    // ---- Camera tab -------------------------------------------------------------------

    @Test
    fun cameraTabRowOrderValuesAndNotes() {
        val rows = formatter.rows(HudTab.Camera, live, degraded = false, debug = true)

        assertEquals(
            listOf(
                R.string.hud_k_lens, R.string.hud_k_zoom, R.string.hud_k_iso, R.string.hud_k_shutter,
                R.string.hud_k_aperture, R.string.hud_k_focus, R.string.hud_k_exposure_state,
                R.string.hud_k_white_balance, R.string.hud_k_ev, R.string.hud_k_focal_length,
                R.string.hud_k_frame_timing,
            ).map(::res),
            rows.map { it.key },
        )
        assertEquals("main", rows[0].value)
        assertEquals(res(R.string.hud_n_camera_id, "0"), rows[0].note)
        assertEquals(res(R.string.hud_f_zoom, "1.0"), rows[1].value)
        assertEquals("640", rows[2].value)
        assertEquals(res(R.string.hud_f_shutter, 50L), rows[3].value)
        assertEquals(res(R.string.hud_n_exposure_time), rows[3].note)
        assertEquals(res(R.string.hud_f_aperture, "1.8"), rows[4].value)
        assertEquals(res(R.string.hud_v_af_locked), rows[5].value)
        assertEquals(res(R.string.hud_f_metres, "1.4"), rows[5].note)
        assertEquals(HudTone.Ok, rows[5].tone)
        assertEquals(res(R.string.hud_v_ae_converged), rows[6].value)
        assertEquals(HudTone.Ok, rows[6].tone)
        assertEquals(res(R.string.hud_v_awb_converged), rows[7].value)
        assertEquals(HudTone.Neutral, rows[7].tone)
        assertEquals("−0.3", rows[8].value)
        assertEquals(res(R.string.hud_f_mm_eq, 24), rows[9].value)
        assertTrue(rows[9].debug)
        assertEquals(res(R.string.hud_f_ms_avg, "33.1"), rows[10].value)
        assertEquals(res(R.string.hud_n_jitter, "1.2"), rows[10].note)
        assertTrue(rows[10].debug)

        assertEquals(9, formatter.rows(HudTab.Camera, live, degraded = false, debug = false).size)
    }

    @Test
    fun shutterFormatsFractionsAndWholeSeconds() {
        fun shutter(ns: Long?) = formatter.rows(HudTab.Camera, live.copy(exposureTimeNs = ns), false, false)[3].value

        assertEquals(res(R.string.hud_f_shutter, 50L), shutter(20_000_000L))
        assertEquals(res(R.string.hud_f_shutter, 250L), shutter(4_000_000L))
        assertEquals(res(R.string.hud_f_shutter, 8000L), shutter(125_000L))
        assertEquals(res(R.string.hud_f_seconds, "1"), shutter(1_000_000_000L))
        assertEquals(res(R.string.hud_f_seconds, "1.5"), shutter(1_500_000_000L))
        assertEquals(na, shutter(null))
        assertEquals(na, shutter(0L))
    }

    @Test
    fun focusStateAndDistanceMapping() {
        fun focus(af: Int?, diopters: Float?) =
            formatter.rows(HudTab.Camera, live.copy(afState = af, focusDistanceDiopters = diopters), false, false)[5]

        assertEquals(res(R.string.hud_v_af_locked), focus(4, 0.5f).value)
        assertEquals(res(R.string.hud_f_metres, "2.0"), focus(4, 0.5f).note)
        assertEquals(HudTone.Ok, focus(4, 0.5f).tone)

        assertEquals(res(R.string.hud_v_af_focused), focus(2, null).value)
        assertNull(focus(2, null).note)
        assertEquals(HudTone.Ok, focus(2, null).tone)

        assertEquals(res(R.string.hud_v_af_scanning), focus(1, 0f).value)
        assertEquals(res(R.string.hud_v_infinity), focus(1, 0f).note)
        assertEquals(HudTone.Neutral, focus(1, 0f).tone)
        assertEquals(res(R.string.hud_v_af_scanning), focus(3, null).value)

        assertEquals(res(R.string.hud_v_af_unfocused), focus(5, null).value)
        assertEquals(HudTone.Warn, focus(5, null).tone)
        assertEquals(res(R.string.hud_v_af_unfocused), focus(6, null).value)
        assertEquals(HudTone.Warn, focus(6, null).tone)

        assertEquals(res(R.string.hud_v_af_inactive), focus(0, null).value)
        assertEquals(HudTone.Neutral, focus(0, null).tone)

        assertEquals(na, focus(null, null).value)
        assertEquals(HudTone.Neutral, focus(null, null).tone)
    }

    @Test
    fun exposureAndWhiteBalanceStateMapping() {
        fun ae(state: Int?) = formatter.rows(HudTab.Camera, live.copy(aeState = state), false, false)[6]
        fun awb(state: Int?) = formatter.rows(HudTab.Camera, live.copy(awbState = state), false, false)[7]

        assertEquals(res(R.string.hud_v_ae_converged), ae(2).value)
        assertEquals(HudTone.Ok, ae(2).tone)
        assertEquals(res(R.string.hud_v_ae_locked), ae(3).value)
        assertEquals(HudTone.Ok, ae(3).tone)
        assertEquals(res(R.string.hud_v_ae_searching), ae(1).value)
        assertEquals(HudTone.Neutral, ae(1).tone)
        assertEquals(res(R.string.hud_v_ae_searching), ae(5).value)
        assertEquals(res(R.string.hud_v_ae_flash), ae(4).value)
        assertEquals(HudTone.Warn, ae(4).tone)
        assertEquals(res(R.string.hud_v_ae_inactive), ae(0).value)
        assertEquals(na, ae(null).value)

        assertEquals(res(R.string.hud_v_awb_converged), awb(2).value)
        assertEquals(res(R.string.hud_v_awb_locked), awb(3).value)
        assertEquals(res(R.string.hud_v_awb_searching), awb(1).value)
        assertEquals(res(R.string.hud_v_awb_inactive), awb(0).value)
        assertEquals(na, awb(null).value)
        assertTrue(listOf(2, 3, 1, 0, null).all { awb(it).tone == HudTone.Neutral })
    }

    @Test
    fun evCompensationUsesTypographicMinusAndExplicitPlus() {
        fun ev(steps: Int?, num: Int?, den: Int?) =
            formatter.rows(HudTab.Camera, live.copy(evSteps = steps, evStepNumerator = num, evStepDenominator = den), false, false)[8].value

        assertEquals("−0.3", ev(-1, 1, 3))
        assertEquals("+0.7", ev(2, 1, 3))
        assertEquals("0.0", ev(0, 1, 3))
        assertEquals("−3.0", ev(-6, 1, 2))
        assertEquals("+2.0", ev(4, 1, 2))
        assertEquals(na, ev(-1, 1, 0))
        assertEquals(na, ev(null, 1, 3))
        assertEquals(na, ev(1, null, 3))
        assertEquals(na, ev(1, 1, null))
    }

    // ---- Network tab ------------------------------------------------------------------

    @Test
    fun networkTabRowOrderValuesAndDebugFiltering() {
        val rows = formatter.rows(HudTab.Network, live, degraded = false, debug = true)

        assertEquals(
            listOf(
                R.string.hud_k_wifi, R.string.hud_k_link_quality, R.string.hud_k_srt_rtt, R.string.hud_k_packet_loss,
                R.string.hud_k_network_type, R.string.hud_k_reconnects, R.string.hud_k_connection_losses, R.string.hud_k_target,
            ).map(::res),
            rows.map { it.key },
        )
        assertEquals(res(R.string.hud_f_dbm, -52), rows[0].value)
        assertEquals(res(R.string.hud_n_wifi_excellent, "5 GHz"), rows[0].note)
        assertEquals(HudTone.Ok, rows[0].tone)
        assertEquals(res(R.string.hud_v_good), rows[1].value)
        assertEquals(HudTone.Ok, rows[1].tone)
        assertEquals(res(R.string.hud_f_ms, "8"), rows[2].value)
        assertEquals(HudTone.Neutral, rows[2].tone)
        assertEquals(res(R.string.hud_f_percent, "0.00"), rows[3].value)
        assertEquals(HudTone.Ok, rows[3].tone)
        assertEquals("Wi-Fi 6 · 5 GHz", rows[4].value)
        assertEquals("0", rows[5].value)
        assertEquals("0", rows[6].value)
        assertEquals("srt://192.168.1.5:9000", rows[7].value)
        assertTrue(rows.drop(5).all { it.debug })

        assertEquals(5, formatter.rows(HudTab.Network, live, degraded = false, debug = false).size)
    }

    @Test
    fun wifiNoteAndLinkQualityFollowRssiBands() {
        fun wifi(rssi: Int?, band: String? = "5 GHz") =
            formatter.rows(HudTab.Network, live.copy(wifiRssi = rssi, wifiBandLabel = band), false, false)

        wifi(-60).let { rows ->
            assertEquals(res(R.string.hud_n_wifi_excellent, "5 GHz"), rows[0].note)
            assertEquals(res(R.string.hud_v_good), rows[1].value)
            assertEquals(HudTone.Ok, rows[1].tone)
        }
        wifi(-65).let { rows ->
            assertEquals(res(R.string.hud_n_wifi_good, "5 GHz"), rows[0].note)
            assertEquals(HudTone.Neutral, rows[0].tone)
            assertEquals(res(R.string.hud_v_good), rows[1].value)
            assertEquals(HudTone.Neutral, rows[1].tone)
        }
        wifi(-75).let { rows ->
            assertEquals(res(R.string.hud_n_wifi_fair, "5 GHz"), rows[0].note)
            assertEquals(HudTone.Warn, rows[0].tone)
            assertEquals(res(R.string.hud_v_fair), rows[1].value)
            assertEquals(HudTone.Warn, rows[1].tone)
        }
        wifi(-85).let { rows ->
            assertEquals(res(R.string.hud_n_wifi_weak), rows[0].note)
            assertEquals(HudTone.Err, rows[0].tone)
            assertEquals(res(R.string.hud_v_poor), rows[1].value)
            assertEquals(HudTone.Err, rows[1].tone)
        }
        wifi(-52, band = null).let { rows ->
            assertNull(rows[0].note)
            assertEquals(res(R.string.hud_v_good), rows[1].value)
        }
        wifi(-85, band = null).let { rows ->
            assertEquals(res(R.string.hud_n_wifi_weak), rows[0].note)
        }
        wifi(null).let { rows ->
            assertEquals(na, rows[0].value)
            assertNull(rows[0].note)
            assertEquals(na, rows[1].value)
            assertEquals(HudTone.Neutral, rows[1].tone)
        }
    }

    @Test
    fun networkTabDegradedValues() {
        val rows = formatter.rows(HudTab.Network, degradedSnapshot, degraded = true, debug = false)
        assertEquals(na, rows[2].value)
        assertEquals(HudTone.Err, rows[2].tone)
        assertEquals(res(R.string.hud_f_percent, "12.40"), rows[3].value)
        assertEquals(HudTone.Err, rows[3].tone)

        val blank = formatter.rows(
            HudTab.Network,
            live.copy(networkTypeLabel = "", targetUrl = ""),
            degraded = false,
            debug = true,
        )
        assertEquals(na, blank[4].value)
        assertEquals(na, blank[7].value)
    }

    // ---- Device tab -------------------------------------------------------------------

    @Test
    fun deviceTabRowOrderValuesAndDebugFiltering() {
        val rows = formatter.rows(HudTab.Device, live, degraded = false, debug = true)

        assertEquals(
            listOf(
                R.string.hud_k_battery, R.string.hud_k_temperature, R.string.hud_k_thermal, R.string.hud_k_microphone,
                R.string.hud_k_audio_level, R.string.hud_k_keep_screen_on, R.string.hud_k_device,
            ).map(::res),
            rows.map { it.key },
        )
        assertEquals(res(R.string.hud_f_percent, "62"), rows[0].value)
        assertEquals(
            res(R.string.hud_n_battery_estimate, res(R.string.hud_v_discharging), 48, "4K30"),
            rows[0].note,
        )
        assertEquals(HudTone.Neutral, rows[0].tone)
        assertEquals(res(R.string.hud_f_celsius, "36.8"), rows[1].value)
        assertEquals(HudTone.Neutral, rows[1].tone)
        assertEquals("NONE", rows[2].value)
        assertNull(rows[2].note)
        assertEquals(HudTone.Ok, rows[2].tone)
        assertEquals(res(R.string.hud_v_mic_active, "48 kHz mono"), rows[3].value)
        assertEquals(HudTone.Ok, rows[3].tone)
        assertEquals(res(R.string.hud_f_dbfs, "−18"), rows[4].value)
        assertEquals(res(R.string.hud_n_peak, "−6"), rows[4].note)
        assertEquals(HudTone.Ok, rows[4].tone)
        assertEquals(res(R.string.hud_v_on), rows[5].value)
        assertEquals("Samsung SM-S916B", rows[6].value)
        assertTrue(rows[6].debug)

        assertEquals(6, formatter.rows(HudTab.Device, live, degraded = false, debug = false).size)
    }

    @Test
    fun batteryNoteReflectsChargingAndEstimateAvailability() {
        fun note(charging: Boolean, minutes: Int?) =
            formatter.rows(HudTab.Device, live.copy(batteryCharging = charging, batteryMinutesRemaining = minutes), false, false)[0].note

        assertEquals(res(R.string.hud_v_charging), note(charging = true, minutes = 120))
        assertEquals(res(R.string.hud_v_discharging), note(charging = false, minutes = null))
        assertEquals(
            res(R.string.hud_n_battery_estimate, res(R.string.hud_v_discharging), 12, "4K30"),
            note(charging = false, minutes = 12),
        )

        val lowBattery = formatter.rows(HudTab.Device, live.copy(batteryPercent = 14), false, false)[0]
        assertEquals(HudTone.Err, lowBattery.tone)
    }

    @Test
    fun thermalRowAddsSevereNoteAndTones() {
        fun thermal(status: Int?, temp: Float? = 36.8f) =
            formatter.rows(HudTab.Device, live.copy(thermalStatus = status, temperatureC = temp), false, false)

        thermal(3, 43.2f).let { rows ->
            assertEquals(res(R.string.hud_f_celsius, "43.2"), rows[1].value)
            assertEquals(HudTone.Err, rows[1].tone)
            assertEquals("SEVERE", rows[2].value)
            assertEquals(res(R.string.hud_n_thermal_severe), rows[2].note)
            assertEquals(HudTone.Err, rows[2].tone)
        }
        thermal(1).let { rows ->
            assertEquals("LIGHT", rows[2].value)
            assertNull(rows[2].note)
            assertEquals(HudTone.Warn, rows[2].tone)
        }
        thermal(null, null).let { rows ->
            assertEquals(na, rows[1].value)
            assertEquals(na, rows[2].value)
            assertEquals(HudTone.Neutral, rows[2].tone)
        }
    }

    @Test
    fun microphoneAndAudioLevelMapping() {
        fun device(mic: MicState, rms: Float?, peak: Float?) =
            formatter.rows(HudTab.Device, live.copy(micState = mic, audioRmsDbfs = rms, audioPeakDbfs = peak), false, false)

        device(MicState.Off, null, null).let { rows ->
            assertEquals(res(R.string.hud_v_mic_off), rows[3].value)
            assertEquals(HudTone.Neutral, rows[3].tone)
            assertEquals(na, rows[4].value)
            assertNull(rows[4].note)
            assertEquals(HudTone.Neutral, rows[4].tone)
        }
        device(MicState.NoPermission, null, null).let { rows ->
            assertEquals(res(R.string.hud_v_mic_no_permission), rows[3].value)
            assertEquals(HudTone.Warn, rows[3].tone)
        }
        device(MicState.Active, -12f, -2.5f).let { rows ->
            assertEquals(res(R.string.hud_f_dbfs, "−12"), rows[4].value)
            assertEquals(res(R.string.hud_n_peak, "−3"), rows[4].note)
            assertEquals(HudTone.Warn, rows[4].tone)
        }
        device(MicState.Active, 0f, null).let { rows ->
            assertEquals(res(R.string.hud_f_dbfs, "0"), rows[4].value)
            assertEquals(HudTone.Warn, rows[4].tone)
        }
        assertEquals(res(R.string.hud_v_off), formatter.rows(HudTab.Device, live.copy(keepScreenOn = false), false, false)[5].value)
    }

    // ---- Companion helpers ------------------------------------------------------------

    @Test
    fun modeLabelRecognises4KAndFallsBackToHeightP() {
        assertEquals("4K30", HudFormatter.modeLabel(3840, 2160, 30))
        assertEquals("4K60", HudFormatter.modeLabel(3840, 2160, 60))
        assertEquals("1080p30", HudFormatter.modeLabel(1920, 1080, 30))
        assertEquals("1080p60", HudFormatter.modeLabel(1920, 1080, 60))
        assertEquals("720p30", HudFormatter.modeLabel(1280, 720, 30))
        assertEquals("1080p30", HudFormatter.modeLabel(1080, 1920, 30))
        assertEquals("4K30", HudFormatter.modeLabel(2160, 3840, 30))
    }

    @Test
    fun formatDurationSwitchesToHoursAtOneHour() {
        assertEquals("00:00", HudFormatter.formatDuration(0))
        assertEquals("00:59", HudFormatter.formatDuration(59))
        assertEquals("01:00", HudFormatter.formatDuration(60))
        assertEquals("24:22", HudFormatter.formatDuration(1_462))
        assertEquals("59:59", HudFormatter.formatDuration(3_599))
        assertEquals("1:00:00", HudFormatter.formatDuration(3_600))
        assertEquals("1:01:01", HudFormatter.formatDuration(3_661))
        assertEquals("10:00:00", HudFormatter.formatDuration(36_000))
        assertEquals("00:00", HudFormatter.formatDuration(-5))
    }

    @Test
    fun thermalLabelCoversPowerManagerRangeAndUnknown() {
        assertEquals("NONE", HudFormatter.thermalLabel(0))
        assertEquals("LIGHT", HudFormatter.thermalLabel(1))
        assertEquals("MODERATE", HudFormatter.thermalLabel(2))
        assertEquals("SEVERE", HudFormatter.thermalLabel(3))
        assertEquals("CRITICAL", HudFormatter.thermalLabel(4))
        assertEquals("EMERGENCY", HudFormatter.thermalLabel(5))
        assertEquals("SHUTDOWN", HudFormatter.thermalLabel(6))
        assertEquals("—", HudFormatter.thermalLabel(7))
        assertEquals("—", HudFormatter.thermalLabel(-1))
        assertEquals("—", HudFormatter.thermalLabel(null))
    }

    @Test
    fun thermalAlertFiresAtSevereOrFortyTwoDegrees() {
        assertTrue(HudFormatter.isThermalAlert(3, null))
        assertTrue(HudFormatter.isThermalAlert(6, 20f))
        assertTrue(HudFormatter.isThermalAlert(null, 42.0f))
        assertTrue(HudFormatter.isThermalAlert(0, 45.5f))
        assertFalse(HudFormatter.isThermalAlert(2, 41.9f))
        assertFalse(HudFormatter.isThermalAlert(0, 36.8f))
        assertFalse(HudFormatter.isThermalAlert(null, null))
    }

    @Test
    fun bitrateToneBandEdges() {
        assertEquals(HudTone.Ok, HudFormatter.bitrateTone(30.0, 30, degraded = false))
        assertEquals(HudTone.Ok, HudFormatter.bitrateTone(25.5, 30, degraded = false))
        assertEquals(HudTone.Warn, HudFormatter.bitrateTone(25.49, 30, degraded = false))
        assertEquals(HudTone.Warn, HudFormatter.bitrateTone(18.0, 30, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.bitrateTone(17.99, 30, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.bitrateTone(0.0, 30, degraded = false))
        assertEquals(HudTone.Neutral, HudFormatter.bitrateTone(null, 30, degraded = false))
        assertEquals(HudTone.Neutral, HudFormatter.bitrateTone(12.0, 0, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.bitrateTone(29.8, 30, degraded = true))
        assertEquals(HudTone.Err, HudFormatter.bitrateTone(null, 30, degraded = true))
    }

    @Test
    fun fpsToneBandEdges() {
        assertEquals(HudTone.Neutral, HudFormatter.fpsTone(30.0, 30, degraded = false))
        assertEquals(HudTone.Neutral, HudFormatter.fpsTone(28.5, 30, degraded = false))
        assertEquals(HudTone.Warn, HudFormatter.fpsTone(28.49, 30, degraded = false))
        assertEquals(HudTone.Warn, HudFormatter.fpsTone(24.0, 30, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.fpsTone(23.99, 30, degraded = false))
        assertEquals(HudTone.Neutral, HudFormatter.fpsTone(null, 30, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.fpsTone(null, 30, degraded = true))
        assertEquals(HudTone.Neutral, HudFormatter.fpsTone(30.0, 0, degraded = false))
    }

    @Test
    fun rssiToneBandEdges() {
        assertEquals(HudTone.Ok, HudFormatter.rssiTone(-30))
        assertEquals(HudTone.Ok, HudFormatter.rssiTone(-60))
        assertEquals(HudTone.Neutral, HudFormatter.rssiTone(-61))
        assertEquals(HudTone.Neutral, HudFormatter.rssiTone(-70))
        assertEquals(HudTone.Warn, HudFormatter.rssiTone(-71))
        assertEquals(HudTone.Warn, HudFormatter.rssiTone(-80))
        assertEquals(HudTone.Err, HudFormatter.rssiTone(-81))
        assertEquals(HudTone.Neutral, HudFormatter.rssiTone(null))
    }

    @Test
    fun rttToneBandEdges() {
        assertEquals(HudTone.Neutral, HudFormatter.rttTone(8.0, degraded = false))
        assertEquals(HudTone.Neutral, HudFormatter.rttTone(49.9, degraded = false))
        assertEquals(HudTone.Warn, HudFormatter.rttTone(50.0, degraded = false))
        assertEquals(HudTone.Warn, HudFormatter.rttTone(150.0, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.rttTone(150.1, degraded = false))
        assertEquals(HudTone.Neutral, HudFormatter.rttTone(null, degraded = false))
        assertEquals(HudTone.Err, HudFormatter.rttTone(null, degraded = true))
    }

    @Test
    fun lossToneBandEdges() {
        assertEquals(HudTone.Ok, HudFormatter.lossTone(0.0))
        assertEquals(HudTone.Neutral, HudFormatter.lossTone(0.01))
        assertEquals(HudTone.Neutral, HudFormatter.lossTone(0.99))
        assertEquals(HudTone.Warn, HudFormatter.lossTone(1.0))
        assertEquals(HudTone.Warn, HudFormatter.lossTone(5.0))
        assertEquals(HudTone.Err, HudFormatter.lossTone(5.01))
        assertEquals(HudTone.Neutral, HudFormatter.lossTone(null))
    }

    @Test
    fun batteryToneBandEdges() {
        assertEquals(HudTone.Err, HudFormatter.batteryTone(0))
        assertEquals(HudTone.Err, HudFormatter.batteryTone(15))
        assertEquals(HudTone.Warn, HudFormatter.batteryTone(16))
        assertEquals(HudTone.Warn, HudFormatter.batteryTone(30))
        assertEquals(HudTone.Neutral, HudFormatter.batteryTone(31))
        assertEquals(HudTone.Neutral, HudFormatter.batteryTone(100))
    }

    @Test
    fun temperatureToneBandEdges() {
        assertEquals(HudTone.Neutral, HudFormatter.temperatureTone(36.8f))
        assertEquals(HudTone.Neutral, HudFormatter.temperatureTone(38.9f))
        assertEquals(HudTone.Warn, HudFormatter.temperatureTone(39.0f))
        assertEquals(HudTone.Warn, HudFormatter.temperatureTone(41.9f))
        assertEquals(HudTone.Err, HudFormatter.temperatureTone(42.0f))
        assertEquals(HudTone.Neutral, HudFormatter.temperatureTone(null))
    }

    @Test
    fun thermalAndAudioTones() {
        assertEquals(HudTone.Ok, HudFormatter.thermalTone(0))
        assertEquals(HudTone.Warn, HudFormatter.thermalTone(1))
        assertEquals(HudTone.Warn, HudFormatter.thermalTone(2))
        assertEquals(HudTone.Err, HudFormatter.thermalTone(3))
        assertEquals(HudTone.Err, HudFormatter.thermalTone(6))
        assertEquals(HudTone.Neutral, HudFormatter.thermalTone(null))

        assertEquals(HudTone.Ok, HudFormatter.audioTone(-18f, -6f))
        assertEquals(HudTone.Ok, HudFormatter.audioTone(-18f, -3f))
        assertEquals(HudTone.Warn, HudFormatter.audioTone(-18f, -2.9f))
        assertEquals(HudTone.Warn, HudFormatter.audioTone(-2f, null))
        assertEquals(HudTone.Ok, HudFormatter.audioTone(-20f, null))
        assertEquals(HudTone.Neutral, HudFormatter.audioTone(null, null))
    }
}
