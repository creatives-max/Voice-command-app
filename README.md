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

- Backend: http://localhost:8080 (`/health`, `/ready`, OpenAPI UI at `/docs`, spec at `/openapi`)
- Dashboard: http://localhost:3000 (sign up, then sign in with the same account on the phone)
- Tracing (optional): `docker compose --profile observability up` → Jaeger at http://localhost:16686

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
| `LOG_FORMAT` | `TEXT` | `JSON` for structured logs with trace ids |
| `AUTH_RATE_LIMIT_PER_MINUTE` | `10` | Register attempts per IP per minute (login: 2×) |
| `CORS_ORIGINS` | `http://localhost:3000` | Allowed browser origins |
| `SCHEDULER_INTERVAL_SECONDS` | `15` | Schedule-trigger check interval (0 = off on this replica) |
| `KAFKA_BOOTSTRAP_SERVERS` / `KAFKA_TOPIC` | unset / `vc.events` | Flow events on Kafka; unset = Redis Streams only. Redis stays the fallback queue |
| `WEBHOOK_INTERVAL_SECONDS` | `5` | Webhook delivery interval (0 = off on this replica) |
| `WEBHOOK_ALLOW_HTTP` / `WEBHOOK_ALLOW_PRIVATE` | `false` | Development only: allow `http://` and private/loopback webhook URLs |

The phone **never** calls an LLM directly. Only the backend holds provider keys.

## CI

`.github/workflows/ci.yml` builds the Android app (debug APK, unit tests, lint), runs the backend
tests (Testcontainers), builds the dashboard (lint, typecheck, tests, build), and builds both Docker
images.

## Using it

1. Install the APK, open VoiceControl, allow the microphone and turn on the accessibility service.
2. (Optional) Sign in: Home → *Sign in*. Set the backend address in Settings if it's not the emulator default.
3. Open any app with a form and tap the floating mic. VoiceControl asks for each field; answer in Hindi, English or
   Hinglish. Say *next*, *previous*, *skip*, *repeat*, *submit*, *scroll*, *back*, *stop*, or *press <button>*.
   Long-press the mic for a touch panel of every field and button.
4. Each completed screen is saved as a flow. Edit it on the dashboard (questions, rules, defaults, skips, order,
   help videos). The next time that screen opens, VoiceControl runs the edited flow.

## Teams, API keys and webhooks

- **Organizations**: create one on the dashboard's *Organization* page and invite people by email (one-time link,
  7 days). Roles: *admin* (members, API keys, webhooks, audit log, publish), *editor* (change flows), *viewer*
  (read and run). Pick the organization in the sidebar's workspace switcher; move a flow in with *Move*. Members'
  phones match the organization's flows too.
- **API keys** (`X-Api-Key: vck_…`): scoped (`flows:read`, `flows:write`, `runs:write`), limited to their
  organization, rate limited per key, revocable. A key acts as its creator and stops working if they leave.
  Keys can expire (30/90/180/365 days) and be *rotated*: a new secret is issued and the old one stops at once.
- **Webhooks**: `flow.version_saved`, `flow.deleted`, `run.finished` POSTed as JSON, signed with
  `X-VoiceControl-Signature: t=<unix>,v1=<HMAC-SHA256(secret, "t.body")>`, retried with backoff; the dashboard shows
  each delivery and can redeliver. Details in the OpenAPI docs at `/docs`.
- **Audit log** of member, flow, key and webhook changes for admins, filterable by date range and downloadable as
  CSV (`GET /v1/orgs/{id}/audit/export`, up to 10 000 rows; cells are quoted so spreadsheets never run formulas).
- **Usage and limits**: the Organization page shows members, pending invitations, API keys, webhooks and flows
  against the per-tenant limits (`ORG_MAX_MEMBERS`, default 200; `ORG_MAX_API_KEYS`, default 25).

## Analytics, visual builder and collaboration

- **Analytics** (sidebar): runs per day, success rate, people and last run for every flow in the workspace; per flow
  also average time, how each step went (filled by voice, typed by hand, skipped…) with the worst steps first, how
  answers were understood and remote-run outcomes. Built from the run history phones upload (no values).
  Headline numbers show the change against the same number of days before ("+20% vs the 30 days before"), and
  *Download CSV* saves every flow's usage, or a flow's runs per day and step outcomes, for spreadsheets.
- **Insights on the phone** (History → Insights): sessions, completion, fields filled by voice, time spent, sessions
  per day, busiest apps and the fields you often type by hand or skip — worked out on the phone, nothing is sent.
- **Canvas** view in the flow editor: steps as nodes, one column per screen, loop edges for repeats; drag a step onto
  another to reorder, drag logic steps from the palette, click a node to edit it. Positions are shared.
- **Undo / redo** in the flow editor (buttons, or Ctrl/⌘+Z and Ctrl/⌘+Shift+Z): up to 100 edits; typing in one
  field counts as one edit.
- **Light or dark** on the phone too: Settings → Look (same as phone, light, dark).
- **Comments** on a flow or a step (resolve, edit, delete) and **presence**: see who else has the flow open or is
  editing, and get told when someone saves a newer version.
- **Dark mode** (system/light/dark) and **Hindi** in the sidebar.

