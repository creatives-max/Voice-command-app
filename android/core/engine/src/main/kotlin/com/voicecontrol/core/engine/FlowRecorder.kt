package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.StepAction

/** What the user did while VoiceControl was watching ("teach by doing"). */
sealed interface RecordedEvent {
    /** The screen as it looks now (new screen, or the same screen with new content). */
    data class Screen(val snapshot: ScreenSnapshot) : RecordedEvent

    /**
     * The user typed into a field (its text is read from the latest screen). [on] is the screen it
     * happened on when known; otherwise the current one.
     */
    data class Typed(val elementId: String, val on: ScreenSnapshot? = null) : RecordedEvent

    /** The user checked/unchecked or switched something. */
    data class Toggled(val elementId: String, val on: ScreenSnapshot? = null) : RecordedEvent

    /** The user pressed a button or link, on screen [on] (read before the app reacted) when known. */
    data class Pressed(val elementId: String, val on: ScreenSnapshot? = null) : RecordedEvent
}

/** One thing done on one screen, in the order the user did it. */
data class RecordedAction(
    val element: ScreenElement,
    val action: StepAction,
    /** Last text typed (never for passwords, OTPs or PINs); offered as a default when saving. */
    val value: String?,
)

/** A screen the user worked on, with what they did there. */
data class RecordedScreen(val snapshot: ScreenSnapshot, val actions: List<RecordedAction>)

data class Recording(val screens: List<RecordedScreen>) {
    val actionCount: Int get() = screens.sumOf { it.actions.size }
    val isEmpty: Boolean get() = actionCount == 0
}

/**
 * Watches what the user does by touch and turns it into a recording. Screens are told apart by app and
 * screen signature; screens where nothing was done (loading screens, menus) are dropped.
 *
 * A press is kept with the screen it was made on, even when the next screen was read before the press
 * arrived (a tap that opens another screen). Taps on the home screen ([homePackages]) and taps that
 * only got to the app before anything was done in it are not part of the flow: running a flow opens
 * its app itself.
 */
class FlowRecorder(private val homePackages: () -> Set<String> = { emptySet() }) {
    private val screens = mutableListOf<MutableScreen>()

    private class MutableScreen(var snapshot: ScreenSnapshot) {
        val actions = linkedMapOf<String, RecordedAction>()
    }

    private val current: MutableScreen? get() = screens.lastOrNull()

    fun onEvent(event: RecordedEvent) {
        when (event) {
            is RecordedEvent.Screen -> onScreen(event.snapshot)
            is RecordedEvent.Typed -> record(event.elementId, StepAction.FILL, event.on)
            is RecordedEvent.Toggled -> record(event.elementId, StepAction.TOGGLE, event.on)
            is RecordedEvent.Pressed -> record(event.elementId, StepAction.CLICK, event.on)
        }
    }

    /** The recorded screen [on] belongs to: the current one or the one before; re-added if it was dropped. */
    private fun screenFor(on: ScreenSnapshot?): MutableScreen? {
        val screen = current
        if (on == null || screen == null) return screen ?: on?.let { MutableScreen(it).also(screens::add) }
        if (sameScreen(screen.snapshot, on)) return screen
        val before = screens.getOrNull(screens.lastIndex - 1)
        if (before != null && sameScreen(before.snapshot, on)) return before
        // The press made the app move on before we saw it: the screen it was made on goes before the new one.
        return MutableScreen(on).also { screens.add(screens.lastIndex, it) }
    }

    private fun onScreen(snapshot: ScreenSnapshot) {
        val screen = current
        if (screen != null && sameScreen(screen.snapshot, snapshot)) {
            screen.snapshot = snapshot
            // Typed text is read from the newest look of the screen.
            screen.actions.replaceAll { id, a ->
                if (a.action == StepAction.FILL) a.copy(value = valueOf(snapshot.element(id) ?: a.element)) else a
            }
            return
        }
        // A screen where nothing happened is replaced by the next one.
        if (screen != null && screen.actions.isEmpty()) screens.removeAt(screens.lastIndex)
        screens += MutableScreen(snapshot)
    }

