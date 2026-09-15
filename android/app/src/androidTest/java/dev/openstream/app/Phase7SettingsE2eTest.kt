package dev.openstream.app

import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamPreset
import dev.openstream.app.stream.StreamProfileStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase7SettingsE2eTest {

    @Test
    fun currentConfigCanBeSavedWithoutCreatingProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val base = StreamConfig.Baseline1080p30
        StreamConfigStore.save(context, base)
        StreamConfig.installRuntimeConfig(base)
        StreamProfileStore.clear(context)

        val baselineModes = StreamingCapabilityResolver(context).resolve(base)
        assertTrue("Device must expose at least one Camera2 + hardware AVC mode", baselineModes.isNotEmpty())
        val lens = baselineModes.first().lens
        context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsActivity.KEY_CAPABILITY_LENS, lens.name)
            .putString(SettingsActivity.KEY_OBS_HOST, "")
            .putInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
            .putInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
            .apply()

        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as SettingsActivity
        instrumentation.waitForIdleSync()

        val profileSpinner = activity.findViewById<Spinner>(R.id.settingsProfile)
        val saveSettings = activity.findViewById<TextView>(R.id.btnSaveSettings)

        instrumentation.runOnMainSync {
            assertTrue("Saving the current config must not require a profile", saveSettings.isEnabled)
            assertTrue(
                "Empty profile state must explain that direct config editing is still available",
                profileSpinner.getItemAtPosition(0).toString().contains("vẫn sửa config"),
            )
            saveSettings.performClick()
        }
        instrumentation.waitForIdleSync()

        assertTrue("Saving current config must not create a profile", StreamProfileStore.list(context).isEmpty())
        assertEquals(base.width, StreamConfigStore.load(context).width)
        assertEquals(base.height, StreamConfigStore.load(context).height)
        assertEquals(base.fps, StreamConfigStore.load(context).fps)
        StreamProfileStore.clear(context)
    }

    @Test
    fun profileCrudPresetSelectionAndCapabilityReasonsWorkOnRealDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val base = StreamConfig.Baseline1080p30
        StreamConfigStore.save(context, base)
        StreamConfig.installRuntimeConfig(base)
        StreamProfileStore.clear(context)

        val resolver = StreamingCapabilityResolver(context)
        val baselineModes = resolver.resolve(base)
        assertTrue("Device must expose at least one Camera2 + hardware AVC mode", baselineModes.isNotEmpty())
        val lens = baselineModes.first().lens
        context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsActivity.KEY_CAPABILITY_LENS, lens.name)
            .putString(SettingsActivity.KEY_OBS_HOST, "100.64.0.10")
            .putInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
            .putInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
            .apply()

        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as SettingsActivity
        instrumentation.waitForIdleSync()

        val profileSpinner = activity.findViewById<Spinner>(R.id.settingsProfile)
        val profileName = activity.findViewById<EditText>(R.id.settingsProfileName)
        val host = activity.findViewById<EditText>(R.id.settingsObsHost)
        val newProfile = activity.findViewById<TextView>(R.id.btnNewProfile)
        val saveProfile = activity.findViewById<TextView>(R.id.btnSaveProfile)
        val useProfile = activity.findViewById<TextView>(R.id.btnUseProfile)
        val deleteProfile = activity.findViewById<TextView>(R.id.btnDeleteProfile)
        val presetSpinner = activity.findViewById<Spinner>(R.id.settingsPreset)
        val presetNote = activity.findViewById<TextView>(R.id.settingsPresetNote)
        val width = activity.findViewById<EditText>(R.id.settingsWidth)
        val height = activity.findViewById<EditText>(R.id.settingsHeight)
        val fps = activity.findViewById<EditText>(R.id.settingsFps)

        instrumentation.runOnMainSync {
            assertNotNull(profileSpinner)
            assertNotNull(presetSpinner)
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsCapabilityLens))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsCapabilityMode))
            assertNotNull(activity.findViewById<android.view.View>(R.id.settingsAudioEnabled))
            assertNotNull(host)
            assertEquals(StreamPreset.entries.size, presetSpinner.count)
        }

        // Create the first profile through the real Settings UI.
        instrumentation.runOnMainSync {
            newProfile.performClick()
            profileName.setText("Studio A")
            host.setText("100.64.0.10")
            saveProfile.performClick()
        }
        instrumentation.waitForIdleSync()
        var stored = StreamProfileStore.list(context)
        assertEquals(1, stored.size)
        assertEquals("Studio A", stored.single().name)
        assertEquals("Studio A", StreamProfileStore.active(context)?.name)

        // Update the selected profile instead of creating a duplicate.
        instrumentation.runOnMainSync {
            host.setText("100.64.0.20")
            saveProfile.performClick()
        }
        instrumentation.waitForIdleSync()
        stored = StreamProfileStore.list(context)
        assertEquals(1, stored.size)
        assertEquals("100.64.0.20", stored.single().obsHost)

        // Create a second profile.
        instrumentation.runOnMainSync {
            newProfile.performClick()
            profileName.setText("Studio B")
            host.setText("100.64.0.30")
            saveProfile.performClick()
        }
        instrumentation.waitForIdleSync()
        stored = StreamProfileStore.list(context)
        assertEquals(listOf("Studio A", "Studio B"), stored.map { it.name })

        // Reuse Studio A and verify that endpoint settings are restored by the UI action.
        instrumentation.runOnMainSync {
            val indexA = (0 until profileSpinner.count).first { index ->
                profileSpinner.getItemAtPosition(index).toString().contains("Studio A")
            }
            profileSpinner.setSelection(indexA)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { useProfile.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals("Studio A", StreamProfileStore.active(context)?.name)
        assertEquals(
            "100.64.0.20",
            context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_OBS_HOST, null),
        )

        // Invalid profile names are blocked before persistence.
        instrumentation.runOnMainSync {
            newProfile.performClick()
            profileName.setText("   ")
            saveProfile.performClick()
        }
        instrumentation.waitForIdleSync()
        assertNotNull(profileName.error)
        assertEquals(2, StreamProfileStore.list(context).size)

        // Delete Studio B using the real UI action.
        instrumentation.runOnMainSync {
            val indexB = (0 until profileSpinner.count).first { index ->
                profileSpinner.getItemAtPosition(index).toString().contains("Studio B")
            }
            profileSpinner.setSelection(indexB)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { deleteProfile.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Studio A"), StreamProfileStore.list(context).map { it.name })

        // Every preset must expose the same supported/unsupported truth as the real capability resolver.
        val labels = (0 until presetSpinner.count).map { index ->
            presetSpinner.getItemAtPosition(index).toString()
        }
        val options = StreamPreset.entries.map { preset ->
            val candidate = preset.applyTo(base)
            val supported = resolver.resolve(candidate).any { mode ->
                mode.lens == lens &&
                    mode.width == preset.width &&
                    mode.height == preset.height &&
                    mode.fps == preset.fps
            }
            preset to supported
        }
        options.forEachIndexed { index, (preset, supported) ->
            assertEquals(
                "Preset label must expose real unsupported state for ${preset.displayName}",
                !supported,
                labels[index].contains("Không hỗ trợ"),
            )
        }

        val supportedIndex = options.indexOfFirst { it.second }
        if (supportedIndex >= 0) {
            val preset = options[supportedIndex].first
            instrumentation.runOnMainSync { presetSpinner.setSelection(supportedIndex) }
            instrumentation.waitForIdleSync()
            assertEquals(preset.width.toString(), width.text.toString())
            assertEquals(preset.height.toString(), height.text.toString())
            assertEquals(preset.fps.toString(), fps.text.toString())
        }

        val unsupportedIndex = options.indexOfFirst { !it.second }
        if (unsupportedIndex >= 0) {
            instrumentation.runOnMainSync { presetSpinner.setSelection(unsupportedIndex) }
            instrumentation.waitForIdleSync()
            assertTrue(
                "Unsupported preset must explain why it cannot be applied",
                presetNote.text.toString().contains("không", ignoreCase = true),
            )
        }

        instrumentation.runOnMainSync { activity.finish() }
        StreamProfileStore.clear(context)
    }
}
