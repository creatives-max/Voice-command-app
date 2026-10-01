package com.voicecontrol.core.data.flows

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenSnapshot

/**
 * On-device screen → flow matching used offline (the backend uses pgvector embeddings).
 * Signatures are "pkg|Activity|kind:label|…"; we compare the element parts with Jaccard similarity.
 */
object FlowMatcher {
    const val THRESHOLD = 0.6

    fun best(snapshot: ScreenSnapshot, candidates: List<FlowDefinition>): FlowDefinition? {
        candidates.firstOrNull { it.screenSignature == snapshot.signature }?.let { return it }
        val parts = elementParts(snapshot.signature)
        return candidates
            .map { it to similarity(parts, elementParts(it.screenSignature)) }
            .filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = a.union(b).size
        return if (union == 0) 0.0 else a.intersect(b).size.toDouble() / union
    }

    fun elementParts(signature: String): Set<String> = signature.split('|').drop(2).filter { it.isNotBlank() }.toSet()
}
