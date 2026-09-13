package dev.openstream.app.stream

import android.content.Context
import dev.openstream.app.camera.CameraCapabilityProbe
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.HardwareVideoCapabilityProbe

data class SupportedStreamMode(
    val lens: CameraLens,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val highProfileAvailable: Boolean,
) {
    val label: String
        get() = "${width}×${height} @ ${fps} fps" + if (highProfileAvailable) " · High khả dụng" else ""
}

/** Giao của khả năng Camera2 và MediaCodec phần cứng. */
class StreamingCapabilityResolver(context: Context) {
    private val cameraProbe = CameraCapabilityProbe(context)
    private val encoderProbe = HardwareVideoCapabilityProbe()

    fun resolve(bitrate: Int): List<SupportedStreamMode> {
        return cameraProbe.query().flatMap { cameraCapability ->
            cameraCapability.modes.mapNotNull { cameraMode ->
                if (!encoderProbe.supportsAvc(
                        cameraMode.width,
                        cameraMode.height,
                        cameraMode.fps,
                        bitrate,
                    )
                ) {
                    return@mapNotNull null
                }
                SupportedStreamMode(
                    lens = cameraCapability.lens,
                    cameraId = cameraCapability.cameraId,
                    width = cameraMode.width,
                    height = cameraMode.height,
                    fps = cameraMode.fps,
                    highProfileAvailable = encoderProbe.supportsHighProfile(
                        cameraMode.width,
                        cameraMode.height,
                        cameraMode.fps,
                        bitrate,
                    ),
                )
            }
        }.distinctBy { listOf(it.lens.name, it.width.toString(), it.height.toString(), it.fps.toString()) }
    }
}
