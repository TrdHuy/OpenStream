package dev.openstream.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore

class SettingsActivity : Activity() {

    private lateinit var inputWidth: EditText
    private lateinit var inputHeight: EditText
    private lateinit var inputFps: EditText
    private lateinit var inputBitrateMbps: EditText
    private lateinit var inputKeyframeInterval: EditText
    private lateinit var inputAudioEnabled: CheckBox
    private lateinit var inputAudioSampleRate: EditText
    private lateinit var inputAudioChannels: EditText
    private lateinit var inputAudioBitrateKbps: EditText
    private lateinit var inputObsHost: EditText
    private lateinit var inputObsPort: EditText
    private lateinit var inputLatency: EditText
    private lateinit var inputListeningPort: EditText
    private lateinit var btnSave: TextView
    private lateinit var btnSaveAndConnect: TextView
    private lateinit var btnBack: TextView
    private lateinit var versionInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        inputWidth = findViewById(R.id.settingsWidth)
        inputHeight = findViewById(R.id.settingsHeight)
        inputFps = findViewById(R.id.settingsFps)
        inputBitrateMbps = findViewById(R.id.settingsBitrateMbps)
        inputKeyframeInterval = findViewById(R.id.settingsKeyframeInterval)
        inputAudioEnabled = findViewById(R.id.settingsAudioEnabled)
        inputAudioSampleRate = findViewById(R.id.settingsAudioSampleRate)
        inputAudioChannels = findViewById(R.id.settingsAudioChannels)
        inputAudioBitrateKbps = findViewById(R.id.settingsAudioBitrateKbps)
        inputObsHost = findViewById(R.id.settingsObsHost)
        inputObsPort = findViewById(R.id.settingsObsPort)
        inputLatency = findViewById(R.id.settingsLatency)
        inputListeningPort = findViewById(R.id.settingsListeningPort)
        btnSave = findViewById(R.id.btnSaveSettings)
        btnSaveAndConnect = findViewById(R.id.btnSaveAndConnect)
        btnBack = findViewById(R.id.btnBackSettings)
        versionInfo = findViewById(R.id.settingsVersionInfo)

        loadSettings()
        showVersionInfo()

