package com.voicecontrol.application.flow

import com.voicecontrol.domain.event.Cache
import com.voicecontrol.domain.event.DomainEvent
import com.voicecontrol.domain.event.EventHandler
import com.voicecontrol.domain.event.FlowDeleted
import com.voicecontrol.domain.event.FlowVersionSaved

/**
 * Drops cached screen→flow match results for an app whenever one of its flows changes. An organization
 * flow can be matched by every member, so its events drop the app's cached results for all users.
 */
class FlowCacheInvalidator(private val cache: Cache) : EventHandler {
    override val eventTypes = setOf(FlowVersionSaved.TYPE, FlowDeleted.TYPE)

    override suspend fun handle(event: DomainEvent) {
        when (event) {
            is FlowVersionSaved -> cache.deleteByPrefix(prefixFor(event.orgId, event.userId, event.appPackage))
            is FlowDeleted -> cache.deleteByPrefix(prefixFor(event.orgId, event.userId, event.appPackage))
            else -> Unit
        }
    }

    companion object {
        fun appCachePrefix(appPackage: String) = "vc:match:$appPackage:"
        fun matchCachePrefix(userId: String, appPackage: String) = appCachePrefix(appPackage) + "$userId:"

        /** Every app's cached results of one user (a glob for [Cache.deleteByPrefix]; package names never contain `*` or `:`). */
        fun userCachePattern(userId: String) = "vc:match:*:$userId:"

        private fun prefixFor(orgId: String?, userId: String, appPackage: String) =
            if (orgId != null) appCachePrefix(appPackage) else matchCachePrefix(userId, appPackage)
    }
}
