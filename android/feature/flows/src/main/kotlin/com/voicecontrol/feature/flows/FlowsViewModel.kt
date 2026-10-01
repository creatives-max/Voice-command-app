package com.voicecontrol.feature.flows

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.automation.RemoteRunRepository
import com.voicecontrol.core.data.flows.EditOutcome
import com.voicecontrol.core.data.flows.FlowLibrary
import com.voicecontrol.core.model.FlowEditing
import com.voicecontrol.core.data.flows.TemplateRepository
import com.voicecontrol.core.engine.port.FlowLauncher
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlowsViewModel @Inject constructor(
    private val library: FlowLibrary,
    private val settings: SettingsRepository,
    private val launcher: FlowLauncher,
    private val teach: com.voicecontrol.core.engine.port.TeachLauncher,
    private val profiles: com.voicecontrol.core.engine.port.ProfileSource,
    private val shortcuts: com.voicecontrol.core.data.shortcuts.VoiceShortcutRepository,
    remoteRuns: RemoteRunRepository,
    templates: TemplateRepository,
) : MviViewModel<FlowsState, FlowsIntent, FlowsEffect>(FlowsState()) {

    init {
        library.observe().onEach { flows ->
            setState {
                copy(
                    loading = false,
                    apps = groupByApp(flows),
                    selected = selected?.let { s -> flows.firstOrNull { it.id == s.id } },
                )
            }
        }.launchIn(viewModelScope)
        templates.templates.onEach { list ->
            setState { copy(templates = list.map { TemplateSummary(it.template.id, it.template.name, it.description, it.template.steps.size) }) }
        }.launchIn(viewModelScope)
        viewModelScope.launch { templates.load() }
        shortcuts.all.onEach { list ->
            setState { copy(shortcuts = list.map { ShortcutItem(it.phrase, it.flowId, local = it.triggerId == null) }) }
        }.launchIn(viewModelScope)
        viewModelScope.launch { remoteRuns.loadCachedTriggers() }
        remoteRuns.appOpenTriggers.onEach { triggers ->
            setState { copy(appOpenFlowIds = triggers.map { it.flowId }.toSet()) }
        }.launchIn(viewModelScope)
    }

    /** Applies an edit to the draft; invalid edits (like a default for an OTP field) are explained instead. */
    private suspend fun edit(change: (com.voicecontrol.core.model.FlowDefinition) -> com.voicecontrol.core.model.FlowDefinition) {
        val draft = currentState.editing ?: return
        runCatching { change(draft) }
            .onSuccess { next -> setState { copy(editing = next) } }
            .onFailure { sendEffect(FlowsEffect.Message(it.message ?: "Can't change that")) }
    }

    override suspend fun handleIntent(intent: FlowsIntent) {
        when (intent) {
            is FlowsIntent.Open -> setState { copy(selected = apps.flatMap { it.flows }.firstOrNull { it.id == intent.flowId }) }
            FlowsIntent.CloseDetail -> setState { copy(selected = null, editing = null, dryRun = null) }
            FlowsIntent.StartDryRun -> {
                val flow = currentState.editing ?: currentState.selected ?: return
                val profile = runCatching { profiles.profile() }.getOrNull()
                val variables = profile?.let { p -> com.voicecontrol.core.engine.FlowSimulator.profileVariables(p::value) }.orEmpty()
                setState { copy(dryRun = DryRunSession.start(flow, variables)) }
            }
            is FlowsIntent.DryRunAnswer -> setState { copy(dryRun = dryRun?.answer(intent.text)) }
            FlowsIntent.DryRunUndo -> setState { copy(dryRun = dryRun?.undo()) }
            FlowsIntent.DryRunRestart -> setState { copy(dryRun = dryRun?.restart()) }
            FlowsIntent.CloseDryRun -> setState { copy(dryRun = null) }
            is FlowsIntent.AddShortcut -> {
                val flow = currentState.selected ?: return
                shortcuts.add(intent.phrase, flow).fold(
                    onSuccess = { sendEffect(FlowsEffect.Message("Say “${intent.phrase.trim()}” to run this flow")) },
                    onFailure = { sendEffect(FlowsEffect.Message(it.message ?: "Can't use that phrase")) },
                )
            }
            is FlowsIntent.RemoveShortcut -> if (intent.item.local) shortcuts.remove(intent.item.phrase, intent.item.flowId)
            FlowsIntent.Teach -> {
                val result = teach.startTeaching()
                teachMessage(result)?.let { sendEffect(FlowsEffect.Message(it)) } ?: sendEffect(FlowsEffect.GoHome)
            }
            FlowsIntent.StartEdit -> setState { copy(editing = selected) }
            FlowsIntent.CancelEdit -> setState { copy(editing = null) }
            is FlowsIntent.Rename -> setState { copy(editing = editing?.copy(name = intent.name.take(120))) }
            is FlowsIntent.SetQuestion -> edit { FlowEditing.setQuestion(it, intent.stepId, intent.question) }
            is FlowsIntent.SetDefault -> edit { FlowEditing.setDefault(it, intent.stepId, intent.value) }
            is FlowsIntent.SetSkip -> edit { FlowEditing.setSkip(it, intent.stepId, intent.skip) }
            is FlowsIntent.MoveStep -> edit { FlowEditing.move(it, intent.stepId, intent.delta) }
            is FlowsIntent.RemoveStep -> edit { FlowEditing.remove(it, intent.stepId) }
            FlowsIntent.SaveEdit -> {
                val draft = currentState.editing ?: return
                val named = runCatching { FlowEditing.rename(draft, draft.name) }.getOrElse {
                    sendEffect(FlowsEffect.Message(it.message ?: "Invalid name"))
                    return
                }
                setState { copy(saving = true) }
                library.saveEdited(named).fold(
                    onSuccess = { outcome ->
                        setState { copy(editing = null, saving = false) }
                        sendEffect(
                            FlowsEffect.Message(
                                if (outcome == EditOutcome.SAVED_TO_ACCOUNT) "Saved as a new version in your account" else "Saved on this phone",
                            ),
                        )
                    },
                    onFailure = {
                        setState { copy(saving = false) }
                        sendEffect(FlowsEffect.Message(it.message ?: "Save failed"))
                    },
                )
            }
            is FlowsIntent.Delete -> {
                library.delete(intent.flow).fold(
                    onSuccess = {
                        setState { copy(selected = null) }
                        sendEffect(FlowsEffect.Message("Flow deleted"))
                    },
                    onFailure = { sendEffect(FlowsEffect.Message("Couldn't delete: ${it.message}")) },
                )
            }
            FlowsIntent.Sync -> {
                library.syncNow()
                sendEffect(FlowsEffect.Message("Syncing with your account…"))
            }
            FlowsIntent.OpenDashboard -> sendEffect(FlowsEffect.OpenUrl(settings.dashboardUrl()))
            is FlowsIntent.Run -> launchMessage(launcher.launch(intent.flow))?.let { sendEffect(FlowsEffect.Message(it)) }
        }
    }
}
