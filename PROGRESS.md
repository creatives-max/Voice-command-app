# VoiceControl — Build Progress

If work is interrupted, read this file first and resume from the first unchecked item.

## Phase 1 — Monorepo setup, module structure, Docker + CI, README
- [x] Repo layout `/android`, `/backend`, `/dashboard`, `/infra`, `/docs`
- [x] Android multi-module Gradle build (app, core:*, feature:*) with Hilt/Compose/Room/Ktor wiring
- [x] Backend multi-module Gradle build (domain, application, infrastructure, api)
- [x] Dashboard Next.js + TS + Tailwind + shadcn/ui + TanStack Query/Router skeleton
- [x] Dockerfiles (backend, dashboard) + `docker-compose.yml` (Postgres+pgvector, Redis, backend, dashboard)
- [x] GitHub Actions CI (Android build, backend tests, dashboard build)
- [x] README + docs/architecture.md
- [x] Commit + push `Phase 1: ...`

## Phase 2 — AccessibilityService screen reading
- [x] Node abstraction + screen parser (fields, buttons, labels, types)
- [x] Stable IDs (viewId / hierarchy path / label hash)
- [x] Password / OTP masking
- [x] VoiceControlAccessibilityService + live screen inspector UI
- [x] Unit tests, commit + push

## Phase 3 — Act on screen + floating overlay
- [x] Fill fields (ACTION_SET_TEXT, paste fallback), click, scroll, back, focus
- [x] Floating mic-button overlay (draggable, state-aware)
- [x] Unit tests, commit + push

## Phase 4 — Voice loop
- [x] TTS + STT interfaces with Android implementations (swappable for cloud)
- [x] Hindi/English/Hinglish normalization (numbers, email, phone)
- [x] Commands: next / submit / back / scroll / skip / repeat / stop
- [x] Assistant session engine (MVI) asks → listens → fills, wired to overlay
- [x] Unit tests, commit + push

## Phase 5 — AI brain
- [x] Backend `/v1/ai/interpret` with pluggable LLM provider (Anthropic / OpenAI / rule-based) via env
- [x] Android client uses backend interpretation with local fallback
- [x] Hindi/English/Hinglish prompt + rule-based parsing
- [x] Tests, commit + push

## Phase 6 — Backend core
- [x] JWT auth (register/login/refresh/logout) with Redis sessions
- [x] User profile (name/email/phone/address) reused in flows
- [x] Redis caching
- [x] Flow save API + event-driven processing (Redis Streams)
- [x] OpenAPI docs + Swagger UI
- [x] Android login/profile screens + flow upload
- [x] Tests, commit + push

## Phase 7 — Flow Library
- [x] Versioned flows (history, rollback)
- [x] pgvector embeddings + screen → flow matching
- [x] Dashboard: login, flows list, flow editor (question, rules, defaults, skip, order, help video), versions
- [x] Android: fetch matched flow and run edited flow next time
- [x] Tests, commit + push

## Phase 8 — Settings, history, vision, compliance, k8s, observability
- [x] Android settings + run history screens; backend run history API + dashboard history
- [x] Screenshot + vision fallback for apps without readable fields
- [x] Privacy policy + Play Store notes
- [x] Kubernetes manifests in `/infra/k8s`
- [x] OpenTelemetry tracing/logging (backend + collector in compose)
- [x] Tests, commit + push

## Phase 9 — Smarter flows
- [x] Expression language (Kotlin engine + backend + TypeScript) with shared test vectors
- [x] Conditional steps, variables, computed values, `{var}` question templates, read-from-screen
- [x] Repeat/loop groups for lists
- [x] Multi-screen and cross-app flows (wait-for-screen, open-app steps)
- [x] Dashboard: editor support + dry-run/test mode simulator
- [x] Tests, commit + push

## Phase 10 — Triggers & scheduling
- [x] Per-flow triggers: on app open, schedules (cron + timezone), manual
- [x] Backend scheduler (multi-replica safe), devices, run requests, device command stream
- [x] Phone executes remote run requests and streams a live run log
- [x] Dashboard: triggers editor, devices, "Run now" with live log
- [x] Tests, commit + push

