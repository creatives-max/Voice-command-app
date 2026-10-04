# LLM-Powered Mobile GUI Agents on Android: State of the Art and Techniques That Improve Task Success

Context: VoiceControl uses an accessibility-tree (a11y) observation, Claude as planner with structured JSON, one call per step, step history, and no screenshots by default. Researched 2026-10-04. Benchmark numbers below are NOT comparable across benchmarks; within AndroidWorld they are task success rate (SR) over 116 tasks.

## 1. Main research systems, benchmarks, and current leaders

### Takeaway
AndroidWorld (Google, ICLR 2025) is the reference benchmark and is now essentially saturated: top systems reported 91-100% in 2026, versus 30.6% for the best agent in the original paper (2024) and an 80% human baseline. The original paper's best agent was text-only (a11y tree). It beat the same agent given screenshot plus Set-of-Mark (SoM) input. Today's leaders are almost all multi-agent systems that combine screenshots with the UI hierarchy, or vision-native models (UI-TARS-2, Seed1.8-GUI, Gemini 3).

### Cited Findings
**AndroidWorld (Rawles et al., Google DeepMind; arXiv 2405.14573, ICLR 2025)**
- 116 programmatic tasks across 20 real Android apps. Task parameters are generated dynamically, so tasks vary in unlimited ways. Human SR is 80.0%. — [AndroidWorld paper](https://arxiv.org/abs/2405.14573)
- Table 3 SR on AndroidWorld:
  - M3A, a11y tree, GPT-4 Turbo: **30.6%**
  - M3A, SoM (screenshot + a11y tree), GPT-4 Turbo: **25.4%**
  - M3A, a11y tree, Gemini 1.5 Pro: 19.4%
  - M3A, SoM, Gemini 1.5 Pro: 22.8%
  - M3A, Gemma 2: 9.5%
  - M3A-Simple (a11y, GPT-4 Turbo): **19.8%**
  - SeeAct (SoM, GPT-4 Turbo): 15.5%
  - On MobileMiniWoB++, the SoM variant was best (67.7% vs 59.7%). The authors attribute this to often-incomplete a11y trees in WebView content, as opposed to native Android apps.
  - [AndroidWorld paper (PDF)](https://arxiv.org/pdf/2405.14573)
- Authors' conclusion: "while multimodal perception can improve performance in some cases, it generally does not outperform the text-only approach." — [AndroidWorld paper](https://arxiv.org/pdf/2405.14573)
- M3A design is zero-shot, combining ReAct-style and Reflexion-style prompting:
  - Stage 1 outputs a JSON action plus its reasoning.
  - Stage 2 reflects on the before/after UI states and writes a summary that is carried forward in history.
  - The JSON schema includes action_type (click, long_press, type, scroll, navigate_home, navigate_back, open_app, wait, status (goal_status complete/infeasible), answer), plus index, text, direction and app_name.
  - This is architecturally very close to VoiceControl's design.
  - [AndroidWorld paper (PDF)](https://arxiv.org/pdf/2405.14573)
- Variance: across 3 random seeds, M3A (a11y, GPT-4T) scored 27.6%, 26.3% and 33.2% (mean 29.0%). Single-seed numbers are noisy by about ±4 points. — [AndroidWorld paper (PDF)](https://arxiv.org/pdf/2405.14573)
- Latency: M3A averaged 3.9 min per task, and 2.5 min for the text-only version, about 3x slower than humans. — [AndroidWorld paper (PDF)](https://arxiv.org/pdf/2405.14573)

**AndroidWorld leaderboard (aggregator snapshot, accessed 2026-10; entries dated 2026-05-27 to 2026-09-18)**
- Top entries:
  - 97.4%: Seed1.8-GUI (ByteDance), Gemini 3 Flash / Flash Lite, and AGI-0 (AGI Inc.)
  - 94.8%: Gemini 3 Pro + Sonnet 4.5, and askui AndroidVisionAgent + Claude models
  - 91.4%: GPT-5 / Gemini 2.5 Pro
  - 87.1%: o3 + Holo1.5-72B
  - 86.2%: Sonnet 4.5 + Sonnet 4
  - 80.2%: AutoGLM-Mobile
  - 80.0%: human
  - 78.0%: Gemini-2.5-Pro + UI-TARS-1.5
  - 73.3%: MAI-UI-32B and GUI-Owl-32B
  - [benchmarklist.com AndroidWorld](https://benchmarklist.com/benchmarks/androidworld/)
  - Caveat: the aggregator labels every entry "Screenshot", which looks generic rather than verified per entry. These are mostly self-reported.
- Other aggregators report different "leaders" (e.g., Qwen3.5-35B-A3B at 71.1% on "AndroidWorld_SR"), so leaderboards are inconsistent. — [llm-stats](https://llm-stats.com/benchmarks/androidworld-sr); [benchlm](https://benchlm.ai/benchmarks/androidWorld)
- Commentary in 2026 describes AndroidWorld as having "hit its ceiling" (>90%), making top agents hard to distinguish. — [agentmarketcap blog, 2026-04](https://agentmarketcap.ai/blog/2026/04/10/computer-use-benchmark-guide-osworld-appagent-androidworld-webagent)
- **Minitap** (arXiv 2602.07787, Feb 2026) claims **100%** on all 116 AndroidWorld tasks.
  - Six agents: Planner, Orchestrator, Contextor, Cortex, Executor and Summarizer, plus a Scratchpad memory.
  - Hybrid perception: screenshot + UI hierarchy.
  - Gemini 3 Pro handles the "Cortex" reasoning step; cheap models do the coordination.
  - Cost: $1.07/task vs $1.58 for an all-frontier setup.
  - [Minitap paper](https://arxiv.org/html/2602.07787v1)
- **UI-TARS-2** (ByteDance, Sept 2025) scores **73.3** on AndroidWorld. It is a native vision agent: screenshots in, coordinates out. — [HF papers 2509.02544](https://huggingface.co/papers/2509.02544)
- **V-Droid** (Microsoft Research, 2025) reaches **59.5%** on AndroidWorld, +9.5 points over the previous SOTA at the time. It also scores 38.3% on AndroidLab and 49% on MobileAgentBench, with **0.7 s/step** latency. — [V-Droid](https://arxiv.org/abs/2503.15937v3)
  - It uses the LLM as a *verifier*: it scores a discretized set of candidate actions taken from the UI hierarchy instead of generating an action.

**AndroidLab (Tsinghua/Zhipu, arXiv 2410.24024, Oct 2024)** compares text-only XML against SoM directly.
- XML (text) mode SR: GPT-4-1106 **31.16%**, GLM4-PLUS 27.54%, GPT-4o 25.36%, Gemini-1.5-Pro 18.84%.
- SoM mode SR: GPT-4o **31.16%**, **Claude-3.5-Sonnet 28.99%**, GPT-4V 26.09%, Gemini-1.5-Pro 16.67%.
- Instruction tuning raised open LLMs from an average of 4.59% to 21.50%. Llama-3.1-8B went from 2.17% to 23.91%.
- "ReAct significantly improves performance only in XML mode"; SeeAct-style prompting "does not enhance performance consistently."
- [AndroidLab](https://arxiv.org/html/2410.24024)

**AndroidControl (Google DeepMind, NeurIPS 2024 D&B, arXiv 2406.03679)**
- 15,283 demonstrations across 833 apps, each with a high-level and low-level instruction. Inputs include the a11y tree plus action history. — [AndroidControl](https://arxiv.org/abs/2406.03679)
- Fine-tuned step success: 86.6% (low-level) and 77.9% (high-level). High-level, multi-step goals are much harder than single-step commands. — [NeurIPS poster/summary](https://neurips.cc/virtual/2024/poster/97433)

**AutoDroid (Wen et al., MobiCom 2024, arXiv 2308.15272)**
- a11y/HTML-based with app memory gathered from offline exploration.
- Results on DroidTask (158 tasks):
  - GPT-4: **90.9% action accuracy, 71.3% task completion**, +36.4 and +39.7 points over GPT-4 baselines
  - GPT-3.5: 65.1% / 41.8%
  - Fine-tuned Vicuna-7B: 57.7% / 41.1%
- [AutoDroid](https://arxiv.org/html/2308.15272v4)

**AppAgent (Tencent, Dec 2023, arXiv 2312.13771)**
- Screenshot plus numeric labels derived from XML resource IDs, i.e. a SoM-like approach.
- GPT-4 SR on 45 tasks: no doc **2.2%**, auto-exploration doc **73.3%**, watching-demos doc **84.4%**, manual doc **95.6%**.
- [AppAgent](https://arxiv.org/html/2312.13771)

**Mobile-Agent-v2 (Alibaba, NeurIPS 2024)**
- Multi-agent design (planning, decision and reflection agents, plus a memory unit), vision-based.
- Over 30% task-completion improvement vs single-agent Mobile-Agent v1.
- [Mobile-Agent-v2](https://arxiv.org/html/2406.01014v1)

**Mobile-Agent-E (Alibaba, Jan 2025)**
- Manager / Perceptor / Operator / Action Reflector / Notetaker, plus self-evolving "Tips" and "Shortcuts".
- Satisfaction score 75.1% vs 53.0% for Mobile-Agent-v2, and 86.9% with evolution (GPT-4o).
- [Mobile-Agent-E](https://arxiv.org/html/2501.11733)

### Inferences
- VoiceControl's text-only, a11y-tree, single-call JSON design matches the configuration that won in the original AndroidWorld paper, and AndroidLab shows parity with SoM for strong models. So text-only is a sound default for native apps.
- The 2025-26 jump from ~30% to over 90% came from:
  - much stronger base models
  - multi-agent decomposition
  - explicit verification
  - hybrid perception

  It did not come from single-call text-only agents. Expect a well-engineered single-call a11y agent with a frontier Claude model to land well below the 90%+ leaders on long tasks. Adding verification/reflection and a screenshot fallback closes much of the gap.
- WebView-heavy screens (payments pages, in-app browsers, many Indian apps built with hybrid frameworks) often have incomplete a11y trees. That is exactly where SoM/screenshots helped in AndroidWorld (MobileMiniWoB++).

### Gaps
- I could not open an official, verified AndroidWorld leaderboard (Google Sheet / GitHub) in this session. Aggregator numbers are self-reported and may mislabel the input modality.
- I did not find published per-system numbers for these in this session, so I cannot cite them: Ferret-UI (Apple), OS-Atlas, CogAgent, Agent-S2/S3, DroidBot-GPT, Android in the Wild, UI-TARS-1.5.
- No Claude-only, a11y-only AndroidWorld result was found for recent Claude models. The "Sonnet 4.5 + Sonnet 4" 86.2% and askui entries do not state the observation format.

## 2. Techniques that measurably increase success (with ablations)

### Takeaway
The largest measured gains come from four things:
- app-specific knowledge or memory, e.g. AppAgent 2.2% → 73-96%
- explicit planning / sub-goal decomposition, e.g. Mobile-Agent-v2 −31.9 points SR on advanced tasks without it
- reflection / post-action verification, e.g. −15.9 points without the reflector, −15 points without post-validation
- richer agent prompting with guidelines plus a reflection summary, e.g. M3A 30.6% vs M3A-Simple 19.8%

Tree compression mainly cuts tokens and cost while preserving accuracy. Screenshots plus SoM do not reliably beat a good a11y tree on native apps. Hybrid perception helps at the frontier (+11 points in Minitap).

### Cited Findings
**Prompting richness, guidelines and reflection (text-only)**
- M3A (full prompting with guidelines and a reflection/summary step) reached 30.6%, vs **19.8%** for M3A-Simple with the same GPT-4 Turbo and a11y input. Additional prompting techniques and domain-specific guidance were "beneficial for navigating the complexity of Android interactions." — [AndroidWorld](https://arxiv.org/pdf/2405.14573)
- ReAct-style reasoning "significantly improves performance only in XML [text] mode". — [AndroidLab](https://arxiv.org/html/2410.24024)

**Planning, reflection and memory: Mobile-Agent-v2 ablation (Table 4)**

| Configuration | Basic SR | Basic CR | Advanced SR | Advanced CR |
|---|---|---|---|---|
| Full system | 88.6% | 93.9% | 61.4% | 82.1% |
| Without planning agent | 59.1% | 63.7% | 29.5% | 43.8% |
| Without reflection agent | 77.3% | 83.6% | 45.5% | 72.3% |
| Without memory unit | 86.4% | 89.2% | 54.5% | 75.9% |

- The reflection agent prevents "operating on incorrect pages or getting stuck in loops of invalid operations."
- Manual knowledge injection gave 100% SR on the basic instructions.
- [Mobile-Agent-v2](https://arxiv.org/html/2406.01014v1)

**Multi-agent components: Minitap ablation on AndroidWorld (relative to 100%)**
- Removing each component cost:
  - multi-agent architecture: −21 points
  - post-validation (verifying the action outcome): −15
  - sequential execution, one action at a time with immediate failure detection: −12
  - hybrid perception (screenshot + hierarchy): −11
  - meta-cognitive reasoning: −9
- Key lesson: "LLMs excel at understanding goals and selecting strategies, but struggle with reliable execution". Separating intent from *deterministic* outcome verification creates an effective feedback loop.
- [Minitap](https://arxiv.org/html/2602.07787v1)

**App knowledge / memory**
- AppAgent:
  - no doc: 2.2%
  - auto-exploration doc: 73.3%
  - human-demo doc: 84.4%
  - manually written per-element doc: 95.6%
  - [AppAgent](https://arxiv.org/html/2312.13771)
- AutoDroid: memory from offline UI exploration (a UI Transition Graph mapping each element to its function) improves completion rate *more* than single-step accuracy. A few critical steps decide task success. Smaller models benefit more. — [AutoDroid](https://arxiv.org/html/2308.15272v4)
- Mobile-Agent-E self-evolution (persistent "Tips" plus reusable "Shortcuts" learned from past tasks):
  - satisfaction 75.1% → 86.9%
  - termination error 32% → 12%
  - [Mobile-Agent-E](https://arxiv.org/html/2501.11733)

**a11y-tree compression / representation**
- AutoDroid converts a raw view hierarchy (~40k tokens) into a compact HTML-like representation. It merges non-interactive containers into their functional children and pairs elements with their text/labels.
  - Result: ~339 tokens per prompt vs 625 for the baseline representation.
  - GUI merging plus shortcuts cut LLM calls by 13.7%.
  - [AutoDroid](https://arxiv.org/html/2308.15272v4)

**Verifier instead of generator**
- V-Droid builds the candidate actions from the UI hierarchy (a discretized action space) and has an LLM score each candidate.
  - Results: 59.5% on AndroidWorld (SOTA at the time) and 0.7 s/step.
  - [V-Droid](https://arxiv.org/abs/2503.15937v3)

**Fine-tuning / data scale (open models)**
- AndroidLab instruction tuning took open LLMs from 4.59% to 21.50% average. — [AndroidLab](https://arxiv.org/html/2410.24024)
- AutoDroid: Vicuna-7B with no fine-tuning scored 0.6% completion; with chain-of-thought plus app-memory fine-tuning it scored 41.1%. — [AutoDroid](https://arxiv.org/html/2308.15272v4)

**Screenshots / SoM**
- On native Android, text a11y beat SoM with GPT-4T (30.6% vs 25.4%). With Gemini 1.5 Pro, SoM was slightly better (22.8% vs 19.4%). SoM helped when the a11y tree was incomplete (web content). — [AndroidWorld](https://arxiv.org/pdf/2405.14573)

### Inferences
These are ranked for VoiceControl's architecture and most need no vision.
1. **Post-action verification in code.** Diff the before/after a11y tree: did the screen change, did the typed text land in the field, did the expected element appear? Feed a short "result of last action" line into the next call. This is the cheapest analogue of the reflection agent and post-validation, worth about 10-16 points in the ablations above.
2. **Explicit sub-goal plan in the JSON output.** Keep a persistent plan/progress field carried across steps. Removing planning was the largest drop in Mobile-Agent-v2.
3. **Per-app knowledge snippets.** Inject short, retrieved notes for the foreground app ("In PhonePe, the Pay button is ...", "In WhatsApp, search via the magnifier icon") and successful past trajectories / shortcuts. This was the largest single gain in AppAgent, AutoDroid and Mobile-Agent-E, and it maps naturally onto VoiceControl's existing flows and record-to-flow features.
4. **Prompt guidelines** for Android idioms (scroll to find, press Enter/search after typing, dismiss dialogs, check before DONE). This explains much of M3A vs M3A-Simple (+10.8 points).
5. **Compress the tree** to interactive and labeled elements with stable ids. This saves cost and latency without an accuracy loss.
6. **Screenshot fallback** only when the tree is sparse: few labeled nodes, a WebView/canvas, or unlabeled icon buttons.

### Gaps
- No clean published ablation was found for few-shot examples alone, or for history length / format, on Android.
- No quantitative ablation was found for tree-pruning effects on accuracy (as opposed to tokens) beyond AutoDroid's overall results.

## 3. Common failure modes and published mitigations

### Takeaway
Documented failures:
- missing visual or state cues
- poor grounding on precise interactions (text editing, sliders)
- inability to recover from mistyping
- losing track of information across scrolls and apps
- endless scrolling or repetitive loops
- wrong termination, including premature DONE
- silent text-input failures
- stale UI state

Mitigations are reflection/verification, loop detection, scratchpad memory, and per-step fresh observation.

### Cited Findings
- AndroidWorld error analysis:
  - agents fail to detect visual cues and struggle with some UI patterns and affordances
  - they lack the ability to "explore and adapt" after reasoning mistakes
  - they struggle to *confirm system states* (e.g., verifying WiFi is on)
  - grounding is weak for text manipulation and sliders, and they are "often unable to recover from mistyping errors"
  - memory-demanding tasks (transcribing across apps, scrolling) fail because agents cannot "remember" content
  - [AndroidWorld](https://arxiv.org/pdf/2405.14573)
- SeeAct cached actions without outcomes, which led to "repetitive, ineffective behaviors such as endless scrolling." Tasks ended at the max-step limit. — [AndroidWorld](https://arxiv.org/pdf/2405.14573)
- Minitap failure taxonomy, before its fixes:
  - context management, 38%: reasoning drift, context pollution, stale UI hierarchies
  - execution reliability, 41%: *silent text-input failures* from keyboard/encoding issues, and latency limiting retries
  - error recovery, 21%: error propagation, repetitive action loops without escape detection
  - Fixes: a summarizer, sequential execution with immediate failure detection, post-validation, and a scratchpad.
  - [Minitap](https://arxiv.org/html/2602.07787v1)
- Termination errors (stopping too early or too late) are large:
  - Mobile-Agent-v2: 52%
  - Mobile-Agent-E: 32%
  - Mobile-Agent-E with evolution: 12%
  - [Mobile-Agent-E](https://arxiv.org/html/2501.11733)
- AutoDroid failure categories:
  - multiple valid paths that differ from the annotation
  - misidentifying when the task is complete
  - overlooking interface hints
  - [AutoDroid](https://arxiv.org/html/2308.15272v4)
- The reflection agent prevents operating on wrong pages and invalid-operation loops. — [Mobile-Agent-v2](https://arxiv.org/html/2406.01014v1)

### Inferences
These are concrete mitigations for VoiceControl.
- **Loop detection.** Hash (screen signature, action) pairs. On a repeat or no-change, tell the model explicitly and force a different action, Back, or ask_user.
- **Typing verification.** After a type action, re-read the field's value. If it is empty or wrong, retry with set-text or clear-then-type. Also suggest submit/IME-action/search-button as the next step.
- **Scroll-to-find.** Give a prompt rule plus a code check that a scroll actually changed the visible items. If it did not, the list end has been reached.
- **DONE gating.** Require the model to cite on-screen evidence (an element text) proving completion. Optionally run a cheap verifier call before announcing success.
- **Pop-up / loading handling.** Detect dialog or progress-bar nodes and wait or dismiss before planning.
- **Scratchpad / notes field** in the JSON output so values read from screens (OTPs, amounts, names) persist across steps.

### Gaps
- No quantitative frequency breakdown was found specific to "typed in search but did not submit" or to pop-ups. These are widely reported anecdotally but I found no numbered source in this session.

## 4. Safety-critical actions and human-in-the-loop

### Takeaway
LLM agents are poor at self-policing risky actions in interactive mobile settings, even when they recognize the risk in QA settings. Automatic risky-action detection is imperfect (AutoDroid: 75% precision / 80.5% recall). Production systems therefore hard-require user confirmation for purchases and payments, and let the user stop the agent at any time. Agents are also highly vulnerable to indirect prompt injection from on-screen content such as messages and posts.

### Cited Findings
- **MobileSafetyBench** (arXiv 2410.17520, AAAI): 100 low-risk tasks, 100 high-risk tasks, and 50 prompt-injection tasks covering messaging, banking and similar apps.
  - GPT-4o completed 82% of benign tasks but refused or prevented only about 31% of risky ones.
  - Phi-4 was over-cautious: 67% refusals but only 3% completion of safe tasks.
  - Agents defended only 3-15 of 50 injection attacks, sometimes taking unauthorized actions such as selling stock.
  - Safety-guided CoT raised GPT-4o's high-risk refusal from 6% to 36%. (Note: the 31% and 6% baselines appear to come from different settings or versions of the paper.)
  - GPT-4o detected 92% of hazards in plain QA format, yet as an agent prevented only 36%.
  - [MobileSafetyBench](https://arxiv.org/html/2410.17520v3)
- **AutoDroid** flags risky actions (e.g., deleting or sending) for user confirmation with **75.0% precision and 80.5% recall**. Adding privacy filtering plus security confirmation cost only a little accuracy: 90.9 → 89.9 action accuracy, 71.3 → 69.9 completion. — [AutoDroid](https://arxiv.org/html/2308.15272v4)
- **Google Gemini Intelligence** (announced at the Android Show / I/O, May 2026) is a computer-use agent for Android, first on Galaxy S26 and Pixel 10.
  - Any task that buys something "will require you to confirm the purchase."
  - A progress bar lets the user stop Gemini at any time.
  - Permissions control when it can see the screen.
  - [Engadget](https://www.engadget.com/2170770/gemini-intelligence-brings-app-automation-to-android/); [9to5Google](https://9to5google.com/2025/12/29/gemini-android-control/)

### Inferences
- Do not rely on the LLM alone to decide what is risky. Combine:
  - a deterministic rule layer: keywords on the target element or screen such as Pay, Send, Transfer, Confirm, Delete, Place order, UPI PIN, ₹ amounts, and package names of banking/UPI apps
  - the LLM's own "requires_confirmation" flag

  Then always stop for spoken confirmation from the user. Never let the agent enter a UPI PIN or OTP on its own.
- Treat on-screen text (SMS, WhatsApp messages) as untrusted data in the prompt. This mitigates prompt injection, which matters for elderly users targeted by scams.
- Show a persistent "agent is acting — tap/say stop" overlay, matching Gemini's progress bar plus stop control.

### Gaps
- No public, detailed documentation was found for Rabbit LAM, Apple Intelligence App Intents, or Samsung's agent safety mechanisms in this session.

## 5. Practical lessons from production systems

### Takeaway
Production moved in 2025-26 toward general "computer-use" agents on phones (Google Gemini Intelligence) with mandatory purchase confirmation and user-interruptible execution. Research systems show that cost and latency are first-class concerns: per-step latency of seconds and multi-minute tasks are the norm unless you use small or verifier-style models.

### Cited Findings
- Gemini Intelligence navigates apps to book classes, order groceries, or fill a shopping cart from an email. It adds confirmations on purchases, a stop control, and permission gating. — [Engadget](https://www.engadget.com/2170770/gemini-intelligence-brings-app-automation-to-android/); [Gizmochina](https://www.gizmochina.com/2026/05/12/google-announces-gemini-intelligence-for-android/)
- Latency:
  - M3A: 3.9 min/task (2.5 min text-only)
  - V-Droid: 0.7 s/step via prefill-only verification
  - Minitap: $1.07/task with mixed model allocation, using a frontier model only for the core decision
  - [AndroidWorld](https://arxiv.org/pdf/2405.14573); [V-Droid](https://arxiv.org/abs/2503.15937v3); [Minitap](https://arxiv.org/html/2602.07787v1)
- AutoDroid's shortcuts (reusing known paths) guided the system successfully in 75% of cases and reduced LLM calls. — [AutoDroid](https://arxiv.org/html/2308.15272v4)

### Inferences
- For VoiceControl:
  - Use a cheaper/faster Claude model for routine steps and verification, and escalate to a stronger model on failure or no-progress.
  - Use prompt caching for the static system prompt and app notes.
  - Replay known successful flows deterministically, with the LLM as fallback. This mirrors AutoDroid shortcuts and Mobile-Agent-E Shortcuts.
- Text-only a11y is the right latency/cost default. Text-only was 36% faster than SoM in M3A.

### Gaps
- No primary-source technical details were found on Rabbit LAM's real-world success rates, Apple App Intents agent behavior, or Samsung Galaxy AI agents. Any claims about them would be unsourced.
