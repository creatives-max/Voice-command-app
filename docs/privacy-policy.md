# VoiceControl privacy policy

_Last updated: 1 October 2026_

VoiceControl helps you fill forms and press buttons in other Android apps using your voice. This policy explains what
data the app and its server handle and how you control it. It is published at `https://<dashboard>/privacy` and in the
app under **Settings → Privacy policy**.

## What VoiceControl reads on your screen

With the VoiceControl accessibility service turned on, the app reads the **labels, types and current values of input
fields and buttons** on the app in front of you. This is needed to ask you what to fill, type your answers, and press
buttons you name.

- The contents of **password, OTP, PIN and CVV fields are never read, spoken, logged, stored or sent**. VoiceControl
  asks you to type those yourself.
- Screen reading happens only to serve the voice session or the touch panel you open.

## Microphone

Audio is captured only while a voice session is running, or — if you turn on **Wake phrase** — while VoiceControl
waits for your phrase; a persistent notification ("VoiceControl is listening" / "Waiting for …") shows when that is
the case, and you can pause it from the notification. Speech is converted to text by your phone's speech recognition
service (on-device when available for the wake phrase); wake-phrase transcripts are only compared with your phrase and
discarded. With **Interrupt by speaking** on, the microphone level is checked while questions are spoken to notice
when you start talking. VoiceControl does not record or keep audio.

## Data sent to the VoiceControl server

Only when you are **signed in** and **local-only mode is off**:

| Data | Purpose |
|---|---|
| Field labels/types of the current screen and the text you spoke | Understand your answer (AI interpretation) |
| Your earlier answers in the same session (field label and value; never passwords, OTPs, PINs or card numbers) | Understand references like "same as above" |
| Screen structure signature (labels and types, no values) | Find and update your saved flows |
| Screenshot (only with **Screenshot fallback** on, only for apps with no readable fields) | Detect fields and buttons |
| Flows: labels, questions, rules, defaults you add | Flow library and dashboard editing |
| History: what happened to each field (filled, skipped, typed by you), no values | Your history page |
| Profile details you enter (name, email, phone, address…) | Offer them when a form asks |
| Flows you publish to the marketplace: name, description, app, steps (never default values) and your display name; ratings you give | Flow marketplace |
| Phone name, app version and last-seen time; remote-run logs (step labels and outcomes, no values) | Run flows from the dashboard, on schedules or when an app opens |

## AI providers

The server may use an AI provider (Anthropic or an OpenAI-compatible API) to interpret speech and screenshots. **Your
phone never contacts these providers directly.** Requests are sent under the providers' commercial API terms; under
those terms, API inputs are not used to train their models by default, and we do not use them for advertising.

## Storage and security

- Server data is stored in PostgreSQL; sessions and caches in Redis. Passwords are stored as bcrypt hashes.
- Access tokens on the phone are encrypted with a key held in the Android Keystore; the dashboard keeps tokens in
  httpOnly, SameSite=Strict cookies.
- All traffic uses HTTPS. Observability traces never include request bodies or authorization headers.

## Your controls

- **Local-only mode**: nothing leaves the phone; understanding runs on-device.
- **History**: turn off in Settings; delete single sessions or all history in the app or dashboard.
- **Flows and profile**: edit or delete any time in the app or dashboard.
- **Account deletion**: email us; all server data is erased within 30 days, including backups.

## Children

VoiceControl is not directed at children under 13 and does not knowingly collect their data.

## Contact

privacy@voicecontrol.app
