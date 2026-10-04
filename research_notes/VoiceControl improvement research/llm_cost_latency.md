# Cutting cost and latency of the VoiceControl LLM phone agent (Claude API, Ktor on Render)

Research date: 2026-10-04. All prices are USD from pages fetched on that date unless noted. Model names were checked against the current Anthropic docs; the lineup is Fable 5.1 / Opus 5.5 / Sonnet 5.5 / Haiku 4.5. Older names like "Claude 3.5" or "Sonnet 4" are retired or legacy.

## Q1. Prompt caching, the current model lineup and prices, effort, and routing

### Takeaway
VoiceControl's ~1.5k-token system prompt is above the 512-token caching minimum on Opus 5.5 and Sonnet 5.5, so it can be cached today. On Opus 5.5 a cache read costs $0.20/MTok, which is 5% of the $4 input price. Combining top-level automatic caching (for the growing step history) with one explicit breakpoint on the system prompt should cut input cost per step a lot, and should also cut prefill latency. Haiku 4.5 is a poor routing target here for three reasons:
- its caching minimum is 4,096 tokens, so this prompt would not cache;
- it does not support `effort`;
- its retirement is "not sooner than October 15, 2026", only days away.

Sonnet 5.5 ($2/$10, cache read $0.20) is the realistic cheaper tier. Before building a cascade, compare it against Opus 5.5 at `low` effort with caching.

### Cited Findings
**Current lineup (models overview, fetched 2026-10-04)**

| Model | Latency label | Price in / out per MTok | Default effort | Context / max output | Retirement |
|---|---|---|---|---|---|
| Claude Fable 5.1 | "Slower" | $10 / $50 | `high` | 1M / 128K | — |
| Claude Opus 5.5 (`claude-opus-5-5`) | "Moderate" | $4 / $20 | `medium` | 1M / 128K | — |
| Claude Sonnet 5.5 (`claude-sonnet-5-5`) | "Fast" | $2 / $10 | `high` | 1M / 128K | — |
| Claude Haiku 4.5 (`claude-haiku-4-5`) | "Fastest" | $1 / $5 | effort not supported (uses extended thinking) | 200K / 64K | "not sooner than October 15, 2026" |

Source: [Models overview](https://platform.claude.com/docs/en/about-claude/models/overview.md)
- Legacy models that are still available: Fable 5, Opus 5 ($5/$25), Opus 4.8/4.7/4.6/4.5 ($5/$25), Sonnet 5 ($2/$10) and Sonnet 4.6 ($3/$15) — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)
- Sonnet 5 was launched at "introductory" $2/$10 pricing. That price is now standard, and the increase to $3/$15 planned for 2026-09-01 "will not occur" — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)
- Claude 4.7 and later use a newer tokenizer that "produces approximately 30% more tokens for the same text" than Sonnet 4.6 and earlier. This matters when comparing token counts across models — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)

**Cache pricing per MTok**

| Model | 5-min write | 1-hour write | Cache read |
|---|---|---|---|
| Opus 5.5 | $5 | $8 | $0.20 (0.05× input) |
| Sonnet 5.5 | $2.50 | $4 | $0.20 (0.1×) |
| Haiku 4.5 | $1.25 | $2 | $0.10 |
| Fable 5.1 | $12.50 | $20 | $0.25 (0.025×) |

- General multipliers are 1.25× base input for a 5-minute write, 2× for a 1-hour write, and 0.1× for a read (except as listed above). A read also refreshes the TTL. The multipliers stack with the Batch discount and data residency — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)
- Batch API is 50% off (Opus 5.5 $2/$10). It is not relevant to interactive steps but could serve offline jobs such as flow learning. US-only inference (`inference_geo: "us"`) costs 1.1× — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)
- Fast mode (research preview, Claude API only) is $8/$40 on Opus 5.5, which is 2× standard. It gives up to 2.5× faster output tokens/sec. Switching `speed` invalidates the cache — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md); speed figure from the Anthropic claude-api skill reference (bundled docs, cached 2026-09-25)

**Minimum cacheable prefix**

