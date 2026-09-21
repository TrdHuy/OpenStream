package com.synclab.airlens

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.synclab.airlens.camera.Camera2Controller
import com.synclab.airlens.camera.CameraLens
import com.synclab.airlens.control.CameraControlServer
import com.synclab.airlens.discovery.DiscoveredObsDevice
import com.synclab.airlens.discovery.ObsDiscoveryClient
import com.synclab.airlens.discovery.PhoneDiscoveryAdvertiser
import com.synclab.airlens.encoder.MediaCodecAudioEncoder
import com.synclab.airlens.encoder.MediaCodecVideoEncoder
import com.synclab.airlens.stream.ConnectionTarget
import com.synclab.airlens.stream.StreamConfig
import com.synclab.airlens.telemetry.CollectorInputs
import com.synclab.airlens.telemetry.HudFormatter
import com.synclab.airlens.telemetry.HudSnapshot
import com.synclab.airlens.telemetry.HudStrings
import com.synclab.airlens.telemetry.HudTab
import com.synclab.airlens.telemetry.HudTelemetryCollector
import com.synclab.airlens.telemetry.MicState
import com.synclab.airlens.telemetry.StreamContext
import com.synclab.airlens.ui.AlertSpec
import com.synclab.airlens.ui.CameraAlert
import com.synclab.airlens.ui.CameraHudController
import com.synclab.airlens.ui.CameraUiModel
import com.synclab.airlens.ui.CameraUiState
import com.synclab.airlens.ui.ConnectionPhase
import com.synclab.airlens.ui.CtaSpec
import com.synclab.airlens.ui.StatusSpec
import java.util.Locale
import kotlin.math.ceil

class MainActivity : Activity() {

    // ── Views ──
    private lateinit var root: FrameLayout
    private lateinit var cameraPreview: SurfaceView
    private lateinit var previewContainer: FrameLayout
    private lateinit var topLayer: LinearLayout
    private lateinit var bottomLayer: LinearLayout
    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusState: TextView
    private lateinit var statusDetail: TextView
    private lateinit var liveBadge: View
    private lateinit var statusDuration: TextView
    private lateinit var alertBanner: LinearLayout
    private lateinit var alertIcon: TextView
    private lateinit var alertTitle: TextView
    private lateinit var alertBody: TextView
    private lateinit var alertAction: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var slotsPanel: LinearLayout
    private lateinit var slotScanLabel: TextView
    private lateinit var obsSlotList: LinearLayout
    private lateinit var lensSelectorRow: LinearLayout
    private lateinit var toolsExpanded: LinearLayout
    private lateinit var btnTorch: LinearLayout
    private lateinit var torchLabel: TextView
    private lateinit var torchSub: TextView
    private lateinit var btnFlipCamera: LinearLayout
    private lateinit var flipLabel: TextView
    private lateinit var flipSub: TextView
    private lateinit var btnKeepScreenOn: LinearLayout
    private lateinit var stayLabel: TextView
    private lateinit var staySub: TextView
    private lateinit var btnScreenOff: LinearLayout
    private lateinit var displayLabel: TextView
    private lateinit var displaySub: TextView
    private lateinit var btnTools: LinearLayout
    private lateinit var toolsLabel: TextView
    private lateinit var toolsCounter: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnCta: LinearLayout
    private lateinit var ctaSpinner: ProgressBar
    private lateinit var ctaLabel: TextView
    private lateinit var bottomHint: TextView
    private lateinit var hudSheet: View
    private lateinit var identifyOverlay: View
    private lateinit var identifyLabel: TextView
    private lateinit var identifySubtitle: TextView
    private lateinit var screenOffOverlay: View
    private lateinit var displayOffDot: View
    private lateinit var displayOffStatus: TextView

    // ── Core components ──
    private lateinit var camera: Camera2Controller
    private lateinit var encoder: MediaCodecVideoEncoder
    private lateinit var audioEncoder: MediaCodecAudioEncoder
    private lateinit var streamClient: com.synclab.airlens.stream.SrtStreamClient
    private lateinit var phoneAdvertiser: PhoneDiscoveryAdvertiser
    private lateinit var obsDiscoveryClient: ObsDiscoveryClient
    private lateinit var controlServer: CameraControlServer
    private lateinit var hud: CameraHudController
    private lateinit var hudCollector: HudTelemetryCollector

    private val streamConfig = StreamConfig.Default1080p30
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeTargetName: String? = null
    @Volatile private var phoneServerRunning = false
    @Volatile private var phoneConnected = false
    @Volatile private var reservedBy: String? = null
    @Volatile private var reservedSlotLabel: String? = null
    @Volatile private var listenerThread: Thread? = null
    @Volatile private var callerConnectThread: Thread? = null
    @Volatile private var callerGeneration = 0L
    @Volatile private var callerModeActive = false
    @Volatile private var pendingListenerStart = false
    @Volatile private var listenerGeneration = 0L
    @Volatile private var activityStarted = false
    @Volatile private var keepScreenOn = false
    private var displayOff = false
    private var originalBrightness = -1f
    private var torchOn = false
    @Volatile private var currentLens: CameraLens = CameraLens.Back
    private var availableLenses: List<CameraLens> = listOf(CameraLens.Back)
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var zoomHideRunnable: Runnable? = null
    private var identifyHideRunnable: Runnable? = null
    private var stoppedDetailRunnable: Runnable? = null
    private var recoveredClearRunnable: Runnable? = null
    private var statusDotAnimator: ObjectAnimator? = null
    private var statusDotPeriodMs: Long? = null
    private var displayOffDotAnimator: ObjectAnimator? = null
    private var currentPort: Int = ConnectionTarget.DEFAULT_PORT
    private var releaseReservationRunnable: Runnable? = null
    private var reservationGeneration = 0L
    private var lensRestartRunnable: Runnable? = null
    private var pendingConnectAfterSettings = false
    private var currentDevices: List<DiscoveredObsDevice> = emptyList()
    @Volatile private var activeStreamBitrate: Int = streamConfig.bitrate
    private val callerLifecycleLock = Any()
    @Volatile private var activeCallerTarget: ConnectionTarget? = null
    private var callerReconnectRunnable: Runnable? = null
    private var callerReconnectAttempt = 0
    private var telemetryTick = 0L
    @Volatile private var liveSinceElapsedMs: Long? = null
    private var lostAtElapsedMs: Long? = null
    private var latestSnapshot: HudSnapshot? = null
    private lateinit var micSummary: String

