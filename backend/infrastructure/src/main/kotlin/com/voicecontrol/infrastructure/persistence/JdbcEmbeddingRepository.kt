package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.match.EmbeddingRepository
import com.voicecontrol.domain.match.VectorMatch
import java.util.UUID

/** pgvector queries. Vectors are passed as text literals ("[0.1,0.2,…]") cast to `vector`. */
class JdbcEmbeddingRepository(private val db: Database) : EmbeddingRepository {

    override suspend fun store(flowId: UUID, version: Int, embedding: FloatArray, model: String) {
        db.tx { update("UPDATE flow_versions SET embedding = ?::vector, embedding_model = ? WHERE flow_id = ? AND version = ?", literal(embedding), model, flowId, version) }
    }

    override suspend fun hasEmbedding(flowId: UUID, version: Int): Boolean = db.tx {
        query("SELECT embedding IS NOT NULL AS has FROM flow_versions WHERE flow_id = ? AND version = ?", flowId, version) { it.getBoolean("has") }
            .firstOrNull() ?: false
    }

    override suspend fun nearest(userId: UUID, appPackage: String, embedding: FloatArray, limit: Int): List<VectorMatch> = db.tx {
        val vector = literal(embedding)
        query(
            """
            SELECT f.id, 1 - (v.embedding <=> ?::vector) AS similarity
            FROM flows f JOIN flow_versions v ON v.flow_id = f.id AND v.version = f.current_version
            WHERE f.user_id = ? AND f.app_package = ? AND f.deleted_at IS NULL AND v.embedding IS NOT NULL
            ORDER BY v.embedding <=> ?::vector
            LIMIT ?
            """.trimIndent(),
            vector, userId, appPackage, vector, limit,
        ) { VectorMatch(it.uuid("id"), it.getDouble("similarity")) }
    }

    private fun literal(v: FloatArray): String = v.joinToString(",", "[", "]")
}
