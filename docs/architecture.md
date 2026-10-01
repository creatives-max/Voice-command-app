# VoiceControl architecture

## System overview

```
┌──────────────────────── Android device ────────────────────────┐
│  Target app (any)                                               │
│      ▲  AccessibilityNodeInfo tree / actions / gestures         │
│  VoiceControlAccessibilityService ──► ScreenParser (core:screen)│
│      │                                   │ ScreenSnapshot       │
│  Overlay mic button (feature:assistant)  ▼                      │
│      │                         AssistantEngine (core:engine)    │
│      │      TTS ◄── asks ──────┤  MVI state machine             │
│      │      STT ── speech ────►│  local commands (core:nlp)     │
│      │                         │  interpret ──► backend /v1/ai  │
│      │                         │  flow match ─► backend /v1/flows/match
│      ▼                         ▼                                │
│  Room (history, cached flows), DataStore (settings, tokens)     │
└─────────────────────────────────┬───────────────────────────────┘
                                  │ HTTPS + JWT
┌─────────────────────────────────▼───────────────────────────────┐
│ Backend (Ktor)                                                   │
│  api ─► application (use cases) ─► domain (entities + ports)     │
│                     ▲                                            │
│  infrastructure: Postgres+Flyway+pgvector, Redis (cache,         │
│  sessions, Streams event bus), LLM providers, embeddings, OTel   │
└─────────────────────────────────▲───────────────────────────────┘
                                  │ same-origin /api proxy
┌─────────────────────────────────┴───────────────────────────────┐
│ Dashboard (Next.js shell + TanStack Router SPA + TanStack Query) │
└──────────────────────────────────────────────────────────────────┘
```

## Android modules

| Module | Kind | Responsibility |
|---|---|---|
| `:core:model` | JVM | Serializable domain models (screen elements, flows, actions, profile, history) |
| `:core:common` | JVM | Dispatchers, result types |
| `:core:screen` | JVM | `UiNode` abstraction, `ScreenParser`, stable ID generation, sensitive-field masking, screen signature |
| `:core:nlp` | JVM | Hindi/English/Hinglish normalization (digits, number words, email/phone dictation), command parser |
| `:core:engine` | JVM | Ports (`ScreenGateway`, `SpeechToText`, `TextToSpeech`, `Interpreter`, `FlowSource`) and the `AssistantEngine` state machine |
| `:core:accessibility` | Android | `AccessibilityService`, `AccessibilityNodeInfo` → `UiNode` adapter, `ActionExecutor` (set text, click, scroll, back, gestures, screenshots) |
| `:core:voice` | Android | `SpeechRecognizer` + `TextToSpeech` implementations of the engine ports |
| `:core:network` | Android | Ktor client, DTOs, auth token refresh |
| `:core:data` | Android | Room DB, DataStore settings, encrypted token store, repositories |
| `:core:ui` | Android | Material 3 theme + shared composables |
| `:feature:*` | Android | `home`, `inspector`, `assistant` (overlay + session), `auth`, `flows`, `history`, `settings` |

Each feature screen follows MVI: an immutable `UiState`, a sealed `Intent` (user actions) and a
`ViewModel` that reduces intents into new state, plus one-off `Effect`s through a `Channel`.

Pure logic lives in JVM modules, so it is unit-tested without an emulator.

## Backend modules (clean architecture)

| Module | Depends on | Contents |
|---|---|---|
| `domain` | – | Entities (`User`, `Profile`, `Flow`, `FlowVersion`, `FlowStep`, `Run`), port interfaces |
| `application` | domain | Use-case services: auth, profile, flows, matching, AI interpretation, history |
| `infrastructure` | application | JDBC repositories (Postgres), Flyway migrations, Redis cache/sessions/streams, LLM + embedding providers |
| `api` | infrastructure | Ktor routes, JWT, validation, error mapping, OpenAPI/Swagger, OpenTelemetry, composition root |

### Event-driven flow processing

Saving a flow writes a new immutable `flow_versions` row and publishes a `FlowVersionSaved` event
to a Redis Stream (`vc:events:flows`). A consumer group (`flow-processor`) in the backend:

1. builds the screen signature text for the version,
2. computes its embedding (pluggable provider) and stores it in `flow_versions.embedding` (pgvector),
3. invalidates the cached match results for that app package.

Retries use the stream's pending-entries list. Messages are acknowledged only after processing succeeds.

