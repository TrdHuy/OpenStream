package com.synclab.airlens.ui

import com.synclab.airlens.R
import com.synclab.airlens.telemetry.HudFormatter
import com.synclab.airlens.telemetry.HudStrings
import com.synclab.airlens.telemetry.HudTab

/** Transport/pairing phase of the camera screen; drives the status pill, banner and CTA. */
enum class ConnectionPhase { Ready, Paired, Connecting, Live, Lost, Reconnecting }

/**
 * Banner shown under the status pill. [Lost] and [Reconnecting] are bound to their
 * phases; [Thermal] and [Recovered] are orthogonal and set explicitly by the activity.
 */
sealed class CameraAlert {
    data class Lost(val holdSeconds: Int) : CameraAlert()

    data class Reconnecting(val targetName: String, val attempt: Int, val delaySeconds: Int) : CameraAlert()

    data class Thermal(val temperatureC: Float?, val statusLabel: String) : CameraAlert()

    data class Recovered(val afterSeconds: Int) : CameraAlert()
}

/**
 * Everything the camera chrome needs to render, owned by MainActivity and rebuilt with
 * `copy()` on every change. Pure data — no views, no media ownership.
 */
data class CameraUiState(
    val phase: ConnectionPhase = ConnectionPhase.Ready,
    val pairedSlot: String? = null,
    val targetName: String? = null,
    val lensName: String = "Back main",
    val modeLabel: String = "1080p30",
    val liveSeconds: Long = 0,
    val lastFrameAgoSeconds: Long? = null,
    val dropsThisSession: Long = 0,
    val recovered: Boolean = false,
    val reconnectAttempt: Int = 0,
    val reconnectDelaySeconds: Int = 0,
    val holdSeconds: Int = 45,
    val discoveredCount: Int = 0,
    val alert: CameraAlert? = null,
    val thermalAlert: Boolean = false,
    val hudOn: Boolean = true,
    val hudExpanded: Boolean = false,
    val hudTab: HudTab = HudTab.Stream,
    val debug: Boolean = false,
    val toolsOpen: Boolean = false,
    val displayOff: Boolean = false,
    val torchOn: Boolean = false,
    val keepScreenOn: Boolean = false,
    val frontLens: Boolean = false,
    /**
     * Transient replacement for the status pill's detail line ("Camera preview remains
     * active", "Send issue", "Listener error · …"). Null = use the phase's own detail.
     */
    val detailOverride: String? = null,
) {
    val isLive: Boolean get() = phase == ConnectionPhase.Live

    val degraded: Boolean get() = phase == ConnectionPhase.Lost || phase == ConnectionPhase.Reconnecting

    val showSlots: Boolean get() = phase == ConnectionPhase.Ready || phase == ConnectionPhase.Paired
}

/**
 * Status pill rendering. [pulsePeriodMs] is null when the dot should not pulse;
 * colour fields are `R.color.cam_*` ids resolved by the view layer.
 */
data class StatusSpec(
    val text: String,
    val detail: String,
    val dotColorRes: Int,
    val pulsePeriodMs: Long?,
    val bgColorRes: Int,
    val borderColorRes: Int,
    val showDuration: Boolean,
)

/** Primary-action button rendering; [bgDrawableRes] is an `R.drawable.bg_cam_cta_*` id. */
data class CtaSpec(
    val label: String,
    val bgDrawableRes: Int,
    val fgColorRes: Int,
    val spinner: Boolean,
    val enabled: Boolean,
)

/** Alert banner rendering; [bgDrawableRes] is an `R.drawable.bg_cam_alert_*` id. */
data class AlertSpec(
    val title: String,
    val body: String,
    val action: String,
    val accentColorRes: Int,
    val bgDrawableRes: Int,
)

/**
 * Pure mapping from [CameraUiState] to view specs, mirroring the design prototype's
 * `status()`, `alert()` and CTA tables. Stateless and side-effect free so the activity
 * can call it on every render pass; all copy comes from [HudStrings].
 */
object CameraUiModel {

    private const val PULSE_READY_MS = 1_800L
    private const val PULSE_CONNECTING_MS = 1_000L
    private const val PULSE_LIVE_MS = 1_400L
    private const val PULSE_LOST_MS = 1_000L
    private const val PULSE_RECONNECTING_MS = 900L

    fun status(state: CameraUiState, strings: HudStrings): StatusSpec {
        val spec = phaseStatus(state, strings)
        val override = state.detailOverride ?: return spec
        return spec.copy(detail = override)
    }

