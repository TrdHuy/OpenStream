package dev.openstream.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size

/** Một chế độ quay có thể dùng với phiên Camera2 thông thường. */
data class CameraStreamMode(
    val lens: CameraLens,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
) {
    val label: String get() = "${width}×${height} @ ${fps} fps"
}

data class CameraLensCapability(
    val lens: CameraLens,
    val cameraId: String,
    val modes: List<CameraStreamMode>,
)

/**
 * Đọc khả năng theo từng camera vật lý. Không suy luận 4K của một ống kính sang
 * ống kính khác và không tạo chế độ bằng nội suy/phóng hình.
 */
class CameraCapabilityProbe(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    fun query(): List<CameraLensCapability> {
        return resolveLensCameraPairs().mapNotNull { (lens, cameraId) ->
            val chars = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull()
                ?: return@mapNotNull null
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val sizes = runCatching { map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty() }
                .getOrDefault(emptyList())
            val aeRanges: List<Range<Int>> = chars
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { range -> Range(range.lower, range.upper) }
                .orEmpty()
            val modes = sizes
                .flatMap { size -> modesForSize(lens, cameraId, size, aeRanges, map) }
                .distinctBy { Triple(it.width, it.height, it.fps) }
                .sortedWith(
                    compareByDescending<CameraStreamMode> { it.width.toLong() * it.height }
                        .thenByDescending { it.fps },
                )
            CameraLensCapability(lens, cameraId, modes)
        }
    }

    private fun modesForSize(
        lens: CameraLens,
        cameraId: String,
        size: Size,
        aeRanges: List<Range<Int>>,
        map: android.hardware.camera2.params.StreamConfigurationMap,
    ): List<CameraStreamMode> {
        val minDurationNs = runCatching {
            map.getOutputMinFrameDuration(MediaCodec::class.java, size)
        }.getOrDefault(0L)
        val maxFpsFromDuration = if (minDurationNs > 0L) {
            1_000_000_000.0 / minDurationNs
        } else {
            Double.POSITIVE_INFINITY
        }

        // Một số HAL (đã gặp trên Galaxy S25 Edge) báo min-frame-duration bảo thủ
        // cho surface MediaCodec, dù public CamcorderProfile/EncoderProfiles và một
        // Camera2 TEMPLATE_RECORD session thực tế vẫn chạy 2160p60. Dùng profile
        // quay public của Android như nguồn capability thứ hai thay vì hardcode
        // theo model/vendor; MediaCodec capability vẫn được intersect ở resolver.
        val publicRecordingMaxFps = publicRecordingProfileMaxFps(cameraId, size)

        val candidates = listOf(24, 30, 60)
        return candidates.filter { fps ->
            val aeAllows = aeRanges.isEmpty() || aeRanges.any { fps in it.lower..it.upper }
            val durationAllows = maxFpsFromDuration + 0.5 >= fps
            val recordingProfileAllows = (publicRecordingMaxFps ?: 0) >= fps
            aeAllows && (durationAllows || recordingProfileAllows)
        }.map { fps ->
            CameraStreamMode(
                lens = lens,
                cameraId = cameraId,
                width = size.width,
                height = size.height,
                fps = fps,
            )
        }
    }

    private fun publicRecordingProfileMaxFps(cameraId: String, size: Size): Int? {
        val quality = qualityForSize(size) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                CamcorderProfile.getAll(cameraId, quality)
                    ?.videoProfiles
                    ?.asSequence()
                    ?.filter { profile ->
                        profile.width == size.width && profile.height == size.height
                    }
                    ?.maxOfOrNull { profile -> profile.frameRate }
            }.getOrNull()
        } else {
            val numericCameraId = cameraId.toIntOrNull() ?: return null
            runCatching {
                if (!CamcorderProfile.hasProfile(numericCameraId, quality)) {
                    return@runCatching null
                }
                @Suppress("DEPRECATION")
                CamcorderProfile.get(numericCameraId, quality)
                    .takeIf { profile ->
                        profile.videoFrameWidth == size.width &&
                            profile.videoFrameHeight == size.height
                    }
                    ?.videoFrameRate
            }.getOrNull()
        }
    }

    private fun qualityForSize(size: Size): Int? = when {
        size.width == 3840 && size.height == 2160 -> CamcorderProfile.QUALITY_2160P
        size.width == 1920 && size.height == 1080 -> CamcorderProfile.QUALITY_1080P
        size.width == 1280 && size.height == 720 -> CamcorderProfile.QUALITY_720P
        size.width == 720 && size.height == 480 -> CamcorderProfile.QUALITY_480P
        else -> null
    }

    private fun resolveLensCameraPairs(): List<Pair<CameraLens, String>> {
        data class CamInfo(val id: String, val focal: Float, val facing: Int)
        val cameras = cameraManager.cameraIdList.mapNotNull { id ->
            val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
            CamInfo(id, focal, facing)
        }
        val back = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy { it.focal }
        val front = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedBy { it.focal }

        val pairs = mutableListOf<Pair<CameraLens, String>>()
        when {
            back.size >= 3 -> {
                pairs += CameraLens.BackUltrawide to back.first().id
                pairs += CameraLens.Back to back[1].id
                pairs += CameraLens.BackTelephoto to back.last().id
            }
            back.size == 2 -> {
                val ratio = if (back[0].focal > 0f) back[1].focal / back[0].focal else 1f
                if (ratio > 1.5f) {
                    // Giữ cùng quy ước lựa chọn hiện tại của Camera2Controller.
                    pairs += CameraLens.Back to back[1].id
                    pairs += CameraLens.BackTelephoto to back.last().id
                } else {
                    pairs += CameraLens.BackUltrawide to back.first().id
                    pairs += CameraLens.Back to back[1].id
                }
            }
            back.size == 1 -> pairs += CameraLens.Back to back.first().id
        }
        front.firstOrNull()?.let { pairs += CameraLens.Front to it.id }
        return pairs.distinct()
    }
}
