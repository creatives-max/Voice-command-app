package com.voicecontrol.feature.assistant.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.engine.AssistantEngine
import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.engine.port.Interpreter
import com.voicecontrol.core.engine.port.ProfileSource
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.SessionConfigProvider
import com.voicecontrol.core.engine.port.SessionRecorder
import com.voicecontrol.core.engine.port.SpeechDetector
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.engine.port.FlowLauncher
import com.voicecontrol.feature.assistant.AssistantController
import com.voicecontrol.feature.assistant.MicPermission
import com.voicecontrol.feature.assistant.remote.RemoteRunCoordinator
import com.voicecontrol.feature.assistant.wake.WakeWordListener
import com.voicecontrol.feature.assistant.overlay.OverlayManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantModule {
    @Binds
    @IntoSet
    abstract fun overlayListener(manager: OverlayManager): ServiceListener

    @Binds
    @IntoSet
    abstract fun remoteRunListener(coordinator: RemoteRunCoordinator): ServiceListener

    @Binds
    @IntoSet
    abstract fun wakeWordListener(listener: WakeWordListener): ServiceListener

    @Binds
    abstract fun flowLauncher(controller: AssistantController): FlowLauncher

    @Binds
    abstract fun documentTextReader(impl: com.voicecontrol.feature.assistant.scan.MlKitDocumentTextReader): com.voicecontrol.feature.assistant.scan.DocumentTextReader

    @Binds
    abstract fun teachLauncher(controller: com.voicecontrol.feature.assistant.teach.TeachController): com.voicecontrol.core.engine.port.TeachLauncher

    companion object {
        @Provides
        @Singleton
        fun engine(
            screen: ScreenGateway,
            stt: SpeechToText,
            tts: TextToSpeech,
            interpreter: Interpreter,
            flows: FlowSource,
            profiles: ProfileSource,
            recorder: SessionRecorder,
            config: SessionConfigProvider,
            vision: VisionDetector,
            speechDetector: SpeechDetector,
            shortcuts: com.voicecontrol.core.engine.port.ShortcutSource,
        ): AssistantEngine = AssistantEngine(
            screen = screen,
            stt = stt,
            tts = tts,
            interpreter = interpreter,
            flows = flows,
            profiles = profiles,
            recorder = recorder,
            config = config,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            vision = vision,
            speechDetector = speechDetector,
            shortcuts = shortcuts,
        )

        @Provides
        fun micPermission(@ApplicationContext context: Context): MicPermission = MicPermission {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }
}
