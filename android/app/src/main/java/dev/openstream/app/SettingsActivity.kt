package dev.openstream.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.VideoBitrateMode
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import dev.openstream.app.stream.SupportedStreamMode

class SettingsActivity : Activity() {

    private lateinit var capabilityLens: Spinner
    private lateinit var capabilityMode: Spinner
    private lateinit var capabilityNote: TextView
    private lateinit var inputWidth: EditText
    private lateinit var inputHeight: EditText
    private lateinit var inputFps: EditText
    private lateinit var inputBitrateMbps: EditText
    private lateinit var inputVideoBitrateMode: Spinner
    private lateinit var inputAvcProfile: Spinner
    private lateinit var inputBFrames: CheckBox
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

    private var supportedModes: List<SupportedStreamMode> = emptyList()
    private var visibleModes: List<SupportedStreamMode> = emptyList()
    private var capabilityLenses: List<CameraLens> = emptyList()
    private var selectedCapabilityLens: CameraLens? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        capabilityLens = findViewById(R.id.settingsCapabilityLens)
        capabilityMode = findViewById(R.id.settingsCapabilityMode)
        capabilityNote = findViewById(R.id.settingsCapabilityNote)
        inputWidth = findViewById(R.id.settingsWidth)
        inputHeight = findViewById(R.id.settingsHeight)
        inputFps = findViewById(R.id.settingsFps)
        inputBitrateMbps = findViewById(R.id.settingsBitrateMbps)
        inputVideoBitrateMode = findViewById(R.id.settingsVideoBitrateMode)
        inputAvcProfile = findViewById(R.id.settingsAvcProfile)
        inputBFrames = findViewById(R.id.settingsBFrames)
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

        val config = loadSettings()
        setupEncodingSelectors(config)
        setupCapabilitySelectors(config)
        showVersionInfo()