        btnSave.setOnClickListener { saveSettings(connectAfterSave = false) }
        btnSaveAndConnect.setOnClickListener { saveSettings(connectAfterSave = true) }
        btnBack.setOnClickListener { finish() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val config = StreamConfigStore.load(this)

        inputWidth.setText(config.width.toString())
        inputHeight.setText(config.height.toString())
        inputFps.setText(config.fps.toString())
        inputBitrateMbps.setText(config.bitrateMbps.toString())
        inputKeyframeInterval.setText(config.keyframeIntervalSeconds.toString())
        inputAudioEnabled.isChecked = config.audioEnabled
        inputAudioSampleRate.setText(config.audioSampleRate.toString())
        inputAudioChannels.setText(config.audioChannelCount.toString())
        inputAudioBitrateKbps.setText(config.audioBitrateKbps.toString())

        inputObsHost.setText(prefs.getString(KEY_OBS_HOST, ""))
        val port = prefs.getInt(KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
        if (port != ConnectionTarget.DEFAULT_PORT) inputObsPort.setText(port.toString())
        inputLatency.setText(config.latencyMs.toString())
        val listenPort = prefs.getInt(KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
        inputListeningPort.setText(listenPort.toString())
    }

    private fun saveSettings(connectAfterSave: Boolean) {
        clearValidationErrors()

        val current = StreamConfigStore.load(this)
        val width = validatedNumber(inputWidth, current.width, StreamConfigStore.MIN_WIDTH..StreamConfigStore.MAX_WIDTH, "Chiều rộng") ?: return
        val height = validatedNumber(inputHeight, current.height, StreamConfigStore.MIN_HEIGHT..StreamConfigStore.MAX_HEIGHT, "Chiều cao") ?: return
        val fps = validatedNumber(inputFps, current.fps, StreamConfigStore.MIN_FPS..StreamConfigStore.MAX_FPS, "Số hình/giây") ?: return
        val bitrateMbps = validatedNumber(inputBitrateMbps, current.bitrateMbps, StreamConfig.MIN_BITRATE_MBPS..StreamConfig.MAX_BITRATE_MBPS, "Tốc độ bit") ?: return
        val keyframeInterval = validatedNumber(inputKeyframeInterval, current.keyframeIntervalSeconds, StreamConfigStore.MIN_KEYFRAME_INTERVAL..StreamConfigStore.MAX_KEYFRAME_INTERVAL, "Chu kỳ khung hình khóa") ?: return
        val audioSampleRate = validatedNumber(inputAudioSampleRate, current.audioSampleRate, StreamConfigStore.MIN_AUDIO_SAMPLE_RATE..StreamConfigStore.MAX_AUDIO_SAMPLE_RATE, "Tần số lấy mẫu âm thanh") ?: return
        val audioChannels = validatedNumber(inputAudioChannels, current.audioChannelCount, StreamConfigStore.MIN_AUDIO_CHANNELS..StreamConfigStore.MAX_AUDIO_CHANNELS, "Số kênh âm thanh") ?: return
        val audioBitrateKbps = validatedNumber(inputAudioBitrateKbps, current.audioBitrateKbps, StreamConfigStore.MIN_AUDIO_BITRATE_KBPS..StreamConfigStore.MAX_AUDIO_BITRATE_KBPS, "Tốc độ bit âm thanh") ?: return

        val host = inputObsHost.text.toString().trim()
        if (!SettingsValidator.isValidHost(host, required = connectAfterSave)) {
            inputObsHost.error = "Nhập tên máy hoặc địa chỉ IP hợp lệ"
            inputObsHost.requestFocus()
            return
        }
        val port = validatedNumber(inputObsPort, ConnectionTarget.DEFAULT_PORT, 1..65535, "Cổng OBS") ?: return
        val latency = validatedNumber(inputLatency, current.latencyMs, StreamConfigStore.MIN_LATENCY_MS..StreamConfigStore.MAX_LATENCY_MS, "Độ trễ") ?: return
        val listenPort = validatedNumber(inputListeningPort, ConnectionTarget.DEFAULT_PORT, 1024..65535, "Cổng lắng nghe") ?: return

        val config = current.copy(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrateMbps * 1_000_000,
            keyframeIntervalSeconds = keyframeInterval,
            latencyMs = latency,
            audioEnabled = inputAudioEnabled.isChecked,
            audioSampleRate = audioSampleRate,
            audioChannelCount = audioChannels,
            audioBitrate = audioBitrateKbps * 1_000,
        )
        StreamConfigStore.save(this, config)
        StreamConfig.installRuntimeConfig(config)

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, host)
            .putInt(KEY_OBS_PORT, port)
            .putInt(KEY_LISTENING_PORT, listenPort)
            .apply()

        Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
        restartMainActivity(connectAfterSave, host, port, latency)
    }

    private fun restartMainActivity(connectAfterSave: Boolean, host: String, port: Int, latency: Int) {
        val restart = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (connectAfterSave) {
                data = Uri.Builder()
                    .scheme("openstream")
                    .authority("connect")
                    .appendQueryParameter("host", host)
                    .appendQueryParameter("port", port.toString())
                    .appendQueryParameter("latency", latency.toString())
                    .appendQueryParameter("name", ConnectionTarget.DEFAULT_NAME)
                    .build()
            }
        }
        startActivity(restart)
        finish()
    }

    private fun validatedNumber(input: EditText, defaultValue: Int, validRange: IntRange, label: String): Int? {
        val raw = input.text.toString().trim()
        if (raw.isBlank()) return defaultValue
        val value = SettingsValidator.parseNumber(raw, defaultValue, validRange)
        if (value == null) {
            input.error = "$label phải nằm trong khoảng ${validRange.first}–${validRange.last}"
            input.requestFocus()
            return null
        }
        return value
    }

    private fun clearValidationErrors() {
        listOf(
            inputWidth,
            inputHeight,
            inputFps,
            inputBitrateMbps,
            inputKeyframeInterval,
            inputAudioSampleRate,
            inputAudioChannels,
            inputAudioBitrateKbps,
            inputObsHost,
            inputObsPort,
            inputLatency,
            inputListeningPort,
        ).forEach { it.error = null }
    }

    private fun showVersionInfo() {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versionInfo.text = "OpenStream v${info.versionName} (${code})"
        }
    }

    companion object {
        const val PREFS_NAME = StreamConfigStore.PREFS_NAME
        const val KEY_OBS_HOST = "obs_host"
        const val KEY_OBS_PORT = "obs_port"
        const val KEY_LATENCY = StreamConfigStore.KEY_LATENCY
        const val KEY_LISTENING_PORT = "listening_port"
        const val EXTRA_CONNECT_AFTER_SAVE = "connect_after_save"
    }
}