    private fun phaseStatus(state: CameraUiState, strings: HudStrings): StatusSpec = when (state.phase) {
        ConnectionPhase.Ready -> StatusSpec(
            text = strings.get(R.string.cam_state_ready),
            detail = strings.get(R.string.cam_state_ready_detail),
            dotColorRes = R.color.cam_warn,
            pulsePeriodMs = PULSE_READY_MS,
            bgColorRes = R.color.cam_surface,
            borderColorRes = R.color.cam_border,
            showDuration = false,
        )

        ConnectionPhase.Paired -> StatusSpec(
            text = strings.get(R.string.cam_state_paired, slotName(state, strings)),
            detail = strings.get(R.string.cam_state_paired_detail),
            dotColorRes = R.color.cam_accent_soft,
            pulsePeriodMs = null,
            bgColorRes = R.color.cam_surface,
            borderColorRes = R.color.cam_pill_blue_border,
            showDuration = false,
        )

        ConnectionPhase.Connecting -> StatusSpec(
            text = strings.get(R.string.cam_state_connecting),
            detail = strings.get(R.string.cam_state_connecting_detail, state.modeLabel, targetName(state, strings)),
            dotColorRes = R.color.cam_accent_soft,
            pulsePeriodMs = PULSE_CONNECTING_MS,
            bgColorRes = R.color.cam_surface,
            borderColorRes = R.color.cam_pill_blue_border,
            showDuration = false,
        )

        ConnectionPhase.Live -> liveStatus(state, strings)

        ConnectionPhase.Lost -> StatusSpec(
            text = strings.get(R.string.cam_state_lost),
            detail = state.lastFrameAgoSeconds
                ?.let { strings.get(R.string.cam_state_lost_detail, it, slotName(state, strings)) }
                ?: strings.get(R.string.cam_state_lost_detail_nofix, slotName(state, strings)),
            dotColorRes = R.color.cam_live,
            pulsePeriodMs = PULSE_LOST_MS,
            bgColorRes = R.color.cam_pill_lost_bg,
            borderColorRes = R.color.cam_pill_lost_border,
            showDuration = false,
        )

        ConnectionPhase.Reconnecting -> StatusSpec(
            text = strings.get(R.string.cam_state_reconnecting),
            detail = strings.get(
                R.string.cam_state_reconnecting_detail,
                state.reconnectAttempt,
                state.reconnectDelaySeconds,
            ),
            dotColorRes = R.color.cam_warn,
            pulsePeriodMs = PULSE_RECONNECTING_MS,
            bgColorRes = R.color.cam_pill_reconnecting_bg,
            borderColorRes = R.color.cam_pill_reconnecting_border,
            showDuration = false,
        )
    }

    /**
     * Live pill. The transient `recovered` window wins over everything (green dot, green
     * border, "Recovered · N drops"); otherwise the red live dot pulses and the border goes
     * amber while a thermal alert is active.
     */
    private fun liveStatus(state: CameraUiState, strings: HudStrings): StatusSpec = if (state.recovered) {
        StatusSpec(
            text = strings.get(R.string.cam_state_live),
            detail = strings.get(R.string.cam_state_recovered_detail, state.dropsThisSession),
            dotColorRes = R.color.cam_ok,
            pulsePeriodMs = null,
            bgColorRes = R.color.cam_surface,
            borderColorRes = R.color.cam_pill_green_border,
            showDuration = true,
        )
    } else {
        StatusSpec(
            text = strings.get(R.string.cam_state_live),
            detail = strings.get(R.string.cam_state_live_detail, state.lensName, state.modeLabel),
            dotColorRes = R.color.cam_live,
            pulsePeriodMs = PULSE_LIVE_MS,
            bgColorRes = R.color.cam_surface,
            borderColorRes = if (state.thermalAlert) R.color.cam_pill_amber_border else R.color.cam_border,
            showDuration = true,
        )
    }

    /**
     * CTA table: Ready→SCANNING (ghost, spinner, disabled) · Paired→GO LIVE (blue) ·
     * Connecting→CONNECTING (blue 60 %, spinner) · Live→STOP (red) · Lost→RECONNECT (blue) ·
     * Reconnecting→CANCEL RETRY (amber, dark text, spinner).
     */
    fun cta(state: CameraUiState, strings: HudStrings): CtaSpec = when (state.phase) {
        ConnectionPhase.Ready -> CtaSpec(
            label = strings.get(R.string.cam_cta_scanning),
            bgDrawableRes = R.drawable.bg_cam_cta_ghost,
            fgColorRes = R.color.cam_text_72,
            spinner = true,
            enabled = false,
        )

        ConnectionPhase.Paired -> CtaSpec(
            label = strings.get(R.string.cam_cta_go_live),
            bgDrawableRes = R.drawable.bg_cam_cta_blue,
            fgColorRes = R.color.cam_text,
            spinner = false,
            enabled = true,
        )

        ConnectionPhase.Connecting -> CtaSpec(
            label = strings.get(R.string.cam_cta_connecting),
            bgDrawableRes = R.drawable.bg_cam_cta_blue_dim,
            fgColorRes = R.color.cam_text,
            spinner = true,
            enabled = true,
        )

        ConnectionPhase.Live -> CtaSpec(
            label = strings.get(R.string.cam_cta_stop),
            bgDrawableRes = R.drawable.bg_cam_cta_stop,
            fgColorRes = R.color.cam_text,
            spinner = false,
            enabled = true,
        )

        ConnectionPhase.Lost -> CtaSpec(
            label = strings.get(R.string.cam_cta_reconnect),
            bgDrawableRes = R.drawable.bg_cam_cta_blue,
            fgColorRes = R.color.cam_text,
            spinner = false,
            enabled = true,
        )

        ConnectionPhase.Reconnecting -> CtaSpec(
            label = strings.get(R.string.cam_cta_cancel_retry),
            bgDrawableRes = R.drawable.bg_cam_cta_amber,
            fgColorRes = R.color.cam_on_light,
            spinner = true,
            enabled = true,
        )
    }

