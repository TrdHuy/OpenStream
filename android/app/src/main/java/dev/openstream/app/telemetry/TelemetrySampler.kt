package dev.openstream.app.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

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
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val rawTemperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = rawTemperature
            ?.takeIf { it != Int.MIN_VALUE && it > 0 }
            ?.div(10f)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val wifiRssi = wifi?.connectionInfo?.rssi?.takeIf { it in -126..0 }

        return DeviceTelemetry(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            streamUrl = streamUrl,
            codec = codec,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            wifiRssi = wifiRssi,
            temperatureCelsius = temperature,
            thermalStatus = thermalStatus,
            encoderState = encoderState,
        )
    }
}
