package de.streberalarm.app

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Feedback is triggered only by the confirmed repository callback, never by restored UI state. */
internal class PracticeEffects(private val activity: MainActivity) : java.io.Closeable {
    private val prefs = activity.getSharedPreferences("practice-effects", Context.MODE_PRIVATE)
    var muted by mutableStateOf(prefs.getBoolean("muted", false))
        private set

    private val played = mutableSetOf<String>()
    private var player: MediaPlayer? = null
    var celebration by mutableStateOf<String?>(null)
        private set

    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) stopAudio()
    }
    init { activity.lifecycle.addObserver(observer) }

    internal val audioPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    override fun close() {
        activity.lifecycle.removeObserver(observer)
        stopAudio()
    }

    fun changeMuted(value: Boolean) {
        if (prefs.edit().putBoolean("muted", value).commit()) {
            muted = value
            if (value) stopAudio()
        }
    }

    fun persistedAnswer(token: String, known: Boolean) {
        if (!played.add(token)) return
        if (!known || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        celebration = token
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (muted || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
                stopAudio()
                MediaPlayer.create(activity, R.raw.practice_success)?.apply {
                    player = this
                    setOnCompletionListener { stopAudio() }
                    setOnErrorListener { _, _, _ ->
                        stopAudio()
                        true
                    }
                    start()
                }
            }
            .onFailure { stopAudio() }
    }

    fun stopAudio() {
        val previous = player
        player = null
        runCatching { previous?.release() }
    }

    fun animationEnabled(): Boolean =
        runCatching {
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    activity
                        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
                        .getBoolean("animations", true) &&
                    Settings.Global.getFloat(
                        activity.contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE,
                        1f,
                    ) > 0f &&
                    Settings.Global.getFloat(
                        activity.contentResolver,
                        Settings.Global.TRANSITION_ANIMATION_SCALE,
                        1f,
                    ) > 0f
            }
            .getOrDefault(true)
}

@Composable
internal fun rememberPracticeEffects(activity: MainActivity): PracticeEffects {
    val effects = remember(activity) { PracticeEffects(activity) }
    DisposableEffect(activity, effects) { onDispose { effects.close() } }
    return effects
}

@Composable
internal fun ColumnScope.PracticeSoundControl(effects: PracticeEffects) {
    androidx.compose.foundation.layout.Row {
        Text("Erfolgston", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = !effects.muted,
            onCheckedChange = { effects.changeMuted(!it) },
            modifier =
                Modifier.testTag("practice-sound").semantics { contentDescription = "Erfolgston" },
        )
    }
}

@Composable
internal fun PracticeCelebration(effects: PracticeEffects, token: String) {
    val visible = effects.celebration == token
    if (effects.animationEnabled()) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(), exit = fadeOut()) {
            Text("Geschafft! ✨", style = MaterialTheme.typography.titleLarge)
        }
    } else if (visible) Text("Geschafft! ✨", style = MaterialTheme.typography.titleLarge)
}
