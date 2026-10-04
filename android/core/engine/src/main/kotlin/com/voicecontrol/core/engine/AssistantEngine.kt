package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.expr.Expressions
import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.engine.port.InterpretRequest
import com.voicecontrol.core.engine.port.Interpreter
import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.ProfileSource
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.SessionConfigProvider
import com.voicecontrol.core.engine.port.SessionRecorder
import com.voicecontrol.core.engine.port.SpeechDetector
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.FlowVariables
import com.voicecontrol.core.model.IntentKind
import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenRecord
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.ScrollDirection
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.nlp.AppRequest
import com.voicecontrol.core.nlp.GoalRequest
import com.voicecontrol.core.nlp.PhoneTask
import com.voicecontrol.core.engine.port.AgentAction
import com.voicecontrol.core.engine.port.AgentProblem
import com.voicecontrol.core.model.StepOutcome
import com.voicecontrol.core.model.StepRecord
import com.voicecontrol.core.model.UserProfile
import com.voicecontrol.core.nlp.FieldValidator
import com.voicecontrol.core.nlp.SpeechNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class EngineStatus { IDLE, STARTING, SPEAKING, LISTENING, THINKING, ACTING, PAUSED, FINISHED, ERROR }

/**
 * A line for the live run log (dashboard "Run now"). Never contains spoken, typed or profile values:
 * only labels, step outcomes and session status.
 */
data class EngineEvent(val kind: String, val message: String, val status: RunStatus? = null) {
    companion object {
        const val STATUS = "status"
        const val SCREEN = "screen"
        const val ASK = "ask"
        const val STEP = "step"
        const val PRESS = "press"
        const val ERROR = "error"
    }
}

/** Observable state of a voice session, rendered by the overlay. */
data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val active: Boolean = false,
    val caption: String? = null,
    val heard: String? = null,
    val progress: String? = null,
    val helpVideoUrl: String? = null,
    val micLevel: Float = 0f,
    val appPackage: String? = null,
)

/**
 * The voice session state machine.
 *
 * For each screen: build a plan (saved flow + live fields), then for every step ask by voice,
 * listen, interpret (commands locally, answers locally or via the backend), validate, type the
 * value, and finally offer to press the submit button. When pressing a button opens a new screen
 * with fields, the session continues there. Every finished session is recorded (history + flows).
 *
 * Flows can carry logic: steps run only when their condition holds (else an alternative value is
 * filled), answers are kept in session variables that later conditions, computed values and
 * `{var}` question templates use, REPEAT steps loop over list items, and NEXT_SCREEN / OPEN_APP
 * steps split a flow into screens, possibly across apps; variables carry across screens.
 */
