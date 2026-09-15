package dev.openstream.app.stream

import android.content.Context
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.encoder.VideoBitrateMode

/** Persists reusable stream + connection profiles without changing the active settings implicitly. */
object StreamProfileStore {
    private const val PREFS_NAME = "openstream_profiles"
    private const val KEY_IDS = "profile_ids"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    fun list(context: Context): List<StreamProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
            .mapNotNull { id -> load(context, id) }
            .sortedBy { it.name.lowercase() }
    }

    fun active(context: Context): StreamProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ACTIVE_ID, null) ?: return null
        return load(context, id)
    }

    fun load(context: Context, id: String): StreamProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(key(id, "name"), null) ?: return null
        val defaults = StreamConfig.Baseline1080p30
        val config = defaults.copy(
            width = prefs.getInt(key(id, "width"), defaults.width),
            height = prefs.getInt(key(id, "height"), defaults.height),
            fps = prefs.getInt(key(id, "fps"), defaults.fps),
            bitrate = prefs.getInt(key(id, "bitrate_mbps"), defaults.bitrateMbps) * 1_000_000,
            keyframeIntervalSeconds = prefs.getInt(
                key(id, "keyframe_seconds"),
                defaults.keyframeIntervalSeconds,
            ),
            latencyMs = prefs.getInt(key(id, "latency_ms"), defaults.latencyMs),
            codecPreference = enumValueOrDefault(
                prefs.getString(key(id, "codec"), null),
                defaults.codecPreference,
            ),
            videoBitrateMode = enumValueOrDefault(
                prefs.getString(key(id, "bitrate_mode"), null),
                defaults.videoBitrateMode,
            ),
            avcProfilePreference = enumValueOrDefault(
                prefs.getString(key(id, "avc_profile"), null),
                defaults.avcProfilePreference,
            ),
            bFramesEnabled = prefs.getBoolean(key(id, "b_frames"), defaults.bFramesEnabled),
            audioEnabled = prefs.getBoolean(key(id, "audio_enabled"), defaults.audioEnabled),
            audioSampleRate = prefs.getInt(key(id, "audio_rate"), defaults.audioSampleRate),
            audioChannelCount = prefs.getInt(key(id, "audio_channels"), defaults.audioChannelCount),
            audioBitrate = prefs.getInt(key(id, "audio_bitrate_kbps"), defaults.audioBitrateKbps) * 1_000,
        )
        val lens = prefs.getString(key(id, "lens"), null)
            ?.let { raw -> runCatching { CameraLens.valueOf(raw) }.getOrNull() }
        return StreamProfile(
            id = id,
            name = name,
            config = config,
            lens = lens,
            obsHost = prefs.getString(key(id, "obs_host"), "").orEmpty(),
            obsPort = prefs.getInt(key(id, "obs_port"), ConnectionTarget.DEFAULT_PORT),
            listeningPort = prefs.getInt(key(id, "listen_port"), ConnectionTarget.DEFAULT_PORT),
        )
    }

    fun save(context: Context, profile: StreamProfile, makeActive: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet().apply {
            add(profile.id)
        }
        prefs.edit()
            .putStringSet(KEY_IDS, ids.toSet())
            .putString(key(profile.id, "name"), profile.name)
            .putInt(key(profile.id, "width"), profile.config.width)
            .putInt(key(profile.id, "height"), profile.config.height)
            .putInt(key(profile.id, "fps"), profile.config.fps)
            .putInt(key(profile.id, "bitrate_mbps"), profile.config.bitrateMbps)
            .putInt(key(profile.id, "keyframe_seconds"), profile.config.keyframeIntervalSeconds)
            .putInt(key(profile.id, "latency_ms"), profile.config.latencyMs)
            .putString(key(profile.id, "codec"), profile.config.codecPreference.name)
            .putString(key(profile.id, "bitrate_mode"), profile.config.videoBitrateMode.name)
            .putString(key(profile.id, "avc_profile"), profile.config.avcProfilePreference.name)
            .putBoolean(key(profile.id, "b_frames"), profile.config.bFramesEnabled)
            .putBoolean(key(profile.id, "audio_enabled"), profile.config.audioEnabled)
            .putInt(key(profile.id, "audio_rate"), profile.config.audioSampleRate)
            .putInt(key(profile.id, "audio_channels"), profile.config.audioChannelCount)
            .putInt(key(profile.id, "audio_bitrate_kbps"), profile.config.audioBitrateKbps)
            .putString(key(profile.id, "lens"), profile.lens?.name)
            .putString(key(profile.id, "obs_host"), profile.obsHost)
            .putInt(key(profile.id, "obs_port"), profile.obsPort)
            .putInt(key(profile.id, "listen_port"), profile.listeningPort)
            .also { editor -> if (makeActive) editor.putString(KEY_ACTIVE_ID, profile.id) }
            .apply()
    }

    fun setActive(context: Context, id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ID, id)
            .apply()
    }

    fun delete(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet().apply { remove(id) }
        val editor = prefs.edit().putStringSet(KEY_IDS, ids.toSet())
        SUFFIXES.forEach { suffix -> editor.remove(key(id, suffix)) }
        if (prefs.getString(KEY_ACTIVE_ID, null) == id) {
            editor.putString(KEY_ACTIVE_ID, ids.firstOrNull())
        }
        editor.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun key(id: String, suffix: String): String = "profile_${id}_$suffix"

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T {
        return raw?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: fallback
    }

    private val SUFFIXES = listOf(
        "name", "width", "height", "fps", "bitrate_mbps", "keyframe_seconds", "latency_ms",
        "codec", "bitrate_mode", "avc_profile", "b_frames", "audio_enabled", "audio_rate",
        "audio_channels", "audio_bitrate_kbps", "lens", "obs_host", "obs_port", "listen_port",
    )
}
