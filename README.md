# VoiceControl

VoiceControl lets you operate **any Android app by voice**. An Android
AccessibilityService reads the current screen (input fields and buttons), asks you by voice
what to fill, listens in **Hindi, English or Hinglish**, types the answer into the right field,
and presses buttons when you say *next*, *submit*, *back* or *scroll*.

Every run can be saved as a **flow**. From the web dashboard you can edit a flow's question text,
validation rules, default values, skipped steps, step order and a help video per step. The next
time that app opens, the edited flow runs.

| Path         | What                                                                                   |
|--------------|----------------------------------------------------------------------------------------|
| `/android`   | Kotlin, Jetpack Compose + Material 3, MVI, multi-module, Hilt, Room, Ktor, DataStore   |
| `/backend`   | Kotlin + Ktor, clean architecture, JWT, PostgreSQL + Flyway + pgvector, Redis, OpenAPI |
| `/dashboard` | Next.js + TypeScript, Tailwind + shadcn/ui, TanStack Query + TanStack Router           |
| `/infra`     | Kubernetes manifests (Kustomize), OpenTelemetry collector config                       |
| `/docs`      | Architecture, API, privacy policy, Play Store notes                                    |

## Quick start (local)

```bash
cp .env.example .env            # optional: choose LLM provider + keys
docker compose up --build       # Postgres(pgvector) + Redis + backend + dashboard
```

- Backend: http://localhost:8080 (`/health`, OpenAPI UI at `/docs`)
- Dashboard: http://localhost:3000

### Android

```bash
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # unit tests
```

Install the APK, open **VoiceControl**, then enable the accessibility service
(*Settings → Accessibility → VoiceControl*). A floating mic button appears over other apps; tap it
to start the voice session for the current screen. In the emulator the default backend URL is
`http://10.0.2.2:8080`. It can be changed in the app's settings.

### Backend

```bash
cd backend
./gradlew test                   # unit + integration tests (Testcontainers: needs Docker)
./gradlew :api:run               # needs Postgres + Redis (docker compose up postgres redis)
```

### Dashboard

```bash
cd dashboard
npm install
npm run dev                      # BACKEND_URL defaults to http://localhost:8080
```

## Configuration (backend)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | local compose values | PostgreSQL (with pgvector) |
| `REDIS_URL` | `redis://localhost:6379` | Cache, sessions, event stream |
| `JWT_SECRET` | (required in prod) | HMAC secret, ≥ 32 chars |
| `LLM_PROVIDER` | `rules` | `rules` (offline), `anthropic`, `openai` |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | – | Anthropic provider |
| `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_BASE_URL` | – | OpenAI-compatible provider |
| `EMBEDDING_PROVIDER` | `hashing` | `hashing` (offline n-gram embedding) or `openai` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | Enables OTLP trace export |

The phone **never** calls an LLM directly. Only the backend holds provider keys.

## CI

`.github/workflows/ci.yml` builds the Android app (debug APK, unit tests, lint), runs the backend
tests (Testcontainers), builds the dashboard (lint, typecheck, tests, build), and builds both Docker
images.

See [`docs/architecture.md`](docs/architecture.md) for the full design and [`PROGRESS.md`](PROGRESS.md)
for phase-by-phase status.
