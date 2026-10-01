package com.voicecontrol.application.flow

import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.FlowDeleted
import com.voicecontrol.domain.event.FlowVersionSaved

/** Drops cached screen→flow match results for an app whenever one of its flows changes. */
class FlowCacheInvalidator(private val cache: Cache) : EventHandler {
    override val eventTypes = setOf(FlowVersionSaved.TYPE, FlowDeleted.TYPE)

    override suspend fun handle(event: DomainEvent) {
        when (event) {
            is FlowVersionSaved -> cache.deleteByPrefix(matchCachePrefix(event.userId, event.appPackage))
            is FlowDeleted -> cache.deleteByPrefix(matchCachePrefix(event.userId, event.appPackage))
        }
    }

    companion object {
        fun matchCachePrefix(userId: String, appPackage: String) = "vc:match:$userId:$appPackage:"
    }
}
