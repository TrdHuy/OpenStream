package com.synclab.airlens.telemetry

import com.synclab.airlens.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure helpers of HudTelemetryCollector / TelemetrySampler that need no Android runtime. */
class HudTelemetryLabelsTest {
    private val strings = FakeHudStrings()

    @Test
    fun wifiBandLabelMapsChannelFrequencyToBand() {
        assertNull(wifiBandLabel(null, strings))
        assertNull(wifiBandLabel(0, strings))
        assertEquals(res(R.string.hud_f_band_ghz, "2.4"), wifiBandLabel(2_437, strings))
        assertEquals(res(R.string.hud_f_band_ghz, "5"), wifiBandLabel(5_180, strings))
        assertEquals(res(R.string.hud_f_band_ghz, "6"), wifiBandLabel(5_955, strings))
    }

    @Test
    fun networkTypeLabelIncludesGenerationAndBandForWifi() {
        val wifi6 = NetworkTelemetry(wifiRssi = -52, frequencyMhz = 5_180, wifiStandard = 6, transport = NetworkTransport.Wifi)
        assertEquals(
            res(R.string.hud_f_network_wifi, "6") + " · " + res(R.string.hud_f_band_ghz, "5"),
            networkTypeLabel(wifi6, strings),
        )

        val unknownGeneration = wifi6.copy(wifiStandard = null, frequencyMhz = null)
        assertEquals(res(R.string.hud_f_network_wifi, ""), networkTypeLabel(unknownGeneration, strings))
    }

    @Test
    fun networkTypeLabelForNonWifiTransports() {
        val base = NetworkTelemetry(wifiRssi = null, frequencyMhz = null, wifiStandard = null, transport = NetworkTransport.Cellular)
        assertEquals(res(R.string.hud_v_cellular), networkTypeLabel(base, strings))
        assertEquals(res(R.string.hud_v_ethernet), networkTypeLabel(base.copy(transport = NetworkTransport.Ethernet), strings))
        assertEquals(res(R.string.hud_v_offline), networkTypeLabel(base.copy(transport = NetworkTransport.Offline), strings))
    }

    @Test
    fun estimateMinutesRemainingUsesChargeOverCurrent() {
        // 3,000 mAh at 1,000 mA discharge → 180 min.
        assertEquals(180, estimateMinutesRemaining(3_000_000L, -1_000_000L))
        assertEquals(180, estimateMinutesRemaining(3_000_000L, 1_000_000L))
    }

    @Test
    fun estimateMinutesRemainingRejectsUnknownOrImplausibleReadings() {
        assertNull(estimateMinutesRemaining(Long.MIN_VALUE, -500_000L))
        assertNull(estimateMinutesRemaining(3_000_000L, Long.MIN_VALUE))
        assertNull(estimateMinutesRemaining(0L, -500_000L))
        assertNull(estimateMinutesRemaining(3_000_000L, 0L))
        // Fuel gauge reporting mA instead of µA → absurd estimate → null.
        assertNull(estimateMinutesRemaining(3_000_000L, -500L))
        // Under 5 minutes is treated as noise.
        assertNull(estimateMinutesRemaining(10_000L, -1_000_000L))
    }
}