## Phase 11 — Flow marketplace
- [x] Publish / browse / search / import (version-aware, update from source), ratings
- [x] Starter template library (signup, login, address, OTP-less, contact, payment details)
- [x] Dashboard marketplace pages + publish flow; Android template import
- [x] Tests, commit + push

## Phase 12 — Voice & AI upgrades
- [x] Streaming partial results with early command detection
- [x] Barge-in (interrupt TTS by speaking)
- [x] Custom wake word
- [x] Confidence-based re-ask
- [x] Context memory ("same as above", pronouns)
- [x] Marathi, Tamil, Telugu, Bengali, Gujarati
- [x] Tests, commit + push

## Phase 13 — Accessibility power
- [x] Screen-reader mode (read whole screen, navigate, activate)
- [x] Undo last action
- [x] Confirm before destructive actions
- [x] Hybrid vision (merge screenshot model with partly readable screens)
- [x] Tests, commit + push

## Phase 14 — Teams & multi-tenant backend
- [x] Organizations, memberships, invitations, RBAC (admin/editor/viewer), per-org flows
- [x] Audit logs
- [x] API keys + per-key rate limiting
- [x] Webhooks on flow events (signed, retried, delivery log)
- [x] Kafka event bus with Redis Streams fallback
- [x] Dashboard: org switcher, team, API keys, webhooks, audit log
- [x] Tests, commit + push

## Phase 15 — Analytics & visual builder
- [x] Per-flow usage + success analytics API and dashboard charts
- [x] Drag-and-drop visual flow builder (canvas)
- [x] Flow comments + presence
- [x] Dashboard dark mode + i18n (English, Hindi)
- [x] Tests, commit + push

## Phase 16 — Security, platform & polish
- [x] Biometric app lock
- [x] On-device-only mode completeness (on-phone flow editing)
- [x] GDPR export + account deletion (backend, dashboard, app)
- [x] Crash reporting (self-hosted)
- [x] Onboarding tutorial with practice form
- [x] Home-screen widget + quick-settings tile
- [x] Tablet layout
- [x] Emulator end-to-end test in CI
- [x] Tests, commit + push

---

# Second feature list (Phases 17–28)

Groups that were already delivered in Phases 9–16 get a phase that only adds what was still missing; the
phase notes say which earlier phase covered the rest.

## Phase 17 — Record-to-flow ("teach by doing")
- [x] Engine recorder: typing, toggles and presses in order, screen changes (next screen / other app), typed values kept only if chosen, never for passwords/OTPs/PINs
- [x] Accessibility bridge reports touches and typing (matched to screen elements) while recording
- [x] Overlay "teach" button and recording bubble; "Teach a new flow" in Saved flows
- [x] Review screen (name, steps, keep typed values as defaults), saved as a local flow and synced
- [x] Backend version source RECORDED; dashboard label "taught on phone"
- [x] Tests, commit + push

## Phase 18 — Smarter flows (gaps; conditions, loops, variables, cross-app and the dashboard dry-run were done in Phase 9)
- [x] Flow simulator in the phone engine (`FlowSimulator`), same walk as the dashboard test mode
- [x] Shared simulator vectors `docs/spec/simulation.json`, run by the dashboard, the phone engine and the backend
- [x] Backend `POST /v1/flows/{id}/dry-run` (signed-in or `flows:read` API key; unsaved steps; profile only for people) + OpenAPI
- [x] Phone "Test" run of a saved flow or of unsaved edits: type or tap answers, undo, start over
- [x] Tests, commit + push

## Phase 19 — Routines & triggers (voice macros; app-open and schedules were done in Phase 10)
- [x] Engine `ShortcutMatcher` (polite words, slips, ties) and voice shortcuts in sessions: on a screen without a form, a phrase runs its flow in its app; visible buttons win
- [x] Backend VOICE triggers with a phrase (migration V9, unique per account, reserved command words), sent to phones; VOICE run source for the run log
- [x] Phone: shortcuts in a flow's details (kept on the phone, follow the flow when it syncs), dashboard shortcuts cached offline, voice runs logged on the dashboard
- [x] Dashboard: "When I say…" trigger with phrase checks; "Voice shortcut" run source
- [x] Tests, commit + push

