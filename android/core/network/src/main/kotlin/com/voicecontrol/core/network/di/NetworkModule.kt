package com.voicecontrol.core.network.di

import android.content.Context
import android.os.Build
import com.voicecontrol.core.network.ApiClient
import com.voicecontrol.core.network.BackendSession
import com.voicecontrol.core.network.BuildConfig
import com.voicecontrol.core.network.clientUserAgent
import com.voicecontrol.core.network.createHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun httpClient(@ApplicationContext context: Context): HttpClient {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return createHttpClient(OkHttp.create(), BuildConfig.DEBUG, clientUserAgent(version, Build.MODEL, Build.VERSION.RELEASE))
    }

    @Provides
    @Singleton
    fun apiClient(http: HttpClient, session: BackendSession): ApiClient = ApiClient(http, session)
}
