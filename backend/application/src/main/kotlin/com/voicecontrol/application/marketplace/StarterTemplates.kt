package com.voicecontrol.application.marketplace

import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.marketplace.MarketplaceRepository
import com.voicecontrol.domain.marketplace.PublishedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.util.UUID

/**
 * Built-in starter templates for common forms (sign-up, login, address, OTP-less sign-up, contact,
 * payment). Source of truth: `templates/starter-templates.json`; seeded into the marketplace at startup.
 * Each step carries `keywords` that identify its field on any app's screen.
 */
object StarterTemplates {
    const val APP_PACKAGE = "*"

    @Serializable
    private data class TemplateFile(val templates: List<TemplateDef>)

    @Serializable
    private data class TemplateDef(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val tags: List<String> = emptyList(),
        val steps: List<JsonObject>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    data class Template(val listing: PublishedFlow, val steps: List<FlowStep>)

    fun load(clock: Clock = Clock.systemUTC()): List<Template> {
        val text = StarterTemplates::class.java.getResourceAsStream("/templates/starter-templates.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("templates/starter-templates.json is missing from the classpath")
        val now = clock.instant()
        return json.decodeFromString(TemplateFile.serializer(), text).templates.map { t ->
            val steps = t.steps.map { json.decodeFromJsonElement(FlowStep.serializer(), it) }
            val keywords = t.steps.associate { s ->
                s["id"]!!.jsonPrimitive.content to (s["keywords"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
            }
            Template(
                PublishedFlow(
                    UUID.fromString(t.id), null, null, null, t.name, t.description, APP_PACKAGE, t.category, t.tags,
                    isTemplate = true, keywords = keywords, latestVersion = 1, installCount = 0, ratingCount = 0, ratingSum = 0,
                    createdAt = now, updatedAt = now,
                ),
                steps,
            )
        }
    }

    /** Inserts new templates and refreshes changed ones (idempotent; safe on every replica start). */
    suspend fun seed(repo: MarketplaceRepository, clock: Clock = Clock.systemUTC()) {
        load(clock).forEach { repo.upsertTemplate(it.listing, it.steps, clock.instant()) }
    }
}
