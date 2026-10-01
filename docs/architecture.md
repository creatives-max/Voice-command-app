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
