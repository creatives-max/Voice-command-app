package com.voicecontrol.application.match

import com.voicecontrol.application.flow.FlowCacheInvalidator
import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.FlowVersionSaved
import com.voicecontrol.domain.flow.FlowRepository
import com.voicecontrol.domain.flow.FlowWithVersion
import com.voicecontrol.domain.match.EmbeddingProvider
import com.voicecontrol.domain.match.EmbeddingRepository
import java.security.MessageDigest
import java.util.UUID

enum class MatchKind { EXACT, VECTOR }

data class FlowMatch(val flow: FlowWithVersion, val kind: MatchKind, val similarity: Double)

/**
 * Screen → saved flow matching.
 * 1. Exact signature (same app, same screen structure).
 * 2. Nearest pgvector embedding among the user's flows for the app, above [threshold],
 *    so a flow still matches after small label changes ("Mobile no." → "Mobile number").
 * Results (including misses) are cached in Redis and invalidated by flow events.
 */
class FlowMatchService(
    private val flows: FlowRepository,
    private val embeddings: EmbeddingRepository,
    private val embedder: EmbeddingProvider,
    private val cache: Cache,
    private val threshold: Double = DEFAULT_THRESHOLD,
) {
    suspend fun match(userId: UUID, appPackage: String, signature: String): FlowMatch? {
        val key = FlowCacheInvalidator.matchCachePrefix(userId.toString(), appPackage) + hash(signature)
        cache.get(key)?.let { cached ->
            if (cached == MISS) return null
            val (id, kind, similarity) = cached.split('|')
            val flow = flows.find(userId, UUID.fromString(id))
            if (flow != null) return FlowMatch(flow, MatchKind.valueOf(kind), similarity.toDouble())
        }
        val result = compute(userId, appPackage, signature)
        cache.put(key, result?.let { "${it.flow.flow.id}|${it.kind}|${it.similarity}" } ?: MISS, CACHE_TTL_SECONDS)
        return result
    }

    private suspend fun compute(userId: UUID, appPackage: String, signature: String): FlowMatch? {
        flows.findBySignature(userId, appPackage, signature)?.let { return FlowMatch(it, MatchKind.EXACT, 1.0) }
        val vector = embedder.embed(SignatureText.of(signature))
        val best = embeddings.nearest(userId, appPackage, vector, limit = 3).firstOrNull() ?: return null
        if (best.similarity < threshold) return null
        val flow = flows.find(userId, best.flowId) ?: return null
        return FlowMatch(flow, MatchKind.VECTOR, best.similarity)
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_THRESHOLD = 0.80
        const val CACHE_TTL_SECONDS = 3_600L
        private const val MISS = "-"
    }
}

/** Event handler: computes and stores the embedding of every saved flow version (idempotent). */
class FlowEmbeddingHandler(
    private val flows: FlowRepository,
    private val embeddings: EmbeddingRepository,
    private val embedder: EmbeddingProvider,
) : EventHandler {
    override val eventTypes = setOf(FlowVersionSaved.TYPE)

    override suspend fun handle(event: DomainEvent) {
        if (event !is FlowVersionSaved) return
        val flowId = UUID.fromString(event.flowId)
        if (embeddings.hasEmbedding(flowId, event.version)) return
        val version = flows.version(UUID.fromString(event.userId), flowId, event.version) ?: return // deleted meanwhile
        embeddings.store(flowId, event.version, embedder.embed(SignatureText.of(version.screenSignature)), embedder.name)
    }
}
