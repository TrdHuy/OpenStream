package dev.openstream.app.stream

import dev.openstream.app.camera.CameraLens

data class StreamProfile(
    val id: String,
    val name: String,
    val config: StreamConfig,
    val lens: CameraLens?,
    val obsHost: String,
    val obsPort: Int,
    val listeningPort: Int,
)