### Screen → flow matching

`POST /v1/flows/match` receives the app package and the masked screen elements. The backend builds
the same signature text (activity + field labels + types + button labels), embeds it, and runs a
cosine-distance nearest-neighbour query restricted to that user's flows for the package:

```sql
SELECT ... FROM flow_versions v JOIN flows f ON f.current_version_id = v.id
WHERE f.user_id = ? AND f.app_package = ?
ORDER BY v.embedding <=> ?::vector LIMIT 3
```

A match above the similarity threshold returns the current (possibly dashboard-edited) version.
Results are cached in Redis by a hash of the signature.

## Privacy

- Password, OTP, PIN and CVV fields are detected on-device and their values are never read,
  spoken, logged, stored or sent. Only `type=password` metadata leaves the device.
- Typed values are not sent to the backend in flow definitions unless the user saves them as a
  default. Profile values are stored server-side only with consent.
- Local-only mode turns off all backend calls. It uses the on-device rule interpreter.

## History, settings and vision fallback

- **History**: every finished session is a `SessionSummary` (per screen: what happened to each element: filled, default,
  kept, skipped, typed by user, clicked). It never contains values. Stored in Room, uploaded in batches to
  `POST /v1/runs` (idempotent by session id), shown in the app and on the dashboard.
- **Settings** (DataStore): language (English/Hindi/Hinglish), speech rate, read-back, confirm-before-submit,
  skip filled fields, Hindi→Latin transliteration, auto-start on screens with saved flows, history on/off,
  local-only mode, screenshot fallback, backend and dashboard addresses.
- **Vision fallback**: when a screen exposes no readable accessibility nodes and the user enabled it, the engine takes a
  screenshot (`AccessibilityService.takeScreenshot`, API 30+), downscales it, and `POST /v1/ai/vision` returns element
  boxes. Those elements get `vision:` ids; the engine operates them with taps (`dispatchGesture`) and types into the
  focused input. Requires a vision-capable provider (`anthropic` or `openai`).

## Flow logic (conditions, variables, loops, multi-screen)

Flows are more than an ordered list of questions:

- **Expressions** (`docs/spec/expressions.json`): a small language with `== != < <= > >= + - * / %`, `and/or/not`,
  and functions (`yes`, `empty`, `contains`, `concat`, `upper`, `digits`, `if`, `today`, …). The phone engine, the
  backend validator and the dashboard simulator each implement it and run the same test vectors.
- **Variables**: every answer is stored under the step's `variable` (default: the label in snake_case). Profile values
  are `profile.name`, `profile.city`, …; values already on screen are available by label. Sensitive fields never
  become variables.
- **Conditional steps**: `condition` decides whether a step runs; `elseValue` is filled when it doesn't (if/else).
  `valueExpression` fills a computed value without asking. Questions can contain `{variable}` templates.
- **Logic steps**: `READ` stores on-screen text, `SET_VARIABLE` computes a value, `REPEAT` runs a group of steps per list
  item (count from an expression, or "add another?") and presses the "add more" button between items.
- **Multi-screen / cross-app**: `NEXT_SCREEN` and `OPEN_APP` split a flow into screens. The engine waits for the screen
  to change (or launches the app via `ScreenAction.LaunchApp`) and continues; variables carry across screens.
- **Dry run**: the dashboard's *Test run* panel simulates the edited flow without a phone, from typed answers.

## Triggers, schedules and remote runs

- **Devices**: a signed-in phone registers itself (`POST /v1/devices`, a random id per installation) and, while its
  accessibility service runs, long-polls `GET /v1/devices/{id}/commands?wait=25` for flows to run or stop. Long polling
  works through proxies and across backend replicas (state is in Postgres).
- **Run requests** (`run_requests` + `run_request_events`): created by "Run now" in the dashboard, by schedules, or by
  the phone when an app-open trigger fires. The phone reports status and value-free log lines (labels and outcomes —
  never spoken, typed or profile values); the dashboard follows them with a long-polled live log. Undelivered requests
  expire after 10 minutes.
- **Triggers** (`flow_triggers`): `APP_OPEN` triggers are sent to the phone (cached for offline use) and evaluated there
  on every foreground-app change; `SCHEDULE` triggers are 5-field cron expressions in an IANA time zone. Every replica
  runs the scheduler; each tick claims due rows with `SELECT … FOR UPDATE SKIP LOCKED`, creates the run request and
  advances `next_run_at` in one transaction, so each due time fires exactly once.

