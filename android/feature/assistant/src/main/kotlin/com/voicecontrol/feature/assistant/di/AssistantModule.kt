package com.voicecontrol.feature.assistant.di

import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.feature.assistant.overlay.OverlayManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantModule {
    @Binds
    @IntoSet
    abstract fun overlayListener(manager: OverlayManager): ServiceListener
}
