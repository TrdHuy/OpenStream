package com.synclab.airlens.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlin.math.abs

data class DeviceTelemetry(
    val deviceName: String,
    val streamUrl: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val batteryPercent: Int,
    val wifiRssi: Int?,
    val temperatureCelsius: Float?,
    val thermalStatus: Int?,
    val encoderState: String,
)

/** Active data path as reported by [ConnectivityManager]. */
enum class NetworkTransport { Wifi, Cellular, Ethernet, Offline }

/**
 * Point-in-time network reading. The Wi-Fi fields are null unless [transport] is
 * [NetworkTransport.Wifi] and the platform reported a usable value.
 */
data class NetworkTelemetry(
    /** Signal strength in dBm, or null when not on Wi-Fi / not reported. */
    val wifiRssi: Int?,
    /** Channel centre frequency in MHz, or null when unknown. */
    val frequencyMhz: Int?,
    /** Raw [WifiInfo.getWifiStandard] value (API 30+), e.g. 6 = Wi-Fi 6; null when unknown. */
    val wifiStandard: Int?,
    val transport: NetworkTransport,
)

/** Point-in-time battery and thermal reading. */
data class BatteryTelemetry(
    /** 0..100; 0 when the platform exposes no usable capacity reading. */
    val percent: Int,
    /** True for `BATTERY_STATUS_CHARGING` and `BATTERY_STATUS_FULL`. */
    val charging: Boolean,
    /** Discharge estimate in minutes; only while discharging and only when plausible (5..2000). */
    val minutesRemaining: Int?,
    val temperatureC: Float?,
    /** [PowerManager.getCurrentThermalStatus] raw value. */
    val thermalStatus: Int?,
)

/**
 * Reads device-side telemetry from platform services.
 *
 * Every method is a handful of binder reads with no shared mutable state, so the sampler is
 * safe to call from any thread. The HUD collector calls [sampleNetwork] and [sampleBattery]
 * from its own background HandlerThread; nothing here may run on the UI thread by design.
 */
class TelemetrySampler(private val context: Context) {
    fun sample(
        streamUrl: String,
        codec: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        encoderState: String = "streaming",
    ): DeviceTelemetry {
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryIntent = batteryIntent()

        return DeviceTelemetry(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            streamUrl = streamUrl,
            codec = codec,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            wifiRssi = legacyConnectionInfo()?.rssi?.takeIf { it in VALID_RSSI_RANGE },
            temperatureCelsius = temperatureCelsius(batteryIntent),
            thermalStatus = thermalStatus(),
            encoderState = encoderState,
        )
    }

    /**
     * Resolves the active network's transport and, when it is Wi-Fi, the link's RSSI,
     * frequency and 802.11 generation. Wi-Fi details come from the network's transport info
     * when the platform attaches one and fall back to [WifiManager.getConnectionInfo].
     */
    fun sampleNetwork(): NetworkTelemetry {
        val connectivity: ConnectivityManager? =
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.let { manager ->
            manager.activeNetwork?.let(manager::getNetworkCapabilities)
        }
        val transport = when {
            capabilities == null -> NetworkTransport.Offline
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
            // Bluetooth/USB tethering and similar: metered data via another device, closest to cellular.
            else -> NetworkTransport.Cellular
        }
        if (transport != NetworkTransport.Wifi) {
            return NetworkTelemetry(
                wifiRssi = null,
                frequencyMhz = null,
                wifiStandard = null,
                transport = transport,
            )
        }

        val info = (capabilities?.transportInfo as? WifiInfo) ?: legacyConnectionInfo()
        return NetworkTelemetry(
            wifiRssi = info?.rssi?.takeIf { it in VALID_RSSI_RANGE },
            frequencyMhz = info?.frequency?.takeIf { it > 0 },
            wifiStandard = info?.let(::wifiStandardOrNull),
            transport = transport,
        )
    }

    /**
     * Battery level, charging state, thermal status and — while discharging — a runtime
     * estimate from the fuel gauge's charge counter and instantaneous current.
     */
    fun sampleBattery(): BatteryTelemetry {
        val manager: BatteryManager? = context.getSystemService(BatteryManager::class.java)
        val intent = batteryIntent()

        val status = intent
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?.takeIf { it != BatteryManager.BATTERY_STATUS_UNKNOWN }
            ?: manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val minutesRemaining = if (charging || manager == null) {
            null
        } else {
            estimateMinutesRemaining(
                chargeCounterMicroAh = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                currentNowMicroA = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            )
        }

        return BatteryTelemetry(
            percent = batteryPercent(manager, intent),
            charging = charging,
            minutesRemaining = minutesRemaining,
            temperatureC = temperatureCelsius(intent),
            thermalStatus = thermalStatus(),
        )
    }

    /** Sticky-broadcast read; no receiver is registered, so nothing leaks. */
    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryPercent(manager: BatteryManager?, intent: Intent?): Int {
        val fromProperty = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (fromProperty != null && fromProperty in 0..100) return fromProperty
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }

    private fun temperatureCelsius(intent: Intent?): Float? =
        intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > 0 }
            ?.div(10f)

    private fun thermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else {
            null
        }

    @Suppress("DEPRECATION")
    private fun legacyConnectionInfo(): WifiInfo? =
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo

    private fun wifiStandardOrNull(info: WifiInfo): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.wifiStandard.takeIf { it != ScanResult.WIFI_STANDARD_UNKNOWN }
        } else {
            null
        }
}

/**
 * Discharge estimate: remaining charge (µAh) over the instantaneous draw (µA), in minutes.
 *
 * Returns null unless both readings are real (non-zero and not the [BatteryManager]
 * failure sentinel `Long.MIN_VALUE`) and the result is plausible (5..2000 min). Some fuel
 * gauges report current in mA or return stale zeros; those must not reach the HUD.
 */
internal fun estimateMinutesRemaining(chargeCounterMicroAh: Long, currentNowMicroA: Long): Int? {
    if (chargeCounterMicroAh == Long.MIN_VALUE || currentNowMicroA == Long.MIN_VALUE) return null
    if (chargeCounterMicroAh <= 0L || currentNowMicroA == 0L) return null
    val minutes = chargeCounterMicroAh * 60L / abs(currentNowMicroA)
    return minutes.takeIf { it in MINUTES_REMAINING_RANGE }?.toInt()
}

private val VALID_RSSI_RANGE = -126..0
private val MINUTES_REMAINING_RANGE = 5L..2000L
