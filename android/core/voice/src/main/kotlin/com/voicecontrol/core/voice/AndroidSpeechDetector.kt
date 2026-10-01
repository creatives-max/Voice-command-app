package com.voicecontrol.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.voicecontrol.core.engine.SpeechOnset
import com.voicecontrol.core.engine.port.SpeechDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Barge-in detector: listens with the voice-communication source (platform echo cancellation) while
 * the assistant talks and returns when the user starts speaking. Audio is analysed for loudness only,
 * in memory, and never stored or sent.
 */
@Singleton
class AndroidSpeechDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechDetector {

    @SuppressLint("MissingPermission")
    override suspend fun awaitSpeech() = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitCancellation()
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) awaitCancellation()
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            awaitCancellation()
        }
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true } else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } else null
        try {
            record.startRecording()
            val onset = SpeechOnset()
            val frame = ShortArray(SAMPLE_RATE / FRAMES_PER_SECOND)
            while (true) {
                ensureActive()
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                if (onset.onFrame(sqrt(sum / n))) return@withContext
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            echo?.release()
            noise?.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAMES_PER_SECOND = 50 // 20 ms frames
    }
}
