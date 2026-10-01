package com.voicecontrol.core.voice.di

import com.voicecontrol.core.engine.port.SpeechDetector
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.voice.AndroidSpeechDetector
import com.voicecontrol.core.voice.AndroidSpeechToText
import com.voicecontrol.core.voice.AndroidTextToSpeech
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the platform speech engines. To use a cloud STT/TTS, provide another implementation of the
 * ports here; nothing else in the app changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    abstract fun speechToText(impl: AndroidSpeechToText): SpeechToText

    @Binds
    abstract fun textToSpeech(impl: AndroidTextToSpeech): TextToSpeech

    @Binds
    abstract fun speechDetector(impl: AndroidSpeechDetector): SpeechDetector
}
