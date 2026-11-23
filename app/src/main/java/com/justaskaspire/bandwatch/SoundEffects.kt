package com.justaskaspire.bandwatch

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Small synthesized tech sound effects. No audio files or network access required.
 * System silent/vibrate modes are respected.
 */
object SoundEffects {
    enum class Event { BAND7, BAND3, SPEED, START, STOP }

    fun play(context: Context, event: Event, preview: Boolean = false) {
        val prefs = context.getSharedPreferences("bandwatch", Context.MODE_PRIVATE)
        if (!preview && (!prefs.getBoolean("sound_master", true) ||
                !prefs.getBoolean(keyFor(event), true))) return
        if (prefs.getInt("sound_volume", 70) <= 0) return

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val streamVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        if (streamVolume <= 0) return

        val volume = prefs.getInt("sound_volume", 70).coerceIn(0, 100)
        val generator = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume) }
            catch (_: Exception) { return }
        val handler = Handler(Looper.getMainLooper())
        val pattern = when (event) {
            Event.BAND7 -> listOf(ToneGenerator.TONE_PROP_PROMPT to 110, ToneGenerator.TONE_PROP_BEEP to 100, ToneGenerator.TONE_PROP_PROMPT to 150)
            Event.BAND3 -> listOf(ToneGenerator.TONE_PROP_BEEP to 180)
            Event.SPEED -> listOf(ToneGenerator.TONE_PROP_ACK to 110, ToneGenerator.TONE_PROP_ACK to 110)
            Event.START -> listOf(ToneGenerator.TONE_PROP_PROMPT to 100, ToneGenerator.TONE_PROP_PROMPT to 100, ToneGenerator.TONE_PROP_ACK to 140)
            Event.STOP -> listOf(ToneGenerator.TONE_PROP_ACK to 100, ToneGenerator.TONE_PROP_NACK to 180)
        }
        var delay = 0L
        pattern.forEach { (tone, duration) ->
            handler.postDelayed({ generator.startTone(tone, duration) }, delay)
            delay += duration.toLong() + 65L
        }
        handler.postDelayed({ generator.release() }, delay + 100L)
    }

    private fun keyFor(event: Event): String = when (event) {
        Event.BAND7 -> "sound_band7"
        Event.BAND3 -> "sound_band3"
        Event.SPEED -> "sound_speed"
        Event.START -> "sound_start"
        Event.STOP -> "sound_stop"
    }
}
