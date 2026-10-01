package com.voicecontrol.core.accessibility.di

import com.voicecontrol.core.accessibility.AccessibilityBridge
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.screen.ScreenParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccessibilityModule {
    /** Declares the (possibly empty) set of [ServiceListener]s. */
    @Multibinds
    abstract fun serviceListeners(): Set<ServiceListener>

    @Binds
    abstract fun screenGateway(bridge: AccessibilityBridge): ScreenGateway

    companion object {
        @Provides
        @Singleton
        fun screenParser(): ScreenParser = ScreenParser()
    }
}
