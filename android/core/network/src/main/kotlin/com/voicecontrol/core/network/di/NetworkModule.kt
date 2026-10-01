package com.voicecontrol.core.network.di

import com.voicecontrol.core.network.ApiClient
import com.voicecontrol.core.network.BackendSession
import com.voicecontrol.core.network.BuildConfig
import com.voicecontrol.core.network.createHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun httpClient(): HttpClient = createHttpClient(OkHttp.create(), BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun apiClient(http: HttpClient, session: BackendSession): ApiClient = ApiClient(http, session)
}