    // ── UI state ──
    private var uiState = CameraUiState()
    private val backgroundCache = HashMap<View, Int>()
    private val hudStrings: HudStrings by lazy {
        object : HudStrings {
            override fun get(resId: Int): String = getString(resId)

            override fun get(resId: Int, vararg args: Any): String = getString(resId, *args)
        }
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContentView(R.layout.activity_main)
        bindViews()

        // Pin the preview buffer to the stream size (capped at 1080p) so the camera
        // gets a deterministic, supported 16:9 size instead of rounding the
        // full-screen portrait surface to an arbitrary size that SurfaceFlinger
        // then stretches into the window.
        val previewWidth = streamConfig.width.coerceAtMost(MAX_PREVIEW_WIDTH)
        val previewHeight = previewWidth * streamConfig.height / streamConfig.width
        cameraPreview.holder.setFixedSize(previewWidth, previewHeight)

        setupGestureDetector()

        currentPort = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            .getInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
            .takeIf { it in 1024..65535 }
            ?: ConnectionTarget.DEFAULT_PORT

        micSummary = getString(
            R.string.hud_f_khz_channels,
            streamConfig.audioSampleRate / 1_000,
            getString(if (streamConfig.audioChannelCount >= 2) R.string.hud_v_stereo else R.string.hud_v_mono),
        )

        streamClient = com.synclab.airlens.stream.SrtStreamClient()
        phoneAdvertiser = PhoneDiscoveryAdvertiser(
            context = this,
            config = streamConfig,
            port = currentPort,
            busyProvider = { phoneConnected || reservedBy != null },
            reservedByProvider = { reservedBy },
        )
        obsDiscoveryClient = ObsDiscoveryClient(
            context = this,
            onDevicesChanged = { devices ->
                currentDevices = devices
                renderObsSlots(devices)
            },
        )
        encoder = createVideoEncoder(activeStreamBitrate)
        audioEncoder = MediaCodecAudioEncoder(
            context = this,
            sampleRate = streamConfig.audioSampleRate,
            channelCount = streamConfig.audioChannelCount,
            bitrate = streamConfig.audioBitrate,
            onEncodedAccessUnit = { accessUnit ->
                val result = streamClient.sendAudioAccessUnit(accessUnit)
                if (result.recoveryRequired &&
                    streamClient.isCurrentSessionGeneration(result.sessionGeneration)
                ) {
                    handleMediaTransportFailure(result.sessionGeneration)
                }
            },
        )
        camera = Camera2Controller(
            context = this,
            previewSurfaceProvider = { cameraPreview.holder.surface },
            lensProvider = { currentLens },
            targetFps = streamConfig.fps,
        )
        controlServer = CameraControlServer(
            cameraProvider = { camera },
            lensListProvider = { availableLenses },
            currentLensProvider = { currentLens },
            onSwitchLens = { lens -> runOnUiThread { selectLens(lens) } },
            onToggleTorch = { enabled -> runOnUiThread {
                torchOn = enabled
                camera.setTorch(enabled)
                update { copy(torchOn = enabled) }
            }},
            reservationProvider = { reservedBy },
            onReserve = { sourceInstanceId, slotLabel, bitrateMbps ->
                reserveForSource(sourceInstanceId, slotLabel, bitrateMbps)
            },
            onRelease = { sourceInstanceId -> releaseForSource(sourceInstanceId) },
            onIdentify = { label, subtitle -> runOnUiThread { showIdentifyOverlay(label, subtitle) } },
        )

        hud = CameraHudController(
            activity = this,
            root = root,
            strings = hudStrings,
            prefs = getSharedPreferences(CameraHudController.PREFS_NAME, MODE_PRIVATE),
            onStateChanged = { hudOn, expanded, debug, tab ->
                update { copy(hudOn = hudOn, hudExpanded = expanded, debug = debug, hudTab = tab) }
            },
        )
        hudCollector = HudTelemetryCollector(
            context = this,
            inputs = CollectorInputs(
                streamStats = { streamClient.stats },
                linkStats = { streamClient.sampleLinkStats() },
                frameMetadata = { camera.frameMetadata() },
                audioLevel = { audioEncoder.latestLevel() },
                micState = ::currentMicState,
                streamContext = ::currentStreamContext,
            ),
            onSnapshot = ::onTelemetrySnapshot,
        )

        cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                initializeLenses()
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("OpenStream", "Preview surface buffer: ${width}x${height}")
                adjustPreviewAspectRatio()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Close the camera before encoder teardown tries to rebuild a
                // preview-only session against this now-invalid surface.
                camera.stop()
                stopPhoneServer(clearReservation = false, updateStatus = false)
            }
        })

        setupButtons()
        setupWindowInsets()
        update {
            copy(
                hudOn = hud.hudOn,
                hudExpanded = hud.isExpanded,
                debug = hud.debug,
                hudTab = hud.tab,
                lensName = lensName(currentLens),
                modeLabel = HudFormatter.modeLabel(streamConfig.width, streamConfig.height, streamConfig.fps),
                frontLens = currentLens.isFrontFacing,
            )
        }
        hud.render(null, uiState.degraded, uiState.thermalAlert, uiState.isLive)
        handlePairingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        phoneAdvertiser.start()
        obsDiscoveryClient.start()
        controlServer.start()
        hudCollector.start()
        startPreviewIfAllowed()
        startPhoneServerIfAllowed()
    }

    override fun onResume() {
        super.onResume()
        // Reload listening port from settings if it changed
        val settingsPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val savedPort = settingsPrefs.getInt(SettingsActivity.KEY_LISTENING_PORT, currentPort)
        if (savedPort != currentPort && savedPort in 1024..65535) {
            changePort(savedPort)
        }
        if (pendingConnectAfterSettings) {
            pendingConnectAfterSettings = false
            startStream(connectionTargetFromSettings())
        }
    }

    override fun onStop() {
        activityStarted = false
        cancelCallerReconnect()
        activeCallerTarget = null
        callerModeActive = false
        lostAtElapsedMs = null
        cancelLensRestart()
        camera.stop()
        stopPhoneServer(clearReservation = false, updateStatus = false)
        hudCollector.stop()
        obsDiscoveryClient.stop()
        phoneAdvertiser.stop()
        controlServer.stop()
        stopStatusDotAnimation()
        super.onStop()
    }

    override fun onDestroy() {
        clearReservation()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            hud.isExpanded -> hud.collapse()
            uiState.toolsOpen -> update { copy(toolsOpen = false) }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            initializeLenses()
            startPreviewIfAllowed()
            startPhoneServerIfAllowed()
        }
    }

    @Deprecated("Uses the platform Activity result API to avoid an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK &&
            data?.getBooleanExtra(SettingsActivity.EXTRA_CONNECT_AFTER_SAVE, false) == true
        ) {
            pendingConnectAfterSettings = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { scaleGestureDetector.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    // ─────────────────────────── View binding ───────────────────────────

    private fun bindViews() {
        root = findViewById(R.id.root)
        cameraPreview = findViewById(R.id.cameraPreview)
        previewContainer = findViewById(R.id.previewContainer)
        topLayer = findViewById(R.id.topLayer)
        bottomLayer = findViewById(R.id.bottomLayer)
        statusPill = findViewById(R.id.statusPill)
        statusDot = findViewById(R.id.statusDot)
        statusState = findViewById(R.id.statusState)
        statusDetail = findViewById(R.id.statusDetail)
        liveBadge = findViewById(R.id.liveBadge)
        statusDuration = findViewById(R.id.statusDuration)
        alertBanner = findViewById(R.id.alertBanner)
        alertIcon = findViewById(R.id.alertIcon)
        alertTitle = findViewById(R.id.alertTitle)
        alertBody = findViewById(R.id.alertBody)
        alertAction = findViewById(R.id.alertAction)
        zoomLabel = findViewById(R.id.zoomLabel)
        slotsPanel = findViewById(R.id.slotsPanel)
        slotScanLabel = findViewById(R.id.slotScanLabel)
        obsSlotList = findViewById(R.id.obsSlotList)
        lensSelectorRow = findViewById(R.id.lensSelectorRow)
        toolsExpanded = findViewById(R.id.toolsExpanded)
        btnTorch = findViewById(R.id.btnTorch)
        torchLabel = findViewById(R.id.torchLabel)
        torchSub = findViewById(R.id.torchSub)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        flipLabel = findViewById(R.id.flipLabel)
        flipSub = findViewById(R.id.flipSub)
        btnKeepScreenOn = findViewById(R.id.btnKeepScreenOn)
        stayLabel = findViewById(R.id.stayLabel)
        staySub = findViewById(R.id.staySub)
        btnScreenOff = findViewById(R.id.btnScreenOff)
        displayLabel = findViewById(R.id.displayLabel)
        displaySub = findViewById(R.id.displaySub)
        btnTools = findViewById(R.id.btnTools)
        toolsLabel = findViewById(R.id.toolsLabel)
        toolsCounter = findViewById(R.id.toolsCounter)
        btnSettings = findViewById(R.id.btnSettings)
        btnCta = findViewById(R.id.btnCta)
        ctaSpinner = findViewById(R.id.ctaSpinner)
        ctaLabel = findViewById(R.id.ctaLabel)
        bottomHint = findViewById(R.id.bottomHint)
        hudSheet = findViewById(R.id.hudSheet)
        identifyOverlay = findViewById(R.id.identifyOverlay)
        identifyLabel = findViewById(R.id.identifyLabel)
        identifySubtitle = findViewById(R.id.identifySubtitle)
        screenOffOverlay = findViewById(R.id.screenOffOverlay)
        displayOffDot = findViewById(R.id.displayOffDot)
        displayOffStatus = findViewById(R.id.displayOffStatus)
    }

    private fun setupButtons() {
        btnKeepScreenOn.setOnClickListener { toggleKeepScreenOn() }
        btnScreenOff.setOnClickListener {
            update { copy(toolsOpen = false) }
            toggleDisplayOff()
        }
        btnTorch.setOnClickListener { toggleTorch() }
        btnFlipCamera.setOnClickListener { flipCamera() }
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SETTINGS_REQUEST_CODE)
        }
        btnCta.setOnClickListener { onCtaClicked() }
        alertBanner.setOnClickListener { onAlertActionClicked() }
        btnTools.setOnClickListener { update { copy(toolsOpen = !toolsOpen) } }

        // Tap the screen-off overlay to re-enable display
        screenOffOverlay.setOnClickListener { if (displayOff) toggleDisplayOff() }
    }

    private fun createVideoEncoder(bitrate: Int): MediaCodecVideoEncoder {
        return MediaCodecVideoEncoder(
            preference = streamConfig.codecPreference,
            width = streamConfig.width,
            height = streamConfig.height,
            fps = streamConfig.fps,
            bitrate = bitrate,
            keyframeIntervalSeconds = streamConfig.keyframeIntervalSeconds,
            onEncodedAccessUnit = { accessUnit ->
                val result = streamClient.sendVideoAccessUnit(accessUnit)
                if (result.recoveryRequired &&
                    streamClient.isCurrentSessionGeneration(result.sessionGeneration)
                ) {
                    handleMediaTransportFailure(result.sessionGeneration)
                }
            },
        )
    }

    private fun handleMediaTransportFailure(sessionGeneration: Long) {
        if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return
        mainHandler.post {
            // A replacement connect/listen may win the race between the encoder
            // callback and this UI task. Never let the old failure tear it down.
            if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return@post
            phoneConnected = false
            if (phoneServerRunning) {
                if (activeTargetName != null) {
                    markLost()
                    update {
                        copy(
                            phase = ConnectionPhase.Lost,
                            pairedSlot = reservedSlotLabel,
                            targetName = activeTargetName,
                            holdSeconds = (RECONNECT_RESERVATION_MS / 1_000L).toInt(),
                            alert = CameraAlert.Lost((RECONNECT_RESERVATION_MS / 1_000L).toInt()),
                            recovered = false,
                            detailOverride = null,
                        )
                    }
                }
                return@post
            }

            val target = activeCallerTarget
            if (!callerModeActive || target == null) return@post
            markLost()
            stopStream(
                updateStatus = false,
                preserveCallerMode = true,
                preserveCallerTarget = true,
            )
            startPreviewIfAllowed()
            scheduleCallerReconnect(target, "media/transport failure")
        }
    }

    private fun useStreamBitrate(bitrateMbps: Int?) {
        val nextBitrate = (bitrateMbps ?: streamConfig.bitrateMbps)
            .coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS) * 1_000_000
        if (activeStreamBitrate == nextBitrate) return
        activeStreamBitrate = nextBitrate
        if (activeTargetName == null) {
            encoder.stop()
            encoder = createVideoEncoder(activeStreamBitrate)
        }
    }

    private fun setupGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newZoom = camera.scaleZoom(detector.scaleFactor)
                showZoomLabel(newZoom)
                return true
            }
        })

        // Also handle pinch on the preview surface itself
        cameraPreview.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    // ─────────────────────────── Lens switching ───────────────────────────

    private fun initializeLenses() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        availableLenses = camera.availableLenses()
        if (currentLens !in availableLenses) {
            currentLens = availableLenses.firstOrNull { it.isBackFacing } ?: availableLenses.first()
        }
        buildLensButtons()
        update { copy(lensName = lensName(currentLens), frontLens = currentLens.isFrontFacing) }
    }

    private fun buildLensButtons() {
        lensSelectorRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (lens in availableLenses) {
            val chip = inflater.inflate(R.layout.item_cam_lens_chip, lensSelectorRow, false) as LinearLayout
            chip.findViewById<TextView>(R.id.lensChipLabel).text = lensChipLabel(lens)
            chip.findViewById<TextView>(R.id.lensChipSub).setText(lensChipSubRes(lens))
            chip.contentDescription = lensName(lens)
            chip.tag = lens
            chip.setOnClickListener { selectLens(lens) }
            lensSelectorRow.addView(chip)
        }
        renderLensChips()
    }

    private fun renderLensChips() {
        for (index in 0 until lensSelectorRow.childCount) {
            val chip = lensSelectorRow.getChildAt(index) as? LinearLayout ?: continue
            val active = chip.tag == currentLens
            val label = chip.findViewById<TextView>(R.id.lensChipLabel)
            val sub = chip.findViewById<TextView>(R.id.lensChipSub)
            setBackgroundIfChanged(chip, if (active) R.drawable.bg_cam_pill_light else R.drawable.bg_cam_pill)
            label.setTextColor(getColor(if (active) R.color.cam_on_light else R.color.cam_white_92))
            sub.setTextColor(getColor(if (active) R.color.cam_on_light_60 else R.color.cam_text_50))
        }
    }

    private fun selectLens(lens: CameraLens) {
        if (lens == currentLens) return
        // Turn off torch when switching cameras
        if (torchOn) {
            torchOn = false
        }
        val wasStreaming = activeTargetName != null
        // Stop the encoder before switching cameras to avoid surface conflicts
        if (wasStreaming) {
            camera.stopStreaming()
            encoder.stop()
        }
        currentLens = lens
        camera.switchLens(lens)
        // If we were streaming, re-create the encoder and re-attach after the camera settles
        if (wasStreaming) {
            cancelLensRestart()
            val restart = Runnable {
                lensRestartRunnable = null
                if (!activityStarted || activeTargetName == null) return@Runnable
                runCatching {
                    encoder.start()
                    camera.startStreaming(encoder.inputSurface())
                }.onFailure { e ->
                    Log.e("OpenStream", "Failed to restart encoder after lens switch", e)
                    update {
                        copy(detailOverride = getString(R.string.cam_state_encoder_error) + " · " + (e.message ?: "Unknown"))
                    }
                }
            }
            lensRestartRunnable = restart
            mainHandler.postDelayed(restart, LENS_RESTART_DELAY_MS)
        }
        update { copy(torchOn = torchOn, lensName = lensName(lens), frontLens = lens.isFrontFacing) }
    }

    private fun flipCamera() {
        val target = if (currentLens.isFrontFacing) {
            availableLenses.firstOrNull { it.isBackFacing } ?: return
        } else {
            availableLenses.firstOrNull { it.isFrontFacing } ?: return
        }
        selectLens(target)
    }

    private fun cancelLensRestart() {
        lensRestartRunnable?.let(mainHandler::removeCallbacks)
        lensRestartRunnable = null
    }

    private fun lensName(lens: CameraLens): String = getString(
        when (lens) {
            CameraLens.Back -> R.string.cam_lens_name_main
            CameraLens.BackUltrawide -> R.string.cam_lens_name_ultra
            CameraLens.BackTelephoto -> R.string.cam_lens_name_tele
            CameraLens.Front -> R.string.cam_lens_name_front
        },
    )

    private fun lensChipLabel(lens: CameraLens): String = when (lens) {
        CameraLens.BackUltrawide -> ".5"
        CameraLens.Back -> "1"
        CameraLens.BackTelephoto -> "2"
        CameraLens.Front -> "⟲"
    }

    private fun lensChipSubRes(lens: CameraLens): Int = when (lens) {
        CameraLens.BackUltrawide -> R.string.cam_lens_ultra
        CameraLens.Back -> R.string.cam_lens_main
        CameraLens.BackTelephoto -> R.string.cam_lens_tele
        CameraLens.Front -> R.string.cam_lens_front
    }

    // ─────────────────────────── Keep screen on ───────────────────────────

    private fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (!displayOff) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        update { copy(keepScreenOn = this@MainActivity.keepScreenOn) }
    }

    // ─────────────────────────── Torch ───────────────────────────

    private fun toggleTorch() {
        // Only works on back-facing cameras
        if (currentLens.isFrontFacing) return
        torchOn = !torchOn
        camera.setTorch(torchOn)
        update { copy(torchOn = this@MainActivity.torchOn) }
    }

    // ─────────────────────────── Zoom ───────────────────────────

    private fun showZoomLabel(zoom: Float) {
        zoomLabel.text = String.format(Locale.US, "%.1f×", zoom)
        zoomLabel.visibility = View.VISIBLE

        zoomHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hideRunnable = Runnable { zoomLabel.visibility = View.GONE }
        zoomHideRunnable = hideRunnable
        mainHandler.postDelayed(hideRunnable, ZOOM_LABEL_MS)
    }

    // ─────────────────────────── Connection ───────────────────────────

    private fun renderObsSlots(devices: List<DiscoveredObsDevice>) {
        obsSlotList.removeAllViews()
        slotScanLabel.text = if (devices.isEmpty()) {
            getString(R.string.cam_slots_scanning)
        } else {
            getString(R.string.cam_slots_found, devices.size)
        }
        if (devices.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.cam_slots_none)
                textSize = 12f
                setTextColor(getColor(R.color.cam_text_45))
                gravity = Gravity.CENTER
                val pad = dp(8)
                setPadding(0, pad, 0, pad)
            }
            obsSlotList.addView(empty)
        } else {
            val inflater = LayoutInflater.from(this)
            devices.forEach { device ->
                val isReservedForThisPhone = reservedBy == device.sourceInstanceId
                val enabled = !device.busy || isReservedForThisPhone
                val row = inflater.inflate(R.layout.item_cam_slot, obsSlotList, false)
                val name = row.findViewById<TextView>(R.id.slotName)
                val sub = row.findViewById<TextView>(R.id.slotSub)
                val mark = row.findViewById<TextView>(R.id.slotMark)
                name.text = device.displayLabel
                sub.text = when {
                    isReservedForThisPhone -> getString(R.string.cam_slot_sub_reserved)
                    device.busy -> getString(R.string.cam_slot_sub_busy)
                    else -> getString(R.string.cam_slot_sub_free, device.host)
                }
                when {
                    isReservedForThisPhone && phoneConnected -> {
                        mark.text = getString(R.string.cam_slot_live)
                        mark.setTextColor(getColor(R.color.cam_ok))
                    }
                    isReservedForThisPhone -> {
                        mark.text = getString(R.string.cam_slot_paired)
                        mark.setTextColor(getColor(R.color.cam_ok))
                    }
                    device.busy -> {
                        mark.text = getString(R.string.cam_slot_busy)
                        mark.setTextColor(getColor(R.color.cam_text_40))
                    }
                    else -> {
                        mark.text = getString(R.string.cam_slot_use)
                        mark.setTextColor(getColor(R.color.cam_accent_soft))
                    }
                }
                row.setBackgroundResource(
                    if (isReservedForThisPhone) R.drawable.bg_cam_slot_row_selected else R.drawable.bg_cam_slot_row,
                )
                name.setTextColor(getColor(if (enabled) R.color.cam_text else R.color.cam_text_45))
                row.alpha = if (enabled) 1f else 0.45f
                row.isEnabled = enabled
                row.isClickable = enabled
                if (enabled) {
                    row.setOnClickListener { reserveForSlot(device) }
                }
                obsSlotList.addView(row)
            }
        }
        update { copy(discoveredCount = devices.size) }
    }

    private fun reserveForSlot(device: DiscoveredObsDevice) {
        if (device.busy && reservedBy != device.sourceInstanceId) return

        // If connected to someone else and user explicitly taps a new slot, disconnect the old stream
        if (phoneConnected && reservedBy != device.sourceInstanceId) {
            stopStream(updateStatus = false)
        }

        if (reserveForSource(device.sourceInstanceId, device.displayLabel, device.bitrateMbps)) {
            if (!phoneConnected) {
                update {
                    copy(
                        phase = ConnectionPhase.Paired,
                        pairedSlot = device.displayLabel,
                        alert = alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Reconnecting },
                        detailOverride = null,
                    )
                }
            }
            renderObsSlots(currentDevices)
        }
    }

    private fun handlePairingIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val sourceInstanceId = uri.getQueryParameter("sourceInstanceId")?.trim().orEmpty()
        if (sourceInstanceId.isNotBlank()) {
            val slotLabel = uri.getQueryParameter("slotLabel")?.trim().orEmpty()
            val bitrateMbps = uri.getQueryParameter("bitrateMbps")?.toIntOrNull()
                ?.coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS)
            if (reserveForSource(sourceInstanceId, slotLabel, bitrateMbps)) {
                if (!phoneConnected) {
                    update {
                        copy(
                            phase = ConnectionPhase.Paired,
                            pairedSlot = slotLabel.ifBlank { reservedSlotLabel ?: "OBS slot" },
                            alert = alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Reconnecting },
                            detailOverride = null,
                        )
                    }
                }
                renderObsSlots(currentDevices)
            }
            return
        }
        val target = ConnectionTarget.fromPairingUri(uri) ?: return
        startStream(target)
    }

    private fun startStream(target: ConnectionTarget, reconnecting: Boolean = false) {
        if (!reconnecting) {
            cancelCallerReconnect()
            activeCallerTarget = target
            callerReconnectAttempt = 0
            lostAtElapsedMs = null
        } else if (activeCallerTarget != target) {
            return
        }
        callerModeActive = true
        stopStream(
            updateStatus = false,
            preserveCallerMode = true,
            preserveCallerTarget = true,
        )
        // Caller mode and listener mode share one native SRT transport. Fully
        // stop the listener before opening a manual caller connection.
        stopPhoneServer(clearReservation = true, updateStatus = false)
        useStreamBitrate(target.bitrateMbps)
        cancelStoppedDetail()
        update {
            copy(
                phase = ConnectionPhase.Connecting,
                targetName = target.name,
                pairedSlot = null,
                modeLabel = HudFormatter.modeLabel(streamConfig.width, streamConfig.height, streamConfig.fps),
                recovered = false,
                alert = alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Recovered },
                detailOverride = null,
            )
        }
        val generation = callerGeneration + 1
        callerGeneration = generation
        val thread = Thread({
            try {
                streamClient.connect(
                    url = target.toSrtCallerUrl(),
                    codecMime = encoder.codecName,
                    width = streamConfig.width,
                    height = streamConfig.height,
                    fps = streamConfig.fps,
                )
                synchronized(callerLifecycleLock) {
                    check(callerGeneration == generation) { "SRT caller connection was cancelled" }
                    encoder.start()
                    startAudioIfAllowed()
                    camera.startStreaming(encoder.inputSurface())
                }
                mainHandler.post {
                    if (callerGeneration != generation || activeCallerTarget != target) return@post
                    callerReconnectAttempt = 0
                    activeTargetName = target.name
                    showLiveState(target.name)
                }
            } catch (error: Throwable) {
                synchronized(callerLifecycleLock) {
                    if (callerGeneration == generation) {
                        streamClient.disconnect()
                        stopActiveEncoding(updateStatus = false)
                    }
                }
                mainHandler.post {
                    if (callerGeneration != generation || activeCallerTarget != target) return@post
                    if (callerModeActive && activityStarted) {
                        hideLiveState()
                        scheduleCallerReconnect(target, error.message ?: "connect failed")
                    }
                }
            } finally {
                if (callerConnectThread === Thread.currentThread()) {
                    callerConnectThread = null
                }
            }
        }, "OpenStreamPhoneSrtCaller").apply {
            isDaemon = true
        }
        callerConnectThread = thread
        thread.start()
    }

    private fun scheduleCallerReconnect(target: ConnectionTarget, reason: String) {
        if (!activityStarted || !callerModeActive || activeCallerTarget != target) return
        if (callerReconnectRunnable != null) return

        callerReconnectAttempt += 1
        val exponent = (callerReconnectAttempt - 1).coerceIn(0, 3)
        val delayMs = minOf(
            CALLER_RECONNECT_MAX_DELAY_MS,
            CALLER_RECONNECT_BASE_DELAY_MS * (1L shl exponent),
        )
        if (lostAtElapsedMs == null) markLost()
        val attempt = callerReconnectAttempt
        val delaySeconds = ceil(delayMs / 1_000.0).toInt()
        update {
            copy(
                phase = ConnectionPhase.Reconnecting,
                targetName = target.name,
                reconnectAttempt = attempt,
                reconnectDelaySeconds = delaySeconds,
                recovered = false,
                alert = CameraAlert.Reconnecting(target.name, attempt, delaySeconds),
                detailOverride = null,
            )
        }
        Log.w(
            "OpenStream",
            "Caller reconnect scheduled attempt=$callerReconnectAttempt delayMs=$delayMs reason=$reason",
        )

        val retry = Runnable {
            callerReconnectRunnable = null
            if (!activityStarted || !callerModeActive || activeCallerTarget != target) return@Runnable
            startStream(target, reconnecting = true)
        }
        callerReconnectRunnable = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun cancelCallerReconnect() {
        callerReconnectRunnable?.let(mainHandler::removeCallbacks)
        callerReconnectRunnable = null
    }

    private fun startAudioIfAllowed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i("OpenStream", "Microphone permission not granted; streaming video without audio")
            return
        }
        runCatching { audioEncoder.start() }.onFailure { e ->
            Log.w("OpenStream", "Audio encoder start failed; continuing video-only", e)
        }
    }

    private fun startPhoneServerIfAllowed() {
        if (callerModeActive) return
        if (phoneServerRunning) return
        if (listenerThread?.isAlive == true) {
            pendingListenerStart = true
            Log.w("OpenStream", "Previous SRT listener is still stopping; not starting another")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!cameraPreview.holder.surface.isValid) return

        pendingListenerStart = false
        val generation = listenerGeneration + 1
        listenerGeneration = generation
        phoneServerRunning = true
        phoneConnected = false
        activeTargetName = null
        showIdlePhase()

        val thread = Thread({
            try {
                while (isListenerActive(generation)) {
                    val listenUrl = "srt://0.0.0.0:${currentPort}?mode=listener&latency=${streamConfig.latencyMs}"
                    val listenResult = runCatching {
                        streamClient.listen(
                            url = listenUrl,
                            codecMime = encoder.codecName,
                            width = streamConfig.width,
                            height = streamConfig.height,
                            fps = streamConfig.fps,
                        )
                        if (!isListenerActive(generation)) {
                            streamClient.disconnect()
                            return@runCatching
                        }
                        phoneConnected = true
                        cancelReservationRelease()
                        val liveTargetName = reservedSlotLabel ?: "OBS"
                        activeTargetName = liveTargetName
                        runOnUiThread {
                            if (!isListenerActive(generation)) return@runOnUiThread
                            showLiveState(liveTargetName)
                        }
                        encoder.start()
                        startAudioIfAllowed()
                        camera.startStreaming(encoder.inputSurface())
                        while (isListenerActive(generation) && phoneConnected) {
                            Thread.sleep(LISTENER_POLL_MS)
                        }
                    }

                    if (listenResult.isFailure && isListenerActive(generation)) {
                        val error = listenResult.exceptionOrNull()
                        runOnUiThread {
                            if (!isListenerActive(generation)) return@runOnUiThread
                            update {
                                copy(
                                    detailOverride = getString(R.string.cam_state_listener_error) + " · " +
                                        (error?.message ?: "Unknown"),
                                )
                            }
                        }
                        try {
                            Thread.sleep(LISTENER_RETRY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }

                    stopActiveEncoding(updateStatus = false)
                    phoneConnected = false
                    activeTargetName = null
                    if (isListenerActive(generation)) {
                        scheduleReservationRelease()
                        runOnUiThread {
                            if (!isListenerActive(generation)) return@runOnUiThread
                            hideLiveState()
                            // A media failure already moved the pill to Lost (reservation held);
                            // otherwise fall back to Paired/Ready.
                            if (uiState.phase != ConnectionPhase.Lost) showIdlePhase()
                        }
                    }
                }
            } finally {
                if (listenerThread === Thread.currentThread()) {
                    listenerThread = null
                    mainHandler.post {
                        if (pendingListenerStart) {
                            pendingListenerStart = false
                            startPhoneServerIfAllowed()
                        }
                    }
                }
            }
        }, "OpenStreamPhoneSrtListener").apply {
            isDaemon = true
        }
        listenerThread = thread
        thread.start()
    }

    private fun isListenerActive(generation: Long): Boolean {
        return phoneServerRunning && listenerGeneration == generation
    }

    private fun stopPhoneServer(
        clearReservation: Boolean = true,
        updateStatus: Boolean = true,
    ) {
        callerGeneration += 1
        callerConnectThread?.interrupt()
        pendingListenerStart = false
        listenerGeneration += 1
        phoneServerRunning = false
        phoneConnected = false
        if (clearReservation) clearReservation()
        activeTargetName = null
        streamClient.disconnect()
        val thread = listenerThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(LISTENER_STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        if (thread?.isAlive == true) {
            Log.w("OpenStream", "SRT listener did not stop within ${LISTENER_STOP_TIMEOUT_MS}ms")
        } else if (listenerThread === thread) {
            listenerThread = null
        }
        synchronized(callerLifecycleLock) {
            stopActiveEncoding(updateStatus)
        }
        hideLiveState()
    }

    private fun stopStream(
        updateStatus: Boolean = true,
        preserveCallerMode: Boolean = false,
        preserveCallerTarget: Boolean = false,
    ) {
        callerGeneration += 1
        callerConnectThread?.interrupt()
        if (!preserveCallerMode) callerModeActive = false
        if (!preserveCallerTarget) {
            activeCallerTarget = null
            callerReconnectAttempt = 0
            cancelCallerReconnect()
            lostAtElapsedMs = null
        }
        activeTargetName = null
        phoneConnected = false
        streamClient.disconnect()
        synchronized(callerLifecycleLock) {
            stopActiveEncoding(updateStatus)
        }
        hideLiveState()
    }

    private fun stopActiveEncoding(updateStatus: Boolean = true) {
        cancelLensRestart()
        camera.stopStreaming()
        encoder.stop()
        audioEncoder.stop()
        if (updateStatus) {
            showStoppedState()
        }
    }

    // ─────────────────────────── Live state UI ───────────────────────────

    @Synchronized
    private fun reserveForSource(
        sourceInstanceId: String,
        slotLabel: String = "",
        bitrateMbps: Int? = null,
    ): Boolean {
        val currentReservation = reservedBy
        if (phoneConnected && currentReservation != sourceInstanceId) return false
        useStreamBitrate(bitrateMbps)
        reservationGeneration += 1
        reservedBy = sourceInstanceId
        reservedSlotLabel = slotLabel.ifBlank { reservedSlotLabel }
        if (phoneConnected) {
            cancelReservationRelease()
        } else {
            scheduleReservationRelease()
        }
        return true
    }

    @Synchronized
    private fun releaseForSource(sourceInstanceId: String): Boolean {
        if (reservedBy == sourceInstanceId) {
            clearReservation()
            mainHandler.post { onReservationDropped() }
            return true
        }
        return reservedBy == null
    }

    @Synchronized
    private fun clearReservation() {
        cancelReservationRelease()
        reservedBy = null
        reservedSlotLabel = null
    }

    @Synchronized
    private fun scheduleReservationRelease() {
        val sourceInstanceId = reservedBy ?: return
        val generation = reservationGeneration
        cancelReservationRelease()
        releaseReservationRunnable = Runnable {
            val dropped = synchronized(this) {
                if (!phoneConnected &&
                    reservedBy == sourceInstanceId &&
                    reservationGeneration == generation
                ) {
                    reservedBy = null
                    reservedSlotLabel = null
                    true
                } else {
                    false
                }
            }
            if (dropped) onReservationDropped()
        }
        mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)
    }

    @Synchronized
    private fun cancelReservationRelease() {
        releaseReservationRunnable?.let { mainHandler.removeCallbacks(it) }
        releaseReservationRunnable = null
    }

    /** Main thread: the held slot expired or OBS released it — leave Lost/Paired for Ready. */
    private fun onReservationDropped() {
        if (reservedBy != null) return
        if (uiState.phase == ConnectionPhase.Lost || uiState.phase == ConnectionPhase.Paired) {
            update {
                copy(
                    phase = ConnectionPhase.Ready,
                    pairedSlot = null,
                    alert = alert?.takeUnless { it is CameraAlert.Lost },
                )
            }
        } else if (uiState.pairedSlot != null) {
            update { copy(pairedSlot = null) }
        }
        renderObsSlots(currentDevices)
    }

    private fun showIdentifyOverlay(label: String, subtitle: String) {
        identifyLabel.text = label
        identifySubtitle.text = subtitle
        identifySubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        identifyHideRunnable?.let(mainHandler::removeCallbacks)
        if (identifyOverlay.visibility != View.VISIBLE) {
            identifyOverlay.alpha = 0f
            identifyOverlay.visibility = View.VISIBLE
            identifyOverlay.animate().alpha(1f).setDuration(IDENTIFY_FADE_MS).start()
        }
        val hide = Runnable {
            identifyHideRunnable = null
            identifyOverlay.animate().alpha(0f).setDuration(IDENTIFY_FADE_MS)
                .withEndAction { identifyOverlay.visibility = View.GONE }
                .start()
        }
        identifyHideRunnable = hide
        mainHandler.postDelayed(hide, IDENTIFY_OVERLAY_MS)
    }

    private fun showLiveState(targetName: String) {
        val now = SystemClock.elapsedRealtime()
        liveSinceElapsedMs = now
        val lostAt = lostAtElapsedMs
        lostAtElapsedMs = null
        val recovered = lostAt != null
        val afterSeconds = lostAt?.let { ((now - it) / 1_000L).toInt().coerceAtLeast(1) } ?: 0
        cancelStoppedDetail()
        cancelRecoveredClear()
        update {
            copy(
                phase = ConnectionPhase.Live,
                targetName = targetName,
                pairedSlot = reservedSlotLabel,
                lensName = lensName(currentLens),
                modeLabel = HudFormatter.modeLabel(streamConfig.width, streamConfig.height, streamConfig.fps),
                liveSeconds = 0,
                lastFrameAgoSeconds = null,
                recovered = recovered,
                reconnectAttempt = 0,
                alert = if (recovered) {
                    CameraAlert.Recovered(afterSeconds)
                } else {
                    alert?.takeUnless {
                        it is CameraAlert.Lost || it is CameraAlert.Reconnecting || it is CameraAlert.Recovered
                    }
                },
                detailOverride = null,
            )
        }
        if (recovered) {
            val clear = Runnable {
                recoveredClearRunnable = null
                update { copy(recovered = false, alert = alert?.takeUnless { it is CameraAlert.Recovered }) }
            }
            recoveredClearRunnable = clear
            mainHandler.postDelayed(clear, RECOVERED_BANNER_MS)
        }
        renderObsSlots(currentDevices)
    }

    private fun hideLiveState() {
        liveSinceElapsedMs = null
        cancelRecoveredClear()
        if (uiState.isLive) {
            update {
                copy(
                    phase = if (reservedBy != null) ConnectionPhase.Paired else ConnectionPhase.Ready,
                    pairedSlot = reservedSlotLabel,
                    recovered = false,
                    alert = alert?.takeUnless { it is CameraAlert.Recovered },
                )
            }
        }
    }

    /** Ready (no reservation) or Paired (reservation held); keeps a pending "stopped" detail. */
    private fun showIdlePhase() {
        update {
            copy(
                phase = if (reservedBy != null) ConnectionPhase.Paired else ConnectionPhase.Ready,
                pairedSlot = reservedSlotLabel,
                recovered = false,
                reconnectAttempt = 0,
                alert = alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Reconnecting },
                detailOverride = if (stoppedDetailRunnable != null) detailOverride else null,
            )
        }
    }

    /** "Camera preview remains active" for 2 s, then the phase's own detail. */
    private fun showStoppedState() {
        cancelStoppedDetail()
        val clear = Runnable {
            stoppedDetailRunnable = null
            update { copy(detailOverride = null) }
        }
        stoppedDetailRunnable = clear
        update {
            copy(
                phase = if (reservedBy != null) ConnectionPhase.Paired else ConnectionPhase.Ready,
                pairedSlot = reservedSlotLabel,
                recovered = false,
                reconnectAttempt = 0,
                alert = alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Reconnecting || it is CameraAlert.Recovered },
                detailOverride = getString(R.string.cam_state_stopped_detail),
            )
        }
        mainHandler.postDelayed(clear, STOPPED_DETAIL_MS)
    }

    private fun cancelStoppedDetail() {
        stoppedDetailRunnable?.let(mainHandler::removeCallbacks)
        stoppedDetailRunnable = null
    }

    private fun cancelRecoveredClear() {
        recoveredClearRunnable?.let(mainHandler::removeCallbacks)
        recoveredClearRunnable = null
    }

    private fun markLost() {
        if (lostAtElapsedMs == null) lostAtElapsedMs = SystemClock.elapsedRealtime()
    }

    // ─────────────────────────── Primary action / alert action ───────────────────────────

    private fun onCtaClicked() {
        when (uiState.phase) {
            ConnectionPhase.Ready -> Unit
            ConnectionPhase.Paired -> {
                val device = currentDevices.firstOrNull { it.sourceInstanceId == reservedBy }
                val target = device?.let { ConnectionTarget.fromDiscoveredDevice(it) } ?: connectionTargetFromSettings()
                startStream(target)
            }
            ConnectionPhase.Connecting -> {
                stopStream()
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            ConnectionPhase.Live -> {
                if (callerModeActive) {
                    stopStream()
                } else {
                    stopPhoneServer(clearReservation = false)
                }
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            ConnectionPhase.Lost -> retryListener()
            ConnectionPhase.Reconnecting -> {
                stopStream()
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
        }
    }

    private fun retryListener() {
        stopPhoneServer(clearReservation = false)
        startPreviewIfAllowed()
        startPhoneServerIfAllowed()
    }

    private fun onAlertActionClicked() {
        when (effectiveAlert()) {
            is CameraAlert.Lost -> retryListener()
            is CameraAlert.Reconnecting -> {
                stopStream()
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            is CameraAlert.Thermal -> hud.expand(HudTab.Device)
            is CameraAlert.Recovered -> {
                cancelRecoveredClear()
                update { copy(recovered = false, alert = alert?.takeUnless { it is CameraAlert.Recovered }) }
            }
            null -> Unit
        }
    }

    /** Mirrors CameraUiModel.alert(): phase-bound banners win over the explicit alert. */
    private fun effectiveAlert(): CameraAlert? = when (uiState.phase) {
        ConnectionPhase.Lost -> uiState.alert as? CameraAlert.Lost ?: CameraAlert.Lost(uiState.holdSeconds)
        ConnectionPhase.Reconnecting -> uiState.alert as? CameraAlert.Reconnecting
            ?: CameraAlert.Reconnecting(
                uiState.targetName ?: uiState.pairedSlot ?: "",
                uiState.reconnectAttempt,
                uiState.reconnectDelaySeconds,
            )
        else -> uiState.alert?.takeUnless { it is CameraAlert.Lost || it is CameraAlert.Reconnecting }
    }

    // ─────────────────────────── Telemetry ───────────────────────────

    private fun currentMicState(): MicState = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> MicState.NoPermission
        !streamConfig.audioEnabled || !audioEncoder.isCapturing -> MicState.Off
        else -> MicState.Active
    }

    private fun currentStreamContext(): StreamContext {
        val live = activeTargetName != null
        val stats = streamClient.stats
        val encoderState = when {
            !live -> HudFormatter.ENCODER_IDLE
            !stats.connected -> HudFormatter.ENCODER_DISCONNECTED
            stats.sendFailures > 0 -> HudFormatter.ENCODER_STALLED
            else -> HudFormatter.ENCODER_STREAMING
        }
        return StreamContext(
            live = live,
            liveSinceElapsedMs = liveSinceElapsedMs,
            targetBitrateMbps = activeStreamBitrate / 1_000_000,
            width = streamConfig.width,
            height = streamConfig.height,
            fps = streamConfig.fps,
            keyframeIntervalSeconds = streamConfig.keyframeIntervalSeconds,
            codecLabel = codecLabel(),
            lens = currentLens,
            zoom = camera.zoomRatio,
            targetUrl = activeCallerTarget?.toSrtCallerUrl() ?: "srt://0.0.0.0:$currentPort?mode=listener",
            micSummary = micSummary,
            keepScreenOn = keepScreenOn,
            encoderState = encoderState,
        )
    }

    /** "H.264 High · CBR" — codec from the encoder MIME, profile from the negotiated output format. */
    private fun codecLabel(): String {
        val mime = encoder.codecName
        val codec = if (mime.contains("hevc", ignoreCase = true)) "HEVC" else "H.264"
        val profile = when (encoder.actualOutputProfile) {
            null -> null
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
            else -> null
        }
        val mode = (encoder.actualBitrateMode ?: streamConfig.videoBitrateMode).name.uppercase(Locale.US)
        return buildString {
            append(codec)
            if (profile != null) append(' ').append(profile)
            append(" · ").append(mode)
        }
    }

    /** Main thread, once per second from HudTelemetryCollector. */
    private fun onTelemetrySnapshot(snapshot: HudSnapshot) {
        latestSnapshot = snapshot
        val thermal = HudFormatter.isThermalAlert(snapshot.thermalStatus, snapshot.temperatureC)
        val now = SystemClock.elapsedRealtime()
        val lastFrameAgo = hudCollector.lastFrameAtElapsedMs?.let { ((now - it) / 1_000L).coerceAtLeast(0L) }
        val stats = streamClient.stats

        update {
            val nextAlert = when {
                thermal -> when (alert) {
                    is CameraAlert.Lost, is CameraAlert.Reconnecting -> alert
                    else -> CameraAlert.Thermal(
                        snapshot.temperatureC,
                        HudFormatter.thermalLabel(snapshot.thermalStatus),
                    )
                }
                alert is CameraAlert.Thermal -> null
                else -> alert
            }
            val liveOverride = when {
                !isLive -> detailOverride
                stats.sendFailures > 0 -> getString(R.string.cam_state_send_issue)
                snapshot.videoFrames == 0L -> getString(R.string.cam_state_waiting_frames)
                else -> null
            }
            copy(
                liveSeconds = snapshot.durationSeconds,
                lastFrameAgoSeconds = lastFrameAgo,
                dropsThisSession = snapshot.connectionLosses,
                thermalAlert = thermal,
                alert = nextAlert,
                detailOverride = liveOverride,
            )
        }
        hud.render(snapshot, uiState.degraded, thermal, uiState.isLive)

        if (uiState.isLive) {
            telemetryTick += 1
            if (telemetryTick % TELEMETRY_LOG_INTERVAL_TICKS == 0L) {
                Log.i(
                    "OpenStreamTelemetry",
                    "targetMbps=${snapshot.targetBitrateMbps} " +
                        "actualMbps=${snapshot.actualBitrateMbps ?: -1.0} " +
                        "fps=${snapshot.actualFps ?: -1.0} " +
                        "videoAu=${snapshot.videoFrames} keyframes=${snapshot.keyframes} " +
                        "audioAu=${snapshot.audioFrames} megabits=${snapshot.sessionMegabits} " +
                        "losses=${snapshot.connectionLosses} reconnects=${snapshot.reconnects} " +
                        "codec=${snapshot.codecLabel} encoder=${snapshot.encoderState} " +
                        "rttMs=${snapshot.srtRttMs ?: -1.0} loss=${snapshot.srtLossPercent ?: -1.0} " +
                        "rssi=${snapshot.wifiRssi} battery=${snapshot.batteryPercent} " +
                        "temperatureC=${snapshot.temperatureC} thermal=${snapshot.thermalStatus}",
                )
            }
        }
    }

    // ─────────────────────────── UI state → views ───────────────────────────

    private fun update(transform: CameraUiState.() -> CameraUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { update(transform) }
            return
        }
        val next = uiState.transform()
        uiState = next
        renderUi()
    }

    private fun renderUi() {
        val state = uiState
        renderStatus(CameraUiModel.status(state, hudStrings), state)
        renderAlert(CameraUiModel.alert(state, hudStrings))
        renderCta(CameraUiModel.cta(state, hudStrings))

        slotsPanel.visibility = if (state.showSlots) View.VISIBLE else View.GONE

        renderTools(state)
        renderLensChips()

        bottomHint.setText(if (state.isLive) R.string.cam_hint_live else R.string.cam_hint_idle)

        renderDisplayOff(state)
    }

    private fun renderStatus(spec: StatusSpec, state: CameraUiState) {
        statusState.text = spec.text
        statusDetail.text = spec.detail
        (statusPill.background?.mutate() as? GradientDrawable)?.let { pill ->
            pill.setColor(getColor(spec.bgColorRes))
            pill.setStroke(dp(1), getColor(spec.borderColorRes))
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(spec.dotColorRes))
        setStatusDotPulse(spec.pulsePeriodMs)
        liveBadge.visibility = if (spec.showDuration) View.VISIBLE else View.GONE
        statusDuration.text = HudFormatter.formatDuration(state.liveSeconds)
    }

    private fun setStatusDotPulse(periodMs: Long?) {
        if (statusDotPeriodMs == periodMs && (periodMs == null || statusDotAnimator != null)) return
        stopStatusDotAnimation()
        statusDotPeriodMs = periodMs
        if (periodMs == null) {
            statusDot.alpha = 1f
            return
        }
        statusDotAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.3f).apply {
            duration = periodMs / 2
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusDotAnimation() {
        statusDotAnimator?.cancel()
        statusDotAnimator = null
        statusDotPeriodMs = null
        statusDot.alpha = 1f
    }

    private fun renderAlert(spec: AlertSpec?) {
        if (spec == null) {
            alertBanner.visibility = View.GONE
            return
        }
        val appearing = alertBanner.visibility != View.VISIBLE
        setBackgroundIfChanged(alertBanner, spec.bgDrawableRes)
        val accent = getColor(spec.accentColorRes)
        alertIcon.backgroundTintList = ColorStateList.valueOf(accent)
        alertAction.setTextColor(accent)
        alertTitle.text = spec.title
        alertBody.text = spec.body
        alertAction.text = spec.action
        if (appearing) {
            alertBanner.visibility = View.VISIBLE
            rise(alertBanner, RISE_ALERT_MS)
        }
    }

    private fun renderCta(spec: CtaSpec) {
        setBackgroundIfChanged(btnCta, spec.bgDrawableRes)
        val fg = getColor(spec.fgColorRes)
        ctaLabel.text = spec.label
        ctaLabel.setTextColor(fg)
        ctaSpinner.indeterminateTintList = ColorStateList.valueOf(fg)
        ctaSpinner.visibility = if (spec.spinner) View.VISIBLE else View.GONE
        btnCta.isEnabled = spec.enabled
        btnCta.isClickable = spec.enabled
        btnCta.alpha = if (spec.enabled) 1f else 0.9f
    }

    private fun renderTools(state: CameraUiState) {
        if (state.toolsOpen && toolsExpanded.visibility != View.VISIBLE) {
            toolsExpanded.visibility = View.VISIBLE
            rise(toolsExpanded, RISE_TOOLS_MS)
        } else if (!state.toolsOpen) {
            toolsExpanded.visibility = View.GONE
        }

        renderTile(btnTorch, torchLabel, torchSub, state.torchOn, if (state.torchOn) R.string.cam_on else R.string.cam_off)
        renderTile(btnFlipCamera, flipLabel, flipSub, state.frontLens, if (state.frontLens) R.string.cam_front else R.string.cam_back)
        renderTile(btnKeepScreenOn, stayLabel, staySub, state.keepScreenOn, if (state.keepScreenOn) R.string.cam_awake else R.string.cam_auto)
        renderTile(btnScreenOff, displayLabel, displaySub, false, R.string.cam_turn_off)

        val open = state.toolsOpen
        setBackgroundIfChanged(btnTools, if (open) R.drawable.bg_cam_light_circle else R.drawable.bg_cam_ghost_circle)
        toolsLabel.setTextColor(getColor(if (open) R.color.cam_on_light else R.color.cam_text))
        toolsCounter.setTextColor(getColor(if (open) R.color.cam_on_light_60 else R.color.cam_text_55))
        toolsCounter.text = getString(R.string.cam_tools_count, CameraUiModel.toolsCount(state))
    }

    private fun renderTile(tile: LinearLayout, label: TextView, sub: TextView, active: Boolean, subRes: Int) {
        setBackgroundIfChanged(tile, if (active) R.drawable.bg_cam_tile_light else R.drawable.bg_cam_tile)
        label.setTextColor(getColor(if (active) R.color.cam_on_light else R.color.cam_text))
        sub.setTextColor(getColor(if (active) R.color.cam_on_light_60 else R.color.cam_text_55))
        sub.setText(subRes)
    }

    private fun renderDisplayOff(state: CameraUiState) {
        if (!state.displayOff) {
            if (screenOffOverlay.visibility != View.GONE) screenOffOverlay.visibility = View.GONE
            displayOffDotAnimator?.cancel()
            displayOffDotAnimator = null
            displayOffDot.alpha = 1f
            return
        }
        screenOffOverlay.visibility = View.VISIBLE
        displayOffStatus.text = if (state.isLive) {
            getString(R.string.cam_display_off_live, HudFormatter.formatDuration(state.liveSeconds))
        } else {
            getString(R.string.cam_display_off_idle)
        }
        if (displayOffDotAnimator == null) {
            displayOffDotAnimator = ObjectAnimator.ofFloat(displayOffDot, View.ALPHA, 1f, 0.3f).apply {
                duration = DISPLAY_OFF_PULSE_MS / 2
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun rise(view: View, durationMs: Long) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(14).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(durationMs).start()
    }

    private fun setBackgroundIfChanged(view: View, drawableRes: Int) {
        if (backgroundCache[view] == drawableRes) return
        backgroundCache[view] = drawableRes
        view.setBackgroundResource(drawableRes)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ─────────────────────────── Utilities ───────────────────────────

    private fun startPreviewIfAllowed() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            cameraPreview.holder.surface.isValid
        ) {
            camera.startPreview()
        }
    }

    private fun requestRuntimePermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun connectionTargetFromSettings(): ConnectionTarget {
        val settingsPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val host = settingsPrefs.getString(SettingsActivity.KEY_OBS_HOST, ConnectionTarget.DEFAULT_HOST)
            ?.trim().orEmpty().ifBlank { ConnectionTarget.DEFAULT_HOST }
        val port = settingsPrefs.getInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
        val latencyMs = settingsPrefs.getInt(SettingsActivity.KEY_LATENCY, ConnectionTarget.DEFAULT_LATENCY_MS)
        return ConnectionTarget(
            name = ConnectionTarget.DEFAULT_NAME,
            host = host,
            port = port.coerceIn(1, 65535),
            latencyMs = latencyMs.coerceIn(80, 200),
        )
    }

    // ─────────────────────────── Display off (screen off while streaming) ───────────────────────────

    private fun toggleDisplayOff() {
        displayOff = !displayOff
        if (displayOff) {
            // Save current brightness and dim to minimum
            originalBrightness = window.attributes.screenBrightness
            val params = window.attributes
            params.screenBrightness = DISPLAY_OFF_BRIGHTNESS
            window.attributes = params
            // Ensure screen stays on even when dimmed
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // Restore original brightness
            val params = window.attributes
            params.screenBrightness = if (originalBrightness >= 0) originalBrightness else -1f
            window.attributes = params
            // Restore keep-screen-on to user's toggle state
            if (!keepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        update { copy(displayOff = this@MainActivity.displayOff) }
    }

    // ─────────────────────────── Port selector ───────────────────────────

    private fun changePort(newPort: Int) {
        val clamped = newPort.coerceIn(1024, 65535)
        if (clamped == currentPort) return
        currentPort = clamped
        // Restart the phone server on the new port
        stopPhoneServer(clearReservation = false)
        // Re-create the advertiser with new port
        phoneAdvertiser.stop()
        phoneAdvertiser = PhoneDiscoveryAdvertiser(
            context = this,
            config = streamConfig,
            port = currentPort,
            busyProvider = { phoneConnected || reservedBy != null },
            reservedByProvider = { reservedBy },
        )
        phoneAdvertiser.start()
        startPhoneServerIfAllowed()
    }

    // ─────────────────────────── Preview aspect ratio fix ───────────────────────────

    private fun adjustPreviewAspectRatio() {
        val bufferAspect = streamConfig.width.toFloat() / streamConfig.height.toFloat()
        val containerWidth = previewContainer.width
        val containerHeight = previewContainer.height
        if (containerWidth == 0 || containerHeight == 0) {
            previewContainer.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (right - left > 0 && bottom - top > 0) {
                        v.removeOnLayoutChangeListener(this)
                        adjustPreviewAspectRatio()
                    }
                }
            })
            return
        }

        // Buffer hiển thị xoay 90° trên màn hình dọc nên khung view mục tiêu là 9:16.
        val targetWidth: Int
        val targetHeight: Int
        if (containerWidth.toFloat() / containerHeight > 1f / bufferAspect) {
            // Container rộng hơn 9:16: fit theo chiều cao, pillarbox hai bên.
            targetHeight = containerHeight
            targetWidth = (containerHeight / bufferAspect).toInt()
        } else {
            // Container cao hơn 9:16: fit theo chiều rộng, letterbox trên/dưới.
            targetWidth = containerWidth
            targetHeight = (containerWidth * bufferAspect).toInt()
        }

        val lp = cameraPreview.layoutParams as FrameLayout.LayoutParams
        if (lp.width == targetWidth && lp.height == targetHeight && lp.gravity == Gravity.CENTER) return
        lp.width = targetWidth
        lp.height = targetHeight
        lp.gravity = Gravity.CENTER
        cameraPreview.layoutParams = lp
    }

    // ─────────────────────────── Window insets (status bar / nav bar) ───────────────────────────

    private fun setupWindowInsets() {
        val basePadTop = resources.getDimensionPixelSize(R.dimen.cam_pad_top)
        val basePadBottom = resources.getDimensionPixelSize(R.dimen.cam_pad_bottom)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            topLayer.setPadding(topLayer.paddingLeft, basePadTop + top, topLayer.paddingRight, topLayer.paddingBottom)
            bottomLayer.setPadding(
                bottomLayer.paddingLeft,
                bottomLayer.paddingTop,
                bottomLayer.paddingRight,
                basePadBottom + bottom,
            )
            hudSheet.setPadding(hudSheet.paddingLeft, hudSheet.paddingTop, hudSheet.paddingRight, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    companion object {
        private const val RECONNECT_RESERVATION_MS = 45_000L
        private const val IDENTIFY_OVERLAY_MS = 3_000L
        private const val IDENTIFY_FADE_MS = 180L
        private const val ZOOM_LABEL_MS = 1_400L
        private const val STOPPED_DETAIL_MS = 2_000L
        private const val RECOVERED_BANNER_MS = 6_000L
        private const val RISE_ALERT_MS = 250L
        private const val RISE_TOOLS_MS = 200L
        private const val DISPLAY_OFF_PULSE_MS = 1_600L
        private const val DISPLAY_OFF_BRIGHTNESS = 0.05f
        private const val LENS_RESTART_DELAY_MS = 500L
        private const val LISTENER_POLL_MS = 250L
        private const val LISTENER_RETRY_MS = 750L
        private const val LISTENER_STOP_TIMEOUT_MS = 2_000L
        private const val CALLER_RECONNECT_BASE_DELAY_MS = 750L
        private const val CALLER_RECONNECT_MAX_DELAY_MS = 5_000L
        private const val TELEMETRY_LOG_INTERVAL_TICKS = 10L
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val SETTINGS_REQUEST_CODE = 200
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