    private fun record(elementId: String, action: StepAction, on: ScreenSnapshot?) {
        val element = (on?.element(elementId) ?: current?.snapshot?.element(elementId)) ?: return
        val screen = screenFor(on?.takeIf { it.element(elementId) != null }) ?: return
        val existing = screen.actions[elementId]
        val step = when {
            action == StepAction.CLICK && element.kind.isToggle -> StepAction.TOGGLE
            action == StepAction.CLICK && element.kind == ElementKind.TEXT_FIELD -> return // focusing a field is not an action
            else -> action
        }
        // Keep the position of the first touch; a button pressed again moves to the end (last action wins).
        if (existing != null && step == StepAction.CLICK) screen.actions.remove(elementId)
        screen.actions[elementId] = RecordedAction(element, step, if (step == StepAction.FILL) valueOf(element) else null)
    }

    fun recording(): Recording {
        val home = homePackages()
        val worked = screens.filter { it.actions.isNotEmpty() && it.snapshot.packageName !in home }.toMutableList()
        // Taps that only got to the app (a launcher, a notification, another app's link) come before it.
        while (worked.size > 1 && worked[0].snapshot.packageName != worked[1].snapshot.packageName &&
            worked[0].actions.values.all { it.action == StepAction.CLICK }
        ) {
            worked.removeAt(0)
        }
        return Recording(worked.map { RecordedScreen(it.snapshot, it.actions.values.toList()) })
    }

    fun clear() = screens.clear()

    private fun sameScreen(a: ScreenSnapshot, b: ScreenSnapshot) = a.packageName == b.packageName && a.signature == b.signature

    private fun valueOf(e: ScreenElement): String? = if (e.isSensitive || e.fieldType?.isSensitive == true) null else e.value?.takeIf { it.isNotBlank() }
}

/**
 * Builds a flow from a recording: the order is the order the user worked in. Taps are replayed on their
 * own; only the last tap of the flow is confirmed first.
 */
object RecordingToFlow {
    const val MAX_STEPS = 100

    /**
     * [keepValues] lists element ids whose typed text becomes the step's default value (the user chose
     * this when reviewing); sensitive fields never keep values.
     */
    fun build(recording: Recording, id: String, nowMillis: Long, keepValues: Set<String> = emptySet(), name: String? = null): FlowDefinition {
        require(!recording.isEmpty) { "Nothing was recorded" }
        val first = recording.screens.first().snapshot
        val steps = mutableListOf<FlowStep>()
        recording.screens.forEachIndexed { index, screen ->
            if (index > 0) {
                val previous = recording.screens[index - 1].snapshot
                val crossApp = previous.packageName != screen.snapshot.packageName
                steps += FlowStep(
                    id = "rec-$index-screen",
                    order = steps.size,
                    elementId = "",
                    label = screen.snapshot.title?.takeIf { it.isNotBlank() } ?: if (crossApp) appName(screen.snapshot.packageName) else "Next screen",
                    kind = ElementKind.BUTTON,
                    action = if (crossApp) StepAction.OPEN_APP else StepAction.NEXT_SCREEN,
                    appPackage = screen.snapshot.packageName,
                    waitSeconds = 15,
                )
            }
            screen.actions.forEach { a ->
                val sensitive = a.element.isSensitive || a.element.fieldType?.isSensitive == true
                steps += FlowStep(
                    id = "rec-$index-${a.element.id.hashCode().toUInt().toString(16)}",
                    order = steps.size,
                    elementId = a.element.id,
                    label = a.element.label.ifBlank { a.element.hint.orEmpty() }.ifBlank { a.element.id },
                    kind = a.element.kind,
                    fieldType = a.element.fieldType,
                    action = a.action,
                    defaultValue = a.value?.takeIf { !sensitive && a.action == StepAction.FILL && a.element.id in keepValues }?.take(500),
                )
            }
        }
        // Taught taps are replayed without asking, except the flow's very last one (it may send or pay).
        val lastClick = steps.indexOfLast { it.action == StepAction.CLICK }
        steps.replaceAll { st -> if (st.action == StepAction.CLICK && steps.indexOf(st) != lastClick) st.copy(skip = true) else st }
        require(steps.size <= MAX_STEPS) { "A taught flow can have at most $MAX_STEPS steps" }
        return FlowDefinition(
            id = id,
            version = 1,
            appPackage = first.packageName,
            name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: defaultName(first),
            screenSignature = first.signature,
            steps = steps,
            updatedAtMillis = nowMillis,
        )
    }

    fun defaultName(first: ScreenSnapshot): String {
        val title = first.title?.takeIf { it.isNotBlank() }
            ?: first.activityName?.substringAfterLast('.')?.removeSuffix("Activity")?.takeIf { it.isNotBlank() }
        return if (title != null) "${appName(first.packageName)} · $title" else appName(first.packageName)
    }

    private fun appName(pkg: String) = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
