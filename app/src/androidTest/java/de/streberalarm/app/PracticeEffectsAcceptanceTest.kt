package de.streberalarm.app

import android.Manifest
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PracticeEffectsAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun ownedPlayerHonorsMuteSilentBackgroundAndReducedMotion() {
        assertTrue("This system-settings test runs only on the owned emulator", Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val audio = ui.activity.getSystemService(AudioManager::class.java)
        val notificationManager = ui.activity.getSystemService(android.app.NotificationManager::class.java)
        val originalPolicyAccess = notificationManager.isNotificationPolicyAccessGranted
        val originalInterruptionFilter = notificationManager.currentInterruptionFilter
        val originalRinger = audio.ringerMode
        val originalAnimations = ui.activity.appearance.state.value.animations
        val originalAnimator = device.executeShellCommand("settings get global animator_duration_scale").trim()
        val originalTransition = device.executeShellCommand("settings get global transition_animation_scale").trim()
        var effects: PracticeEffects? = null
        var originalMute = false
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.ACCESS_NOTIFICATION_POLICY)
        try {
            if (!originalPolicyAccess) device.executeShellCommand("cmd notification allow_dnd com.streberalarm.app")
            assertTrue("The emulator-only ringer test has explicit policy access", notificationManager.isNotificationPolicyAccessGranted)
            device.executeShellCommand("settings put global animator_duration_scale 1")
            device.executeShellCommand("settings put global transition_animation_scale 1")
            ui.runOnIdle {
                val e = PracticeEffects(ui.activity)
                effects = e
                originalMute = e.muted
                e.changeMuted(false)
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                e.persistedAnswer("normal-1", true)
                assertTrue("A persisted successful answer starts the bundled player", e.audioPlaying)
                e.changeMuted(true)
                assertFalse("Muting immediately releases an active player", e.audioPlaying)
                e.changeMuted(false)
                audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
                e.persistedAnswer("silent-1", true)
                assertFalse("Silent mode suppresses sound", e.audioPlaying)
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                e.persistedAnswer("normal-2", true)
                assertTrue(e.audioPlaying)
            }
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {
                val e = effects!!
                assertFalse("Background lifecycle stops active sound", e.audioPlaying)
                e.persistedAnswer("background-1", true)
                assertFalse("A late persisted callback cannot play in background", e.audioPlaying)
                assertFalse(e.animationEnabled())
            }
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.runOnIdle {
                val e = effects!!
                assertFalse("Resume does not replay prior feedback", e.audioPlaying)
                e.persistedAnswer("normal-2", true)
                assertFalse("The same persisted token is never replayed", e.audioPlaying)
                ui.activity.appearance.animate(true)
                assertTrue(e.animationEnabled())
            }
            device.executeShellCommand("settings put global animator_duration_scale 0")
            ui.runOnIdle { assertFalse(effects!!.animationEnabled()) }
            device.executeShellCommand("settings put global animator_duration_scale 1")
            ui.runOnIdle {
                ui.activity.appearance.animate(false)
                assertFalse(effects!!.animationEnabled())
            }
        } finally {
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.runOnIdle {
                effects?.changeMuted(originalMute)
                effects?.close()
                audio.ringerMode = originalRinger
                notificationManager.setInterruptionFilter(originalInterruptionFilter)
                ui.activity.appearance.animate(originalAnimations)
            }
            for ((key, value) in listOf("animator_duration_scale" to originalAnimator, "transition_animation_scale" to originalTransition)) {
                if (value == "null") device.executeShellCommand("settings delete global $key")
                else device.executeShellCommand("settings put global $key $value")
            }
            if (!originalPolicyAccess) device.executeShellCommand("cmd notification disallow_dnd com.streberalarm.app")
            assertEquals(originalPolicyAccess, notificationManager.isNotificationPolicyAccessGranted)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