## Phase 20 — Photo/document auto-fill (OCR → fill)
- [x] On-device OCR (ML Kit, Devanagari + Latin) with reading-order merge of both recognisers
- [x] Extractor for PAN, Aadhaar (front/back), driving licence, voter ID, passport, bank papers, bills/letters: name, father's name, DOB, gender, IDs, phone, email, address, PIN, IFSC, account
- [x] Mapping to screen fields by type and label (first/last name, confirm fields), never to password/OTP/PIN fields
- [x] "Fill from a photo" in the overlay panel: camera or photo picker, review screen (untick, edit, masked Aadhaar/account numbers), fill when the form is back; photos deleted, screen kept out of screenshots
- [x] Tests, commit + push

## Phase 21 — Remote caregiver mode (with consent)
- [x] Backend care links (migration V10): invite codes created by the person helped (one-time, 30 min, hashed), chosen permissions (edit flows, run flows, see history), accept, change, end from either side, activity log, data export
- [x] Acting for someone (`X-Care-Link`): existing flow, trigger, dry-run, analytics, device, run and history endpoints work on their account within the permissions; everything else refused; edits saved as CAREGIVER versions; actions logged
- [x] Dashboard: Caregiving page (enter a code, people I help, invites, permissions, activity, remove), workspace switcher entry, "You are helping …" banner
- [x] Phone: Caregivers screen (create and share a code, helpers' permissions, what they did, stop their help)
- [x] Tests, commit + push

## Phase 22 — Offline + regional languages (gaps; the six languages and offline understanding were done in Phase 12)
- [x] On-device speech recognition (Android 12+) when "Speech on the phone" is on or in on-device only mode; automatic switch to on-device speech when the internet drops; spoken hint (8 languages) when a pack is missing
- [x] Offline languages screen: per-language listening/speaking status, download speech packs (Android 13+ in the background), install TTS voices
- [x] Pack status logic (tag matching, summary) in the engine with tests
- [x] Tests, commit + push

## Phase 23 — Accessibility narrator (gaps; reading, navigating, activating, undo and confirm-before-risky were done in Phase 13)
- [x] Jump by kind: next/previous button, field, switch or text ("agla khana", "पिछला बटन"); top/bottom
- [x] "Where am I" (screen, position, fields, empty fields, buttons); "find <words>" / "<words> kahan hai"
- [x] Speaking speed by voice ("faster", "dheere") for the session
- [x] "Read everything": reads to the end and keeps scrolling the page, never repeating what was read
- [x] Spoken narrator help in 8 languages; tests, commit + push

## Phase 24 — Voice & AI upgrades (gaps; streaming partials, barge-in, custom wake word, confidence re-ask and in-session context memory were done in Phase 12)
- [x] Context memory across sessions (opt-in, phone only): last answer per app and field offered next time; "same as last time" in 8 languages; never sensitive fields
- [x] Remembered answers screen (by app, forget one or all); forgotten when the setting is turned off; part of the phone export
- [x] Wake phrase test (listen once, say whether it would wake VoiceControl)
- [x] Tests, commit + push

## Phase 25 — Flow marketplace + templates (gaps; publish, search, import, update-from-source, ratings, starter templates and dashboard pages were done in Phase 11)
- [x] Marketplace on the phone: search, categories, sort, listing details (steps in plain words, reviews), install/update into my flows, rate
- [x] Reporting listings (broken, unsafe, spam, other; migration V11): hidden from search after 3 people report it, owner sees why; publishing a fixed version clears reports; templates never hidden
- [x] Dashboard Report dialog and "hidden after reports" notice; OpenAPI
- [x] Tests, commit + push

## Phase 26 — Teams & multi-tenant backend (gaps; organizations, roles, invitations, scoped API keys, webhooks, audit log and Kafka events were done in Phase 14)
- [x] API key expiry (1–365 days; expired keys rejected) and rotation (new secret, old one stops at once; migration V12)
- [x] Audit log date range filter and CSV export (formula-safe, capped at 10 000 rows, the export itself is audited)
- [x] Organization usage (members, invitations, keys, webhooks, flows) and per-tenant limits for seats and API keys
- [x] Dashboard: key expiry picker, expiry badges and Rotate; audit From/To and Download CSV; usage card; OpenAPI
- [x] Tests, commit + push

## Phase 27 — Analytics & visual builder (gaps; per-flow analytics charts, the drag-and-drop canvas, comments, presence, dashboard dark mode and Hindi i18n were done in Phase 15)
- [x] Period comparison: analytics API returns the previous period's totals; KPIs show the change (runs, success, people); totals count each run and person once
- [x] Analytics CSV download (workspace overview; a flow's days and step outcomes), formula-safe
- [x] Flow editor undo/redo (buttons and keyboard, 100 steps, typing merged, change note kept)
- [x] Phone Insights tab (History): 7/30/90 days, completion vs the period before, voice share, time, per-day chart, busiest apps, fields that need work — computed on the phone
- [x] Phone theme choice (same as phone / light / dark); English and Hindi strings for the new dashboard parts
- [x] Tests, commit + push

## Phase 28 — Security & platform polish (gaps; biometric lock, on-device-only mode, GDPR export/delete, crash reports, onboarding, widget, quick-settings tile, tablet layout and the emulator test were done in Phase 16)
- [x] Signed-in sessions: each browser/phone keeps an id and a description across token rotation (access tokens carry `sid`); list and sign out one or all others
- [x] Password change (checks the current one, signs out other sessions; migration V13 `password_changed_at`)
- [x] Per-account sign-in lockout after 10 wrong passwords for 15 minutes (on top of the per-IP limit)
- [x] Dashboard Profile → Security card; the proxy forwards the browser's User-Agent on sign-in/refresh; the phone sends `VoiceControl-Android/<version> (<model>; Android <n>)`
- [x] Phone launcher shortcuts (Insights, My flows, Get flows, Settings); emulator test independent of window focus
- [x] OpenAPI (also merged a duplicate `/v1/me` entry), privacy policy, README; tests, commit + push


## After launch — taught flows and personal assistant (from testing on a phone)
- [x] Taught flows keep every screen: a tap is matched on the screen it was made on (not the one it opened); home-screen taps are left out
- [x] Taught flows replay all screens: taps play by themselves (the last one is confirmed), taps match by name, search boxes get Enter
- [x] Personal assistant on any screen without a form: time and date, alarms, timers, YouTube / Google / Maps search, calls by contact name or number, WhatsApp messages (asks what to write, confirms, sends)
- [x] Friendlier talk: varied follow-ups ("Aur kuch?"), and the AI answers general questions in one or two spoken sentences
- [x] "Do it for me" helper: say a goal ("mujhe bijli ka bill bharna hai", "PhonePe kholo aur recharge karo") and the AI operates the app step by step (press, type, scroll, back, open apps), asks the user only for what it needs (passwords/OTP/PIN are typed by the user), confirms payments and sends, asks the user when stuck; `POST /v1/ai/agent/step` (checked against the screen, 60/min)
- [x] Hearing: on-screen names bias the recognizer (Android 13+), its other guesses are tried for button names, longer pauses allowed
- [x] Helper learns: reached goals are kept as flows with the goal as voice shortcut (asked first); a remembered way that breaks falls back to the helper; presses that changed nothing are reported to the planner
- [x] Wake phrase fixed for Android 14+: the microphone service is kept between sessions and restarted when VoiceControl opens; "voice control" works without "hey"; Settings shows what the listener is doing
- [x] Helper reads the screen: visible text (amounts, messages, errors) goes to the planner with long numbers and one-time codes masked on the phone and again on the server; it checks success before saying done; privacy policy updated
- [x] Wake phrase + request in one breath ("voice control, YouTube kholo"): the request is done right away instead of asking first
- [x] Phone controls by voice: torch, volume (up/down/full/vibrate), battery level, Wi-Fi/Bluetooth/internet/display/sound/location settings
- [x] Reminders ("raat 9 baje dawai ki yaad dilana" → labelled alarm); reading new notifications aloud (kept in memory on the phone, last 12 h); "tum kya kya kar sakte ho" tour; home screen "Things you can say"
- [x] Dashboard: while the free backend wakes up the proxy keeps trying (~2 min) instead of 502, then says the server is starting
- [x] More phone controls: home, recent apps, notification panel, quick settings, lock, screenshot, power menu, camera/selfie/video, music play/pause/next/previous, brightness (after a one-time permission), airplane/DND settings, sums ("25 guna 4", "100 ka 18 percent")
- [x] Assistant jobs (apps opened, alarms, calls, goals…) are kept in the history as an "Assistant" screen, so they reach the dashboard; no flow is made from them
- [x] Emergency: "bachao" / "emergency" calls the emergency contact (set by voice: "mera emergency contact Rahul hai"); without one it asks before calling 112; "Rahul ka number kya hai" reads the number
- [x] Notes by voice ("note karo ki…", "mere notes padho", "notes mita do"), "mere alarm dikhao", weather / news / cricket score searches, "phir se bolo" repeats the last answer, caller's name said when the phone rings (Settings → Say who is calling)

### Talks more like a person
- First words greet by name (from the profile), by time of day and about the app that is open: "Namaste Rahul ji! WhatsApp khula hai, bataiye kya karna hai?".
- "usko call karo", "call him", "uska number kya hai" use the person talked about just before; with no one yet it asks "Kisko? Naam bataiye."
- On screens without a form the AI sees the last turns of the conversation (masked), so follow-ups and small talk continue naturally.
- Barge-in (stop talking when the user speaks) is on by default.
- Usual apps: apps opened by voice are counted on the phone; when there is nothing to press it offers them ("Aap aksar WhatsApp, YouTube kholte hain…").
- "dobara call karo", "phir se phone lagao", "call again" call the last person again.

### Smart mode (Claude)
- Settings → "Smart mode (Claude)", off by default; can't be on together with On-device only.
- When on, a form screen without a saved flow is handled by the AI: it reads the screen, asks the user in its own words one value at a time, fills them in and submits once the user agrees.
- On other screens every request that isn't a plain command ("stop", "back", "next") or an exact button name goes to the AI, which decides what to press and what to ask.
- Saved flows still run as taught. Pay/send/delete are still confirmed. Passwords, OTPs and PINs are still typed by the user.
- If the AI can't be reached, smart mode switches itself off for that session and the built-in way carries on.
- The helper now always gets the user's saved profile details ("Known about the user") and the last few things they said, so it fills those without asking and understands follow-ups.
- One confirmation before pay/send/submit, in the helper's own words with the key details read back ("Rahul ko 500 rupaye bhej doon?"), instead of a generic "Press Pay?".
- No greeting after a job is done; friendlier, non-technical wording in the helper's prompt.
- Tested against the real model (backend `LiveAgentScenariosTest`, runs only with `VC_ANTHROPIC_API_KEY`): electricity bill, sign-up form with saved details, WhatsApp message, "usko 500 bhejo" follow-up all finished correctly (~2.5–3 s per step).
- Fixes from that run: a payment is confirmed once (the PIN screen's "Submit"/"Confirm payment" right after isn't asked again), and no "paying now" line is spoken before "shall I pay?".

### Search boxes
- A search box is never named by what is typed in it (some apps put the typed text in the description too); a search box without a real caption is called "Search". SearchView-type fields count as search boxes.
- Enter: Android 11+ presses the keyboard's Enter/Search key; where there is none (Android 8–10, some apps) the Search/Go button next to the box is pressed instead.
- Smart mode knows to press the search box itself after typing when there is no search button (checked with the real model: "YouTube pe kesariya gaana chalao" typed, searched and played).