        btnSave.setOnClickListener { saveSettings(connectAfterSave = false) }
        btnSaveAndConnect.setOnClickListener { saveSettings(connectAfterSave = true) }
        btnBack.setOnClickListener { finish() }
    }

    private fun loadSettings(): StreamConfig {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val config = StreamConfigStore.load(this)

        inputWidth.setText(config.width.toString())
        inputHeight.setText(config.height.toString())
        inputFps.setText(config.fps.toString())
        inputBitrateMbps.setText(config.bitrateMbps.toString())
        inputKeyframeInterval.setText(config.keyframeIntervalSeconds.toString())
        inputBFrames.isChecked = config.bFramesEnabled
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
        return config
    }

    private fun setupEncodingSelectors(config: StreamConfig) {
        inputVideoBitrateMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VideoBitrateMode.entries.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        inputVideoBitrateMode.setSelection(VideoBitrateMode.entries.indexOf(config.videoBitrateMode))

        inputAvcProfile.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            AvcProfilePreference.entries.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        inputAvcProfile.setSelection(AvcProfilePreference.entries.indexOf(config.avcProfilePreference))
    }

    private fun setupCapabilitySelectors(config: StreamConfig) {
        supportedModes = runCatching {
            StreamingCapabilityResolver(this).resolve(config)
        }.getOrElse { error ->
            capabilityNote.text = "Không đọc được khả năng camera/bộ mã hóa: ${error.message ?: "lỗi không xác định"}"
            emptyList()
        }

        if (supportedModes.isEmpty()) {
            capabilityLens.isEnabled = false
            capabilityMode.isEnabled = false
            btnSave.isEnabled = false
            btnSaveAndConnect.isEnabled = false
            capabilityNote.text = "Không tìm thấy tổ hợp Camera2 + H.264 phần cứng hợp lệ ở cấu hình hiện tại."
            return
        }

        capabilityLenses = supportedModes.map { it.lens }.distinct()
        capabilityLens.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            capabilityLenses.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedLens = prefs.getString(KEY_CAPABILITY_LENS, null)
            ?.let { name -> runCatching { CameraLens.valueOf(name) }.getOrNull() }
        val matchingLens = capabilityLenses.firstOrNull { lens ->
            lens == savedLens || supportedModes.any {
                it.lens == lens && it.width == config.width && it.height == config.height && it.fps == config.fps
            }
        } ?: capabilityLenses.first()
        val initialLensIndex = capabilityLenses.indexOf(matchingLens).coerceAtLeast(0)

        capabilityLens.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lens = capabilityLenses.getOrNull(position) ?: return
                selectedCapabilityLens = lens
                updateModeSpinner(lens, config)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        capabilityLens.setSelection(initialLensIndex)
        selectedCapabilityLens = matchingLens
        updateModeSpinner(matchingLens, config)
    }

    private fun updateModeSpinner(lens: CameraLens, preferred: StreamConfig) {
        visibleModes = supportedModes.filter { it.lens == lens }
        capabilityMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            visibleModes.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        capabilityMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                visibleModes.getOrNull(position)?.let(::applyCapabilityMode)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val preferredIndex = visibleModes.indexOfFirst {
            it.width == preferred.width && it.height == preferred.height && it.fps == preferred.fps
        }.takeIf { it >= 0 } ?: 0
        capabilityMode.setSelection(preferredIndex)
        visibleModes.getOrNull(preferredIndex)?.let(::applyCapabilityMode)
    }

    private fun applyCapabilityMode(mode: SupportedStreamMode) {
        inputWidth.setText(mode.width.toString())
        inputHeight.setText(mode.height.toString())
        inputFps.setText(mode.fps.toString())

        val is4k = mode.width >= 3840 && mode.height >= 2160
        if (is4k && mode.fps == 30) {
            val bitrate = inputBitrateMbps.text.toString().toIntOrNull()
            if (bitrate == null || bitrate !in 20..40) inputBitrateMbps.setText("30")
            val keyframe = inputKeyframeInterval.text.toString().toIntOrNull()
            if (keyframe == null || keyframe == 1) inputKeyframeInterval.setText("2")
        } else if (is4k && mode.fps >= 60) {
            val maxMbps = (mode.maxHardwareBitrate ?: 0) / 1_000_000
            val currentMbps = inputBitrateMbps.text.toString().toIntOrNull() ?: 0
            if (currentMbps < 35 && maxMbps >= 35) inputBitrateMbps.setText("35")
            if (inputKeyframeInterval.text.toString().toIntOrNull() == 1) inputKeyframeInterval.setText("2")
        }

        capabilityNote.text = buildString {
            append("Camera ID ${mode.cameraId} · H.264 phần cứng")
            if (mode.highProfileAvailable) append(" · High khả dụng")
            mode.maxHardwareBitrate?.let { append(" · tối đa codec ~${it / 1_000_000} Mbps") }
            if (is4k && mode.fps >= 60) append(" · 4K60 được capability cho phép")
        }
    }

    private fun saveSettings(connectAfterSave: Boolean) {
        clearValidationErrors()

        val current = StreamConfigStore.load(this)
        val width = validatedNumber(inputWidth, current.width, StreamConfigStore.MIN_WIDTH..StreamConfigStore.MAX_WIDTH, "Chiều rộng") ?: return
        val height = validatedNumber(inputHeight, current.height, StreamConfigStore.MIN_HEIGHT..StreamConfigStore.MAX_HEIGHT, "Chiều cao") ?: return
        val fps = validatedNumber(inputFps, current.fps, StreamConfigStore.MIN_FPS..StreamConfigStore.MAX_FPS, "Số hình/giây") ?: return
        val bitrateMbps = validatedNumber(
            inputBitrateMbps,
            current.bitrateMbps,
            StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS..StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS,
            "Tốc độ bit",
        ) ?: return
        val keyframeInterval = validatedNumber(inputKeyframeInterval, current.keyframeIntervalSeconds, StreamConfigStore.MIN_KEYFRAME_INTERVAL..StreamConfigStore.MAX_KEYFRAME_INTERVAL, "Chu kỳ khung hình khóa") ?: return
        val audioSampleRate = validatedNumber(inputAudioSampleRate, current.audioSampleRate, StreamConfigStore.MIN_AUDIO_SAMPLE_RATE..StreamConfigStore.MAX_AUDIO_SAMPLE_RATE, "Tần số lấy mẫu âm thanh") ?: return
        val audioChannels = validatedNumber(inputAudioChannels, current.audioChannelCount, StreamConfigStore.MIN_AUDIO_CHANNELS..StreamConfigStore.MAX_AUDIO_CHANNELS, "Số kênh âm thanh") ?: return
        val audioBitrateKbps = validatedNumber(inputAudioBitrateKbps, current.audioBitrateKbps, StreamConfigStore.MIN_AUDIO_BITRATE_KBPS..StreamConfigStore.MAX_AUDIO_BITRATE_KBPS, "Tốc độ bit âm thanh") ?: return
        val bitrateMode = VideoBitrateMode.entries[inputVideoBitrateMode.selectedItemPosition.coerceIn(0, VideoBitrateMode.entries.lastIndex)]
        val avcProfile = AvcProfilePreference.entries[inputAvcProfile.selectedItemPosition.coerceIn(0, AvcProfilePreference.entries.lastIndex)]

        val lens = selectedCapabilityLens
        val validAtRequestedSettings = lens != null && runCatching {
            StreamingCapabilityResolver(this).resolve(
                bitrate = bitrateMbps * 1_000_000,
                bitrateMode = bitrateMode,
                profilePreference = avcProfile,
            ).any {
                it.lens == lens && it.width == width && it.height == height && it.fps == fps
            }
        }.getOrDefault(false)
        if (!validAtRequestedSettings) {
            inputBitrateMbps.error = "Tổ hợp ống kính/độ phân giải/FPS/bitrate/profile này không được codec phần cứng hỗ trợ"
            return
        }

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
            videoBitrateMode = bitrateMode,
            avcProfilePreference = avcProfile,
            bFramesEnabled = inputBFrames.isChecked,
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
            .putString(KEY_CAPABILITY_LENS, lens?.name)
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
        const val KEY_CAPABILITY_LENS = "capability_lens"
        const val EXTRA_CONNECT_AFTER_SAVE = "connect_after_save"
    }
}