    /**
     * Banner for the current state, or null.
     *
     * Lost/Reconnecting phases always show their connection banner (an explicit
     * [CameraAlert.Lost]/[CameraAlert.Reconnecting] in [CameraUiState.alert] supplies the
     * parameters, otherwise they come from the state fields). In every other phase the
     * explicit alert is shown, but stale connection alerts are suppressed so a
     * "Connection lost" banner can never sit under a LIVE pill.
     */
    fun alert(state: CameraUiState, strings: HudStrings): AlertSpec? {
        val alert: CameraAlert = when (state.phase) {
            ConnectionPhase.Lost ->
                state.alert as? CameraAlert.Lost ?: CameraAlert.Lost(state.holdSeconds)

            ConnectionPhase.Reconnecting ->
                state.alert as? CameraAlert.Reconnecting ?: CameraAlert.Reconnecting(
                    targetName = targetName(state, strings),
                    attempt = state.reconnectAttempt,
                    delaySeconds = state.reconnectDelaySeconds,
                )

            else -> state.alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Reconnecting }
        } ?: return null

        return when (alert) {
            is CameraAlert.Lost -> AlertSpec(
                title = strings.get(R.string.cam_alert_lost_title),
                body = strings.get(R.string.cam_alert_lost_body, alert.holdSeconds),
                action = strings.get(R.string.cam_alert_lost_action),
                accentColorRes = R.color.cam_err,
                bgDrawableRes = R.drawable.bg_cam_alert_err,
            )

            is CameraAlert.Reconnecting -> AlertSpec(
                title = strings.get(R.string.cam_alert_reconnecting_title, alert.targetName),
                body = strings.get(R.string.cam_alert_reconnecting_body, alert.attempt, alert.delaySeconds),
                action = strings.get(R.string.cam_alert_reconnecting_action),
                accentColorRes = R.color.cam_warn,
                bgDrawableRes = R.drawable.bg_cam_alert_warn,
            )

            is CameraAlert.Thermal -> AlertSpec(
                title = strings.get(R.string.cam_alert_thermal_title, thermalHeadline(alert, strings)),
                body = strings.get(R.string.cam_alert_thermal_body, alert.statusLabel),
                action = strings.get(R.string.cam_alert_thermal_action),
                accentColorRes = R.color.cam_warn,
                bgDrawableRes = R.drawable.bg_cam_alert_warn,
            )

            is CameraAlert.Recovered -> AlertSpec(
                title = strings.get(R.string.cam_alert_recovered_title),
                body = strings.get(R.string.cam_alert_recovered_body, alert.afterSeconds),
                action = strings.get(R.string.cam_alert_recovered_action),
                accentColorRes = R.color.cam_ok,
                bgDrawableRes = R.drawable.bg_cam_alert_ok,
            )
        }
    }

    /** Number of Tools tiles currently active: torch + keep-screen-on (display-off is momentary). */
    fun toolsCount(state: CameraUiState): Int = (if (state.torchOn) 1 else 0) + (if (state.keepScreenOn) 1 else 0)

    /** "43.2 °C" when the temperature is known, otherwise the thermal status label ("SEVERE"). */
    private fun thermalHeadline(alert: CameraAlert.Thermal, strings: HudStrings): String =
        alert.temperatureC
            ?.let { strings.get(R.string.hud_f_celsius, HudFormatter.decimal(it.toDouble(), 1)) }
            ?: alert.statusLabel

    /** Paired OBS slot label, falling back to the caller-mode target and then "—". */
    private fun slotName(state: CameraUiState, strings: HudStrings): String =
        state.pairedSlot ?: state.targetName ?: strings.get(R.string.hud_v_na)

    /** Caller-mode target label, falling back to the paired slot and then "—". */
    private fun targetName(state: CameraUiState, strings: HudStrings): String =
        state.targetName ?: state.pairedSlot ?: strings.get(R.string.hud_v_na)
}
