package com.voicecontrol.feature.flows

import com.voicecontrol.core.model.FlowDefinition

data class AppFlows(val appPackage: String, val flows: List<FlowDefinition>)

data class FlowsState(
    val loading: Boolean = true,
    val apps: List<AppFlows> = emptyList(),
    val selected: FlowDefinition? = null,
    /** Flows that start by themselves when their app opens (set on the dashboard). */
    val appOpenFlowIds: Set<String> = emptySet(),
    /** Starter templates used on screens without a saved flow. */
    val templates: List<TemplateSummary> = emptyList(),
    /** Draft while the selected flow is being edited on the phone. */
    val editing: FlowDefinition? = null,
    val saving: Boolean = false,
    /** Test run of the selected flow (or of the draft being edited). */
    val dryRun: DryRunSession? = null,
    /** Voice shortcuts (voice macros) of every flow: the account's and this phone's. */
    val shortcuts: List<ShortcutItem> = emptyList(),
)

/** A phrase that runs a flow; [local] ones were made on this phone and can be removed here. */
data class ShortcutItem(val phrase: String, val flowId: String, val local: Boolean)

data class TemplateSummary(val id: String, val name: String, val description: String, val stepCount: Int)

sealed interface FlowsIntent {
    data class Open(val flowId: String) : FlowsIntent
    data object CloseDetail : FlowsIntent
    data class Delete(val flow: FlowDefinition) : FlowsIntent
    data object Sync : FlowsIntent
    data object OpenDashboard : FlowsIntent
    /** Run this flow now: opens its app and starts the voice session. */
    data class Run(val flow: FlowDefinition) : FlowsIntent
    data object StartEdit : FlowsIntent
    data object CancelEdit : FlowsIntent
    data object SaveEdit : FlowsIntent
    data class Rename(val name: String) : FlowsIntent
    data class SetQuestion(val stepId: String, val question: String) : FlowsIntent
    data class SetDefault(val stepId: String, val value: String) : FlowsIntent
    data class SetSkip(val stepId: String, val skip: Boolean) : FlowsIntent
    data class MoveStep(val stepId: String, val delta: Int) : FlowsIntent
    data class RemoveStep(val stepId: String) : FlowsIntent
    /** Start "teach by doing" and send the user to the app they want to teach. */
    data object Teach : FlowsIntent
    /** Test the selected flow (or the draft being edited) with typed answers; nothing is filled. */
    data object StartDryRun : FlowsIntent
    data class DryRunAnswer(val text: String) : FlowsIntent
    data object DryRunUndo : FlowsIntent
    data object DryRunRestart : FlowsIntent
    data object CloseDryRun : FlowsIntent
    /** Saying [phrase] (on a screen without a form) will run the selected flow. */
    data class AddShortcut(val phrase: String) : FlowsIntent
    data class RemoveShortcut(val item: ShortcutItem) : FlowsIntent
}

sealed interface FlowsEffect {
    data class Message(val text: String) : FlowsEffect
    data class OpenUrl(val url: String) : FlowsEffect
    /** Recording started: go to the home screen so the user can open the app to teach. */
    data object GoHome : FlowsEffect
}

/** What to tell the user after asking to run a flow. */
fun launchMessage(result: com.voicecontrol.core.engine.port.LaunchResult): String? = when (result) {
    com.voicecontrol.core.engine.port.LaunchResult.STARTED -> null
    com.voicecontrol.core.engine.port.LaunchResult.BUSY -> "A voice session is already running"
    com.voicecontrol.core.engine.port.LaunchResult.SERVICE_OFF -> "Turn on the VoiceControl accessibility service first"
    com.voicecontrol.core.engine.port.LaunchResult.NO_MIC_PERMISSION -> "Allow microphone access to run flows"
}

/** What to tell the user after asking to start teaching. */
fun teachMessage(result: com.voicecontrol.core.engine.port.LaunchResult): String? = when (result) {
    com.voicecontrol.core.engine.port.LaunchResult.STARTED -> null
    com.voicecontrol.core.engine.port.LaunchResult.BUSY -> "Finish the current voice session or recording first"
    com.voicecontrol.core.engine.port.LaunchResult.SERVICE_OFF -> "Turn on the VoiceControl accessibility service first"
    com.voicecontrol.core.engine.port.LaunchResult.NO_MIC_PERMISSION -> null
}

/** Groups flows by app, apps ordered by most recent change. */
fun groupByApp(flows: List<FlowDefinition>): List<AppFlows> =
    flows.groupBy { it.appPackage }
        .map { (pkg, list) -> AppFlows(pkg, list.sortedByDescending { it.updatedAtMillis }) }
        .sortedByDescending { group -> group.flows.maxOf { it.updatedAtMillis } }
