package com.voicecontrol.infrastructure.ai

import com.voicecontrol.application.ai.AiService
import com.voicecontrol.domain.ai.AgentActionKind
import com.voicecontrol.domain.ai.AgentStepCommand
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.ai.ScreenContext
import com.voicecontrol.domain.ai.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The helper against the real model, on simulated phone screens that change as it presses and types, with
 * scripted user answers. Runs only with VC_ANTHROPIC_API_KEY set (it costs money); writes a transcript to
 * build/live-agent.txt. Mirrors what the phone sends: the history lines use the phone's wording.
 */
class LiveAgentScenariosTest {
    private val key = System.getenv("VC_ANTHROPIC_API_KEY")
    private val model = System.getenv("VC_ANTHROPIC_MODEL") ?: "claude-opus-5-5"
    private val out = StringBuilder()

    private class Screen(val name: String, val pkg: String, val elements: List<ScreenElement>, val texts: List<String> = emptyList())

    private class Sim(
        screens: List<Screen>,
        start: String,
        /** Pressing an id moves to a screen. */
        val onClick: Map<String, String>,
        /** Answers for fields (by id) and for other questions (matched by a word in the question). */
        val fieldAnswers: Map<String, String> = emptyMap(),
        val otherAnswer: (String) -> String = { "pata nahi" },
    ) {
        val byName = screens.associateBy { it.name }
        var now = byName.getValue(start)
        val values = HashMap<String, String>()
        val asked = mutableListOf<String>()
        val confirms = mutableListOf<String>()
        val filledByAgent = HashMap<String, String>()
        val pressed = mutableListOf<String>()
        var saysBeforeConfirm = 0

        fun context() = ScreenContext(
            now.pkg,
            elements = now.elements.map { e -> values[e.id]?.let { e.copy(value = if (e.sensitive) null else it) } ?: e },
            signature = now.name,
        )
    }

