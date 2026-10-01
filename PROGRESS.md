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
- [ ] Android settings + run history screens; backend run history API + dashboard history
- [ ] Screenshot + vision fallback for apps without readable fields
- [ ] Privacy policy + Play Store notes
- [ ] Kubernetes manifests in `/infra/k8s`
- [ ] OpenTelemetry tracing/logging (backend + collector in compose)
- [ ] Tests, commit + push
