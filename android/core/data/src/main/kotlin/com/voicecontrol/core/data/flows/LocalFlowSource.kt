package com.voicecontrol.core.data.flows

import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.TemplateMatcher
import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.network.FlowApi
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flow lookup used by the engine at the start of every screen.
 *
 * Signed in (and not local-only): ask the backend, which matches by exact signature or pgvector
 * similarity and always returns the *current* version, so dashboard edits apply on the next run.
 * The result is cached locally. Offline or signed out: on-device matching against cached flows.
 * Screens without any saved flow get the best-fitting starter template, if enabled.
 */
@Singleton
class LocalFlowSource @Inject constructor(
    private val flows: FlowRepository,
    private val api: FlowApi,
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
    private val templates: TemplateRepository,
) : FlowSource {

    override suspend fun flowFor(snapshot: ScreenSnapshot): FlowDefinition? =
        savedFlow(snapshot) ?: templateFlow(snapshot)

    private suspend fun savedFlow(snapshot: ScreenSnapshot): FlowDefinition? {
        if (!settings.appSettings().localOnly && tokens.tokens() != null) {
            val remote = withTimeoutOrNull(REMOTE_TIMEOUT_MS) {
                runCatching { api.match(snapshot.packageName, snapshot.signature) }.getOrNull()
            }
            if (remote != null) {
                val flow = remote.flow ?: return flows.localMatch(snapshot)?.takeIf { !it.isSynced }
                flows.save(flow, synced = true)
                return flow
            }
        }
        return flows.localMatch(snapshot)
    }

    /** No saved flow: fit a starter template to the screen (questions, rules, profile suggestions). */
    private suspend fun templateFlow(snapshot: ScreenSnapshot): FlowDefinition? {
        if (!settings.appSettings().useTemplates) return null
        return TemplateMatcher.best(snapshot, templates.current())
    }

    private companion object {
        const val REMOTE_TIMEOUT_MS = 3_000L
    }
}
