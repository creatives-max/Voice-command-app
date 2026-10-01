package com.voicecontrol.domain.match

import java.util.UUID

/** Turns text into a fixed-size, L2-normalized vector. */
interface EmbeddingProvider {
    val name: String
    val dimensions: Int
    suspend fun embed(text: String): FloatArray
}

data class VectorMatch(val flowId: UUID, val similarity: Double)

/** pgvector access for flow-version embeddings. */
interface EmbeddingRepository {
    suspend fun store(flowId: UUID, version: Int, embedding: FloatArray, model: String)
    suspend fun hasEmbedding(flowId: UUID, version: Int): Boolean
    /** Nearest current versions of flows [userId] can access (personal + organizations) for [appPackage]. */
    suspend fun nearest(userId: UUID, appPackage: String, embedding: FloatArray, limit: Int): List<VectorMatch>
}