class AssistantEngine(
    private val screen: ScreenGateway,
    private val stt: SpeechToText,
    private val tts: TextToSpeech,
    private val interpreter: Interpreter,
    private val flows: FlowSource,
    private val profiles: ProfileSource,
    private val recorder: SessionRecorder,
    private val config: SessionConfigProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val screenSettleMillis: Long = 1_200L,
    private val vision: VisionDetector? = null,
    /** Barge-in: detects the user speaking over a question. */
    private val speechDetector: SpeechDetector? = null,
    /** Voice macros: on a screen without a form, saying a shortcut phrase runs its flow. */
    private val shortcuts: com.voicecontrol.core.engine.port.ShortcutSource? = null,
    /** Answers remembered across sessions (used when [SessionConfig.rememberAnswers] is on). */
    private val answers: com.voicecontrol.core.engine.port.AnswerMemory? = null,
    /** AI-written questions for screens without saved ones (not in on-device only mode). */
    private val questionWriter: com.voicecontrol.core.engine.port.QuestionWriter? = null,
    private val questionTimeoutMillis: Long = 30_000L,
    /** Installed apps, for "open WhatsApp". */
    private val appDirectory: com.voicecontrol.core.engine.port.AppDirectory? = null,
    /** Alarms, timers, searches, calls and messages for the personal assistant. */
    private val phoneActions: com.voicecontrol.core.engine.port.PhoneActions? = null,
    /** Operates apps towards a spoken goal ("mujhe bill bharna hai"), asking the user for what it needs. */
    private val goalAgent: com.voicecontrol.core.engine.port.GoalAgent? = null,
    /** Keeps goals that were done as flows with a voice shortcut, so they run straight away next time. */
    private val goalMemory: com.voicecontrol.core.engine.port.GoalMemory? = null,
) {
    private val personal = PersonalTasks(phoneActions, clock)
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    /** Value-free progress events of the current session, for remote run logs. */
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private fun emit(kind: String, message: String, status: RunStatus? = null) {
        _events.tryEmit(EngineEvent(kind, message, status))
    }

    private var job: Job? = null
    private val localCommands = LocalInterpreter()

    val isActive: Boolean get() = job?.isActive == true

    fun toggle() = if (isActive) stop() else start()

    /** Starts a session; with [flow], that flow is run instead of matching one to the screen. */
    /**
     * Starts a session. [request] is what the user already asked for while starting it ("voice control,
     * YouTube kholo"): it is handled first instead of asking "What can I do for you?".
     */
    fun start(flow: FlowDefinition? = null, request: String? = null) {
        if (isActive) return
        job = scope.launch { runSession(flow, request?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    /** Starts screen-reader mode: reads the whole screen and lets the user move and activate by voice. */
    fun startReader() {
        if (isActive) return
        job = scope.launch { runReaderSession() }
    }

    /** What can be reverted, most recent last (fills, toggles and button presses of the last session). */
    private data class UndoEntry(val elementId: String, val label: String, val previousValue: String?, val previousChecked: Boolean?, val wasPress: Boolean)

    private val undoStack = ArrayDeque<UndoEntry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /**
     * Reverts the last fill (previous text), toggle (previous state) or button press (goes back).
     * Returns the label of what was undone, or null when there is nothing to undo or it failed.
     */
    suspend fun undoLast(): String? {
        val entry = undoStack.removeLastOrNull() ?: return null
        val action = when {
            entry.wasPress -> ScreenAction.Back
            entry.previousChecked != null -> ScreenAction.SetChecked(entry.elementId, entry.previousChecked)
            else -> ScreenAction.SetText(entry.elementId, entry.previousValue.orEmpty())
        }
        return if (perform(action).isSuccess) entry.label else null
    }

    private fun recordUndo(element: ScreenElement, wasPress: Boolean = false, checkedBefore: Boolean? = null) {
        if (element.isSensitive) return
        undoStack += UndoEntry(element.id, element.label, element.value, checkedBefore, wasPress)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    fun stop() {
        stt.cancel()
        tts.stop()
        job?.cancel(UserStop())
    }

    // ---------------------------------------------------------------------------------------------
    // Session

    private class UserStop : CancellationException("stopped by user")

    private sealed interface StepResult {
        data object Done : StepResult
        data object Previous : StepResult
        data object Submit : StepResult
        data object Stop : StepResult
        /** A button/back was pressed; the screen may have changed. */
        data object Navigated : StepResult
    }

    private enum class ScreenOutcome { COMPLETED, NAVIGATED, STOPPED, SWITCHED }

    /** Mutable context for one running session. */
    private inner class Session(val cfg: SessionConfig) {
        val id = newId()
        val startedAt = clock()
        val phrases = Phrases(cfg.language)
        val screens = mutableListOf<ScreenRecord>()
        var appPackage: String = ""
        var status = RunStatus.COMPLETED
        var silentFailures = 0
        /** Requests in a row that weren't understood (re-asked in other words, then with examples). */
        var misses = 0
        /** Flow variables: answers, READ results, SET_VARIABLE values. Never sensitive values. */
        val vars = LinkedHashMap<String, String>()
        /** Values already on the current screen, by label slug (lowest precedence). */
        var screenVars: Map<String, String> = emptyMap()
        var profile: UserProfile? = null
        /** Earlier answers (never sensitive) for "same as above" and the AI's context. */
        val memory = mutableListOf<MemoryItem>()
        /** Recognizer confidence of the last answer (null when the recognizer gives none). */
        var lastConfidence: Float? = null
        /** Hybrid-vision results per screen signature, so a screen is sent to the model once per session. */
        val hybrid = HashMap<String, HybridVision.Result>()
        /** Signature of the screen as last seen (loops can change it by adding rows). */
        var currentSignature: String = ""
        /** Screens smart mode already filled in this session (not filled again when they come back). */
        val smartHandled = HashSet<String>()

        /** The user was told once why smart mode isn't helping in this session. */
        var toldSmartOff = false

        /** Smart mode could not reach the AI in this session: carry on the built-in way. */
        var smartOff = false

        /** Smart mode is on and usable: the AI operates screens that have no saved flow. */
        val smart get() = cfg.smartMode && !cfg.localOnly && !smartOff && goalAgent != null

        /** The assistant already said "What can I do for you?" in this session. */
        var greeted = false
        /** Asked for when the session started (with the wake phrase); answered before asking anything. */
        var firstRequest: String? = null
        /** What the assistant last answered (time, battery, messages…), for "phir se bolo". */
        val lastAnswer = mutableListOf<String>()
        /** Assistant jobs done (apps opened, alarms, calls, goals): kept in the history as their own screen. */
        val assistantSteps = mutableListOf<StepRecord>()

        /** The conversation so far ("assistant said" / "user said"), so "usko", "doosra wala" make sense to the AI. */
        val turns = ArrayDeque<MemoryItem>()

        fun noteTurn(who: String, text: String) {
            if (text.isBlank()) return
            turns.addLast(MemoryItem(who, null, TextMask.mask(text.take(MAX_TURN_CHARS))))
            while (turns.size > MAX_TURNS) turns.removeFirst()
        }

        fun noteAssistant(what: String) {
            assistantSteps += StepRecord("assistant:${assistantSteps.size}", what.take(80), ElementKind.BUTTON, outcome = StepOutcome.CLICKED)
        }
        /** Names on the current screen, to help the recognizer hear them. */
        var bias: List<String> = emptyList()
        /** The recognizer's other guesses for the last answer (tried when the first matches nothing). */
        var alternatives: List<String> = emptyList()
        /** The last field filled on the current screen (a search box is sent with Enter when nothing else moves on). */
        var lastFilledId: String? = null
        /** AI-written questions for the current screen, arriving while the first questions are asked. */
        var written: kotlinx.coroutines.Deferred<Map<String, com.voicecontrol.core.engine.port.WrittenQuestion>>? = null
        var writtenIds: Set<String> = emptySet()
        /** Flow chosen by a voice shortcut, run next ([ScreenOutcome.SWITCHED]). */
        var switchTo: FlowDefinition? = null
        /** The internet dropped: keep listening with on-device speech for the rest of the session. */
        var offline = cfg.preferOffline
        /** The missing-language-pack hint was already spoken. */
        var toldPackMissing = false
        /** Speaking speed; the narrator's "faster" / "slower" change it for this session. */
        var rate = cfg.speechRate

        fun lookup(name: String): Any? = vars[name] ?: profileVar(name) ?: screenVars[name]

        private fun profileVar(name: String): String? {
            if (!name.startsWith("profile.")) return null
            val key = FlowVariables.profileNames.entries.firstOrNull { it.value == name }?.key ?: return null
            return profile?.value(key)
        }
    }

    /** Per-screen recording. */
    private class ScreenLog(
        val snapshot: ScreenSnapshot,
        val flowId: String?,
        val flowVersion: Int?,
        val onRecord: (StepRecord) -> Unit = {},
    ) {
        val steps = LinkedHashMap<String, StepRecord>()
        /** Set while a REPEAT item runs, so each item's steps are recorded separately. */
        var keySuffix = ""
        fun put(step: PlanStep, outcome: StepOutcome, question: String?, by: String? = null) {
            if (step.virtual) return
            val record = StepRecord(step.elementId, step.label, step.kind, step.fieldType, question, outcome, by)
            steps[step.elementId + keySuffix] = record
            onRecord(record)
        }
        fun putClick(element: ScreenElement) {
            val record = StepRecord(element.id, element.label, element.kind, null, null, StepOutcome.CLICKED)
            steps[element.id + keySuffix] = record
            onRecord(record)
        }
        fun toRecord() = ScreenRecord(
            appPackage = snapshot.packageName,
            activityName = snapshot.activityName,
            screenTitle = snapshot.title,
            screenSignature = snapshot.signature,
            flowId = flowId,
            flowVersion = flowVersion,
            steps = steps.values.toList(),
        )
    }

    private suspend fun runSession(preselected: FlowDefinition?, request: String? = null) {
        val cfg = config.current()
        val session = Session(cfg)
        session.firstRequest = request
        undoStack.clear()
        _state.value = EngineState(status = EngineStatus.STARTING, active = true)
        emit(EngineEvent.STATUS, if (preselected != null) "Started “${preselected.name}”" else "Session started")
        try {
            if (!screen.isAvailable.value) {
                emit(EngineEvent.ERROR, "The accessibility service is off")
                say(session, session.phrases.serviceOff())
                session.status = RunStatus.FAILED
                return
            }
            var first = true
            var visited = 0
            // A multi-screen (or preselected) flow in progress, and which of its screens is next.
            var active: FlowDefinition? = preselected
            var segment = 0
            val opening = preselected?.let(::openingStep)
            opening?.let { boundary ->
                if (!enterSegment(session, boundary, previousSignature = null)) {
                    emit(EngineEvent.ERROR, "Could not open ${boundary.appPackage ?: "the app"}")
                    say(session, session.phrases.screenNotReached())
                    session.status = RunStatus.FAILED
                    return
                }
            }
            while (visited < MAX_SCREENS) {
                visited++
                val snapshot = readScreen(session)
                if (snapshot == null || !snapshot.hasReadableElements) {
                    emit(EngineEvent.ERROR, "Could not read the screen")
                    say(session, session.phrases.cannotRead())
                    session.status = RunStatus.FAILED
                    return
                }
                session.appPackage = snapshot.packageName
                _state.update { it.copy(appPackage = snapshot.packageName) }
                setStatus(EngineStatus.THINKING, caption = null)
                val running = active
                val flow = if (running != null) {
                    running.copy(steps = running.segments[segment])
                } else {
                    val matched = runCatching { flows.flowFor(snapshot) }.getOrNull()
                    if (matched != null && matched.segments.size > 1) {
                        active = matched
                        segment = 0
                        matched.copy(steps = matched.segments[0])
                    } else {
                        matched
                    }
                }
                val profile = runCatching { profiles.profile() }.getOrNull()
                session.profile = profile
                session.screenVars = screenVariables(snapshot)
                session.currentSignature = snapshot.signature
                session.lastFilledId = null
                val plan = PlanBuilder(session.phrases).build(snapshot, flow, profile)
                startWrittenQuestions(session, snapshot, plan)
                emit(EngineEvent.SCREEN, "Screen: ${snapshot.title ?: snapshot.activityName?.substringAfterLast('.') ?: snapshot.packageName}")
                val log = ScreenLog(snapshot, plan.flowId, plan.flowVersion) { record ->
                    val verb = when (record.outcome) {
                        StepOutcome.FILLED -> "Filled"
                        StepOutcome.DEFAULT_FILLED -> "Filled automatically"
                        StepOutcome.KEPT -> "Kept"
                        StepOutcome.SKIPPED -> "Skipped"
                        StepOutcome.MANUAL -> "Typed by the user"
                        StepOutcome.CLICKED -> "Pressed"
                        StepOutcome.TOGGLED -> "Set"
                        StepOutcome.FAILED -> "Could not fill"
                    }
                    emit(if (record.outcome == StepOutcome.CLICKED) EngineEvent.PRESS else EngineEvent.STEP, "$verb: ${record.label}")
                }
                val outcome = try {
                    runScreen(session, snapshot, plan, log, announce = first)
                } finally {
                    if (log.steps.isNotEmpty()) session.screens += log.toRecord()
                }
                first = false
                if (outcome == ScreenOutcome.STOPPED) {
                    say(session, session.phrases.stopped())
                    session.status = RunStatus.STOPPED
                    return
                }
                val chosen = session.switchTo
                if (outcome == ScreenOutcome.SWITCHED && chosen != null) {
                    // A voice shortcut: run its flow from its first screen, in its own app.
                    session.switchTo = null
                    active = chosen
                    segment = 0
                    if (!enterSegment(session, openingStep(chosen), previousSignature = null)) {
                        emit(EngineEvent.ERROR, "Could not open ${chosen.appPackage}")
                        say(session, session.phrases.screenNotReached())
                        session.status = RunStatus.FAILED
                        return
                    }
                    continue
                }
                val multi = active
                if (multi != null && segment + 1 < multi.segments.size) {
                    // Continue the same flow on its next screen (possibly in another app).
                    segment++
                    if (!enterSegment(session, multi.segments[segment].first(), previousSignature = session.currentSignature)) {
                        // A remembered way that no longer fits (the app changed): do the goal step by step instead.
                        if (goalAgent != null && multi.id.startsWith(FlowDefinition.TAUGHT_PREFIX)) {
                            active = null
                            val here = readScreen(session) ?: snapshot
                            val goalLog = ScreenLog(here, null, null)
                            val result = try {
                                runGoal(session, multi.name, goalLog)
                            } finally {
                                if (goalLog.steps.isNotEmpty()) session.screens += goalLog.toRecord()
                            }
                            if (result == ScreenOutcome.STOPPED) {
                                say(session, session.phrases.stopped())
                                session.status = RunStatus.STOPPED
                                return
                            }
                            delay(screenSettleMillis)
                            continue
                        }
                        emit(EngineEvent.ERROR, "The next screen did not appear")
                        say(session, session.phrases.screenNotReached())
                        session.status = RunStatus.FAILED
                        return
                    }
                    continue
                }
                active = null
                if (outcome == ScreenOutcome.COMPLETED) break
                // Something was pressed or opened: wait for the app to react and carry on, on a new form or as
                // the assistant ("What next?"), until the user says stop or stays silent.
                delay(screenSettleMillis)
                val next = readScreen(session) ?: break
                val hasForm = next.elements.any { it.kind.isInput || it.kind.isToggle }
                val sameScreen = next.signature == session.currentSignature
                // The form just filled is still showing (submitted, or the app shows it again): finished.
                if (hasForm && sameScreen) break
            }
            say(session, session.phrases.done())
        } catch (e: UserStop) {
            session.status = RunStatus.STOPPED
        } catch (e: CancellationException) {
            session.status = RunStatus.STOPPED
            throw e
        } catch (e: Exception) {
            session.status = RunStatus.FAILED
            emit(EngineEvent.ERROR, e.message ?: "Unexpected error")
            _state.update { it.copy(status = EngineStatus.ERROR, caption = e.message ?: session.phrases.actionFailed()) }
        } finally {
            withContext(NonCancellable) {
                finish(session)
            }
        }
    }

    /** Key of a field in [AnswerMemory]: its type and label, so the same field of the same app matches. */
    private fun answerKey(step: PlanStep): String = "${step.fieldType?.name ?: "TEXT"}:${FlowVariables.slug(step.label, 0)}"

    private fun remembersAnswers(session: Session, step: PlanStep): Boolean =
        answers != null && session.cfg.rememberAnswers && !step.isSensitive && step.fieldType?.isSensitive != true &&
            step.action == StepAction.FILL && step.kind == ElementKind.TEXT_FIELD && session.appPackage.isNotEmpty()

    private suspend fun recallAnswer(session: Session, step: PlanStep): String? {
        if (!remembersAnswers(session, step)) return null
        return runCatching { answers?.recall(session.appPackage, answerKey(step)) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun rememberAnswer(session: Session, step: PlanStep, value: String) {
        if (!remembersAnswers(session, step) || value.isBlank()) return
        runCatching { answers?.remember(session.appPackage, answerKey(step), step.label, value) }
    }

    /** How a flow started on demand gets to its first screen: its own boundary step, else opening its app. */
    private fun openingStep(flow: FlowDefinition): FlowStep =
        flow.segments.firstOrNull()?.firstOrNull()?.takeIf { it.action.isScreenBoundary }
            // A flow started on demand runs in its own app: open it unless it is already in front.
            ?: FlowStep("open", -1, "", flow.name, ElementKind.BUTTON, action = StepAction.OPEN_APP, appPackage = flow.appPackage)

    /**
     * The flow of the voice shortcut the user just said, if any. A visible button with the same name
     * wins, so shortcuts never hide what is on the screen.
     */
    private suspend fun shortcutFlow(session: Session, snapshot: ScreenSnapshot, heard: String): FlowDefinition? {
        val source = shortcuts ?: return null
        val buttons = snapshot.buttons.map { ShortcutMatcher.normalize(it.label) }.toSet()
        if (ShortcutMatcher.core(heard) in buttons) return null
        val hit = ShortcutMatcher.match(heard, runCatching { source.shortcuts() }.getOrDefault(emptyList())) ?: return null
        if (ShortcutMatcher.core(hit.phrase) in buttons) return null
        val flow = runCatching { source.flow(hit.flowId) }.getOrNull()
        if (flow == null) {
            emit(EngineEvent.ERROR, "Voice shortcut “${hit.phrase}”: its flow is not available")
            say(session, session.phrases.shortcutUnavailable(hit.phrase))
            return null
        }
        say(session, session.phrases.startingShortcut(flow.name))
        runCatching { source.started(hit) }
        emit(EngineEvent.STATUS, "Voice shortcut “${hit.phrase}”: ${flow.name}")
        return flow
    }

    /**
     * Reads the screen through accessibility; if nothing readable is exposed and vision fallback is on,
     * detects elements on a screenshot instead (those elements are then operated with taps).
     */
    private suspend fun readScreen(session: Session): ScreenSnapshot? =
        readScreenOnce(session)?.also { snap -> session.bias = biasFor(snap) }

    /** Button, field and screen names, short ones first: what the recognizer should expect to hear. */
    private fun biasFor(snapshot: ScreenSnapshot): List<String> =
        (snapshot.elements.map { it.label } + listOfNotNull(snapshot.title))
            .map { it.trim() }
            .filter { it.length in 2..MAX_BIAS_CHARS && it.any(Char::isLetter) }
            .distinct()
            .sortedBy { it.length }
            .take(MAX_BIAS_PHRASES)

    private suspend fun readScreenOnce(session: Session): ScreenSnapshot? {
        val snapshot = screen.capture()
        visionElements = emptyMap()
        if (snapshot != null && snapshot.hasReadableElements) return withHybridVision(session, snapshot)
        val detector = vision ?: return snapshot
        // Smart mode looks at screens the phone can't read (web pages, games) too.
        if (!(session.cfg.visionFallback || session.cfg.smartMode) || session.cfg.localOnly) return snapshot
        setStatus(EngineStatus.THINKING, caption = session.phrases.lookingAtScreen())
        val shot = screen.screenshot() ?: return snapshot
        val pkg = snapshot?.packageName ?: _state.value.appPackage ?: ""
        val elements = runCatching { detector.detect(shot, pkg, session.cfg.language) }.getOrNull()
            ?.filter { it.id.startsWith(VisionDetector.VISION_ID_PREFIX) && !it.bounds.isEmpty }
            .orEmpty()
        if (elements.isEmpty()) return snapshot
        visionElements = elements.associateBy { it.id }
        val base = ScreenSnapshot(packageName = pkg, activityName = snapshot?.activityName, title = snapshot?.title, elements = elements, capturedAtMillis = clock())
        return base.copy(signature = com.voicecontrol.core.screen.ScreenSignature.of(base))
    }

    /**
     * Partly readable screens (unlabelled inputs, custom-drawn or web content): merge the screenshot
     * model's detections into the accessibility elements. The signature stays the accessibility one, so
     * flow matching is unaffected.
     */
    private suspend fun withHybridVision(session: Session, snapshot: ScreenSnapshot): ScreenSnapshot {
        val detector = vision ?: return snapshot
        if (!(session.cfg.visionFallback || session.cfg.smartMode) || session.cfg.localOnly || !HybridVision.needsHelp(snapshot)) return snapshot
        val result = session.hybrid[snapshot.signature] ?: run {
            setStatus(EngineStatus.THINKING, caption = session.phrases.lookingAtScreen())
            val shot = screen.screenshot() ?: return snapshot
            val detected = runCatching { detector.detect(shot, snapshot.packageName, session.cfg.language) }.getOrNull()
                ?.filter { it.id.startsWith(VisionDetector.VISION_ID_PREFIX) }
                .orEmpty()
            HybridVision.merge(snapshot.elements, detected).also { session.hybrid[snapshot.signature] = it }
        }
        visionElements = result.added.associateBy { it.id }
        return snapshot.copy(elements = result.elements)
    }

    /**
     * Gets to the screen a flow segment starts on: OPEN_APP launches the app and waits for it,
     * NEXT_SCREEN waits until the screen differs from [previousSignature]. Returns false on timeout.
     */
    private suspend fun enterSegment(session: Session, boundary: FlowStep, previousSignature: String?): Boolean {
        val timeoutMs = (boundary.waitSeconds ?: DEFAULT_WAIT_SECONDS).coerceIn(1, MAX_WAIT_SECONDS) * 1_000L
        val pkg = boundary.appPackage?.takeIf { it.isNotBlank() }
        return when (boundary.action) {
            StepAction.OPEN_APP -> {
                if (pkg == null) return false
                if (screen.capture()?.packageName == pkg) return true
                say(session, session.phrases.openingApp(boundary.label.ifBlank { pkg }))
                emit(EngineEvent.SCREEN, "Opening $pkg")
                setStatus(EngineStatus.ACTING)
                if (!perform(ScreenAction.LaunchApp(pkg)).isSuccess) return false
                waitForScreen(timeoutMs) { it.packageName == pkg }
            }
            StepAction.NEXT_SCREEN -> {
                if (previousSignature == null) return true
                setStatus(EngineStatus.THINKING, caption = session.phrases.waitingForScreen())
                emit(EngineEvent.SCREEN, "Waiting for the next screen")
                val moved: (ScreenSnapshot) -> Boolean = { snap ->
                    (pkg == null || snap.packageName == pkg) && snap.signature != previousSignature
                }
                val field = session.lastFilledId
                if (field == null) return waitForScreen(timeoutMs, moved)
                // Search boxes have no button: when typing alone didn't move on, press the keyboard's Enter.
                val first = minOf(timeoutMs, ENTER_AFTER_MS)
                if (waitForScreen(first, moved)) return true
                session.lastFilledId = null
                if (perform(ScreenAction.PressEnter(field)).isSuccess) emit(EngineEvent.STEP, "Pressed Enter")
                waitForScreen((timeoutMs - first).coerceAtLeast(SCREEN_POLL_MS), moved)
            }
            else -> true
        }
    }

    private suspend fun waitForScreen(timeoutMs: Long, accept: (ScreenSnapshot) -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            val snap = screen.capture()
            if (snap != null && snap.hasReadableElements && accept(snap)) {
                delay(screenSettleMillis)
                return true
            }
            if (waited >= timeoutMs) return false
            delay(SCREEN_POLL_MS)
            waited += SCREEN_POLL_MS
        }
    }

    /** Non-sensitive values already on screen, available to expressions by label slug. */
    private fun screenVariables(snapshot: ScreenSnapshot): Map<String, String> = buildMap {
        snapshot.elements.forEach { e ->
            if (e.isSensitive || e.label.none { it.isLetterOrDigit() && it.code < 128 }) return@forEach
            val value = when {
                e.kind.isToggle -> e.isChecked?.let { if (it) "yes" else "no" }
                e.kind.isInput -> e.value?.takeIf { it.isNotBlank() }
                else -> null
            } ?: return@forEach
            putIfAbsent(FlowVariables.slug(e.label, 0), value)
        }
    }

    /** Elements detected by vision on the current screen, operated by coordinates. */
    @Volatile
    private var visionElements: Map<String, ScreenElement> = emptyMap()

    /** Routes actions on vision-detected elements to taps/typing; everything else goes to the gateway. */
    /**
     * The keyboard's Enter/Search key on [action]'s field. Where the phone or app has none (Android 10 and
     * older, some apps), the search button next to the field is pressed instead.
     */
    private suspend fun pressEnter(action: ScreenAction.PressEnter): ActionResult {
        val enter = screen.perform(action)
        if (enter.isSuccess) return enter
        val snap = screen.capture() ?: return enter
        val field = snap.element(action.elementId) ?: return enter
        val button = snap.buttons
            .filter { b -> b.isEnabled && b.id != field.id && listOf(b.label, b.hint.orEmpty()).any { it.trim().lowercase() in SEARCH_BUTTON_WORDS } }
            .minByOrNull { b -> kotlin.math.abs(b.bounds.centerY - field.bounds.centerY) * 2 + kotlin.math.abs(b.bounds.centerX - field.bounds.centerX) }
            ?: return enter
        emit(EngineEvent.STEP, "No Enter key here; pressed ${button.label}")
        return screen.perform(ScreenAction.Click(button.id))
    }

    private suspend fun perform(action: ScreenAction): ActionResult {
        if (action is ScreenAction.PressEnter) return pressEnter(action)
        val elementId = when (action) {
            is ScreenAction.SetText -> action.elementId
            is ScreenAction.Click -> action.elementId
            is ScreenAction.SetChecked -> action.elementId
            is ScreenAction.Focus -> action.elementId
            else -> null
        }
        val target = elementId?.let { visionElements[it] } ?: return screen.perform(action)
        val tap = ScreenAction.TapAt(target.bounds.centerX, target.bounds.centerY)
        return when (action) {
            is ScreenAction.SetText -> {
                val tapped = screen.perform(tap)
                if (!tapped.isSuccess) return tapped
                delay(VISION_FOCUS_DELAY_MS)
                screen.perform(ScreenAction.TypeIntoFocused(action.text))
            }
            else -> screen.perform(tap)
        }
    }

    private suspend fun finish(session: Session) {
        val summary = SessionSummary(
            sessionId = session.id,
            appPackage = session.appPackage.ifEmpty { ASSISTANT_SCREEN },
            startedAtMillis = session.startedAt,
            endedAtMillis = clock(),
            status = session.status,
            language = session.cfg.language,
            // Assistant jobs go in as one screen marked with its own flow id, so no flow is made from them.
            screens = session.screens.toList() + listOfNotNull(
                session.assistantSteps.takeIf { it.isNotEmpty() }?.let { steps ->
                    ScreenRecord(
                        appPackage = session.appPackage.ifEmpty { ASSISTANT_SCREEN },
                        screenTitle = "Assistant",
                        screenSignature = ASSISTANT_SCREEN,
                        flowId = ASSISTANT_SCREEN,
                        steps = steps.toList(),
                    )
                },
            ),
        )
        if (summary.screens.isNotEmpty()) runCatching { recorder.record(summary) }
        val ending = when (session.status) {
            RunStatus.COMPLETED -> "Finished"
            RunStatus.STOPPED -> "Stopped"
            RunStatus.FAILED -> "Failed"
        }
        emit(EngineEvent.STATUS, ending, session.status)
        _state.update {
            it.copy(
                status = if (session.status == RunStatus.FAILED) EngineStatus.ERROR else EngineStatus.FINISHED,
                active = false,
                heard = null,
                progress = null,
                helpVideoUrl = null,
                micLevel = 0f,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Screen

    private suspend fun runScreen(
        session: Session,
        snapshot: ScreenSnapshot,
        plan: ScreenPlan,
        log: ScreenLog,
        announce: Boolean,
    ): ScreenOutcome {
        val phrases = session.phrases
        // Nothing to fill and no press taught by a flow, or the user already said what they want: talk instead.
        if ((plan.steps.isEmpty() && !plan.submitFromFlow) || session.firstRequest != null) return commandMode(session, snapshot, log)
        // Smart mode: a form without a saved flow is filled by the AI, which asks the user in plain words.
        // A video's comment box or a lone search box isn't a form: talk instead of explaining that it isn't.
        if (session.smart && plan.flowId == null) {
            if (!isRealForm(snapshot) || snapshot.signature in session.smartHandled) return commandMode(session, snapshot, log)
            session.smartHandled += snapshot.signature
            val outcome = runGoal(session, SMART_FORM_GOAL, log, auto = true)
            if (!session.smartOff) return outcome
        }
        // "Yahan aapka naam aur mobile number bharna hai. Main ek-ek karke poochta hoon." instead of "I found 2 fields".
        val asked = plan.steps.filter { !it.skip && !it.virtual && it.action != StepAction.CLICK && it.action != StepAction.READ }
        if (announce && asked.isNotEmpty()) {
            say(session, phrases.fillIntro(asked.map { it.label.trim().trimEnd('*', ':', ' ') }.filter { it.isNotBlank() }.distinct()))
        }

        when (runSteps(session, snapshot, plan.steps, log, progressPrefix = "")) {
            StepResult.Stop -> return ScreenOutcome.STOPPED
            StepResult.Navigated -> return ScreenOutcome.NAVIGATED
            else -> Unit
        }
        _state.update { it.copy(progress = null, helpVideoUrl = null) }
        return submit(session, snapshot, plan, log)
    }

    /**
     * Screens without fields: an assistant conversation. It asks what the user wants ("What can I do for
     * you?"), then presses a button they name, opens an app ("open WhatsApp"), scrolls or goes back. When
     * the user is unsure ("what can I do?", "pata nahi") or isn't understood, it suggests what this screen
     * offers (or the AI's suggestion). It keeps listening until the user acts, says stop, or stays silent.
     */
    private suspend fun commandMode(session: Session, snapshot: ScreenSnapshot, log: ScreenLog): ScreenOutcome {
        val phrases = session.phrases
        // After something was done the mic just listens (the bubble shows it); "What next?" only if the user stays quiet.
        var prompt = if (session.greeted) "" else greeting(session, snapshot)
        session.greeted = true
        repeat(MAX_COMMAND_TURNS) { turn ->
            val heard = session.firstRequest?.also {
                session.firstRequest = null
                emit(EngineEvent.STATUS, "Request with the wake phrase")
                _state.update { s -> s.copy(heard = it) }
            } ?: askAndListen(session, prompt) ?: run {
                if (prompt.isBlank()) prompt = phrases.whatNext()
                return@repeat
            }
            session.noteTurn(USER_SAID, heard)
            // Misunderstandings count only in a row: anything understood starts again from zero.
            val missed = session.misses
            session.misses = 0
            shortcutFlow(session, snapshot, heard)?.let { flow ->
                session.switchTo = flow
                return ScreenOutcome.SWITCHED
            }
            // "bhejo" on a chat presses Send, "call karo" in the dialer presses Call.
            PersonalTasks.spokenAlias(heard, snapshot.elements)?.let { button ->
                if (press(session, button, log)) return ScreenOutcome.NAVIGATED
                return@repeat
            }
            // Personal assistant: "6 baje ka alarm", "YouTube pe gaane chalao", "Rahul ko call karo", "time kya hua".
            PhoneTask.parse(heard)?.let { task ->
                // "Maggi search karo" inside an app with a search box searches there, not on Google.
                if (task is PhoneTask.Search && task.place == com.voicecontrol.core.nlp.SearchPlace.WEB && GOOGLE_WORDS.none { it in heard.lowercase() }) {
                    snapshot.elements.firstOrNull { it.kind.isInput && it.fieldType == com.voicecontrol.core.model.FieldType.SEARCH }?.let { box ->
                        if (searchHere(session, box, task.query)) return ScreenOutcome.NAVIGATED
                        prompt = phrases.anythingElse(turn)
                        return@repeat
                    }
                }
                if (task is PhoneTask.CloseApp) {
                    if (closeApp(session, snapshot, task.app)) return ScreenOutcome.NAVIGATED
                    prompt = phrases.anythingElse(turn)
                    return@repeat
                }
                emit(EngineEvent.STEP, "Assistant: ${task::class.simpleName}")
                session.noteAssistant(task::class.simpleName ?: "Assistant")
                setStatus(EngineStatus.ACTING)
                session.lastAnswer.clear()
                val result = personal.run(task, phrases, session.cfg.language, say = { say(session, it); session.lastAnswer += it }, ask = { askAndListen(session, it) })
                if (result == PersonalTasks.Result.MESSAGE_READY) {
                    sendMessage(session, log)
                    return ScreenOutcome.NAVIGATED
                }
                if (result == PersonalTasks.Result.MOVED) return ScreenOutcome.NAVIGATED
                prompt = phrases.anythingElse(turn)
                return@repeat
            }
            // "Mujhe bijli ka bill bharna hai": the helper operates the app and asks for what it needs.
            // In smart mode every request that isn't a plain command ("stop", "back", "next") goes to it.
            val smartRequest = session.smart && localCommands.commandOf(request(session, snapshot, heard)) == null &&
                ButtonMatcher.find(heard, snapshot.elements, ButtonMatcher.STRICT) == null
            if (goalAgent != null && (smartRequest || GoalRequest.isGoal(heard))) {
                // Something the user asked for: announced and offered as a shortcut; without the AI, carry on.
                val outcome = runGoal(session, heard, log, fallback = smartRequest && !GoalRequest.isGoal(heard))
                if (!session.smartOff) return outcome
            }
            // The answer is usually just a button's name: "Login", "लॉगिन", "OK", "Next". When the recognizer's
            // first guess names nothing, its other guesses may ("log in" heard as "lock in").
            (spokenButton(session, snapshot, heard) ?: session.alternatives.firstNotNullOfOrNull { alt ->
                ButtonMatcher.find(alt, snapshot.elements, ButtonMatcher.STRICT)
            })?.let { button ->
                if (press(session, button, log)) return ScreenOutcome.NAVIGATED
                return@repeat
            }
            // "open WhatsApp", "YouTube kholo": a visible button of that name wins, else the installed app.
            AppRequest.parse(heard)?.let { name ->
                ButtonMatcher.find(name, snapshot.elements, ButtonMatcher.STRICT)?.let { button ->
                    if (press(session, button, log)) return ScreenOutcome.NAVIGATED
                    return@repeat
                }
                if (openApp(session, name)) return ScreenOutcome.NAVIGATED
                prompt = suggestHere(session, snapshot)
                return@repeat
            }
            val interp = interpret(session, snapshot, null, heard, prompt)
            when (interp.intent) {
                IntentKind.CLICK -> if (clickTarget(session, snapshot, interp, log)) return ScreenOutcome.NAVIGATED
                IntentKind.NEXT, IntentKind.SUBMIT -> {
                    val main = ButtonMatcher.primarySubmit(snapshot.elements)
                    if (main == null) prompt = suggestHere(session, snapshot)
                    else if (press(session, main, log)) return ScreenOutcome.NAVIGATED
                }
                IntentKind.BACK -> {
                    act(session, ScreenAction.Back, phrases.wentBack())
                    return ScreenOutcome.NAVIGATED
                }
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> {
                    scroll(session, interp.intent)
                    return ScreenOutcome.NAVIGATED
                }
                IntentKind.STOP, IntentKind.NO -> return ScreenOutcome.STOPPED
                IntentKind.HELP -> prompt = interp.reply?.takeIf { it.isNotBlank() } ?: suggestHere(session, snapshot)
                IntentKind.UNDO -> say(session, undoLast()?.let(phrases::undone) ?: phrases.nothingToUndo())
                IntentKind.READ_SCREEN -> if (runReader(session, snapshot, log)) return ScreenOutcome.NAVIGATED
                // "phir se bolo": the last answer again (the question is asked again anyway).
                IntentKind.REPEAT -> session.lastAnswer.takeIf { it.isNotEmpty() }?.let { say(session, it.joinToString(" ")) }
                else -> {
                    // A short name that isn't on screen ("Maggi"): look further down the list for it.
                    if (interp.reply.isNullOrBlank() && heard.trim().split(' ').size <= MAX_SCROLL_SEARCH_WORDS && snapshot.isScrollable) {
                        val found = findByScrolling(session, heard)
                        if (found != null && press(session, found, log)) return ScreenOutcome.NAVIGATED
                        if (found == null) {
                            prompt = phrases.notFoundAfterScrolling(heard)
                            return@repeat
                        }
                    }
                    // A longer request nothing on screen matches: try doing it step by step.
                    if (goalAgent != null && interp.reply.isNullOrBlank() && heard.trim().split(' ').size >= MIN_GOAL_WORDS) {
                        return runGoal(session, heard, log)
                    }
                    prompt = interp.reply?.takeIf { it.isNotBlank() }
                        ?: notUnderstood(session, snapshot, missed)
                }
            }
        }
        return ScreenOutcome.STOPPED
    }

    /**
     * "Do it for me": works towards [goal] one step at a time, like a person helping, across screens and
     * apps. Each step is planned by [goalAgent] from the screen and what happened so far; values it needs
     * are asked from the user (passwords, OTPs and PINs the user types; they are never heard or sent), and presses
     * that pay, send or delete are confirmed first. Ends when the goal is reached, can't be done, the user
     * says stop, or after [MAX_AGENT_STEPS]; then the conversation carries on ("What next?").
     */
    /**
     * [auto]: started by smart mode rather than asked for as a job (a form): it doesn't announce itself and
     * isn't offered as a shortcut. [fallback]: when the AI can't be reached it turns smart mode off for the
     * session (silently) so the caller carries on the built-in way.
     */
    private suspend fun runGoal(session: Session, goal: String, log: ScreenLog, auto: Boolean = false, fallback: Boolean = auto): ScreenOutcome {
        val agent = goalAgent ?: return ScreenOutcome.NAVIGATED
        val phrases = session.phrases
        emit(EngineEvent.STATUS, if (auto) "Smart mode" else "Helping with a goal")
        session.noteAssistant(if (auto) "Smart mode" else "Did it for me")
        if (!auto) say(session, phrases.onIt())
        session.greeted = true
        val history = mutableListOf<String>()
        // Always sent first: details saved in the app and what was said before (the helper uses them without asking).
        val known = listOfNotNull(
            knownAbout(session.profile),
            session.turns.filter { it.label == USER_SAID && it.value != goal }.takeLast(MAX_EARLIER_TURNS)
                .takeIf { it.isNotEmpty() }?.let { said -> "Earlier the user said: " + said.joinToString(" / ") { it.value } },
        )
        // What was done, screen by screen, to remember the way when the goal is reached.
        val learned = FlowRecorder()
        var lastKey = ""
        var repeats = 0
        var saidMoment = false
        var stepsSinceAgreed = Int.MAX_VALUE / 2
        // The helper's own plan, carried from step to step.
        var plan: String? = null
        // Notes from earlier jobs in each app ("Tips for this app"), read once per app.
        val tips = HashMap<String, String?>()
        suspend fun appTips(pkg: String): String? = tips.getOrPut(pkg) {
            runCatching { goalMemory?.notes(pkg) }.getOrNull().orEmpty().takeIf { it.isNotEmpty() }
                ?.joinToString(" | ", prefix = "Tips for this app from earlier jobs: ")
        }
        // A job asked for already got "Theek hai, main kar deta hoon": the steps can stay quiet.
        var narrated = !auto
        var wakeRetries = 0
        repeat(MAX_AGENT_STEPS) {
            stepsSinceAgreed++
            val snap = readScreen(session) ?: return ScreenOutcome.NAVIGATED
            learned.onEvent(RecordedEvent.Screen(snap))
            session.appPackage = snap.packageName
            setStatus(EngineStatus.THINKING, caption = null)
            // The helper also sees the screen's other text (amounts, messages, errors), with long numbers and codes masked.
            val shown = snap.redacted().copy(
                texts = snap.texts.take(MAX_AGENT_TEXTS).map { it.copy(text = TextMask.mask(it.text.take(MAX_AGENT_TEXT_CHARS))) },
            )
            val d = coroutineScope {
                val planned = async { runCatching { agent.next(goal, shown, known + listOfNotNull(appTips(snap.packageName), plan?.let { "Your plan: $it" }) + history.takeLast(MAX_AGENT_HISTORY), session.cfg.language) }.getOrNull() }
                // A slow step: say "one moment" (once per goal) instead of going quiet.
                val filler = if (saidMoment) null else launch {
                    delay(AGENT_FILLER_MS)
                    saidMoment = true
                    say(session, phrases.oneMoment())
                }
                val result = planned.await()
                if (filler != null && !saidMoment) filler.cancel() else filler?.join()
                result
            }
            val problem = if (d == null) runCatching { agent.problem() }.getOrNull() else null
            // A sleeping or slow server: say so once and try again (it usually answers within a minute).
            if (d == null && (problem == AgentProblem.SERVER_SLOW || problem == AgentProblem.NO_CONNECTION) && wakeRetries < MAX_WAKE_RETRIES) {
                if (wakeRetries == 0) say(session, phrases.serverWaking())
                wakeRetries++
                emit(EngineEvent.STATUS, "Server slow or asleep: trying again ($wakeRetries)")
                delay(WAKE_RETRY_DELAY_MS)
                return@repeat
            }
            if (d == null && fallback && history.isEmpty()) {
                emit(EngineEvent.STATUS, "Smart mode unavailable (${problem ?: "unknown"}); carrying on without it")
                // Tell once why smart mode isn't helping, unless it's simply switched off for this phone.
                if (!session.toldSmartOff && problem != null && problem != AgentProblem.LOCAL_ONLY) {
                    session.toldSmartOff = true
                    say(session, phrases.smartModeUnavailable(problem))
                }
                session.smartOff = true
                return ScreenOutcome.NAVIGATED
            }
            if (d == null) {
                say(session, if (history.isEmpty()) phrases.helperUnavailable(problem) else phrases.goalFailed())
                return ScreenOutcome.NAVIGATED
            }
            wakeRetries = 0
            d.plan?.takeIf { it.isNotBlank() }?.let { plan = it }
            // The same step on the same screen again and again: the plan is stuck, so ask the user.
            val key = "${snap.signature}|${d.action}|${d.targetId}|${d.appName}"
            repeats = if (key == lastKey) repeats + 1 else 0
            lastKey = key
            if (repeats >= MAX_AGENT_REPEATS) {
                val answer = askAndListen(session, phrases.agentStuck(suggestions(snap))) ?: return ScreenOutcome.NAVIGATED
                if (localCommands.commandOf(request(session, snap, answer))?.intent == IntentKind.STOP) return ScreenOutcome.STOPPED
                history += "The plan was stuck; the user said: $answer"
                repeats = 0
                lastKey = ""
                return@repeat
            }
            // Spoken: the first step, the result and anything that went wrong. The steps in between only show
            // under the mic, so the helper doesn't narrate every tap. Not "paying now" before "shall I pay?":
            // a confirmed press speaks its question instead.
            val finalStep = d.action == AgentAction.DONE || d.action == AgentAction.GIVE_UP
            if (d.action != AgentAction.ASK && !(d.action == AgentAction.CLICK && d.confirm)) d.say?.let { line ->
                if (finalStep || !narrated) say(session, line) else setStatus(EngineStatus.ACTING, caption = line)
                narrated = true
            }
            when (d.action) {
                AgentAction.CLICK -> {
                    val target = d.targetId?.let(snap::element)
                    if (target == null) {
                        history += "Tried to press ${d.targetId}, but it is not on the screen"
                        return@repeat
                    }
                    val named = target.label.ifBlank { target.hint.orEmpty() }
                    // One confirmation, in the planner's words when it gave them ("Rahul ko 500 rupaye bhej doon?").
                    // The "Submit" after the PIN of a payment the user just agreed to isn't asked again.
                    val destructive = session.cfg.confirmDestructive && DestructiveActions.isDestructive(named)
                    val risky = destructive && (d.confirm || stepsSinceAgreed > AGREED_FOLLOW_UP_STEPS)
                    if (d.confirm || risky) {
                        val question = d.question?.takeIf { d.confirm } ?: if (risky) phrases.confirmDestructive(named) else phrases.confirmPress(named)
                        when (askYesNoOr(session, snap, scamCheck(session, question), default = false)) {
                            true -> {
                                history += "The user agreed: $question"
                                stepsSinceAgreed = 0
                            }
                            null -> return ScreenOutcome.STOPPED
                            false -> {
                                say(session, phrases.notPressed(named))
                                // "Order Maggi, up to paying": the way so far is worth keeping; next time the
                                // flow stops at this same question.
                                if (!auto) offerToRemember(session, goal, learned.recording(), snap)
                                return ScreenOutcome.NAVIGATED
                            }
                        }
                    }
                    if (press(session, target, log, confirmed = d.confirm || destructive)) {
                        learned.onEvent(RecordedEvent.Pressed(target.id, on = snap))
                        delay(screenSettleMillis)
                        // Tell the planner when a press did nothing, so it tries another way.
                        val after = screen.capture()
                        val unchanged = after != null && after.signature == snap.signature &&
                            after.elements.map { it.label to it.value } == snap.elements.map { it.label to it.value }
                        history += "Pressed \"$named\"" + if (unchanged) "; nothing changed on the screen" else ""
                    } else {
                        history += "Pressing \"$named\" did not work or the user said no"
                        if (d.confirm || DestructiveActions.isDestructive(named)) return ScreenOutcome.NAVIGATED
                    }
                }
                AgentAction.FILL -> {
                    val target = d.targetId?.let(snap::element)
                    val value = d.value
                    if (target == null || value.isNullOrBlank() || target.isSensitive || target.fieldType?.isSensitive == true) {
                        history += "Could not type into ${d.targetId}"
                        return@repeat
                    }
                    val ok = perform(ScreenAction.SetText(target.id, value)).isSuccess
                    if (ok) {
                        recordUndo(target)
                        learned.onEvent(RecordedEvent.Typed(target.id, on = snap))
                    }
                    // Check the words really landed: some apps ignore typing or change it (masks, autocomplete).
                    val after = if (ok) { delay(screenSettleMillis); screen.capture()?.element(target.id) } else null
                    val shown = after?.value
                    history += when {
                        !ok -> "Typing into \"${target.label}\" failed"
                        after != null && shown.isNullOrBlank() -> "Typed \"$value\" into \"${target.label}\", but the field still looks empty"
                        shown == null || sameText(shown, value) -> "Typed \"$value\" into \"${target.label}\""
                        else -> "Typed \"$value\" into \"${target.label}\", but the field shows \"${shown.take(MAX_AGENT_TEXT_CHARS)}\""
                    }
                }
                AgentAction.ASK -> {
                    val field = d.targetId?.let(snap::element)?.takeIf { it.kind.isInput || it.kind.isToggle }
                    if (field != null) {
                        val planned = PlanBuilder(phrases).build(snap, null, session.profile).steps.firstOrNull { it.elementId == field.id }
                        if (planned != null) {
                            val step = d.question?.let { planned.copy(question = it, customQuestion = true) } ?: planned
                            when (handleStep(session, snap, step, log, mutableSetOf())) {
                                StepResult.Stop -> return ScreenOutcome.STOPPED
                                else -> Unit
                            }
                            learned.onEvent(RecordedEvent.Typed(field.id, on = snap))
                            val now = screen.capture()?.element(field.id)
                            history += when {
                                field.isSensitive || field.fieldType?.isSensitive == true -> "The user typed \"${field.label}\" themselves"
                                !now?.value.isNullOrBlank() -> "Asked for \"${field.label}\"; filled with \"${now?.value}\""
                                else -> "Asked for \"${field.label}\"; the user did not give it"
                            }
                            return@repeat
                        }
                    }
                    val question = d.question ?: return@repeat
                    val answer = askAndListen(session, question)
                    if (answer == null) {
                        history += "Asked \"$question\"; no answer"
                        return@repeat
                    }
                    if (localCommands.commandOf(request(session, snap, answer))?.intent == IntentKind.STOP) return ScreenOutcome.STOPPED
                    history += "Asked \"$question\"; the user said: $answer"
                }
                AgentAction.SCROLL_DOWN, AgentAction.SCROLL_UP -> {
                    val up = d.action == AgentAction.SCROLL_UP
                    perform(ScreenAction.Scroll(if (up) ScrollDirection.UP else ScrollDirection.DOWN))
                    history += if (up) "Scrolled up" else "Scrolled down"
                    delay(screenSettleMillis)
                }
                AgentAction.BACK -> {
                    perform(ScreenAction.Back)
                    history += "Went back"
                    delay(screenSettleMillis)
                }
                AgentAction.OPEN_APP -> {
                    val name = d.appName ?: return@repeat
                    history += if (openApp(session, name)) "Opened $name" else "$name is not installed"
                    delay(screenSettleMillis)
                }
                AgentAction.WAIT -> {
                    history += "Waited for the screen"
                    delay(AGENT_WAIT_MS)
                }
                AgentAction.DONE -> {
                    if (d.say == null) say(session, phrases.goalDone())
                    rememberHowItWent(goal, snap.packageName, history)
                    emit(EngineEvent.STATUS, "Goal done")
                    screen.capture()?.let { learned.onEvent(RecordedEvent.Screen(it)) }
                    if (!auto) offerToRemember(session, goal, learned.recording(), snap)
                    return ScreenOutcome.NAVIGATED
                }
                AgentAction.GIVE_UP -> {
                    if (d.say == null) say(session, phrases.goalFailed())
                    return ScreenOutcome.NAVIGATED
                }
            }
        }
        say(session, phrases.goalFailed())
        return ScreenOutcome.NAVIGATED
    }

    /**
     * After a job: a short note for this app ("To order maggi: Search > Maggi 2-minute > Add to cart"), so
     * the helper finds its way faster next time. Only the names of what was pressed or filled; never values.
     */
    private suspend fun rememberHowItWent(goal: String, appPackage: String, history: List<String>) {
        val memory = goalMemory ?: return
        val steps = history.mapNotNull { line ->
            when {
                line.startsWith("Pressed \"") -> line.substringAfter("Pressed \"").substringBefore('"')
                line.startsWith("Typed \"") -> "type in " + line.substringAfter(" into \"").substringBefore('"')
                line.startsWith("Asked for \"") -> "ask " + line.substringAfter("Asked for \"").substringBefore('"')
                line == "Scrolled down" -> "scroll"
                else -> null
            }
        }.takeLast(MAX_NOTE_STEPS)
        if (steps.isEmpty()) return
        runCatching { memory.addNote(appPackage, "To ${goal.take(60)}: " + steps.joinToString(" > ")) }
    }

    /** After a goal was reached: offer to keep the way as a flow that the goal's words start next time. */
    private suspend fun offerToRemember(session: Session, goal: String, recording: Recording, context: ScreenSnapshot) {
        val memory = goalMemory ?: return
        if (recording.actionCount < MIN_LEARNED_ACTIONS) return
        val phrase = shortcutPhrase(goal) ?: return
        val flow = runCatching { RecordingToFlow.build(recording, FlowDefinition.TAUGHT_PREFIX + newId(), clock(), name = phrase) }.getOrNull() ?: return
        if (askYesNoOr(session, context, session.phrases.offerToRemember(phrase), default = false) != true) return
        if (runCatching { memory.learn(phrase, flow) }.getOrDefault(false)) {
            say(session, session.phrases.remembered())
            emit(EngineEvent.STATUS, "Learned “$phrase”")
        }
    }

    /** The goal's words as a voice shortcut (at most [ShortcutMatcher.MAX_LENGTH] letters, whole words). */
    private fun shortcutPhrase(goal: String): String? {
        val words = goal.trim().trimEnd('.', '?', '!', '।').split(Regex("\\s+"))
        val kept = StringBuilder()
        for (w in words) {
            if (kept.length + w.length + 1 > ShortcutMatcher.MAX_LENGTH) break
            if (kept.isNotEmpty()) kept.append(' ')
            kept.append(w)
        }
        return kept.toString().takeIf { ShortcutMatcher.validate(it) == null }
    }

    /** A WhatsApp chat opened with the message typed in: ask once, then press Send. */
    private suspend fun sendMessage(session: Session, log: ScreenLog) {
        var send: ScreenElement? = null
        waitForScreen(MESSAGE_WAIT_MS) { snap -> PersonalTasks.sendButton(snap.elements)?.also { send = it } != null }
        val button = send ?: return
        val name = personal.messageTo
        val context = screen.capture() ?: return
        if (!confirmSend(session, context, name, button)) return
        val ok = perform(ScreenAction.Click(button.id)).isSuccess
        if (ok) log.putClick(button)
        say(session, if (ok) session.phrases.messageSent(name) else session.phrases.actionFailed())
    }

    /** "haan", "yes", "bhejo", "send" send it; "no", "stop" or silence leave it in the chat. */
    private suspend fun confirmSend(session: Session, context: ScreenSnapshot, name: String, button: ScreenElement): Boolean {
        val question = session.phrases.messageReady(name)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            if (PersonalTasks.spokenAlias(heard, listOf(button)) != null) return true
            when (interpret(session, context, null, heard, question).intent) {
                IntentKind.YES, IntentKind.SUBMIT, IntentKind.NEXT -> return true
                IntentKind.NO, IntentKind.STOP, IntentKind.SKIP -> return false
                else -> say(session, session.phrases.didNotCatch())
            }
        }
        return false
    }

    /** The screen's main actions, for suggestions: enabled buttons with short, readable names. */
    private fun suggestions(snapshot: ScreenSnapshot): List<String> =
        snapshot.buttons
            .filter { it.isEnabled && it.label.isNotBlank() && it.label.length <= MAX_SUGGESTION_CHARS }
            .map { it.label.trim() }
            .distinct()
            .take(MAX_SUGGESTIONS)

    /** Types [query] into this app's search [box] and presses Enter (or the search button next to it). */
    private suspend fun searchHere(session: Session, box: ScreenElement, query: String): Boolean {
        setStatus(EngineStatus.ACTING)
        say(session, session.phrases.searching(query))
        if (!perform(ScreenAction.SetText(box.id, query)).isSuccess) {
            say(session, session.phrases.taskFailed())
            return false
        }
        delay(screenSettleMillis)
        perform(ScreenAction.PressEnter(box.id))
        emit(EngineEvent.STEP, "Searched in the app: $query")
        session.noteAssistant("Searched $query")
        return true
    }

    /**
     * Scrolls down (up to [MAX_SCROLL_SEARCHES] times) looking for a button or item named [name]; null when
     * the list ends or nothing matched. It says "Dhoondh raha hoon…" once so the scrolling isn't silent.
     */
    private suspend fun findByScrolling(session: Session, name: String): ScreenElement? {
        say(session, session.phrases.lookingFor(name))
        var lastSignature = screen.capture()?.signature
        repeat(MAX_SCROLL_SEARCHES) {
            if (!perform(ScreenAction.Scroll(ScrollDirection.DOWN)).isSuccess) return null
            delay(screenSettleMillis)
            val snap = screen.capture() ?: return null
            ButtonMatcher.find(name, snap.elements, ButtonMatcher.STRICT)?.let { return it }
            if (snap.signature == lastSignature) return null
            lastSignature = snap.signature
        }
        return null
    }

    /**
     * Worth filling as a form: two or more fields, or one field with a button to send it — not counting
     * search boxes and comment or message boxes (a video's comments, a chat).
     */
    private fun isRealForm(snapshot: ScreenSnapshot): Boolean {
        val fields = snapshot.elements.filter { e ->
            e.kind.isInput && e.fieldType != com.voicecontrol.core.model.FieldType.SEARCH &&
                NOT_FORM_FIELD_WORDS.none { it in (e.label + " " + e.hint.orEmpty()).lowercase() }
        }
        return fields.size >= 2 || (fields.size == 1 && ButtonMatcher.primarySubmit(snapshot.elements) != null)
    }

    /**
     * Gentler each time something isn't understood: first "say it another way", then what this screen
     * offers, then examples of what can be said.
     */
    private suspend fun notUnderstood(session: Session, snapshot: ScreenSnapshot, before: Int): String {
        session.misses = before + 1
        return when (session.misses) {
            1 -> session.phrases.sayItAnotherWay()
            2 -> session.phrases.didNotCatch() + " " + suggestHere(session, snapshot)
            else -> session.phrases.examplesToSay()
        }
    }

    /** What the user can say here: the screen's buttons, else the apps they open most. */
    private suspend fun suggestHere(session: Session, snapshot: ScreenSnapshot): String {
        val here = suggestions(snapshot)
        val favourites = if (here.isNotEmpty()) emptyList() else runCatching { appDirectory?.favourites(MAX_FAVOURITES) }.getOrNull().orEmpty()
            .filter { it.packageName != snapshot.packageName }.map { it.label }
        return session.phrases.suggest(here, favourites)
    }

    /**
     * "WhatsApp band karo": leaves the app in front for the home screen (Android doesn't let other apps
     * end it; it stays in recent apps). [name] null means whatever app is open. False after explaining.
     */
    private suspend fun closeApp(session: Session, snapshot: ScreenSnapshot, name: String?): Boolean {
        val phrases = session.phrases
        val apps = runCatching { appDirectory?.apps() }.getOrNull().orEmpty()
        val front = apps.firstOrNull { it.packageName == snapshot.packageName }
        val target = if (name == null) front else AppMatcher.find(name, apps)
        when {
            target == null && name == null -> say(session, phrases.noAppOpen())
            target == null -> say(session, phrases.appNotFound(name.orEmpty()))
            target.packageName != snapshot.packageName -> say(session, phrases.appNotOpen(target.label))
            else -> {
                setStatus(EngineStatus.ACTING)
                val ok = runCatching { phoneActions?.system(com.voicecontrol.core.nlp.SystemAction.HOME) }.getOrNull() == true ||
                    perform(ScreenAction.Back).isSuccess && screen.capture()?.packageName != target.packageName
                say(session, if (ok) phrases.closedApp(target.label) else phrases.taskFailed())
                if (ok) {
                    emit(EngineEvent.STEP, "Closed ${target.label}")
                    session.noteAssistant("Closed ${target.label}")
                }
                return ok
            }
        }
        return false
    }

    /** Opens the installed app the user named; false (after explaining) when there is none. */
    private suspend fun openApp(session: Session, name: String): Boolean {
        val apps = runCatching { appDirectory?.apps() }.getOrNull().orEmpty()
        val app = AppMatcher.find(name, apps)
        if (app == null) {
            say(session, session.phrases.appNotFound(name))
            return false
        }
        // Apps that let someone else control the phone are how most "support" scams empty accounts.
        if (REMOTE_ACCESS_APPS.any { app.packageName.startsWith(it) }) {
            emit(EngineEvent.STEP, "Refused to open ${app.label} (remote access)")
            say(session, session.phrases.remoteAccessRefused(app.label))
            return false
        }
        val ok = act(session, ScreenAction.LaunchApp(app.packageName), session.phrases.opening(app.label))
        if (ok) {
            runCatching { appDirectory?.opened(app.packageName) }
            emit(EngineEvent.STEP, "Opened ${app.label}")
            session.noteAssistant("Opened ${app.label}")
        }
        return ok
    }

    private suspend fun submit(session: Session, snapshot: ScreenSnapshot, plan: ScreenPlan, log: ScreenLog): ScreenOutcome {
        val button = plan.submitButton ?: return ScreenOutcome.COMPLETED
        val phrases = session.phrases
        if (plan.autoSubmit || !session.cfg.askBeforeSubmit) {
            return if (press(session, button, log)) ScreenOutcome.NAVIGATED else ScreenOutcome.COMPLETED
        }
        val question = plan.submitQuestion ?: phrases.confirmPress(button.label)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            // Naming another button ("Cancel", "Edit") presses that one instead.
            if (localCommands.commandOf(request(session, snapshot, heard)) == null) {
                ButtonMatcher.find(heard, snapshot.elements, ButtonMatcher.STRICT)?.takeIf { it.id != button.id }?.let { other ->
                    return if (press(session, other, log)) ScreenOutcome.NAVIGATED else ScreenOutcome.COMPLETED
                }
            }
            val interp = interpret(session, snapshot, null, heard, question)
            when (interp.intent) {
                IntentKind.YES, IntentKind.SUBMIT, IntentKind.NEXT ->
                    return if (press(session, button, log)) ScreenOutcome.NAVIGATED else ScreenOutcome.COMPLETED
                IntentKind.CLICK -> if (clickTarget(session, snapshot, interp, log)) return ScreenOutcome.NAVIGATED
                IntentKind.NO, IntentKind.SKIP -> return ScreenOutcome.COMPLETED
                IntentKind.STOP -> return ScreenOutcome.STOPPED
                IntentKind.BACK -> {
                    act(session, ScreenAction.Back, phrases.wentBack())
                    return ScreenOutcome.NAVIGATED
                }
                IntentKind.UNDO -> say(session, undoLast()?.let(phrases::undone) ?: phrases.nothingToUndo())
                IntentKind.READ_SCREEN -> if (runReader(session, snapshot, log)) return ScreenOutcome.NAVIGATED
                else -> Unit
            }
        }
        return ScreenOutcome.COMPLETED
    }

    // ---------------------------------------------------------------------------------------------
    // Steps and flow logic

    /**
     * Runs [steps] in order, honouring conditions and "previous". Returns Done when all ran, or the
     * result that ended the run early (Submit, Stop, Navigated).
     */
    private suspend fun runSteps(
        session: Session,
        snapshot: ScreenSnapshot,
        steps: List<PlanStep>,
        log: ScreenLog,
        progressPrefix: String,
    ): StepResult {
        val filledByExtras = mutableSetOf<String>()
        var index = 0
        var goingBack = false
        while (index < steps.size) {
            val step = steps[index]
            _state.update { it.copy(progress = "$progressPrefix${index + 1} / ${steps.size}", helpVideoUrl = step.helpVideoUrl) }
            if (!conditionHolds(session, step)) {
                if (goingBack && index > 0) {
                    index--
                    continue
                }
                goingBack = false
                applyElse(session, step, log)
                index++
                continue
            }
            goingBack = false
            val result = if (step.elementId in filledByExtras) {
                StepResult.Done
            } else {
                runStep(session, snapshot, step, log, filledByExtras)
            }
            when (result) {
                StepResult.Done -> index++
                StepResult.Previous -> {
                    index = (index - 1).coerceAtLeast(0)
                    goingBack = true
                }
                StepResult.Submit, StepResult.Stop, StepResult.Navigated -> return result
            }
        }
        return StepResult.Done
    }

    private suspend fun runStep(
        session: Session,
        snapshot: ScreenSnapshot,
        step: PlanStep,
        log: ScreenLog,
        filledByExtras: MutableSet<String>,
    ): StepResult {
        when (step.action) {
            StepAction.SET_VARIABLE -> {
                val name = step.variable
                val expression = step.valueExpression
                if (name != null && expression != null) evaluateText(session, expression)?.let { session.vars[name] = it }
                return StepResult.Done
            }
            StepAction.REPEAT -> return runRepeat(session, snapshot, step, log)
            StepAction.READ -> {
                val fresh = screen.capture()?.element(step.elementId) ?: step.element
                if (!fresh.isSensitive) remember(session, step, fresh.value?.takeIf { it.isNotBlank() } ?: fresh.label)
                return StepResult.Done
            }
            StepAction.CLICK -> {
                // A conditional button press inside the screen (e.g. "Add nominee"); the plan continues.
                if (press(session, step.element, log)) delay(screenSettleMillis)
                return StepResult.Done
            }
            StepAction.NEXT_SCREEN, StepAction.OPEN_APP -> return StepResult.Done
            StepAction.FILL, StepAction.TOGGLE -> Unit
        }
        val expression = step.valueExpression
        if (expression != null && !step.isSensitive) {
            val value = evaluateText(session, expression)
            if (!value.isNullOrBlank()) {
                setStatus(EngineStatus.ACTING)
                val ok = if (step.action == StepAction.TOGGLE) {
                    perform(ScreenAction.SetChecked(step.elementId, Expressions.truthy(value))).isSuccess
                } else {
                    perform(ScreenAction.SetText(step.elementId, value)).isSuccess
                }
                log.put(step, if (ok) StepOutcome.DEFAULT_FILLED else StepOutcome.FAILED, null)
                if (ok) remember(session, step, if (step.action == StepAction.TOGGLE) yesNo(Expressions.truthy(value)) else value)
                return StepResult.Done
            }
        }
        val templated = if ('{' in step.question) step.copy(question = template(session, step.question)) else step
        if (!step.skip) emit(EngineEvent.ASK, "Asking: ${step.label}")
        return handleStep(session, snapshot, templated, log, filledByExtras)
    }

    /** The "else" branch of a step whose condition is false: fill the alternative value, or skip. */
    private suspend fun applyElse(session: Session, step: PlanStep, log: ScreenLog) {
        if (step.virtual || step.action == StepAction.READ || step.action == StepAction.CLICK) return
        val expression = step.elseValue
        val value = expression?.let { evaluateText(session, it) }
        if (value.isNullOrBlank() || step.isSensitive) {
            log.put(step, StepOutcome.SKIPPED, null)
            return
        }
        setStatus(EngineStatus.ACTING)
        val ok = if (step.action == StepAction.TOGGLE) {
            perform(ScreenAction.SetChecked(step.elementId, Expressions.truthy(value))).isSuccess
        } else {
            perform(ScreenAction.SetText(step.elementId, value)).isSuccess
        }
        log.put(step, if (ok) StepOutcome.DEFAULT_FILLED else StepOutcome.FAILED, null)
        if (ok) remember(session, step, if (step.action == StepAction.TOGGLE) yesNo(Expressions.truthy(value)) else value)
    }

    /** Runs a REPEAT step: its steps once per list item, pressing "add another" between items. */
    private suspend fun runRepeat(session: Session, snapshot: ScreenSnapshot, step: PlanStep, log: ScreenLog): StepResult {
        val spec = step.repeat ?: return StepResult.Done
        val phrases = session.phrases
        val builder = PlanBuilder(phrases)
        val itemName = spec.itemLabel?.takeIf { it.isNotBlank() } ?: step.label.takeIf { it.isNotBlank() } ?: "item"
        val max = spec.maxIterations.coerceIn(1, MAX_REPEAT)
        var current = snapshot
        try {
            for (index in 1..max) {
                session.vars[FlowVariables.INDEX] = index.toString()
                val items = builder.resolveRepeatItem(current, step.repeatBody, index, session.profile)
                if (items.isEmpty()) break
                say(session, phrases.item(itemName, index))
                log.keySuffix = "#$index"
                val result = runSteps(session, current, items, log, progressPrefix = "$itemName $index · ")
                if (result != StepResult.Done) return result
                val more = when (val count = spec.countExpression?.takeIf { it.isNotBlank() }) {
                    null -> askYesNoOr(session, current, phrases.addAnother(itemName), default = false) ?: return StepResult.Stop
                    else -> index < (Expressions.number(evaluate(session, count)) ?: 1.0).toInt()
                }
                if (!more || index == max) break
                val live = screen.capture() ?: current
                val addMore = builder.locate(spec.addMoreElementId, spec.addMoreLabel, live.elements)
                if (addMore != null) {
                    if (!act(session, ScreenAction.Click(addMore.id), null)) break
                    delay(screenSettleMillis)
                }
                current = readScreen(session) ?: break
                session.currentSignature = current.signature
            }
        } finally {
            log.keySuffix = ""
            session.vars.remove(FlowVariables.INDEX)
        }
        return StepResult.Done
    }

    private fun conditionHolds(session: Session, step: PlanStep): Boolean {
        val condition = step.condition ?: return true
        // A broken expression should not silently hide a question: ask it.
        return runCatching { Expressions.evaluateBoolean(condition, session::lookup, today()) }.getOrDefault(true)
    }

    private fun evaluate(session: Session, expression: String): Any? =
        runCatching { Expressions.evaluate(expression, session::lookup, today()) }.getOrNull()

    private fun evaluateText(session: Session, expression: String): String? =
        runCatching { Expressions.evaluateText(expression, session::lookup, today()) }.getOrNull()

    private fun template(session: Session, text: String): String = Expressions.template(text, session::lookup, today())

    private fun today(): () -> LocalDate = { Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate() }

    /** Stores a step's answer in its variable (and `<name>_<index>` inside a loop). Never sensitive values. */
    private fun remember(session: Session, step: PlanStep, value: String) {
        if (step.isSensitive) return
        session.memory.removeAll { it.label == step.label }
        session.memory += MemoryItem(step.label, step.fieldType, value)
        if (session.memory.size > MAX_MEMORY) session.memory.removeAt(0)
        val name = step.variable ?: return
        session.vars[name] = value
        session.vars[FlowVariables.INDEX]?.let { session.vars["${name}_$it"] = value }
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** The recognizer was unsure about an answer it heard (not one taken from context). */
    private fun needsConfirmation(session: Session, interp: Interpretation): Boolean {
        if (!session.cfg.confirmLowConfidence || interp.source == CONTEXT_SOURCE) return false
        val confidence = session.lastConfidence ?: return false
        return confidence > 0f && confidence < LOW_CONFIDENCE
    }

    // ---------------------------------------------------------------------------------------------
    // Screen reader

    /** A session that only runs the screen reader (started from the overlay or a "read screen" command). */
    private suspend fun runReaderSession() {
        val session = Session(config.current())
        _state.value = EngineState(status = EngineStatus.STARTING, active = true)
        emit(EngineEvent.STATUS, "Screen reader started")
        try {
            if (!screen.isAvailable.value) {
                say(session, session.phrases.serviceOff())
                session.status = RunStatus.FAILED
                return
            }
            val snapshot = readScreen(session)
            if (snapshot == null || (!snapshot.hasReadableElements && snapshot.texts.isEmpty() && snapshot.title == null)) {
                say(session, session.phrases.cannotRead())
                session.status = RunStatus.FAILED
                return
            }
            session.appPackage = snapshot.packageName
            session.profile = runCatching { profiles.profile() }.getOrNull()
            val log = ScreenLog(snapshot, null, null)
            try {
                runReader(session, snapshot, log, standalone = true)
            } finally {
                if (log.steps.isNotEmpty()) session.screens += log.toRecord()
            }
            say(session, session.phrases.stopped())
        } catch (e: UserStop) {
            session.status = RunStatus.STOPPED
        } catch (e: CancellationException) {
            session.status = RunStatus.STOPPED
            throw e
        } catch (e: Exception) {
            session.status = RunStatus.FAILED
            _state.update { it.copy(status = EngineStatus.ERROR, caption = e.message ?: session.phrases.actionFailed()) }
        } finally {
            withContext(NonCancellable) { finish(session) }
        }
    }

    /**
     * Reads the screen item by item: "next", "previous", "repeat", "read all", "select" (press a button,
     * flip a switch, or answer a field), a button's name, back/scroll, undo, "stop". Inside a form session
     * it returns to the form on "stop"; returns true when the screen changed (the caller re-plans).
     */
    private suspend fun runReader(session: Session, initial: ScreenSnapshot, log: ScreenLog, standalone: Boolean = false): Boolean {
        val phrases = session.phrases
        var snapshot = initial
        var items = ScreenReader.items(snapshot)
        var index = 0
        var navigated = false
        _state.update { it.copy(progress = null) }
        say(session, phrases.readerStart(items.size))
        suspend fun refresh(announce: Boolean) {
            delay(screenSettleMillis)
            snapshot = readScreen(session) ?: snapshot
            items = ScreenReader.items(snapshot)
            index = 0
            navigated = true
            if (announce) say(session, phrases.readerStart(items.size))
        }
        while (true) {
            if (items.isEmpty()) {
                say(session, phrases.cannotRead())
                return navigated
            }
            val item = items[index.coerceIn(0, items.lastIndex)]
            _state.update { it.copy(progress = "${index + 1} / ${items.size}") }
            val heard = askAndListen(session, ScreenReader.describe(item, phrases)) ?: continue
            val narrator = NarratorCommands.parse(heard)
            if (narrator != null) {
                when (narrator) {
                    is NarratorCommand.Jump -> NarratorCommands.jump(items, index, narrator.kind, narrator.forward)
                        ?.let { index = it } ?: say(session, phrases.noMoreOfThat())
                    is NarratorCommand.Edge -> index = if (narrator.first) 0 else items.lastIndex
                    NarratorCommand.WhereAmI -> {
                        val fields = snapshot.elements.filter { it.kind.isInput }
                        say(
                            session,
                            phrases.whereAmI(
                                snapshot.title?.takeIf { it.isNotBlank() } ?: snapshot.packageName.substringAfterLast('.'),
                                index + 1, items.size, fields.size, fields.count { it.isEmpty && !it.isSensitive },
                                snapshot.elements.count { it.kind == ElementKind.BUTTON || it.kind == ElementKind.LINK },
                            ),
                        )
                    }
                    is NarratorCommand.Find -> NarratorCommands.find(items, index, narrator.query) { ScreenReader.describe(it, phrases) }
                        ?.let { index = it } ?: say(session, phrases.notFoundOnScreen(narrator.query))
                    is NarratorCommand.Rate -> {
                        session.rate = (session.rate + if (narrator.faster) RATE_STEP else -RATE_STEP).coerceIn(MIN_RATE, MAX_RATE)
                        say(session, phrases.rateChanged(narrator.faster))
                    }
                    NarratorCommand.ReadEverything -> {
                        val seen = items.map { ScreenReader.describe(it, phrases) }.toMutableSet()
                        for (i in index + 1..items.lastIndex) say(session, ScreenReader.describe(items[i], phrases))
                        // Keep scrolling down and read what is new, until the page stops moving.
                        for (page in 1..MAX_READ_PAGES) {
                            if (!perform(ScreenAction.Scroll(ScrollDirection.DOWN)).isSuccess) break
                            delay(screenSettleMillis)
                            val next = readScreen(session) ?: break
                            val fresh = ScreenReader.items(next).filter { seen.add(ScreenReader.describe(it, phrases)) }
                            snapshot = next
                            items = ScreenReader.items(next)
                            navigated = true
                            if (fresh.isEmpty()) break
                            fresh.forEach { say(session, ScreenReader.describe(it, phrases)) }
                        }
                        index = items.lastIndex
                        say(session, phrases.readerEnd())
                    }
                }
                continue
            }
            val request = InterpretRequest(snapshot.redacted(), null, heard, session.cfg.language, null)
            val command = localCommands.commandOf(request)
            when (command?.intent) {
                IntentKind.NEXT, IntentKind.SKIP -> if (index < items.lastIndex) index++ else say(session, phrases.readerEnd())
                IntentKind.PREVIOUS -> index = (index - 1).coerceAtLeast(0)
                IntentKind.REPEAT -> Unit
                IntentKind.READ_SCREEN -> {
                    for (i in index + 1..items.lastIndex) say(session, ScreenReader.describe(items[i], phrases))
                    index = items.lastIndex
                    say(session, phrases.readerEnd())
                }
                IntentKind.YES, IntentKind.SUBMIT -> if (activate(session, snapshot, item, log)) refresh(announce = true) else {
                    // A field or switch changed: read it again from the live screen.
                    snapshot = screen.capture() ?: snapshot
                    items = ScreenReader.items(snapshot)
                }
                IntentKind.CLICK -> if (clickTarget(session, snapshot, command, log)) refresh(announce = true)
                IntentKind.BACK -> if (act(session, ScreenAction.Back, phrases.wentBack())) refresh(announce = true)
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> {
                    scroll(session, command.intent)
                    refresh(announce = false)
                }
                IntentKind.UNDO -> say(session, undoLast()?.let(phrases::undone) ?: phrases.nothingToUndo())
                IntentKind.HELP -> say(session, phrases.readerHelp())
                IntentKind.STOP, IntentKind.NO -> {
                    if (standalone) throw UserStop()
                    _state.update { it.copy(progress = null) }
                    return navigated
                }
                else -> say(session, phrases.didNotCatch())
            }
        }
    }

    /** "Select" on the current item. Returns true when a button was pressed (the screen may change). */
    private suspend fun activate(session: Session, snapshot: ScreenSnapshot, item: ReaderItem, log: ScreenLog): Boolean {
        val element = (item as? ReaderItem.Element)?.element ?: return false
        val phrases = session.phrases
        return when {
            element.kind == ElementKind.BUTTON || element.kind == ElementKind.LINK -> press(session, element, log)
            element.kind.isToggle -> {
                val checked = element.isChecked != true
                if (act(session, ScreenAction.SetChecked(element.id, checked), phrases.describeToggle(element.label, checked))) {
                    recordUndo(element, checkedBefore = !checked)
                }
                false
            }
            element.isSensitive -> {
                perform(ScreenAction.Focus(element.id))
                say(session, phrases.sensitiveManual(element.label))
                false
            }
            else -> {
                val step = PlanBuilder(phrases).build(snapshot.copy(elements = listOf(element)), null, session.profile).steps.firstOrNull() ?: return false
                handleStep(session, snapshot, step, log, mutableSetOf())
                false
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step

    private suspend fun handleStep(
        session: Session,
        snapshot: ScreenSnapshot,
        planned: PlanStep,
        log: ScreenLog,
        filledByExtras: MutableSet<String>,
    ): StepResult {
        val step = withWritten(session, planned)
        val phrases = session.phrases
        val element = step.element

        if (step.skip) {
            val default = step.defaultValue
            if (default != null && step.action == StepAction.FILL && !step.isSensitive) {
                val ok = perform(ScreenAction.SetText(step.elementId, default)).isSuccess
                log.put(step, if (ok) StepOutcome.DEFAULT_FILLED else StepOutcome.FAILED, null)
                if (ok) remember(session, step, default)
            } else {
                log.put(step, StepOutcome.SKIPPED, null)
            }
            return StepResult.Done
        }

        if (step.isSensitive) return handleSensitive(session, snapshot, step, log)

        var question = step.question
        if (!element.value.isNullOrBlank() && element.kind == ElementKind.TEXT_FIELD) {
            if (session.cfg.skipFilledFields) {
                log.put(step, StepOutcome.KEPT, null)
                remember(session, step, element.value!!)
                return StepResult.Done
            }
            val keep = askYesNo(session, snapshot, step, phrases.keepExisting(step.label, element.value!!))
            when (keep) {
                true -> {
                    log.put(step, StepOutcome.KEPT, null)
                    remember(session, step, element.value!!)
                    return StepResult.Done
                }
                null -> return StepResult.Stop
                false -> Unit
            }
        }
        var suggestion = step.suggestedValue
        val lastTime = recallAnswer(session, step)
        if (suggestion != null) {
            question = phrases.askUseSuggested(question, suggestion)
        } else if (lastTime != null) {
            suggestion = lastTime
            question = phrases.askUseLastTime(question, lastTime)
        }

        if (step.kind == ElementKind.DROPDOWN) return handleDropdown(session, step, question, log)

        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            val heard = askAndListen(session, question)
            if (heard == null) {
                attempts++
                continue
            }
            // "same as above", "my email": resolved from earlier answers or the profile, offline.
            val referenced = if (step.action == StepAction.FILL && step.kind == ElementKind.TEXT_FIELD) {
                lastTime?.takeIf { ContextResolver.isLastTimeReference(heard) }
                    ?: ContextResolver.resolve(heard, element, session.memory, session.profile)
            } else {
                null
            }
            val interp = if (referenced != null) {
                Interpretation(IntentKind.FILL, step.elementId, referenced, source = CONTEXT_SOURCE)
            } else {
                interpret(session, snapshot, step.elementId, heard, question)
            }
            when (interp.intent) {
                IntentKind.NEXT, IntentKind.SKIP -> {
                    log.put(step, StepOutcome.SKIPPED, question)
                    return StepResult.Done
                }
                IntentKind.PREVIOUS -> return StepResult.Previous
                IntentKind.REPEAT -> Unit
                IntentKind.HELP -> say(session, listOfNotNull(step.hint, phrases.help()).joinToString(" "))
                IntentKind.STOP -> return StepResult.Stop
                IntentKind.SUBMIT -> return StepResult.Submit
                IntentKind.BACK -> {
                    act(session, ScreenAction.Back, phrases.wentBack())
                    return StepResult.Navigated
                }
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> scroll(session, interp.intent)
                IntentKind.CLICK -> if (clickTarget(session, snapshot, interp, log)) return StepResult.Navigated
                IntentKind.CLEAR -> act(session, ScreenAction.SetText(step.elementId, ""), phrases.cleared(step.label))
                IntentKind.YES -> when {
                    step.action == StepAction.TOGGLE -> return toggle(session, step, true, log, question)
                    suggestion != null -> {
                        fill(session, step, suggestion, log, question, interp.source, emptyList(), filledByExtras)?.let { return it }
                        attempts++
                    }
                    else -> attempts++
                }
                IntentKind.NO -> when {
                    step.action == StepAction.TOGGLE -> return toggle(session, step, false, log, question)
                    suggestion != null -> {
                        suggestion = null
                        question = step.question
                    }
                    else -> attempts++
                }
                IntentKind.FILL -> {
                    if (step.action == StepAction.TOGGLE) {
                        attempts++
                        continue
                    }
                    val target = interp.targetId?.let(snapshot::element)?.takeIf { !it.isSensitive } ?: element
                    val value = interp.value ?: SpeechNormalizer.normalize(heard, target.fieldType, session.cfg.transliterate)
                    if (target.id != step.elementId) {
                        // The user answered a different field ("my phone is …"); fill it and keep asking this one.
                        if (perform(ScreenAction.SetText(target.id, value)).isSuccess) filledByExtras += target.id
                        continue
                    }
                    val validation = FieldValidator.validate(value, step.fieldType, step.rules)
                    if (validation is FieldValidator.Result.Invalid) {
                        say(session, listOfNotNull(phrases.invalid(validation.message), step.hint).joinToString(" "))
                        attempts++
                        continue
                    }
                    if (needsConfirmation(session, interp)) {
                        when (askYesNoOr(session, snapshot, phrases.didYouSay(value), default = false)) {
                            null -> return StepResult.Stop
                            false -> {
                                attempts++
                                continue
                            }
                            true -> Unit
                        }
                    }
                    val result = fill(session, step, value, log, question, interp.source, interp.extraFills.map { it.targetId to it.value }, filledByExtras, snapshot)
                    if (result != null) return result
                    attempts++
                }
                IntentKind.UNDO -> {
                    val undone = undoLast()
                    say(session, undone?.let(phrases::undone) ?: phrases.nothingToUndo())
                    // Undoing an earlier field goes back to it so it can be answered again.
                    if (undone != null && undone != step.label) return StepResult.Previous
                }
                IntentKind.READ_SCREEN -> if (runReader(session, snapshot, log)) return StepResult.Navigated
                IntentKind.UNKNOWN -> {
                    say(session, listOfNotNull(phrases.didNotCatch(), step.hint).joinToString(" "))
                    attempts++
                }
            }
        }
        log.put(step, StepOutcome.SKIPPED, question)
        return StepResult.Done
    }

    /** Fills the step's field; returns Done on success or null when typing failed. */
    private suspend fun fill(
        session: Session,
        step: PlanStep,
        value: String,
        log: ScreenLog,
        question: String,
        source: String,
        extras: List<Pair<String, String>>,
        filledByExtras: MutableSet<String>,
        snapshot: ScreenSnapshot? = null,
    ): StepResult? {
        setStatus(EngineStatus.ACTING)
        val result = perform(ScreenAction.SetText(step.elementId, value))
        if (result is ActionResult.Failure) {
            say(session, session.phrases.actionFailed())
            return null
        }
        recordUndo(step.element)
        session.lastFilledId = step.elementId
        for ((id, extraValue) in extras) {
            val target = snapshot?.element(id) ?: continue
            if (target.isSensitive || id == step.elementId) continue
            if (perform(ScreenAction.SetText(id, extraValue)).isSuccess) filledByExtras += id
        }
        log.put(step, StepOutcome.FILLED, question, source)
        remember(session, step, value)
        rememberAnswer(session, step, value)
        say(session, if (session.cfg.confirmValues && value.length <= MAX_READBACK) session.phrases.filled(value) else session.phrases.filledShort())
        return StepResult.Done
    }

    private suspend fun toggle(session: Session, step: PlanStep, checked: Boolean, log: ScreenLog, question: String): StepResult {
        val ok = act(session, ScreenAction.SetChecked(step.elementId, checked), null)
        if (ok) recordUndo(step.element, checkedBefore = step.element.isChecked ?: !checked)
        log.put(step, if (ok) StepOutcome.TOGGLED else StepOutcome.FAILED, question)
        if (ok) remember(session, step, yesNo(checked))
        return StepResult.Done
    }

    private suspend fun handleSensitive(session: Session, snapshot: ScreenSnapshot, step: PlanStep, log: ScreenLog): StepResult {
        perform(ScreenAction.Focus(step.elementId))
        val prompt = session.phrases.sensitiveManual(step.label)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, prompt) ?: return@repeat
            // Never send what was said here anywhere: only local command parsing.
            val command = localCommands.commandOf(InterpretRequest(snapshot.redacted(), null, heard, session.cfg.language, null))
            when (command?.intent) {
                IntentKind.NEXT, IntentKind.YES, IntentKind.SKIP -> {
                    log.put(step, StepOutcome.MANUAL, null)
                    return StepResult.Done
                }
                IntentKind.PREVIOUS -> return StepResult.Previous
                IntentKind.STOP -> return StepResult.Stop
                IntentKind.SUBMIT -> return StepResult.Submit
                else -> Unit
            }
        }
        log.put(step, StepOutcome.MANUAL, null)
        return StepResult.Done
    }

    private suspend fun handleDropdown(session: Session, step: PlanStep, question: String, log: ScreenLog): StepResult {
        act(session, ScreenAction.Click(step.elementId), null)
        delay(DROPDOWN_OPEN_MS)
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            val options = screen.capture() ?: return@repeat
            val command = localCommands.commandOf(InterpretRequest(options.redacted(), null, heard, session.cfg.language, question))
            when (command?.intent) {
                IntentKind.STOP -> return StepResult.Stop
                IntentKind.SKIP, IntentKind.NEXT -> {
                    act(session, ScreenAction.Back, null)
                    log.put(step, StepOutcome.SKIPPED, question)
                    return StepResult.Done
                }
                IntentKind.SCROLL_DOWN, IntentKind.SCROLL_UP -> {
                    scroll(session, command.intent)
                    return@repeat
                }
                else -> Unit
            }
            val option = ButtonMatcher.find(SpeechNormalizer.stripLeadIns(heard), options.elements)
            if (option != null && act(session, ScreenAction.Click(option.id), session.phrases.filled(option.label))) {
                log.put(step, StepOutcome.FILLED, question)
                remember(session, step, option.label)
                return StepResult.Done
            }
            say(session, session.phrases.didNotCatch())
        }
        log.put(step, StepOutcome.SKIPPED, question)
        return StepResult.Done
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    /**
     * Speaks the question (interruptible by speech when barge-in is on), then listens. Returns the
     * transcript or null if nothing usable was heard. A command recognized in the live partial results
     * ("stop", "next", "haan"…) ends listening early instead of waiting for the end-of-speech timeout.
     */
    private suspend fun askAndListen(session: Session, question: String): String? {
        if (session.cfg.bargeIn && speechDetector != null) sayInterruptible(session, question, speechDetector) else say(session, question)
        // Listening without a question (after something was done): a soft tone instead of words.
        if (question.isBlank()) tts.earcon()
        setStatus(EngineStatus.LISTENING, caption = question)
        var early: String? = null
        suspend fun listenOnce(): ListenResult = coroutineScope {
            var latest = ""
            var pending: Job? = null
            val heard = stt.listen(
                ListenRequest(session.cfg.language.speechTag, preferOffline = session.offline, biasPhrases = session.bias),
                onPartial = { partial ->
                    _state.update { it.copy(heard = partial) }
                    latest = partial
                    pending?.cancel()
                    if (isEarlyCommand(partial)) {
                        pending = launch {
                            delay(EARLY_COMMAND_STABLE_MS)
                            if (latest == partial && early == null) {
                                early = partial
                                stt.stopListening()
                            }
                        }
                    }
                },
                onLevel = { level -> _state.update { it.copy(micLevel = level) } },
            )
            pending?.cancel()
            heard
        }
        var result = listenOnce()
        if (result is ListenResult.Error && result.cause == ListenResult.ErrorCause.NETWORK && !session.offline) {
            // No internet: switch to on-device speech and listen again (the question was already asked).
            session.offline = true
            emit(EngineEvent.STATUS, "No internet: listening with on-device speech")
            result = listenOnce()
        }
        _state.update { it.copy(micLevel = 0f) }
        session.alternatives = emptyList()
        early?.let { command ->
            session.silentFailures = 0
            session.lastConfidence = null
            _state.update { it.copy(heard = command) }
            return command
        }
        // "Rahul ko …": half a sentence. Listen once more (without asking again) and join the two parts.
        val firstPart = (result as? ListenResult.Heard)?.text
        if (early == null && firstPart != null && com.voicecontrol.core.nlp.Unfinished.looksUnfinished(firstPart)) {
            emit(EngineEvent.STATUS, "Sentence sounds unfinished: listening a little longer")
            _state.update { it.copy(heard = "$firstPart …") }
            val rest = listenOnce()
            if (rest is ListenResult.Heard && rest.text.isNotBlank()) result = ListenResult.Heard("$firstPart ${rest.text}", emptyList(), rest.confidence)
            _state.update { it.copy(micLevel = 0f) }
        }
        return when (result) {
            is ListenResult.Heard -> {
                session.silentFailures = 0
                session.lastConfidence = result.confidence
                session.alternatives = result.alternatives.filter { it.isNotBlank() && it != result.text }
                _state.update { it.copy(heard = result.text) }
                result.text.takeIf { it.isNotBlank() }
            }
            ListenResult.NoMatch -> {
                noSpeech(session)
                null
            }
            is ListenResult.Error -> {
                if (!result.recoverable) throw IllegalStateException(result.message)
                val packMissing = result.cause == ListenResult.ErrorCause.LANGUAGE_UNAVAILABLE ||
                    (result.cause == ListenResult.ErrorCause.NETWORK && session.offline)
                if (packMissing && !session.toldPackMissing) {
                    session.toldPackMissing = true
                    emit(EngineEvent.ERROR, "Offline speech for ${session.cfg.language.name.lowercase()} is not installed")
                    say(session, session.phrases.offlinePackMissing(session.cfg.language.nativeName))
                }
                noSpeech(session)
                null
            }
        }
    }

    /** Commands short enough to act on from a stable partial transcript. */
    private fun isEarlyCommand(partial: String): Boolean {
        val command = com.voicecontrol.core.nlp.CommandParser.parse(partial) ?: return false
        return command !is com.voicecontrol.core.nlp.VoiceCommand.Press
    }

    /** Speaks [text] but stops as soon as the user starts talking (barge-in). */
    private suspend fun sayInterruptible(session: Session, text: String, detector: SpeechDetector) {
        if (text.isBlank()) return
        session.noteTurn(ASSISTANT_SAID, text)
        setStatus(EngineStatus.SPEAKING, caption = text)
        coroutineScope {
            val speaking = async { tts.speak(text, session.cfg.language.voiceTag, session.rate) }
            val interrupted = async { detector.awaitSpeech() }
            select<Unit> {
                speaking.onAwait { interrupted.cancel() }
                interrupted.onAwait {
                    tts.stop()
                    speaking.cancel()
                }
            }
        }
    }

    private suspend fun noSpeech(session: Session) {
        session.silentFailures++
        if (session.silentFailures >= MAX_SILENT_FAILURES) {
            say(session, session.phrases.pausedNoSpeech())
            throw UserStop()
        }
        say(session, session.phrases.didNotCatch())
    }

    private suspend fun askYesNo(session: Session, snapshot: ScreenSnapshot, step: PlanStep, question: String): Boolean? {
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            when (interpret(session, snapshot, step.elementId, heard, question).intent) {
                IntentKind.YES, IntentKind.NEXT, IntentKind.SKIP -> return true
                IntentKind.NO, IntentKind.CLEAR -> return false
                IntentKind.STOP -> return null
                else -> Unit
            }
        }
        return true
    }

    /** Yes/no question not tied to a field; [default] when nothing usable was heard, null on "stop". */
    private suspend fun askYesNoOr(session: Session, snapshot: ScreenSnapshot, question: String, default: Boolean): Boolean? {
        repeat(MAX_ATTEMPTS) {
            val heard = askAndListen(session, question) ?: return@repeat
            when (interpret(session, snapshot, null, heard, question).intent) {
                IntentKind.YES, IntentKind.NEXT -> return true
                IntentKind.NO, IntentKind.SKIP, IntentKind.SUBMIT -> return false
                IntentKind.STOP -> return null
                else -> say(session, session.phrases.didNotCatch())
            }
        }
        return default
    }

    private suspend fun interpret(
        session: Session,
        snapshot: ScreenSnapshot,
        fieldId: String?,
        utterance: String,
        question: String?,
    ): Interpretation {
        setStatus(EngineStatus.THINKING)
        // On a screen without a form the AI also sees the conversation, for "usko", "wahi wala", "aur ek baar".
        val context = if (fieldId == null) session.memory + session.turns.toList().dropLast(1) else session.memory.toList()
        val request = InterpretRequest(
            snapshot.redacted(), fieldId, utterance, session.cfg.language, question, session.cfg.transliterate, context,
        )
        return localCommands.commandOf(request)
            ?: runCatching { interpreter.interpret(request) }.getOrElse { localCommands.interpret(request) }
    }

    /** AI-written questions by screen and language, so a screen is asked about once per session. */
    private val writtenQuestions = java.util.concurrent.ConcurrentHashMap<String, Map<String, com.voicecontrol.core.engine.port.WrittenQuestion>>()

    /**
     * Starts fetching AI-written questions for fields that have no saved question. A screen seen before
     * (or cached on the server) is ready almost at once and used from the first field; otherwise the first
     * questions use the built-in wording and later fields switch over as soon as the AI's arrive. Saved
     * questions always win; on-device only mode, a slow or failing server keep the built-in ones.
     */
    private suspend fun startWrittenQuestions(session: Session, snapshot: ScreenSnapshot, plan: ScreenPlan) {
        session.written = null
        session.writtenIds = emptySet()
        val writer = questionWriter ?: return
        if (session.cfg.localOnly) return
        val open = plan.steps.filter { !it.virtual && !it.customQuestion && !it.isSensitive && it.action != StepAction.CLICK && it.action != StepAction.READ }
        if (open.isEmpty()) return
        session.writtenIds = open.map { it.elementId }.toSet()
        val key = "${snapshot.packageName}|${snapshot.signature}|${session.cfg.language}"
        writtenQuestions[key]?.let {
            session.written = kotlinx.coroutines.CompletableDeferred(it)
            return
        }
        val redacted = snapshot.redacted()
        val language = session.cfg.language
        val pending = scope.async {
            val result = withTimeoutOrNull(questionTimeoutMillis) { runCatching { writer.write(redacted, language) }.getOrNull() }.orEmpty()
            if (result.isNotEmpty()) writtenQuestions[key] = result
            result
        }
        session.written = pending
        // Give a cached answer a moment, so even the first field gets the friendlier question.
        withTimeoutOrNull(FIRST_QUESTION_WAIT_MS) { pending.join() }
    }

    /** [step] with the AI-written question and hint, when they have arrived. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun withWritten(session: Session, step: PlanStep): PlanStep {
        val pending = session.written ?: return step
        if (!pending.isCompleted || pending.isCancelled || step.elementId !in session.writtenIds || step.customQuestion) return step
        val w = runCatching { pending.getCompleted() }.getOrNull()?.get(step.elementId)?.takeIf { it.question.isNotBlank() } ?: return step
        return step.copy(question = w.question.trim().take(MAX_WRITTEN_CHARS), hint = w.hint?.trim()?.take(MAX_WRITTEN_CHARS)?.takeIf { it.isNotEmpty() })
    }

    private fun request(session: Session, snapshot: ScreenSnapshot, heard: String) =
        InterpretRequest(snapshot.redacted(), null, heard, session.cfg.language, null)

    /**
     * A visible button named by [heard] when the answer is a button's name rather than a command. Words that
     * are also commands ("next", "ok", "submit", "back", "skip", "no") count as names when such a button is
     * on screen, so "OK" presses the OK button instead of being taken as "yes".
     */
    private fun spokenButton(session: Session, snapshot: ScreenSnapshot, heard: String): ScreenElement? {
        val command = localCommands.commandOf(request(session, snapshot, heard))
        if (command == null) return ButtonMatcher.find(heard, snapshot.elements)
        // "back" must not press "Bank": command words need the button's own name.
        return if (command.intent in BUTTON_WORDS) ButtonMatcher.find(heard, snapshot.elements, ButtonMatcher.STRICT) else null
    }

    private suspend fun clickTarget(session: Session, snapshot: ScreenSnapshot, interp: Interpretation, log: ScreenLog): Boolean {
        val target = interp.targetId?.let(snapshot::element)
        if (target == null) {
            say(session, session.phrases.buttonNotFound(interp.value ?: ""))
            return false
        }
        return press(session, target, log)
    }

    /** Presses [button]; asks first when it can't be undone, unless the user [confirmed] it already. */
    private suspend fun press(session: Session, button: ScreenElement, log: ScreenLog, confirmed: Boolean = false): Boolean {
        if (!confirmed && session.cfg.confirmDestructive && DestructiveActions.isDestructive(button.label)) {
            val context = screen.capture() ?: ScreenSnapshot.empty(session.appPackage)
            if (askYesNoOr(session, context, scamCheck(session, session.phrases.confirmDestructive(button.label)), default = false) != true) {
                say(session, session.phrases.notPressed(button.label))
                emit(EngineEvent.STEP, "Not pressed (needs confirmation): ${button.label}")
                return false
            }
        }
        // A taught "press Enter" in a search box is a press on the field itself.
        val action = if (button.kind == ElementKind.TEXT_FIELD) ScreenAction.PressEnter(button.id) else ScreenAction.Click(button.id)
        val ok = act(session, action, session.phrases.pressed(button.label))
        if (ok) {
            log.putClick(button)
            recordUndo(button, wasPress = true)
        }
        return ok
    }

    private suspend fun scroll(session: Session, intent: IntentKind) {
        val direction = if (intent == IntentKind.SCROLL_UP) ScrollDirection.UP else ScrollDirection.DOWN
        act(session, ScreenAction.Scroll(direction), session.phrases.scrolled())
    }

    private suspend fun act(session: Session, action: ScreenAction, successPhrase: String?): Boolean {
        setStatus(EngineStatus.ACTING)
        val result = perform(action)
        if (result.isSuccess) {
            successPhrase?.let { say(session, it) }
        } else {
            say(session, session.phrases.actionFailed())
        }
        return result.isSuccess
    }

    /** Typed text as the field shows it, ignoring case, spaces and separators ("98765 43210" = "9876543210"). */
    private fun sameText(shown: String, typed: String): Boolean {
        fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
        val a = norm(shown)
        val b = norm(typed)
        return a == b || (b.isNotEmpty() && a.contains(b))
    }

    /** [question] for a payment or send, with a scam warning first when a call is going on. */
    private suspend fun scamCheck(session: Session, question: String): String =
        if (runCatching { phoneActions?.inCall() }.getOrNull() == true) {
            emit(EngineEvent.STEP, "Payment asked during a call: warned the user")
            session.phrases.callScamWarning() + " " + question
        } else {
            question
        }

    /** The user's saved details for the helper, or null when none are saved. */
    private fun knownAbout(profile: UserProfile?): String? {
        profile ?: return null
        val known = listOfNotNull(
            profile.fullName?.let { "name $it" }, profile.phone?.let { "mobile $it" }, profile.email?.let { "email $it" },
            profile.addressLine?.let { "address $it" }, profile.city?.let { "city $it" }, profile.state?.let { "state $it" },
            profile.pincode?.let { "pincode $it" }, profile.dateOfBirth?.let { "date of birth $it" },
        ).filter { it.substringAfter(' ').isNotBlank() }
        return known.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "Known about the user: ")
    }

    /** "Namaste Rahul ji! WhatsApp khula hai, bataiye kya karna hai?": by name, time of day and the open app. */
    private suspend fun greeting(session: Session, snapshot: ScreenSnapshot): String {
        val name = session.profile?.fullName?.trim()?.substringBefore(' ')?.takeIf { it.length in 2..20 && it.all(Char::isLetter) }
        val app = runCatching { appDirectory?.apps() }.getOrNull().orEmpty()
            .firstOrNull { it.packageName == snapshot.packageName && !it.packageName.startsWith(OWN_PACKAGE) }?.label
        val hour = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault()).hour
        return session.phrases.greeting(name, app, hour)
    }

    private suspend fun say(session: Session, text: String) {
        if (text.isBlank()) return
        session.noteTurn(ASSISTANT_SAID, text)
        setStatus(EngineStatus.SPEAKING, caption = text)
        tts.speak(text, session.cfg.language.voiceTag, session.rate)
    }

    private fun setStatus(status: EngineStatus, caption: String? = _state.value.caption) {
        _state.update {
            it.copy(
                status = status,
                caption = caption,
                heard = if (status == EngineStatus.LISTENING) null else it.heard,
            )
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_SILENT_FAILURES = 4
        const val RATE_STEP = 0.25f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2f
        /** "Read everything" scrolls at most this many pages. */
        const val MAX_READ_PAGES = 10
        const val MAX_SCREENS = 40
        const val MAX_BUTTONS_SPOKEN = 6
        /** Turns the assistant listens on one screen before giving up (silence ends it sooner). */
        const val MAX_COMMAND_TURNS = 12
        const val MAX_SUGGESTIONS = 4
        private const val MAX_TURNS = 8
        private const val MAX_NOTE_STEPS = 8
        /** Scrolls when looking further down a list for a name. */
        private const val MAX_SCROLL_SEARCHES = 5
        /** Screen-sharing / remote-control apps (package prefixes) VoiceControl won't open by voice. */
        private val REMOTE_ACCESS_APPS = listOf(
            "com.anydesk.", "com.teamviewer.", "com.rustdesk.", "com.sand.airdroid", "com.splashtop.", "com.realvnc.",
            "com.microsoft.rdc.", "com.google.chromeremotedesktop", "com.carriez.flutter_hbb", "com.zoho.assist", "com.logmein.",
        )
        /** Saying one of these sends a search to Google even inside an app with its own search box. */
        private val GOOGLE_WORDS = listOf("google", "गूगल", "internet", "इंटरनेट", "web")
        private const val MAX_SCROLL_SEARCH_WORDS = 4
        /** Text boxes that don't make a screen a form. */
        private val NOT_FORM_FIELD_WORDS = listOf("comment", "message", "reply", "chat", "टिप्पणी", "संदेश", "मैसेज", "search", "खोज")
        private const val MAX_EARLIER_TURNS = 3
        /** What smart mode asks the AI to do on a form screen without a saved flow. */
        const val SMART_FORM_GOAL = "Help the user with the form on this screen: ask them, in a friendly way and one at a time, " +
            "for each value it needs (skip what is already filled), fill it in, then submit once they agree. " +
            "If it is not really a form, finish with DONE and an empty say."
        private const val MAX_FAVOURITES = 3
        private const val MAX_TURN_CHARS = 200
        const val USER_SAID = "user said"
        const val ASSISTANT_SAID = "assistant said"
        private const val OWN_PACKAGE = "com.voicecontrol"
        private const val MAX_SUGGESTION_CHARS = 30
        private const val MAX_WRITTEN_CHARS = 200
        /** How long the first question waits for AI-written ones (enough for a cached answer). */
        const val FIRST_QUESTION_WAIT_MS = 2_500L
        /** Commands that are also common button labels. */
        private val BUTTON_WORDS = setOf(
            IntentKind.NEXT, IntentKind.SUBMIT, IntentKind.YES, IntentKind.NO, IntentKind.BACK, IntentKind.SKIP,
        )
        const val MAX_READBACK = 40
        const val DROPDOWN_OPEN_MS = 600L
        const val VISION_FOCUS_DELAY_MS = 400L
        const val DEFAULT_WAIT_SECONDS = 20
        const val MAX_WAIT_SECONDS = 120
        const val SCREEN_POLL_MS = 500L
        /** How long a flow waits after the last answer before pressing Enter in that field. */
        const val ENTER_AFTER_MS = 3_000L
        /** Buttons that do what the keyboard's Enter does in a search box. */
        private val SEARCH_BUTTON_WORDS = setOf(
            "search", "go", "find", "submit", "search button", "खोजें", "खोज", "सर्च", "ढूंढें", "शोधा", "தேடு", "వెతకండి", "খুঁজুন", "શોધો",
        )
        const val MAX_AGENT_STEPS = 40
        /** App package / signature / flow id of the history screen that lists assistant jobs. */
        const val ASSISTANT_SCREEN = "assistant"
        const val AGENT_FILLER_MS = 2_500L
        /** A goal done in fewer steps (just one tap) isn't worth a flow. */
        const val MIN_LEARNED_ACTIONS = 2
        const val MAX_BIAS_PHRASES = 50
        const val MAX_BIAS_CHARS = 40
        const val MAX_AGENT_HISTORY = 30
        const val MAX_AGENT_TEXTS = 80
        const val MAX_AGENT_TEXT_CHARS = 200
        const val MAX_AGENT_REPEATS = 2
        /** Tries when the server is asleep or slow (each try waits up to the network timeout). */
        const val MAX_WAKE_RETRIES = 3
        const val WAKE_RETRY_DELAY_MS = 5_000L
        /** Steps after the user agreed to a payment or send in which its PIN "Submit" isn't asked again. */
        const val AGREED_FOLLOW_UP_STEPS = 3
        const val AGENT_WAIT_MS = 1_500L
        /** A request this long that matched nothing on screen is tried as a goal. */
        const val MIN_GOAL_WORDS = 4
        /** How long to wait for a WhatsApp chat to open. */
        const val MESSAGE_WAIT_MS = 10_000L
        const val MAX_REPEAT = 50
        const val MAX_MEMORY = 12
        const val MAX_UNDO = 30
        const val LOW_CONFIDENCE = 0.5f
        const val EARLY_COMMAND_STABLE_MS = 500L
        const val CONTEXT_SOURCE = "context"
    }
}