## Marketplace and starter templates

- **Publishing** copies a flow's current version into `published_flows` / `published_flow_versions` with default values
  removed (they may be personal). Publishing the same flow again adds a version. Listings are searchable with Postgres
  full-text search (weighted name, description, app and tags), filterable by category/app, sortable by imports, rating
  or recency, and rated 1–5 stars (one rating per user, not on your own listing).
- **Importing** creates a flow in the importer's account (or a new version of their flow for the same screen) that
  remembers `source_published_id` + `source_version`; when the publisher releases a newer version the dashboard offers
  "update from source", applied as a new version (history keeps the old steps).
- **Starter templates** (sign-up, login, address, OTP-less sign-up, contact, payment) live in
  `backend/application/src/main/resources/templates/starter-templates.json`, seeded idempotently at startup and also
  bundled into the app. Each step has keywords; on screens without a saved flow the phone fits the best template to
  the screen (`TemplateMatcher`), and the dashboard can apply a template to an existing flow. Both matchers share
  `docs/spec/template-matching.json`.

## Voice upgrades

- **Streaming partials + early commands**: the overlay shows partial transcripts live; when a partial is a control
  command (stop, next, skip, haan…) and stays unchanged for 500 ms, listening ends right away instead of waiting for
  the recognizer's end-of-speech timeout.
- **Barge-in** (setting): while a question is spoken, `AndroidSpeechDetector` listens through the voice-communication
  source with platform echo cancellation; `SpeechOnset` calibrates on the assistant's own echo and declares speech
  after ~240 ms clearly above it. TTS stops and the answer is heard immediately. Audio is analysed for loudness only.
- **Wake phrase** (setting): while idle, `WakeWordListener` runs on-device-preferred recognition in a loop and matches
  the user's phrase approximately and across scripts (`WakeWord`), then starts a session. It runs under the
  microphone foreground service (with its own notification) and yields the recognizer to tap-started sessions.
- **Confidence re-ask**: when the recognizer's confidence is below 0.5, the assistant asks "Did you say …?" first.
- **Context memory**: answers given earlier in the session (never sensitive values) are remembered; `ContextResolver`
  resolves "same as above", "same as permanent address", "my email", "वही", "அதே", "అదే", "একই", "એ જ" on the
  device, and the last 12 answers are sent to the backend LLM (secrets filtered again server-side) for harder references.
- **Languages**: English, Hindi, Hinglish, Marathi, Tamil, Telugu, Bengali and Gujarati. Each has its phrases,
  command words, number words and native digits; names/emails/addresses dictated in any of these scripts can be
  transliterated to Latin letters (Bengali/Gujarati/Tamil/Telugu map onto Devanagari by Unicode block, keeping
  Telugu/Tamil final vowels).

## Observability

- OpenTelemetry SDK (autoconfigured) with Ktor server instrumentation; spans for every request and for each event
  handled from the Redis stream. `OTEL_EXPORTER_OTLP_ENDPOINT` turns on OTLP export.
- Logs are JSON (`LOG_FORMAT=JSON`) via logstash-logback-encoder with `trace_id`, `span_id` and `requestId` in every
  line, so logs and traces correlate.
- Collector config: `infra/k8s/base/collector.yaml` (drops auth headers/bodies, batches, exports to Jaeger/OTLP).
  Locally: `docker compose --profile observability up` → Jaeger UI at http://localhost:16686.
- Health: `/health` (liveness), `/ready` (Postgres + Redis) used by Kubernetes probes.

## Deployment (Kubernetes)

`infra/k8s/base` (Kustomize): namespace, ConfigMap + Secret template, Postgres (pgvector) and Redis StatefulSets,
backend Deployment (2+ replicas, HPA, PDB, non-root read-only root FS, readiness on `/ready`), dashboard Deployment +
HPA, OTel collector, Ingress (TLS via cert-manager), NetworkPolicy (datastores reachable only from the backend).
`infra/k8s/overlays/production` pins image tags and scales the backend. Render with `kubectl kustomize
infra/k8s/overlays/production`. CI validates the manifests with kubeconform.

Backend replicas are stateless: sessions, rate limits, caches and the event stream live in Redis; each replica joins
the same consumer group, so event processing is shared and at-least-once.