    private fun run(title: String, goal: String, sim: Sim, known: List<String> = emptyList(), language: Language = Language.HINGLISH): Sim = runBlocking {
        val service = AiService(AnthropicProvider(key!!, model))
        val history = mutableListOf<String>()
        out.appendLine("=== $title").appendLine("goal: $goal")
        known.forEach { out.appendLine("  [given] $it") }
        repeat(MAX_STEPS) {
            val started = System.currentTimeMillis()
            val step = service.nextAgentStep(AgentStepCommand(goal, sim.context(), known + history, language, sim.now.texts))
            val ms = System.currentTimeMillis() - started
            out.appendLine("  [${sim.now.name}] ${step.action} ${step.targetId ?: ""} ${step.value?.let { "value=\"$it\"" } ?: ""}" +
                " ${step.say?.let { "say=\"$it\"" } ?: ""} ${step.question?.let { "q=\"$it\"" } ?: ""}${if (step.confirm) " CONFIRM" else ""} (${ms}ms)")
            val target = step.targetId?.let { id -> sim.now.elements.firstOrNull { it.id == id } }
            when (step.action) {
                AgentActionKind.CLICK -> {
                    val named = target?.label ?: return@repeat
                    if (step.confirm) {
                        if (step.say != null) sim.saysBeforeConfirm++
                        val question = step.question ?: "(no question) $named"
                        sim.confirms += question
                        history += "The user agreed: $question"
                    }
                    sim.pressed += target.id
                    val next = sim.onClick[target.id]
                    if (next != null) sim.now = sim.byName.getValue(next)
                    history += "Pressed \"$named\"" + if (next == null) "; nothing changed on the screen" else ""
                }
                AgentActionKind.FILL -> {
                    target ?: return@repeat
                    sim.values[target.id] = step.value.orEmpty()
                    sim.filledByAgent[target.id] = step.value.orEmpty()
                    history += "Typed \"${step.value}\" into \"${target.label}\""
                }
                AgentActionKind.ASK -> {
                    sim.asked += step.question.orEmpty()
                    if (target != null && target.sensitive) {
                        sim.values[target.id] = "****"
                        history += "The user typed \"${target.label}\" themselves"
                    } else if (target != null) {
                        val answer = sim.fieldAnswers[target.id] ?: sim.otherAnswer(step.question.orEmpty())
                        sim.values[target.id] = answer
                        out.appendLine("      user: $answer")
                        history += "Asked for \"${target.label}\"; filled with \"$answer\""
                    } else {
                        val answer = sim.otherAnswer(step.question.orEmpty())
                        out.appendLine("      user: $answer")
                        history += "Asked \"${step.question}\"; the user said: $answer"
                    }
                }
                AgentActionKind.SCROLL_DOWN, AgentActionKind.SCROLL_UP -> history += "Scrolled"
                AgentActionKind.BACK -> history += "Went back"
                AgentActionKind.WAIT -> history += "Waited for the screen"
                AgentActionKind.OPEN_APP -> history += "Opened ${step.appName}"
                AgentActionKind.DONE, AgentActionKind.GIVE_UP -> {
                    out.appendLine("  -> ${step.action}")
                    return@runBlocking sim
                }
            }
        }
        out.appendLine("  -> ran out of steps")
        sim
    }

    private fun button(id: String, label: String) = ScreenElement(id, ElementKind.BUTTON, label)
    private fun field(id: String, label: String, type: FieldType? = null) = ScreenElement(id, ElementKind.TEXT_FIELD, label, type)

    @Test
    fun scenarios() {
        assumeTrue(!key.isNullOrBlank(), "VC_ANTHROPIC_API_KEY not set")
        val problems = mutableListOf<String>()

        // 1. Electricity bill in a payments app: asks only for the consumer number, the PIN is typed by the user,
        //    one confirmation that reads back the amount.
        val bill = run(
            "Bijli ka bill (PhonePe)", "mujhe bijli ka bill bharna hai",
            Sim(
                listOf(
                    Screen("home", "com.phonepe.app", listOf(button("send", "To Mobile Number"), button("recharge", "Mobile Recharge"), button("elec", "Electricity"), button("scan", "Scan & Pay"))),
                    Screen("board", "com.phonepe.app", listOf(button("mseb", "MSEDCL (Maharashtra)"), button("bses", "BSES Rajdhani"), button("tata", "Tata Power Mumbai")), listOf("Select Electricity Board")),
                    Screen("bill", "com.phonepe.app", listOf(field("consumer", "Consumer Number", FieldType.NUMBER), button("confirm", "Confirm")), listOf("MSEDCL (Maharashtra)")),
                    Screen("amount", "com.phonepe.app", listOf(button("paybill", "Pay ₹540")), listOf("Bill amount ₹540", "Due date 12 Oct", "Customer: R SHARMA")),
                    Screen("pin", "com.phonepe.app", listOf(field("upipin", "ENTER UPI PIN", FieldType.PIN), button("submitpin", "Submit"))),
                    Screen("done", "com.phonepe.app", listOf(button("ok", "Done")), listOf("Payment Successful", "₹540 paid to MSEDCL")),
                ),
                "home",
                onClick = mapOf("elec" to "board", "mseb" to "bill", "confirm" to "amount", "paybill" to "pin", "submitpin" to "done"),
                fieldAnswers = mapOf("consumer" to "170012345678"),
                otherAnswer = { q -> if (listOf("board", "company", "kaun", "kaunsa", "konsa", "state").any { it in q.lowercase() }) "Maharashtra wala, MSEDCL" else "haan" },
            ),
        )
        if (bill.now.name != "done") problems += "bill: did not finish (at ${bill.now.name})"
        if (bill.asked.none { "consumer" in it.lowercase() || "number" in it.lowercase() }) problems += "bill: never asked for the consumer number"
        if (bill.confirms.none { "540" in it }) problems += "bill: no confirmation reading back ₹540 (${bill.confirms})"
        if ("upipin" in bill.filledByAgent) problems += "bill: typed the PIN itself"
        if (bill.confirms.size > 1) problems += "bill: asked more than once (${bill.confirms})"

        // 2. Sign-up form with saved details: fills name, mobile and email without asking, asks only the city,
        //    and confirms before submitting.
        val form = run(
            "Sign-up form (saved details)",
            "Help the user with the form on this screen: ask them, in a friendly way and one at a time, for each value it needs " +
                "(skip what is already filled), fill it in, then submit once they agree. If it is not really a form, ask what they want to do here.",
            Sim(
                listOf(
                    Screen(
                        "form", "com.example.shop",
                        listOf(field("name", "Full name", FieldType.NAME), field("mobile", "Mobile number", FieldType.PHONE), field("email", "Email", FieldType.EMAIL), field("city", "City"), button("create", "Create account")),
                        listOf("Create your account"),
                    ),
                    Screen("welcome", "com.example.shop", listOf(button("shop", "Start shopping")), listOf("Welcome, Rahul!")),
                ),
                "form",
                onClick = mapOf("create" to "welcome"),
                fieldAnswers = mapOf("city" to "Pune"),
                otherAnswer = { "haan" },
            ),
            known = listOf("Known about the user: name Rahul Sharma; mobile 9876543210; email rahul.sharma@example.com"),
        )
        listOf("name", "mobile", "email").forEach { if (it !in form.filledByAgent) problems += "form: did not fill $it from the saved details" }
        if (form.asked.any { q -> listOf("naam", "name", "mobile", "email").any { it in q.lowercase() } }) problems += "form: asked for a saved detail (${form.asked})"
        if (form.values["city"] != "Pune") problems += "form: city not filled"
        if (form.confirms.isEmpty()) problems += "form: submitted without asking"
        if (form.now.name != "welcome") problems += "form: did not finish"

        // 3. WhatsApp message: opens the right chat, types the message, confirms before sending.
        val chat = run(
            "WhatsApp message", "Rahul ko bolo main 10 minute mein aa raha hoon",
            Sim(
                listOf(
                    Screen("chats", "com.whatsapp", listOf(button("search", "Search"), button("c_mummy", "Mummy"), button("c_rahul", "Rahul Sharma"), button("c_office", "Office group"))),
                    Screen("rahul", "com.whatsapp", listOf(field("msg", "Message"), button("send", "Send"), button("call", "Voice call")), listOf("Rahul Sharma", "online")),
                    Screen("sent", "com.whatsapp", listOf(field("msg2", "Message"), button("call2", "Voice call")), listOf("Rahul Sharma", "Main 10 minute mein aa raha hoon ✓✓")),
                ),
                "chats",
                onClick = mapOf("c_rahul" to "rahul", "send" to "sent"),
                otherAnswer = { "haan" },
            ),
        )
        if ("c_rahul" !in chat.pressed) problems += "chat: did not open Rahul's chat"
        if (chat.filledByAgent["msg"]?.contains("10") != true) problems += "chat: message not typed (${chat.filledByAgent})"
        if (chat.confirms.isEmpty()) problems += "chat: sent without asking"
        if (chat.now.name != "sent") problems += "chat: not sent"

        // 4. A follow-up: "usko" means the person talked about just before.
        val money = run(
            "Follow-up: usko 500 bhejo", "usko 500 rupaye bhej do",
            Sim(
                listOf(
                    Screen("home", "com.phonepe.app", listOf(button("send", "To Mobile Number"), button("elec", "Electricity"))),
                    Screen("contacts", "com.phonepe.app", listOf(field("q", "Search name or number", FieldType.SEARCH), button("p_amit", "Amit"), button("p_rahul", "Rahul Sharma"), button("p_mummy", "Mummy"))),
                    Screen("amount", "com.phonepe.app", listOf(field("amt", "Enter amount", FieldType.AMOUNT), button("pay", "Pay")), listOf("Paying Rahul Sharma")),
                    Screen("pin", "com.phonepe.app", listOf(field("upipin", "ENTER UPI PIN", FieldType.PIN), button("submitpin", "Submit"))),
                    Screen("done", "com.phonepe.app", listOf(button("ok", "Done")), listOf("₹500 sent to Rahul Sharma")),
                ),
                "home",
                onClick = mapOf("send" to "contacts", "p_rahul" to "amount", "pay" to "pin", "submitpin" to "done"),
                otherAnswer = { "haan" },
            ),
            known = listOf("Earlier the user said: Rahul ka number kya hai"),
        )
        if ("p_rahul" !in money.pressed) problems += "follow-up: did not pick Rahul (${money.pressed})"
        if (money.confirms.none { "500" in it }) problems += "follow-up: no confirmation with ₹500 (${money.confirms})"
        if (money.now.name != "done") problems += "follow-up: did not finish"
        if (money.confirms.size > 1) problems += "follow-up: asked more than once (${money.confirms})"
        listOf(bill, form, chat, money).forEach { s ->
            if (s.saysBeforeConfirm > 0) problems += "${s.now.pkg}: spoke a 'doing it' line together with a confirmation"
        }

        out.appendLine().appendLine("PROBLEMS: ${if (problems.isEmpty()) "none" else ""}")
        problems.forEach { out.appendLine("  - $it") }
        File("build").mkdirs()
        File("build/live-agent.txt").writeText(out.toString())
        println(out)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private companion object {
        const val MAX_STEPS = 14
    }
}