| Minimum | Models |
|---|---|
| 512 tokens | Opus 5.5, Opus 5, Sonnet 5.5, Fable 5/5.1 |
| 1,024 tokens | Opus 4.8, Sonnet 5, Sonnet 4.6 |
| 2,048 tokens | Opus 4.7 |
| 4,096 tokens | Opus 4.6/4.5 and Haiku 4.5 |

Shorter prefixes are "processed without caching, and no error is returned" — [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**How caching works**
- Up to 4 `cache_control` breakpoints per request. Prefix order is tools → system → messages. A write happens only at a breakpoint.
- Lookback is 20 blocks per breakpoint. A run of consecutive tool_use blocks, or of consecutive tool_result blocks, counts as one position.
- Top-level automatic caching (`cache_control: {type: "ephemeral"}` on the request) moves the breakpoint forward as the conversation grows.

Source: [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**TTL**
- The default TTL is 5 minutes. `"ttl": "1h"` costs 2× on the write.
- The lifetime is measured "from the start of the request that writes or reads", so generation time counts against it.

Source: [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**What invalidates the cache**
- Changing tool definitions invalidates everything.
- Changing tool_choice, adding or removing images, changing thinking parameters, or changing top-level effort invalidates the messages cache.
- Toggling speed (fast mode) invalidates system and messages.

Source: [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**Recommended agent-loop pattern**
- Put one explicit breakpoint on the last static system block, and use top-level automatic caching for the growing tail.
- Keep the system prompt frozen. Put dynamic data (date, mode) in later messages, or in a mid-conversation `role: "system"` message, which Opus 5.5 supports.
- Serialize tools deterministically.
- Verify that `usage.cache_read_input_tokens` is non-zero.

Source: Anthropic claude-api skill reference `shared/prompt-caching.md` (bundled docs); [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**Pre-warming and keep-alive**
- A `max_tokens: 0` request writes the cache and returns immediately with zero output tokens billed. It removes the cache-miss latency on the first real request.
- It is worth doing when first-request latency is user-visible (the docs name chat and voice) and there is a moment before traffic, such as app start or service boot.
- With the 5-minute TTL, re-warm at least every 5 minutes, or use the 1-hour TTL.

Source: [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**Latency savings**
- The current prompt caching page gives no specific % or TTFT figure. It says only that caching "significantly reduces processing time and costs" — [Prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md)

**Effort**
- Levels are `low`, `medium`, `high`, `xhigh` and `max`. On Opus 5.5, `medium` is the default, and thinking is always on and cannot be disabled (disabling returns a 400).
- `low` is "Most efficient. Significant token savings with some capability reduction… Simpler tasks that need the best speed and lowest costs."
- Lower effort means "fewer and terser tool calls" and less preamble.
- Effort affects all output tokens, including thinking.

Source: [Effort docs](https://platform.claude.com/docs/en/build-with-claude/effort.md)
- Changing top-level effort between requests invalidates the prompt cache. Opus 5.5 and Sonnet 5.5 support per-message effort (beta header `mid-conversation-output-config-2026-07-01`): a `role:"system"` message with empty content and `output_config.effort`, which preserves the cache. The docs advise holding effort constant within cached conversations — [Effort docs](https://platform.claude.com/docs/en/build-with-claude/effort.md)
- Sonnet 5.5 guidance is to start chat and latency-sensitive work at `medium` or `low`. `thinking: {type: "between_tools"}` turns off up-front thinking at effort `high` or below. Effort levels are recalibrated versus Sonnet 5 — [Effort docs](https://platform.claude.com/docs/en/build-with-claude/effort.md)

**Routing and cascades**
- Anthropic guidance is to measure the most capable model at lower effort before building a multi-model cascade. Caches are model-scoped, so a cascade gives up cache reuse across its models. Judge cost per completed task, not per request — Anthropic claude-api skill reference (bundled `SKILL.md` / `shared/cost-optimization.md`)
- Generic pricing-page advice: "Choose Haiku for simple tasks, Sonnet for most production workloads, and Opus for the most complex reasoning" — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)

**API changes that affect VoiceControl on Opus 5.5 and Sonnet 5.5**
- Forced `tool_choice` (`any` or `tool`) returns a 400. Use `auto` with `strict: true`, or structured outputs.
- Assistant prefill returns a 400.

Source: Anthropic claude-api skill reference (bundled docs, cached 2026-09-25)
- The tool-use system prompt overhead is 286 tokens on Opus 5.5 and Sonnet 5.5 (with `tool_choice` auto or none). It is 496 on Haiku 4.5 — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)

### Inferences
**Back-of-envelope cost per step on Opus 5.5.** These are estimates, not measurements. Assume 1.5k system + ~3k screen JSON + ~1.5k history = ~6k input tokens, and ~400 output tokens including low-effort thinking:

| Scenario | Input cost | Output cost | Total per step |
|---|---|---|---|
| Uncached | 6,000 × $4/M ≈ $0.024 | 400 × $20/M ≈ $0.008 | ≈ $0.032 |
| System + prior history cached (~3k reads at $0.20/M, ~3k fresh at $4/M, plus ~3k 5-min writes at $5/M for the next turn) | ≈ $0.0006 read + $0.012 fresh + $0.015 write | ≈ $0.008 | ≈ $0.03 |

- The cached case saves little because the screen JSON is new each step and gets written at 1.25×. Caching mainly pays off on the system prompt (+ tools) and on long histories.
- Shrinking the screen JSON (Q2) is the bigger cost lever. Output tokens (thinking) are the second lever: at $20/MTok, 400 output tokens cost as much as 2,000 input tokens.
- Consider putting the explicit breakpoint only on the system prompt and history, not on the volatile screen block, so you don't pay write premiums on content that is never reread.

**Latency**
- With ~6k input tokens, prefill is a small part of 2.5–3.5 s. Most step latency is likely thinking plus output generation, plus network and server overhead. That makes these the main latency levers:
  - effort (`low`);
  - shorter output schemas;
  - fewer output tokens;
  - Sonnet 5.5;
  - fast mode (at 2× price).
- The model is told to minimise reasoning by effort and prompt, and cannot be told to skip thinking on Opus 5.5.

**Routing candidates**
- Sonnet 5.5 with `between_tools` or adaptive thinking at `low` effort is the natural "simple step" tier: half the price of Opus 5.5 and labelled "Fast" versus "Moderate".
- Interpreting single utterances and writing field questions are good candidates for Sonnet 5.5, or Haiku 4.5 before its retirement date. These calls are separate from the agent loop, so model-scoped caches don't hurt.
- For the agent loop itself, switching models mid-task forfeits the history cache.

### Gaps
- No official number for TTFT or latency reduction from prompt caching was found on the current docs page. Older launch-era claims (e.g. "up to 85% latency reduction" in 2024) were not re-verified, so do not cite them.
- No published tokens/sec or TTFT benchmarks for Opus 5.5, Sonnet 5.5 or Haiku 4.5 were found. Only the qualitative latency labels exist. VoiceControl should measure with its own logs: `usage`, time to first token, and total time.
- Whether a successor Haiku model is planned was not found. The only fact found is Haiku 4.5's retirement-not-before date of 2026-10-15.

## Q2. Structured outputs, tool use, streaming, and shrinking tokens

### Takeaway
Structured outputs (`output_config.format`) are GA on Opus 5.5, Sonnet 5.5 and Haiku 4.5. Their only latency cost is grammar compilation on the first use of a schema, after which the grammar is cached for 24h. Keep one stable schema, because changing the format invalidates the prompt cache. Streaming works with structured outputs, so the backend can start a TTS acknowledgement or an early action before the final JSON arrives. On the input side, compressing the screen JSON and summarizing history are the largest cost levers.

### Cited Findings
- Structured outputs are supported on `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-4-5-20251001`, and others. They are GA on the Claude API, Bedrock, Google Cloud and Foundry — [Structured outputs docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md)
- On latency: the first request incurs "additional latency while grammar compiles". The compiled grammar is cached "for 24 hours from last use". Changing the schema structure or the tool set invalidates it. Name- or description-only changes do not — [Structured outputs docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md)
- "Changing the `output_config.format` parameter will invalidate any prompt cache for that conversation thread" — [Structured outputs docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md)
- Limits per request:
  - at most 20 strict tools;
  - at most 24 optional parameters in total;
  - at most 16 union-type parameters;
  - "Schema is too complex for compilation" is returned as a 400.
  - Unsupported schema features: recursive schemas, `minimum`/`maximum`, `minLength`/`maxLength`, and `additionalProperties` other than false. The SDKs strip these and validate them locally.
  - Tips: make fields required, flatten nesting.

  Source: [Structured outputs docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md)
- Java SDK specifics:
  - `.outputConfig(MyClass.class)` derives the schema from the class.
  - Classes must be top-level or `static` nested.
  - When streaming, use `MessageAccumulator` and then `.message(Class<T>)` to deserialize.

  Source: [Structured outputs docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md)
- Invalid outputs can still happen when `stop_reason` is `refusal` or `max_tokens`, so always check `stop_reason` — [Structured outputs docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md)
- Tool use adds a hidden system prompt of 286 tokens on Opus 5.5 and Sonnet 5.5, plus the tool schemas. Structured outputs without tools avoid this overhead — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)
- For streaming tool inputs, set `eager_input_streaming: true` on client tools so that large tool inputs stream as they are generated. The client must then validate the partial JSON — Anthropic claude-api skill reference (bundled docs)
- Context editing (beta `context-management-2025-06-27`, `clear_tool_uses_20250919`) clears old tool results. Server-side compaction (beta `compact-2026-01-12`) summarizes history, with a default trigger at 150K tokens, which is far above VoiceControl's sizes. Both change the prefix, so the cache misses from the edited point — Anthropic claude-api skill reference (bundled docs)
- The browser-use toolset docs list accessibility trees as a major input-token consumer. A 10 kB web page is about 2,500 tokens, as a rule of thumb for text→token size — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)

### Inferences
**Screen JSON compression.** Rough sizing is 1 token ≈ 4 chars of English, and roughly 30% more on the 4.7+ tokenizer. Recommended steps for VoiceControl:
- drop invisible, offscreen, zero-size and non-interactive container nodes;
- dedupe repeated texts;
- replace resource-ids and view paths with short integer ids (e.g. `#12`) and keep the mapping server-side;
- use compact keys or a line-oriented format instead of verbose JSON;
- truncate long texts to about 80 characters;
- cap list items, e.g. first N plus a "+K more" marker;
- send diffs versus the previous screen when the screen signature is unchanged.

A 2–4× token reduction on the screen part is plausible, but VoiceControl must measure it with the `count_tokens` endpoint.

**History**
- Keep the last N steps verbatim and collapse older ones into a one-line summary each, generated deterministically by code rather than by the LLM, so the prefix stays stable.
- Append only. Never rewrite earlier messages. Rewrites break the cache and, on Opus 5.5 and Sonnet 5.5, can invalidate thinking blocks under preserved thinking.

**Streaming for voice**
- Stream the response and parse fields incrementally. Put a short `say`/`ack` field first in the schema (property order in the schema guides output order) so TTS can start before the action fields finish.
- Parse partial JSON defensively.
- Keep `max_tokens` modest but not truncating.

### Gaps
- No published measurement was found of grammar-compilation latency in ms, or of the per-token speed overhead of constrained decoding.
- No primary-source academic numbers on accessibility-tree compression ratios for Android agents were gathered here because of the tool budget. The adjacent research notes may cover this.

## Q3. App-level caching: plan/trajectory reuse, learned flows, semantic caching

### Takeaway
No primary sources were gathered in this pass. The guidance below is inference grounded in the API mechanics above. VoiceControl already has "flows" and "voice macros" features (Phases 18–19 of the project). These are the natural place to replay known trajectories without calling the LLM.

### Cited Findings
- Batch API at 50% off suits non-interactive work such as post-hoc distilling of successful trajectories into flows — [Pricing](https://platform.claude.com/docs/en/about-claude/pricing.md)
- Anthropic guidance is to cache only stable prefixes. Prompts that differ in the first ~1K tokens per request should not be cached at all, because that pays the write premium for zero reads — Anthropic claude-api skill reference `shared/prompt-caching.md`

### Inferences
**Screen-signature cache**
- Key each entry on (package, activity, a hash of sorted interactive-element ids and labels, the normalized goal).
- Store the action that succeeded. On a hit, execute it directly, verifying the target element exists, and skip the LLM.
- Fall back to the LLM on a mismatch or failure.

**Learned flows**
- Promote a trajectory to a reusable flow after K successful runs.
- Parameterize slot values such as contact names and amounts.
- Use the LLM only for slot extraction from the utterance, which is a small, cheap call on Sonnet 5.5 or Haiku 4.5, or done on-device.

**Semantic cache for utterance interpretation**
- Normalize the text, then match it against a recent-interpretations table by embedding similarity or by exact match after normalization.
- Use a high similarity threshold and verify that entities match, because a false hit causes wrong actions on a phone.

### Gaps
- No primary sources were gathered on hit rates, or on accuracy and risk of semantic caching for GUI agents. Examples would include published Android-agent papers on trajectory reuse, or GPTCache-style evaluations. This needs a follow-up search.

## Q4. On-device intent classification and small models (Gemini Nano, ML Kit GenAI, MediaPipe)

### Takeaway
Android's ML Kit GenAI APIs run Gemini Nano on-device through AICore. They include a general Prompt API, currently in **beta**, that can return structured output. This makes it a candidate for classifying common commands without a server round-trip. Device support, language coverage (important for Indic languages) and quotas were not stated on the pages fetched, so VoiceControl needs a server fallback.

### Cited Findings
- AICore is an Android system service. The ML Kit GenAI APIs built on it offer:
  - Prompt (text or multimodal);
  - Summarization;
  - Proofreading;
  - Rewriting;
  - Image Description;
  - Speech Recognition.

  Execution is on-device, with no network needed — [Android Gemini Nano page](https://developer.android.com/ai/gemini-nano)
- The ML Kit GenAI Prompt API is a "beta" offering "not subject to any SLA or deprecation policy" and may break backward compatibility. It accepts text, or image plus text, and "emits text output or structured output". Listed uses include text classification and entity extraction — [ML Kit Prompt API (Android)](https://developers.google.com/ml-kit/genai/prompt/android)
- The fetched pages did not list supported devices, languages, token limits, quotas, foreground restrictions or latency — [Android Gemini Nano page](https://developer.android.com/ai/gemini-nano); [ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android)

### Inferences
**Tiered design**
1. A deterministic matcher handles exact and fuzzy voice macros. This already exists from Phase 19.
2. On supported devices, the Gemini Nano Prompt API does intent and slot classification into a fixed enum.
3. Otherwise, the call goes to the server LLM.

Only multi-step, unknown-screen tasks would reach Claude. A tiny on-device text classifier (e.g. a TFLite/MediaPipe text classifier trained on the app's own intents) works on every device and in any language you train it on, and makes a good fallback when Nano is unavailable.

### Gaps
- Not verified this pass:
  - the Gemini Nano supported-device list (historically flagship Pixel/Samsung devices);
  - language support, especially Hindi and other Indic languages;
  - per-app inference quotas and background restrictions;
  - on-device latency.
- MediaPipe LLM Inference API status and model options (e.g. Gemma variants), and small Indic models (e.g. Sarvam- or AI4Bharat-class models), were not researched because of the tool budget.

## Q5. Hosting: free-tier cold starts, keep-warm, and cheap always-on options

### Takeaway
The Render Free plan spins a service down after 15 minutes without inbound traffic, and spin-up takes "about one minute". That fits the observed 50–100 s cold start for a JVM/Ktor app. Pinging to keep warm is not explicitly banned, but Render may suspend free services with uncommonly high traffic, and a ping from inside the app doesn't count. The 750 free hours/month covers one service running 24/7 (~730 h).

The clean fix is a small always-on instance. Options, by price:
- Hetzner VPS: ~€4.5–6/mo, self-managed; availability was tightening in late 2026.
- Fly.io shared-cpu-1x 512MB: $3.69/mo.
- Railway Hobby: $5 floor plus usage.
- Render Starter: $7/mo, 512MB / 0.5 CPU.

A JVM Ktor service realistically needs ≥512MB, and 1GB is more comfortable.

### Cited Findings
- Render Free behaviour:
  - spins down after "15 minutes without receiving any inbound traffic" (HTTP or WebSocket);
  - reactivation "takes about one minute";
  - "750 Free instance hours" per workspace per month, after which free services are suspended until next month;
  - Render "may suspend a Free web service that initiates an uncommonly high volume of traffic";
  - Render auto-answers `/robots.txt` while spun down, so monitoring pings to that path won't wake the service;
  - no scaling, no persistent disk, no SSH.

  Source: [Render Free docs](https://render.com/docs/free)
- Render instance pricing (third-party review dated 2026-07-15, updated 2026-08-06):

  | Instance | Price | RAM | CPU |
  |---|---|---|---|
  | Free | $0 | 512MB | 0.1 |
  | Starter | $7/mo | 512MB | 0.5 |
  | Standard | $25/mo | 2GB | 1 |

  Workspace-plan bandwidth was cut on 2026-08-01: Hobby went from 100GB to 5GB included, with $0.15/GB overage after that — [jwatte.com Render review](https://jwatte.com/blog/render-com-platform-review.md). Search snippets also list Pro $85 (2 vCPU/4GB) — [search aggregate incl. render.com/pricing](https://render.com/pricing). The official pricing page did not render, so this is secondary.
- Fly.io always-on shared-cpu-1x in Ashburn: 256MB $2.19/mo, 512MB $3.69/mo, 1GB $6.70/mo; 2GB (shared-cpu-2x) $13.39/mo. A stopped Machine costs only rootfs at $0.15 per GB per 30 days, and auto-stop/auto-start is supported. The trial is 2 h runtime or 7 days, after which a card is required. The page had no date; fetched 2026-10-04 — [Fly.io pricing](https://docs.fly.io/about/pricing)
- Railway Hobby is $5/mo, which includes $5 of usage. RAM is $10/GB/mo, CPU $20/vCPU/mo and egress $0.10/GB — [Railway pricing docs](https://docs.railway.com/reference/pricing) (via search snippet)
- Hetzner raised prices on 2026-04-01; the CX22 went from €3.29 to €4.49, and cloud servers rose 30–43%. One aggregator reports a later 2026 increase to €5.99 and the CX22 being marked unavailable on 2026-09-07. These figures come from aggregators and forums, not Hetzner's own page, so treat them as unverified — [agentdeals.dev Hetzner 2026](https://agentdeals.dev/hetzner-pricing-2026); [Cloudron forum](https://forum.cloudron.io/topic/15111/hetzner-price-increases-by-20-30-other-hosting-providers-soon-to-follow/29)
- Cloud Run min-instances: an idle min instance is billed at a reduced idle rate. A search-summary estimate was about $3.24/mo for 0.25 vCPU / 256MB idle in a Tier 1 region, with always-allocated CPU priced lower per unit. This was not verified on cloud.google.com and may be inaccurate (e.g. minimum CPU/memory constraints for JVM apps) — [Cloud Run pricing](https://cloud.google.com/run/pricing) (search summary only)

### Inferences
**Keep-warm on Render Free**
- An external pinger (e.g. a cron job every 10–14 min hitting a lightweight `/health`, not `/robots.txt`) would keep one service up within the 750 h budget.
- Risks:
  - it works against the intent of the free tier, and Render reserves the right to suspend for unusual traffic;
  - a second free service in the same workspace would exhaust the hours;
  - free instances are still 0.1 CPU, which hurts JVM throughput and GC even when warm.

**Cost comparison.** Moving to Render Starter costs $7/mo. One avoided cold start per user session easily justifies this compared with LLM spend: about 220 Opus 5.5 steps at ~$0.03 cost about $7.

**Other mitigations**
- Pre-warm the Anthropic prompt cache on boot with `max_tokens: 0`.
- Have the Android app fire a `/warmup` request when the user opens the assistant, overlapping the cold start with speech capture.
- Use JVM startup tuning: CDS/AppCDS, smaller heap, Ktor with CIO, or GraalVM native-image for sub-second start.

### Gaps
- The official Render pricing page and the official Cloud Run pricing page did not load or were not verified. Render Starter and Standard prices come from a dated third-party review.
- No official Render statement explicitly allowing or forbidding keep-alive pings was found. Only the "uncommonly high volume" suspension clause exists.
- Official Hetzner prices as of October 2026 were not verified on hetzner.com.
