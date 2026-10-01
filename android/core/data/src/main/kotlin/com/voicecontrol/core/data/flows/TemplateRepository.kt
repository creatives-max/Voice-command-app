package com.voicecontrol.core.data.flows

import android.content.Context
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.FlowTemplate
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.network.MarketplaceApi
import com.voicecontrol.core.network.NetworkJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starter templates (sign-up, login, address…) used on screens without a saved flow.
 * Ships with a bundled copy (works offline and in local-only mode); refreshed from the server on sync.
 */
@Singleton
class TemplateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MarketplaceApi,
    private val settings: SettingsRepository,
) {
    @Serializable
    private data class CachedTemplate(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val version: Int,
        val steps: List<FlowStep>,
        val keywords: Map<String, List<String>>,
    )

    data class Template(val description: String, val template: FlowTemplate)

    private val cacheSerializer = ListSerializer(CachedTemplate.serializer())
    private val state = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = state.asStateFlow()

    /** Templates to match against, loading the cached or bundled copy on first use. */
    suspend fun current(): List<FlowTemplate> {
        if (state.value.isEmpty()) load()
        return state.value.map { it.template }
    }

    suspend fun load() {
        val cached = settings.templatesJson()?.let { runCatching { NetworkJson.decodeFromString(cacheSerializer, it) }.getOrNull() }
        state.value = (cached ?: bundled()).map(::toTemplate)
    }

    /** Fetches the latest templates from the server and caches them. */
    suspend fun refresh() {
        val fresh = api.templates().map { t ->
            CachedTemplate(t.listing.id, t.listing.name, t.listing.description, t.listing.category, t.listing.latestVersion, t.steps, t.keywords)
        }
        if (fresh.isEmpty()) return
        settings.saveTemplatesJson(NetworkJson.encodeToString(cacheSerializer, fresh))
        state.value = fresh.map(::toTemplate)
    }

    private fun toTemplate(c: CachedTemplate) =
        Template(c.description, FlowTemplate(c.id, c.name, c.category, c.version, c.steps, c.keywords))

    /** The copy built into the app from the same source file the backend seeds from. */
    private fun bundled(): List<CachedTemplate> = runCatching {
        val text = context.assets.open(BUNDLED_FILE).bufferedReader().use { it.readText() }
        val root = NetworkJson.parseToJsonElement(text) as JsonObject
        root["templates"]!!.jsonArray.map { element ->
            val o = element as JsonObject
            val steps = o["steps"]!!.jsonArray.map { it as JsonObject }
            CachedTemplate(
                id = o["id"]!!.jsonPrimitive.content,
                name = o["name"]!!.jsonPrimitive.content,
                description = o["description"]!!.jsonPrimitive.content,
                category = o["category"]!!.jsonPrimitive.content,
                version = 1,
                steps = steps.map { NetworkJson.decodeFromJsonElement(FlowStep.serializer(), it) },
                keywords = steps.associate { s -> s["id"]!!.jsonPrimitive.content to (s["keywords"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()) },
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val BUNDLED_FILE = "starter-templates.json"
    }
}
