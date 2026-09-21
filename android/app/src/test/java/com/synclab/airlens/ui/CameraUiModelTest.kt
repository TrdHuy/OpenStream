package com.synclab.airlens.ui

import com.synclab.airlens.R
import com.synclab.airlens.telemetry.FakeHudStrings
import com.synclab.airlens.telemetry.HudTab
import com.synclab.airlens.telemetry.res
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiModelTest {
    private val strings = FakeHudStrings()

    private val paired = CameraUiState(
        phase = ConnectionPhase.Paired,
        pairedSlot = "Stage left",
        targetName = "studio-pc.local",
        lensName = "Back main",
        modeLabel = "4K30",
    )

    private fun inPhase(phase: ConnectionPhase) = paired.copy(phase = phase)

    // ---- Status pill ------------------------------------------------------------------

    @Test
    fun readyStatusSearchesWithAmberPulse() {
        val spec = CameraUiModel.status(CameraUiState(), strings)

        assertEquals(res(R.string.cam_state_ready), spec.text)
        assertEquals(res(R.string.cam_state_ready_detail), spec.detail)
        assertEquals(R.color.cam_warn, spec.dotColorRes)
        assertEquals(1_800L, spec.pulsePeriodMs)
        assertEquals(R.color.cam_surface, spec.bgColorRes)
        assertEquals(R.color.cam_border, spec.borderColorRes)
        assertFalse(spec.showDuration)
    }

    @Test
    fun pairedStatusNamesTheSlotWithSteadyBlueDot() {
        val spec = CameraUiModel.status(paired, strings)

        assertEquals(res(R.string.cam_state_paired, "Stage left"), spec.text)
        assertEquals(res(R.string.cam_state_paired_detail), spec.detail)
        assertEquals(R.color.cam_accent_soft, spec.dotColorRes)
        assertNull(spec.pulsePeriodMs)
        assertEquals(R.color.cam_surface, spec.bgColorRes)
        assertEquals(R.color.cam_pill_blue_border, spec.borderColorRes)
        assertFalse(spec.showDuration)
    }

    @Test
    fun pairedStatusFallsBackToTargetThenDashWhenSlotUnknown() {
        assertEquals(
            res(R.string.cam_state_paired, "studio-pc.local"),
            CameraUiModel.status(paired.copy(pairedSlot = null), strings).text,
        )
        assertEquals(
            res(R.string.cam_state_paired, res(R.string.hud_v_na)),
            CameraUiModel.status(paired.copy(pairedSlot = null, targetName = null), strings).text,
        )
    }

    @Test
    fun connectingStatusShowsModeArrowTarget() {
        val spec = CameraUiModel.status(inPhase(ConnectionPhase.Connecting), strings)

        assertEquals(res(R.string.cam_state_connecting), spec.text)
        assertEquals(res(R.string.cam_state_connecting_detail, "4K30", "studio-pc.local"), spec.detail)
        assertEquals(R.color.cam_accent_soft, spec.dotColorRes)
        assertEquals(1_000L, spec.pulsePeriodMs)
        assertEquals(R.color.cam_pill_blue_border, spec.borderColorRes)
        assertFalse(spec.showDuration)

        val noTarget = CameraUiModel.status(inPhase(ConnectionPhase.Connecting).copy(targetName = null), strings)
        assertEquals(res(R.string.cam_state_connecting_detail, "4K30", "Stage left"), noTarget.detail)
    }

    @Test
    fun liveStatusShowsLensAndModeWithRedPulseAndDuration() {
        val spec = CameraUiModel.status(inPhase(ConnectionPhase.Live), strings)

        assertEquals(res(R.string.cam_state_live), spec.text)
        assertEquals(res(R.string.cam_state_live_detail, "Back main", "4K30"), spec.detail)
        assertEquals(R.color.cam_live, spec.dotColorRes)
        assertEquals(1_400L, spec.pulsePeriodMs)
        assertEquals(R.color.cam_surface, spec.bgColorRes)
        assertEquals(R.color.cam_border, spec.borderColorRes)
        assertTrue(spec.showDuration)
    }

    @Test
    fun liveStatusBorderTurnsAmberUnderThermalAlert() {
        val spec = CameraUiModel.status(inPhase(ConnectionPhase.Live).copy(thermalAlert = true), strings)

        assertEquals(R.color.cam_pill_amber_border, spec.borderColorRes)
        assertEquals(R.color.cam_live, spec.dotColorRes)
        assertEquals(res(R.string.cam_state_live_detail, "Back main", "4K30"), spec.detail)
    }

    @Test
    fun recoveredLiveStatusIsGreenWithDropCount() {
        val spec = CameraUiModel.status(
            inPhase(ConnectionPhase.Live).copy(recovered = true, dropsThisSession = 1, thermalAlert = true),
            strings,
        )

        assertEquals(res(R.string.cam_state_live), spec.text)
        assertEquals(res(R.string.cam_state_recovered_detail, 1L), spec.detail)
        assertEquals(R.color.cam_ok, spec.dotColorRes)
        assertNull(spec.pulsePeriodMs)
        assertEquals(R.color.cam_pill_green_border, spec.borderColorRes)
        assertTrue(spec.showDuration)
    }

    @Test
    fun lostStatusIsRedTintedWithLastFrameAge() {
        val spec = CameraUiModel.status(inPhase(ConnectionPhase.Lost).copy(lastFrameAgoSeconds = 4), strings)

        assertEquals(res(R.string.cam_state_lost), spec.text)
        assertEquals(res(R.string.cam_state_lost_detail, 4L, "Stage left"), spec.detail)
        assertEquals(R.color.cam_live, spec.dotColorRes)
        assertEquals(1_000L, spec.pulsePeriodMs)
        assertEquals(R.color.cam_pill_lost_bg, spec.bgColorRes)
        assertEquals(R.color.cam_pill_lost_border, spec.borderColorRes)
        assertFalse(spec.showDuration)

        val noFix = CameraUiModel.status(inPhase(ConnectionPhase.Lost).copy(lastFrameAgoSeconds = null), strings)
        assertEquals(res(R.string.cam_state_lost_detail_nofix, "Stage left"), noFix.detail)
    }

    @Test
    fun reconnectingStatusIsAmberTintedWithAttemptAndDelay() {
        val spec = CameraUiModel.status(
            inPhase(ConnectionPhase.Reconnecting).copy(reconnectAttempt = 3, reconnectDelaySeconds = 3),
            strings,
        )

        assertEquals(res(R.string.cam_state_reconnecting), spec.text)
        assertEquals(res(R.string.cam_state_reconnecting_detail, 3, 3), spec.detail)
        assertEquals(R.color.cam_warn, spec.dotColorRes)
        assertEquals(900L, spec.pulsePeriodMs)
        assertEquals(R.color.cam_pill_reconnecting_bg, spec.bgColorRes)
        assertEquals(R.color.cam_pill_reconnecting_border, spec.borderColorRes)
        assertFalse(spec.showDuration)
    }

    @Test
    fun durationSegmentShowsOnlyWhileLive() {
        for (phase in ConnectionPhase.entries) {
            val spec = CameraUiModel.status(inPhase(phase), strings)
            assertEquals("showDuration for $phase", phase == ConnectionPhase.Live, spec.showDuration)
        }
    }

    // ---- CTA --------------------------------------------------------------------------

    @Test
    fun ctaFollowsPhaseTable() {
        CameraUiModel.cta(inPhase(ConnectionPhase.Ready), strings).let {
            assertEquals(res(R.string.cam_cta_scanning), it.label)
            assertEquals(R.drawable.bg_cam_cta_ghost, it.bgDrawableRes)
            assertEquals(R.color.cam_text_72, it.fgColorRes)
            assertTrue(it.spinner)
            assertFalse(it.enabled)
        }
        CameraUiModel.cta(inPhase(ConnectionPhase.Paired), strings).let {
            assertEquals(res(R.string.cam_cta_go_live), it.label)
            assertEquals(R.drawable.bg_cam_cta_blue, it.bgDrawableRes)
            assertEquals(R.color.cam_text, it.fgColorRes)
            assertFalse(it.spinner)
            assertTrue(it.enabled)
        }
        CameraUiModel.cta(inPhase(ConnectionPhase.Connecting), strings).let {
            assertEquals(res(R.string.cam_cta_connecting), it.label)
            assertEquals(R.drawable.bg_cam_cta_blue_dim, it.bgDrawableRes)
            assertEquals(R.color.cam_text, it.fgColorRes)
            assertTrue(it.spinner)
            assertTrue(it.enabled)
        }
        CameraUiModel.cta(inPhase(ConnectionPhase.Live), strings).let {
            assertEquals(res(R.string.cam_cta_stop), it.label)
            assertEquals(R.drawable.bg_cam_cta_stop, it.bgDrawableRes)
            assertEquals(R.color.cam_text, it.fgColorRes)
            assertFalse(it.spinner)
            assertTrue(it.enabled)
        }
        CameraUiModel.cta(inPhase(ConnectionPhase.Lost), strings).let {
            assertEquals(res(R.string.cam_cta_reconnect), it.label)
            assertEquals(R.drawable.bg_cam_cta_blue, it.bgDrawableRes)
            assertEquals(R.color.cam_text, it.fgColorRes)
            assertFalse(it.spinner)
            assertTrue(it.enabled)
        }
        CameraUiModel.cta(inPhase(ConnectionPhase.Reconnecting), strings).let {
            assertEquals(res(R.string.cam_cta_cancel_retry), it.label)
            assertEquals(R.drawable.bg_cam_cta_amber, it.bgDrawableRes)
            assertEquals(R.color.cam_on_light, it.fgColorRes)
            assertTrue(it.spinner)
            assertTrue(it.enabled)
        }
    }

    @Test
    fun onlyScanningCtaIsDisabled() {
        for (phase in ConnectionPhase.entries) {
            assertEquals("enabled for $phase", phase != ConnectionPhase.Ready, CameraUiModel.cta(inPhase(phase), strings).enabled)
        }
    }

    // ---- Alerts -----------------------------------------------------------------------

    @Test
    fun noBannerInReadyPairedConnectingOrPlainLive() {
        for (phase in listOf(ConnectionPhase.Ready, ConnectionPhase.Paired, ConnectionPhase.Connecting, ConnectionPhase.Live)) {
            assertNull("alert for $phase", CameraUiModel.alert(inPhase(phase), strings))
        }
    }

    @Test
    fun lostPhaseDerivesLostBannerFromHoldSeconds() {
        val spec = CameraUiModel.alert(inPhase(ConnectionPhase.Lost).copy(holdSeconds = 45), strings)

        assertNotNull(spec)
        assertEquals(res(R.string.cam_alert_lost_title), spec!!.title)
        assertEquals(res(R.string.cam_alert_lost_body, 45), spec.body)
        assertEquals(res(R.string.cam_alert_lost_action), spec.action)
        assertEquals(R.color.cam_err, spec.accentColorRes)
        assertEquals(R.drawable.bg_cam_alert_err, spec.bgDrawableRes)
    }

    @Test
    fun explicitLostAlertSuppliesItsOwnHoldSeconds() {
        val spec = CameraUiModel.alert(
            inPhase(ConnectionPhase.Lost).copy(holdSeconds = 45, alert = CameraAlert.Lost(holdSeconds = 12)),
            strings,
        )
        assertEquals(res(R.string.cam_alert_lost_body, 12), spec!!.body)
    }

    @Test
    fun reconnectingPhaseDerivesBannerFromStateFields() {
        val spec = CameraUiModel.alert(
            inPhase(ConnectionPhase.Reconnecting).copy(reconnectAttempt = 3, reconnectDelaySeconds = 3),
            strings,
        )

        assertNotNull(spec)
        assertEquals(res(R.string.cam_alert_reconnecting_title, "studio-pc.local"), spec!!.title)
        assertEquals(res(R.string.cam_alert_reconnecting_body, 3, 3), spec.body)
        assertEquals(res(R.string.cam_alert_reconnecting_action), spec.action)
        assertEquals(R.color.cam_warn, spec.accentColorRes)
        assertEquals(R.drawable.bg_cam_alert_warn, spec.bgDrawableRes)
    }

    @Test
    fun explicitReconnectingAlertOverridesStateFields() {
        val spec = CameraUiModel.alert(
            inPhase(ConnectionPhase.Reconnecting).copy(
                reconnectAttempt = 1,
                reconnectDelaySeconds = 1,
                alert = CameraAlert.Reconnecting(targetName = "Overhead", attempt = 7, delaySeconds = 30),
            ),
            strings,
        )
        assertEquals(res(R.string.cam_alert_reconnecting_title, "Overhead"), spec!!.title)
        assertEquals(res(R.string.cam_alert_reconnecting_body, 7, 30), spec.body)
    }

    @Test
    fun thermalAlertHeadlinesTemperatureAndBodyStatus() {
        val spec = CameraUiModel.alert(
            inPhase(ConnectionPhase.Live).copy(thermalAlert = true, alert = CameraAlert.Thermal(43.2f, "SEVERE")),
            strings,
        )

        assertNotNull(spec)
        assertEquals(res(R.string.cam_alert_thermal_title, res(R.string.hud_f_celsius, "43.2")), spec!!.title)
        assertEquals(res(R.string.cam_alert_thermal_body, "SEVERE"), spec.body)
        assertEquals(res(R.string.cam_alert_thermal_action), spec.action)
        assertEquals(R.color.cam_warn, spec.accentColorRes)
        assertEquals(R.drawable.bg_cam_alert_warn, spec.bgDrawableRes)
    }

    @Test
    fun thermalAlertWithoutTemperatureHeadlinesStatusLabel() {
        val spec = CameraUiModel.alert(
            inPhase(ConnectionPhase.Ready).copy(thermalAlert = true, alert = CameraAlert.Thermal(null, "CRITICAL")),
            strings,
        )
        assertEquals(res(R.string.cam_alert_thermal_title, "CRITICAL"), spec!!.title)
    }

    @Test
    fun recoveredAlertIsGreenWithReconnectDelay() {
        val spec = CameraUiModel.alert(
            inPhase(ConnectionPhase.Live).copy(recovered = true, alert = CameraAlert.Recovered(afterSeconds = 6)),
            strings,
        )

        assertNotNull(spec)
        assertEquals(res(R.string.cam_alert_recovered_title), spec!!.title)
        assertEquals(res(R.string.cam_alert_recovered_body, 6), spec.body)
        assertEquals(res(R.string.cam_alert_recovered_action), spec.action)
        assertEquals(R.color.cam_ok, spec.accentColorRes)
        assertEquals(R.drawable.bg_cam_alert_ok, spec.bgDrawableRes)
    }

    @Test
    fun staleConnectionAlertsAreSuppressedOutsideDegradedPhases() {
        assertNull(CameraUiModel.alert(inPhase(ConnectionPhase.Live).copy(alert = CameraAlert.Lost(45)), strings))
        assertNull(
            CameraUiModel.alert(
                inPhase(ConnectionPhase.Ready).copy(alert = CameraAlert.Reconnecting("x", 1, 1)),
                strings,
            ),
        )
    }

    @Test
    fun degradedPhasesShowConnectionBannerAheadOfThermalOrRecovered() {
        val lost = CameraUiModel.alert(
            inPhase(ConnectionPhase.Lost).copy(thermalAlert = true, alert = CameraAlert.Thermal(43.2f, "SEVERE")),
            strings,
        )
        assertEquals(res(R.string.cam_alert_lost_title), lost!!.title)

        val reconnecting = CameraUiModel.alert(
            inPhase(ConnectionPhase.Reconnecting).copy(alert = CameraAlert.Recovered(6)),
            strings,
        )
        assertEquals(res(R.string.cam_alert_reconnecting_title, "studio-pc.local"), reconnecting!!.title)
    }

    // ---- Tools + derived flags --------------------------------------------------------

    @Test
    fun toolsCountSumsTorchAndKeepScreenOn() {
        assertEquals(0, CameraUiModel.toolsCount(CameraUiState()))
        assertEquals(1, CameraUiModel.toolsCount(CameraUiState(torchOn = true)))
        assertEquals(1, CameraUiModel.toolsCount(CameraUiState(keepScreenOn = true)))
        assertEquals(2, CameraUiModel.toolsCount(CameraUiState(torchOn = true, keepScreenOn = true)))
        assertEquals(0, CameraUiModel.toolsCount(CameraUiState(displayOff = true, frontLens = true, toolsOpen = true)))
    }

    @Test
    fun derivedFlagsFollowPhase() {
        assertTrue(inPhase(ConnectionPhase.Live).isLive)
        assertTrue(ConnectionPhase.entries.filter { it != ConnectionPhase.Live }.none { inPhase(it).isLive })

        assertTrue(inPhase(ConnectionPhase.Lost).degraded)
        assertTrue(inPhase(ConnectionPhase.Reconnecting).degraded)
        assertFalse(inPhase(ConnectionPhase.Live).degraded)
        assertFalse(inPhase(ConnectionPhase.Ready).degraded)

        assertTrue(inPhase(ConnectionPhase.Ready).showSlots)
        assertTrue(inPhase(ConnectionPhase.Paired).showSlots)
        assertFalse(inPhase(ConnectionPhase.Connecting).showSlots)
        assertFalse(inPhase(ConnectionPhase.Live).showSlots)
        assertFalse(inPhase(ConnectionPhase.Lost).showSlots)
    }

    @Test
    fun defaultStateMatchesContract() {
        val state = CameraUiState()
        assertEquals(ConnectionPhase.Ready, state.phase)
        assertEquals("Back main", state.lensName)
        assertEquals("1080p30", state.modeLabel)
        assertEquals(45, state.holdSeconds)
        assertTrue(state.hudOn)
        assertFalse(state.hudExpanded)
        assertEquals(HudTab.Stream, state.hudTab)
        assertFalse(state.debug)
        assertFalse(state.toolsOpen)
        assertFalse(state.displayOff)
    }
}
