package dev.openstream.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.VideoBitrateMode
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamPreset
import dev.openstream.app.stream.StreamProfile
import dev.openstream.app.stream.StreamProfileStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import dev.openstream.app.stream.SupportedStreamMode
import java.util.UUID

class SettingsActivity : Activity() {

    private lateinit var profileSpinner: Spinner
    private lateinit var profileName: EditText
    private lateinit var btnNewProfile: TextView
    private lateinit var btnUseProfile: TextView
    private lateinit var btnSaveProfile: TextView
    private lateinit var btnDeleteProfile: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var presetNote: TextView
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
    private var profiles: List<StreamProfile> = emptyList()
    private var selectedProfileId: String? = null
    private var presetOptions: List<PresetOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        profileSpinner = findViewById(R.id.settingsProfile)
        profileName = findViewById(R.id.settingsProfileName)
        btnNewProfile = findViewById(R.id.btnNewProfile)
        btnUseProfile = findViewById(R.id.btnUseProfile)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile)
        presetSpinner = findViewById(R.id.settingsPreset)
        presetNote = findViewById(R.id.settingsPresetNote)
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

        profileName.hint = "Tên profile (chỉ cần khi muốn lưu profile)"
        btnUseProfile.text = "Áp dụng"
        btnSaveProfile.text = "Lưu profile"

        val config = loadSettings()
        setupEncodingSelectors(config)
        setupCapabilitySelectors(config)
        setupProfiles()
        showVersionInfo()

        btnNewProfile.setOnClickListener { beginNewProfile() }
        btnUseProfile.setOnClickListener { useSelectedProfile() }
        btnSaveProfile.setOnClickListener { saveCurrentProfile() }
        btnDeleteProfile.setOnClickListener { deleteSelectedProfile() }
        btnSave.setOnClickListener { saveSettings(connectAfterSave = false) }
        btnSaveAndConnect.setOnClickListener { saveSettings(connectAfterSave = true) }
        btnBack.setOnClickListener { finish() }

        setupHelpIcons()
    }

    private fun setupHelpIcons() {
        findAndSetupHelpIcon(R.id.helpCapabilityMode, "Chế độ Video", R.string.help_capability_mode)
        findAndSetupHelpIcon(R.id.helpVideoBitrate, "Bitrate Video", R.string.help_video_bitrate)
        findAndSetupHelpIcon(R.id.helpBitrateMode, "Chế độ Bitrate", R.string.help_bitrate_mode)
        findAndSetupHelpIcon(R.id.helpH264Profile, "Profile H.264", R.string.help_h264_profile)
        findAndSetupHelpIcon(R.id.helpKeyframeInterval, "Chu kỳ Keyframe", R.string.help_keyframe_interval)
        findAndSetupHelpIcon(R.id.helpBFrames, "B-frame", R.string.help_bframes)
        findAndSetupHelpIcon(R.id.helpAudioSampleRate, "Sample Rate", R.string.help_audio_sample_rate)
        findAndSetupHelpIcon(R.id.helpAudioChannels, "Audio Channels", R.string.help_audio_channels)
        findAndSetupHelpIcon(R.id.helpAudioBitrate, "Bitrate Âm", R.string.help_audio_bitrate)
        findAndSetupHelpIcon(R.id.helpObsHost, "OBS Host", R.string.help_obs_host)
        findAndSetupHelpIcon(R.id.helpObsPort, "OBS Port", R.string.help_obs_port)
        findAndSetupHelpIcon(R.id.helpLatency, "SRT Latency", R.string.help_latency)
        findAndSetupHelpIcon(R.id.helpListeningPort, "Listening Port", R.string.help_listening_port)
    }

    private fun findAndSetupHelpIcon(iconId: Int, title: String, messageResId: Int) {
        try {
            val helpButton = findViewById<ImageButton>(iconId)
            helpButton?.setOnClickListener {
                showHelpDialog(title, getString(messageResId))
            }
        } catch (e: Exception) {
            // Icon not found in this view hierarchy, skip silently
        }
    }

    private fun showHelpDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun loadSettings(): StreamConfig {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val config = StreamConfigStore.load(this)
        applyConfigToInputs(config)

        inputObsHost.setText(prefs.getString(KEY_OBS_HOST, ""))
        val port = prefs.getInt(KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
        if (port != ConnectionTarget.DEFAULT_PORT) inputObsPort.setText(port.toString())
        inputLatency.setText(config.latencyMs.toString())
        val listenPort = prefs.getInt(KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
        inputListeningPort.setText(listenPort.toString())
        return config
    }

    private fun applyConfigToInputs(config: StreamConfig) {
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
    }

    private fun setupEncodingSelectors(config: StreamConfig) {
        inputVideoBitrateMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VideoBitrateMode.entries.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        inputVideoBitrateMode.setSelection(VideoBitrateMode.entries.indexOf(config.videoBitrateMode).coerceAtLeast(0))

        inputAvcProfile.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            AvcProfilePreference.entries.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        inputAvcProfile.setSelection(AvcProfilePreference.entries.indexOf(config.avcProfilePreference).coerceAtLeast(0))
    }

    private fun setupCapabilitySelectors(config: StreamConfig, preferredLens: CameraLens? = null) {
        val resolver = StreamingCapabilityResolver(this)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedLens = prefs.getString(KEY_CAPABILITY_LENS, null)
            ?.let { name -> runCatching { CameraLens.valueOf(name) }.getOrNull() }

        var capabilityReadError: String? = null
        supportedModes = runCatching {
            resolver.resolve(config)
        }.getOrElse { error ->
            capabilityReadError = error.message ?: "lỗi không xác định"
            emptyList()
        }

        val usingRecoveryModes = supportedModes.isEmpty()
        if (usingRecoveryModes) {
            val recoveryConfig = config.copy(
                bitrate = StreamConfig.Baseline1080p30.bitrate,
                videoBitrateMode = VideoBitrateMode.Cbr,
                avcProfilePreference = AvcProfilePreference.Auto,
            )
            supportedModes = runCatching { resolver.resolve(recoveryConfig) }.getOrDefault(emptyList())
        }

        // Không khóa nút lưu chỉ vì cấu hình cũ đang unsupported. Người dùng phải
        // luôn có đường sửa cấu hình rồi lưu lại; readValidatedSettings() sẽ kiểm
        // chính cấu hình mới tại thời điểm bấm Lưu/Lưu & kết nối.
        btnSave.isEnabled = true
        btnSaveAndConnect.isEnabled = true

        if (supportedModes.isEmpty()) {
            selectedCapabilityLens = preferredLens ?: savedLens ?: CameraLens.Back
            capabilityLens.isEnabled = false
            capabilityMode.isEnabled = false
            presetSpinner.isEnabled = false
            capabilityNote.text = if (capabilityReadError != null) {
                "Không đọc được khả năng camera/bộ mã hóa: $capabilityReadError. Anh vẫn có thể sửa cấu hình; app sẽ kiểm tra lại khi bấm Lưu."
            } else {
                "Cấu hình hiện tại chưa được Camera2 + H.264 phần cứng xác nhận. Anh vẫn có thể sửa bitrate/chế độ/profile rồi bấm Lưu cấu hình để kiểm tra lại."
            }
            presetNote.text = "Preset tạm thời chưa khả dụng; profile không bắt buộc để sửa hoặc lưu cấu hình."
            return
        }

        capabilityLens.isEnabled = true
        capabilityMode.isEnabled = true
        presetSpinner.isEnabled = true
        capabilityLenses = supportedModes.map { it.lens }.distinct()
        capabilityLens.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            capabilityLenses.map { it.displayName },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val matchingLens = capabilityLenses.firstOrNull { it == preferredLens }
            ?: capabilityLenses.firstOrNull { lens ->
                lens == savedLens || supportedModes.any {
                    it.lens == lens && it.width == config.width && it.height == config.height && it.fps == config.fps
                }
            }
            ?: capabilityLenses.first()
        val initialLensIndex = capabilityLenses.indexOf(matchingLens).coerceAtLeast(0)

        capabilityLens.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lens = capabilityLenses.getOrNull(position) ?: return
                selectedCapabilityLens = lens
                updateModeSpinner(lens, config)
                updatePresetOptions(lens)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        capabilityLens.setSelection(initialLensIndex)
        selectedCapabilityLens = matchingLens
        updateModeSpinner(matchingLens, config)
        updatePresetOptions(matchingLens)

        if (usingRecoveryModes) {
            capabilityNote.text = "Cấu hình cũ chưa được xác nhận. App đã mở các mode an toàn để anh chọn lại; chưa có gì được ghi cho tới khi bấm Lưu cấu hình."
        }
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

    private fun updatePresetOptions(lens: CameraLens) {
        val base = configFromInputs()
        val resolver = StreamingCapabilityResolver(this)
        presetOptions = StreamPreset.entries.map { preset ->
            val candidate = preset.applyTo(base)
            val mode = runCatching {
                resolver.resolve(candidate).firstOrNull {
                    it.lens == lens &&
                        it.width == preset.width &&
                        it.height == preset.height &&
                        it.fps == preset.fps
                }
            }.getOrNull()
            PresetOption(
                preset = preset,
                mode = mode,
                reason = if (mode == null) {
                    "${preset.displayName} không được Camera2 + H.264 phần cứng xác nhận ở ${preset.bitrateMbps} Mbps trên ${lens.displayName}."
                } else {
                    null
                },
            )
        }

        presetSpinner.onItemSelectedListener = null
        presetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            presetOptions.map { option ->
                if (option.mode != null) "✓ ${option.preset.displayName}" else "— ${option.preset.displayName} · Không hỗ trợ"
            },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val selectedIndex = presetOptions.indexOfFirst { it.preset.matches(base) }.takeIf { it >= 0 } ?: 0
        presetSpinner.setSelection(selectedIndex)
        showPresetNote(presetOptions.getOrNull(selectedIndex))
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val option = presetOptions.getOrNull(position) ?: return
                showPresetNote(option)
                if (option.mode != null) applyPreset(option, lens)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showPresetNote(option: PresetOption?) {
        if (option == null) return
        val mode = option.mode
        presetNote.text = if (mode == null) {
            option.reason
        } else {
            buildString {
                append("${option.preset.displayName}: ${option.preset.bitrateMbps} Mbps · keyframe ${option.preset.keyframeIntervalSeconds}s")
                mode.maxHardwareBitrate?.let { append(" · codec max ~${it / 1_000_000} Mbps") }
            }
        }
    }

    private fun applyPreset(option: PresetOption, lens: CameraLens) {
        val mode = option.mode ?: return
        val candidate = option.preset.applyTo(configFromInputs())
        supportedModes = runCatching { StreamingCapabilityResolver(this).resolve(candidate) }.getOrDefault(supportedModes)
        inputWidth.setText(candidate.width.toString())
        inputHeight.setText(candidate.height.toString())
        inputFps.setText(candidate.fps.toString())
        inputBitrateMbps.setText(option.preset.bitrateMbps.toString())
        inputKeyframeInterval.setText(option.preset.keyframeIntervalSeconds.toString())
        updateModeSpinner(lens, candidate)
        inputBitrateMbps.setText(option.preset.bitrateMbps.toString())
        inputKeyframeInterval.setText(option.preset.keyframeIntervalSeconds.toString())
        applyCapabilityMode(mode)
        inputBitrateMbps.setText(option.preset.bitrateMbps.toString())
        inputKeyframeInterval.setText(option.preset.keyframeIntervalSeconds.toString())
    }

    private fun setupProfiles(preferredId: String? = null) {
        profiles = StreamProfileStore.list(this)
        val activeId = StreamProfileStore.active(this)?.id
        profileSpinner.onItemSelectedListener = null
        profileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            if (profiles.isEmpty()) {
                listOf("Không có profile · vẫn sửa config bình thường")
            } else {
                profiles.map { profile -> if (profile.id == activeId) "★ ${profile.name}" else profile.name }
            },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val targetId = preferredId ?: activeId ?: profiles.firstOrNull()?.id
        val selectedIndex = profiles.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
        val hasProfiles = profiles.isNotEmpty()
        profileSpinner.isEnabled = hasProfiles
        btnUseProfile.isEnabled = hasProfiles
        btnDeleteProfile.isEnabled = hasProfiles
        if (hasProfiles) {
            profileSpinner.setSelection(selectedIndex)
            val selected = profiles[selectedIndex]
            selectedProfileId = selected.id
            profileName.setText(selected.name)
        } else {
            selectedProfileId = null
            profileName.setText("")
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val profile = profiles.getOrNull(position) ?: return
                selectedProfileId = profile.id
                profileName.setText(profile.name)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun beginNewProfile() {
        selectedProfileId = null
        profileName.setText("")
        profileName.requestFocus()
        Toast.makeText(this, "Profile là tùy chọn. Nhập tên rồi bấm Lưu profile nếu muốn lưu riêng cấu hình này.", Toast.LENGTH_LONG).show()
    }

    private fun saveCurrentProfile() {
        clearValidationErrors()
        profileName.error = null
        val name = profileName.text.toString().trim()
        if (!SettingsValidator.isValidProfileName(name)) {
            profileName.error = "Tên profile phải có 1–40 ký tự"
            profileName.requestFocus()
            Toast.makeText(this, "Chưa lưu profile: hãy nhập tên profile hợp lệ.", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = readValidatedSettings(requireHost = false)
        if (pending == null) {
            Toast.makeText(this, "Chưa lưu profile: hãy sửa mục đang báo lỗi.", Toast.LENGTH_SHORT).show()
            return
        }
        persistCurrent(pending)
        val id = selectedProfileId ?: UUID.randomUUID().toString()
        StreamProfileStore.save(
            this,
            StreamProfile(
                id = id,
                name = name,
                config = pending.config,
                lens = pending.lens,
                obsHost = pending.host,
                obsPort = pending.port,
                listeningPort = pending.listenPort,
            ),
            makeActive = true,
        )
        selectedProfileId = id
        setupProfiles(id)
        Toast.makeText(this, "Đã lưu profile $name", Toast.LENGTH_SHORT).show()
    }

    private fun useSelectedProfile() {
        val profile = profiles.firstOrNull { it.id == selectedProfileId }
        if (profile == null) {
            Toast.makeText(this, "Chưa có profile để áp dụng.", Toast.LENGTH_SHORT).show()
            return
        }
        StreamProfileStore.setActive(this, profile.id)
        StreamConfigStore.save(this, profile.config)
        StreamConfig.installRuntimeConfig(profile.config)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, profile.obsHost)
            .putInt(KEY_OBS_PORT, profile.obsPort)
            .putInt(KEY_LISTENING_PORT, profile.listeningPort)
            .putString(KEY_CAPABILITY_LENS, profile.lens?.name)
            .apply()
        applyProfileToInputs(profile)
        setupProfiles(profile.id)
        Toast.makeText(this, "Đang dùng profile ${profile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun applyProfileToInputs(profile: StreamProfile) {
        applyConfigToInputs(profile.config)
        inputObsHost.setText(profile.obsHost)
        inputObsPort.setText(profile.obsPort.toString())
        inputLatency.setText(profile.config.latencyMs.toString())
        inputListeningPort.setText(profile.listeningPort.toString())
        setupEncodingSelectors(profile.config)
        setupCapabilitySelectors(profile.config, profile.lens)
    }

    private fun deleteSelectedProfile() {
        val profile = profiles.firstOrNull { it.id == selectedProfileId }
        if (profile == null) {
            Toast.makeText(this, "Chưa có profile để xóa.", Toast.LENGTH_SHORT).show()
            return
        }
        StreamProfileStore.delete(this, profile.id)
        selectedProfileId = null
        setupProfiles()
        Toast.makeText(this, "Đã xóa profile ${profile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun saveSettings(connectAfterSave: Boolean) {
        clearValidationErrors()
        val pending = readValidatedSettings(requireHost = connectAfterSave)
        if (pending == null) {
            Toast.makeText(this, "Chưa lưu cấu hình: hãy sửa mục đang báo lỗi.", Toast.LENGTH_SHORT).show()
            return
        }
        persistCurrent(pending)
        Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
        restartMainActivity(connectAfterSave, pending.host, pending.port, pending.config.latencyMs)
    }

    private fun readValidatedSettings(requireHost: Boolean): PendingSettings? {
        val current = StreamConfigStore.load(this)
        val width = validatedNumber(inputWidth, current.width, StreamConfigStore.MIN_WIDTH..StreamConfigStore.MAX_WIDTH, "Chiều rộng") ?: return null
        val height = validatedNumber(inputHeight, current.height, StreamConfigStore.MIN_HEIGHT..StreamConfigStore.MAX_HEIGHT, "Chiều cao") ?: return null
        val fps = validatedNumber(inputFps, current.fps, StreamConfigStore.MIN_FPS..StreamConfigStore.MAX_FPS, "Số hình/giây") ?: return null
        val bitrateMbps = validatedNumber(
            inputBitrateMbps,
            current.bitrateMbps,
            StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS..StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS,
            "Tốc độ bit",
        ) ?: return null
        val keyframeInterval = validatedNumber(
            inputKeyframeInterval,
            current.keyframeIntervalSeconds,
            StreamConfigStore.MIN_KEYFRAME_INTERVAL..StreamConfigStore.MAX_KEYFRAME_INTERVAL,
            "Chu kỳ khung hình khóa",
        ) ?: return null
        val audioSampleRate = validatedNumber(
            inputAudioSampleRate,
            current.audioSampleRate,
            StreamConfigStore.MIN_AUDIO_SAMPLE_RATE..StreamConfigStore.MAX_AUDIO_SAMPLE_RATE,
            "Tần số lấy mẫu âm thanh",
        ) ?: return null
        val audioChannels = validatedNumber(
            inputAudioChannels,
            current.audioChannelCount,
            StreamConfigStore.MIN_AUDIO_CHANNELS..StreamConfigStore.MAX_AUDIO_CHANNELS,
            "Số kênh âm thanh",
        ) ?: return null
        val audioBitrateKbps = validatedNumber(
            inputAudioBitrateKbps,
            current.audioBitrateKbps,
            StreamConfigStore.MIN_AUDIO_BITRATE_KBPS..StreamConfigStore.MAX_AUDIO_BITRATE_KBPS,
            "Tốc độ bit âm thanh",
        ) ?: return null
        val bitrateMode = VideoBitrateMode.entries[
            inputVideoBitrateMode.selectedItemPosition.coerceIn(0, VideoBitrateMode.entries.lastIndex)
        ]
        val avcProfile = AvcProfilePreference.entries[
            inputAvcProfile.selectedItemPosition.coerceIn(0, AvcProfilePreference.entries.lastIndex)
        ]

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
            inputBitrateMbps.requestFocus()
            return null
        }

        val host = inputObsHost.text.toString().trim()
        if (!SettingsValidator.isValidHost(host, required = requireHost)) {
            inputObsHost.error = "Nhập tên máy hoặc địa chỉ IP hợp lệ"
            inputObsHost.requestFocus()
            return null
        }
        val port = validatedNumber(inputObsPort, ConnectionTarget.DEFAULT_PORT, 1..65535, "Cổng OBS") ?: return null
        val latency = validatedNumber(
            inputLatency,
            current.latencyMs,
            StreamConfigStore.MIN_LATENCY_MS..StreamConfigStore.MAX_LATENCY_MS,
            "Độ trễ",
        ) ?: return null
        val listenPort = validatedNumber(
            inputListeningPort,
            ConnectionTarget.DEFAULT_PORT,
            1024..65535,
            "Cổng lắng nghe",
        ) ?: return null

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
        return PendingSettings(config, lens, host, port, listenPort)
    }

    private fun persistCurrent(pending: PendingSettings) {
        StreamConfigStore.save(this, pending.config)
        StreamConfig.installRuntimeConfig(pending.config)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, pending.host)
            .putInt(KEY_OBS_PORT, pending.port)
            .putInt(KEY_LISTENING_PORT, pending.listenPort)
            .putString(KEY_CAPABILITY_LENS, pending.lens?.name)
            .apply()
    }

    private fun configFromInputs(): StreamConfig {
        val current = StreamConfigStore.load(this)
        val bitrateMode = VideoBitrateMode.entries.getOrNull(inputVideoBitrateMode.selectedItemPosition)
            ?: current.videoBitrateMode
        val profile = AvcProfilePreference.entries.getOrNull(inputAvcProfile.selectedItemPosition)
            ?: current.avcProfilePreference
        return current.copy(
            width = inputWidth.text.toString().toIntOrNull() ?: current.width,
            height = inputHeight.text.toString().toIntOrNull() ?: current.height,
            fps = inputFps.text.toString().toIntOrNull() ?: current.fps,
            bitrate = (inputBitrateMbps.text.toString().toIntOrNull() ?: current.bitrateMbps) * 1_000_000,
            keyframeIntervalSeconds = inputKeyframeInterval.text.toString().toIntOrNull()
                ?: current.keyframeIntervalSeconds,
            latencyMs = inputLatency.text.toString().toIntOrNull() ?: current.latencyMs,
            videoBitrateMode = bitrateMode,
            avcProfilePreference = profile,
            bFramesEnabled = inputBFrames.isChecked,
            audioEnabled = inputAudioEnabled.isChecked,
            audioSampleRate = inputAudioSampleRate.text.toString().toIntOrNull() ?: current.audioSampleRate,
            audioChannelCount = inputAudioChannels.text.toString().toIntOrNull() ?: current.audioChannelCount,
            audioBitrate = (inputAudioBitrateKbps.text.toString().toIntOrNull() ?: current.audioBitrateKbps) * 1_000,
        )
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

    private data class PendingSettings(
        val config: StreamConfig,
        val lens: CameraLens?,
        val host: String,
        val port: Int,
        val listenPort: Int,
    )

    private data class PresetOption(
        val preset: StreamPreset,
        val mode: SupportedStreamMode?,
        val reason: String?,
    )

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
