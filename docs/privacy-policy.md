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

## Teach by doing

While you record a flow by touch, VoiceControl notes which fields you type into and which buttons you press. What
you typed stays in the phone's memory until you review the recording; it is saved only for fields you tick as
defaults, and never for password, OTP, PIN or CVV fields.

## Remembered answers

If you turn on *Remember my answers*, the last answer you gave for each field of each app is kept on your phone so
VoiceControl can offer it next time. These answers never leave the phone (not synced, not sent to the server), are
never kept for password, OTP or PIN fields, can be viewed and deleted under *Settings → Remembered answers*, and are
deleted when you turn the setting off or wipe the phone. They are included in the phone's data export.

## Caregivers

You can let someone you trust (a caregiver) set up your flows from the VoiceControl website. Nothing is shared
until you create an invite code on your phone and give it to them; the code works once, for 30 minutes. You choose
what they may do (edit flows and when they run, run flows on your phone, see your run history) and can change or
end it at any time; they can always see your flows, phones and runs, but never your profile, passwords or what you
typed. Everything a caregiver does is recorded and shown to both of you, and is part of your data export.

## Fill from a photo

When you use *Fill from a photo*, the photo is read on your phone by an on-device text recogniser. It is never
uploaded, it is deleted as soon as it has been read, and the scan screen is kept out of screenshots. The values found
(for example your name, date of birth or PAN) stay in the phone's memory only until you review them and they are
filled; Aadhaar and bank account numbers are shown masked unless you tap *Show*. Nothing is saved.

## Voice shortcuts

Phrases you add on the phone stay on the phone. Phrases you add on the dashboard are stored with your account
so your phones can use them. When you say one, VoiceControl compares what you said with your phrases on the phone;
nothing extra is sent. Runs started this way show in the flow's run log (step names only, never values).

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
| Organizations you create or join: name, members' email, name and role, invitations (email, role) | Share flows with a team |
| Audit log of organization changes (who changed which flow, member, API key or webhook, and when) | Accountability for organization admins |
| Comments you write on flows; which flow you have open on the dashboard (kept about a minute) | Collaboration with your team |
| Crash reports (only with **Send crash reports** on): error type, code location, app and Android version, phone model; numbers and email addresses are removed on the phone and again on the server | Fixing crashes |

## Organizations, API keys and webhooks

Flows you move into an organization are visible to all its members, and editors and admins can change them;
their phones use those flows too. Organization admins can create **API keys** for other services (only a hash of
each key is stored) and **webhooks** that send flow and run events (ids, names, app, status — never spoken or typed
values) to an address the admin chooses. Leaving an organization removes your access to its flows; deleting it
removes its flows, keys, webhooks and audit log. Flow analytics show organization members only totals (runs,
success rate, how each step went) built from everyone's history — never who ran what or any values.

## AI providers

The server may use an AI provider (Anthropic or an OpenAI-compatible API) to interpret speech and screenshots. **Your
phone never contacts these providers directly.** Requests are sent under the providers' commercial API terms; under
those terms, API inputs are not used to train their models by default, and we do not use them for advertising.

## Storage and security

- Server data is stored in PostgreSQL; sessions and caches in Redis. Passwords are stored as bcrypt hashes.
- Each signed-in session stores a short description of the browser or app (from its User-Agent, e.g. “Chrome on
  Windows” or “VoiceControl app 1.4.0 on Android”), when it signed in and when it was last used, so you can review
  and end sessions on the dashboard (Profile → Security). It is deleted when the session ends.
- Access tokens on the phone are encrypted with a key held in the Android Keystore; the dashboard keeps tokens in
  httpOnly, SameSite=Strict cookies.
- All traffic uses HTTPS. Observability traces never include request bodies or authorization headers.

## Your controls

- **On-device only**: nothing leaves the phone; understanding runs on-device and flows are edited in the app.
- **App lock**: require your fingerprint, face or screen lock to open VoiceControl; its screens are then hidden from
  screenshots and the recent-apps preview.
- **Export your data**: Settings → *Export my data* saves everything on the phone (and, when signed in, everything
  on the server) as a JSON file; the dashboard's Profile page downloads the server copy.
- **History**: turn off in Settings; delete single sessions or all history in the app or dashboard.
- **Flows and profile**: edit or delete any time in the app or dashboard.
- **Account deletion**: Profile → *Delete my account* (app or dashboard, password required) erases your account and
  data immediately; backups are overwritten within 30 days. *Delete everything on this phone* wipes the app's data
  without touching your account.

## Children

VoiceControl is not directed at children under 13 and does not knowingly collect their data.

## Contact

privacy@voicecontrol.app