## Teach by doing

Instead of answering by voice the first time, show VoiceControl what to do: tap the red record button in the
floating panel (or the record icon in *Saved flows*), fill the form by touch across screens and apps, then tap the
red bubble. Review the steps, choose which typed values to keep as defaults (never passwords, OTPs or PINs) and save.
Next time VoiceControl asks for each field in that order.

## Marketplace on the phone

*Saved flows → Get flows* (store icon) lists flows other people shared: search, filter by category, sort by most
used, best rated or newest, read what each flow does step by step, install it (or update your copy), rate it, or
report it. Listings reported by three people are hidden until their author publishes a fixed version.

## Voice memory and wake phrase test

*Remember my answers* (Settings, off by default) keeps the last answer per app and field on the phone and offers it
next time: "Last time you said Pune. Say yes to use it again", or say "same as last time" / "pichhli baar wala".
Review or forget them under *Remembered answers*; passwords, OTPs and PINs are never kept. Next to the wake phrase,
*Test* listens once and tells you whether what it heard would wake VoiceControl.

## Narrator (screen reader mode)

Tap the read-aloud button in the panel or say "read screen": VoiceControl reads the title, text and controls in
order (never private values). Say *next*/*previous*, *next button*/*next field* (*agla khana*), *top*/*bottom*,
*select* to press, toggle or answer, *read all* or *read everything* (keeps scrolling to the end of the page),
*where am I*, *find <words>* (*OTP kahan hai*), *faster*/*slower*, *undo* or *stop*. Risky buttons (pay, delete…)
are confirmed first.

## Offline languages

*Settings → Offline languages* shows, for English, Hindi, Hinglish, Marathi, Tamil, Telugu, Bengali and Gujarati,
whether listening (on-device recognition pack) and speaking (TTS voice) work without internet, with *Download* and
*Get voice* buttons (Android 13+ downloads speech packs in the background; older versions open the system voice
settings). *Speech on the phone* uses the downloaded packs even when online; without internet a session switches to
on-device speech by itself and says so if the language pack is missing. Understanding answers is always offline.

## Caregivers (remote help, with consent)

A son, daughter or friend can set up flows for someone else's phone. On the phone, *Settings → Caregivers → Create
a code* (choose what the helper may do: edit flows, run flows, see history). The helper enters the code on the
dashboard (*Caregiving*), then picks the person in the workspace switcher: Flows, triggers, devices, runs and the
marketplace then work on that person's account (`X-Care-Link`), within the granted permissions. Edits are saved as
*by caregiver* versions; everything is logged on the link, visible to both, and either side can end it at once.

## Fill from a photo (OCR)

On a form, open the VoiceControl panel and tap the document button: take or choose a photo of a PAN card, Aadhaar
card, driving licence, voter ID, passport, cheque/passbook, bill or letter. The text is read on the phone (ML Kit,
Devanagari and Latin scripts), values are matched to the form's fields by type and label (English and Indian
languages), and you review them — masked numbers, untick or correct anything — before they are filled. Password,
OTP and PIN fields are never filled; photos are deleted right after reading.

## Voice shortcuts (voice macros)

Say a phrase to run a flow: "pay electricity bill", "बिजली का बिल". Add phrases in a flow's details on the phone
(kept on the phone) or as a *When I say…* trigger on the dashboard (synced to your phones, unique per account).
Tap the mic (or say the wake word) on any screen without a form and say the phrase: VoiceControl opens the flow's
app and runs it. Polite words ("please … karo") and small recognition slips are fine; a button on screen with the
same name always wins.

## Test runs (dry run)

Try a flow without touching the app: *Test* in a flow's details (or while editing it on the phone), the
dashboard's test mode, or `POST /v1/flows/{id}/dry-run` with scripted `answers`. All three walk conditions, loops,
variables, rules and screen/app changes identically (shared vectors in `docs/spec/simulation.json`) and return the
transcript plus the next pending question.

## Security, privacy and platform

- **App lock** (Settings): fingerprint, face or screen lock to open the app, re-locks after a chosen time in the
  background; screens are kept out of screenshots while it is on.
- **On-device only**: no server at all; flows are edited in the app (questions, defaults, skips, order).
- **Your data**: export everything as JSON (app Settings, dashboard Profile); delete the account (app Profile,
  dashboard Profile) or wipe the phone.
- **Crash reports** (opt-in): scrubbed of numbers and emails, stored by the backend (`/v1/crashes`), grouped on the
  dashboard's Devices page.
- **First-run tutorial** with a practice form the accessibility service can fill, a **home-screen widget** and a
  **quick-settings tile** to start talking, and a navigation rail with list-detail flows on tablets.
- CI runs the app on an **Android emulator** (`android-e2e` job, `./gradlew :app:connectedDebugAndroidTest`).

## Kubernetes

```bash
kubectl apply -k infra/k8s/overlays/production   # after creating the real backend-secrets
```

See [`docs/architecture.md`](docs/architecture.md) for the design, [`docs/privacy-policy.md`](docs/privacy-policy.md),
[`docs/play-store.md`](docs/play-store.md) for release steps, and [`PROGRESS.md`](PROGRESS.md) for phase status.
