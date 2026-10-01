package com.voicecontrol.feature.flows

import com.voicecontrol.core.model.FlowDefinition

data class AppFlows(val appPackage: String, val flows: List<FlowDefinition>)

data class FlowsState(
    val loading: Boolean = true,
    val apps: List<AppFlows> = emptyList(),
    val selected: FlowDefinition? = null,
)

sealed interface FlowsIntent {
    data class Open(val flowId: String) : FlowsIntent
    data object CloseDetail : FlowsIntent
    data class Delete(val flow: FlowDefinition) : FlowsIntent
    data object Sync : FlowsIntent
    data object OpenDashboard : FlowsIntent
}

sealed interface FlowsEffect {
    data class Message(val text: String) : FlowsEffect
    data class OpenUrl(val url: String) : FlowsEffect
}

/** Groups flows by app, apps ordered by most recent change. */
fun groupByApp(flows: List<FlowDefinition>): List<AppFlows> =
    flows.groupBy { it.appPackage }
        .map { (pkg, list) -> AppFlows(pkg, list.sortedByDescending { it.updatedAtMillis }) }
        .sortedByDescending { group -> group.flows.maxOf { it.updatedAtMillis } }
