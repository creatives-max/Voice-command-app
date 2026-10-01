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
- [ ] Organizations, memberships, invitations, RBAC (admin/editor/viewer), per-org flows
- [ ] Audit logs
- [ ] API keys + per-key rate limiting
- [ ] Webhooks on flow events (signed, retried, delivery log)
- [ ] Kafka event bus with Redis Streams fallback
- [ ] Dashboard: org switcher, team, API keys, webhooks, audit log
- [ ] Tests, commit + push

## Phase 15 — Analytics & visual builder
- [ ] Per-flow usage + success analytics API and dashboard charts
- [ ] Drag-and-drop visual flow builder (canvas)
- [ ] Flow comments + presence
- [ ] Dashboard dark mode + i18n (English, Hindi)
- [ ] Tests, commit + push

## Phase 16 — Security, platform & polish
- [ ] Biometric app lock
- [ ] On-device-only mode completeness (on-phone flow editing)
- [ ] GDPR export + account deletion (backend, dashboard, app)
- [ ] Crash reporting (self-hosted)
- [ ] Onboarding tutorial with practice form
- [ ] Home-screen widget + quick-settings tile
- [ ] Tablet layout
- [ ] Emulator end-to-end test in CI
- [ ] Tests, commit + push
