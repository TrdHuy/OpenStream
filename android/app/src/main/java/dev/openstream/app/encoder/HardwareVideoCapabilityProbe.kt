package dev.openstream.app.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Mô tả khả năng của một bộ mã hóa AVC phần cứng. */
data class HardwareAvcCapability(
    val codecName: String,
    val minBitrate: Int,
    val maxBitrate: Int,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val profileLevels: List<Pair<Int, Int>>,
)

class HardwareVideoCapabilityProbe {
    private data class Candidate(
        val info: MediaCodecInfo,
        val capabilities: MediaCodecInfo.CodecCapabilities,
    )

    private val avcCandidates: List<Candidate> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (!info.isEncoder || !info.isHardwareAccelerated || info.isSoftwareOnly ||
                info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            ) {
                return@mapNotNull null
            }
            val caps = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }.getOrNull() ?: return@mapNotNull null
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                return@mapNotNull null
            }
            Candidate(info, caps)
        }
    }

    fun capabilities(): List<HardwareAvcCapability> {
        return avcCandidates.map { candidate ->
            val video = candidate.capabilities.videoCapabilities
            val encoder = candidate.capabilities.encoderCapabilities
            HardwareAvcCapability(
                codecName = candidate.info.name,
                minBitrate = video.bitrateRange.lower,
                maxBitrate = video.bitrateRange.upper,
                supportsCbr = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
                supportsVbr = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
                profileLevels = candidate.capabilities.profileLevels.map { it.profile to it.level },
            )
        }
    }

    fun supportsAvc(width: Int, height: Int, fps: Int, bitrate: Int): Boolean {
        return avcCandidates.any { candidate ->
            val video = candidate.capabilities.videoCapabilities
            runCatching {
                video.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                    video.bitrateRange.contains(bitrate) &&
                    candidate.capabilities.encoderCapabilities.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
            }.getOrDefault(false)
        }
    }

    fun supportsHighProfile(width: Int, height: Int, fps: Int, bitrate: Int): Boolean {
        return avcCandidates.any { candidate ->
            val highProfile = candidate.capabilities.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            }
            highProfile && runCatching {
                candidate.capabilities.videoCapabilities.areSizeAndRateSupported(
                    width,
                    height,
                    fps.toDouble(),
                ) && candidate.capabilities.videoCapabilities.bitrateRange.contains(bitrate)
            }.getOrDefault(false)
        }
    }
}
