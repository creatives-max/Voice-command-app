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
- [ ] Caregiver links, consent, remote flow setup, tests, commit + push

## Phase 22 — Offline + regional languages (gaps; six languages were done in Phase 12)
- [ ] Offline speech packs, tests, commit + push

## Phase 23 — Accessibility narrator (gaps; Phase 13)
- [ ] Gap-fill, tests, commit + push

## Phase 24 — Voice & AI upgrades (gaps; Phase 12)
- [ ] Gap-fill, tests, commit + push

## Phase 25 — Flow marketplace + templates (gaps; Phase 11)
- [ ] Gap-fill, tests, commit + push

## Phase 26 — Teams & multi-tenant backend (gaps; Phase 14)
- [ ] Gap-fill, tests, commit + push

## Phase 27 — Analytics & visual builder (gaps; Phase 15)
- [ ] Gap-fill, tests, commit + push

## Phase 28 — Security & platform polish (gaps; Phase 16)
- [ ] Gap-fill, tests, commit + push

