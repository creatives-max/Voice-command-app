package com.voicecontrol.infrastructure.embedding

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.match.EmbeddingProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.sqrt

/**
 * Offline embedding via feature hashing of word unigrams, word bigrams and character trigrams.
 * Small label edits ("mobile no" vs "mobile number") keep most trigrams, so cosine similarity stays
 * high, while structurally different screens land far apart. Deterministic and free.
 */
class HashingEmbeddingProvider(override val dimensions: Int = 256) : EmbeddingProvider {
    override val name: String = "hashing-v1"

    override suspend fun embed(text: String): FloatArray = embedSync(text)

    fun embedSync(text: String): FloatArray {
        val v = FloatArray(dimensions)
        val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} .]"), " ")
        val words = normalized.split(Regex("[\\s.]+")).filter { it.isNotBlank() }
        words.forEach { add(v, "w:$it", 1.0f) }
        words.zipWithNext().forEach { (a, b) -> add(v, "b:$a $b", 0.7f) }
        words.forEach { w ->
            val padded = "#$w#"
            for (i in 0..padded.length - 3) add(v, "c:" + padded.substring(i, i + 3), 0.5f)
        }
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    private fun add(v: FloatArray, feature: String, weight: Float) {
        val h = fnv(feature)
        val index = Math.floorMod(h, dimensions)
        val sign = if ((h ushr 31) and 1 == 0) 1f else -1f
        v[index] += sign * weight
    }

    private fun fnv(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return h
    }
}

/** `EMBEDDING_PROVIDER=openai`: text-embedding-3-small reduced to the column size via `dimensions`. */
class OpenAiEmbeddingProvider(
    private val http: HttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String = "text-embedding-3-small",
    override val dimensions: Int = 256,
) : EmbeddingProvider {
    override val name: String = "openai:$model"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun embed(text: String): FloatArray {
        val response = http.post("${baseUrl.trimEnd('/')}/embeddings") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("model", model); put("input", text); put("dimensions", dimensions) }.toString())
        }
        if (!response.status.isSuccess()) throw DomainException.Upstream("Embedding provider returned ${response.status.value}")
        val data = json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw DomainException.Upstream("Embedding response had no data")
        val values = data["embedding"]!!.jsonArray.map { it.jsonPrimitive.float }
        require(values.size == dimensions) { "expected $dimensions dimensions, got ${values.size}" }
        return values.toFloatArray()
    }
}
