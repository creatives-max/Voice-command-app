# Google Play release notes

Checklist and declarations for publishing VoiceControl on Google Play.

## Build

```bash
cd android
export VC_KEYSTORE_PATH=/secure/voicecontrol.jks VC_KEYSTORE_PASSWORD=… VC_KEY_ALIAS=voicecontrol VC_KEY_PASSWORD=…
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab
```

- `targetSdk 35`, `minSdk 26`. R8 + resource shrinking enabled for release.
- Set the production backend/dashboard addresses: change `DEFAULT_BACKEND_URL` / `DEFAULT_DASHBOARD_URL` in
  `core/network/build.gradle.kts` (HTTPS only: cleartext is allowed for emulator/localhost hosts only).
- Enroll in Play App Signing; keep the upload key out of the repository.

## AccessibilityService policy (most important)

Google Play requires a prominent disclosure and a clear core use for accessibility APIs.

1. **Declaration form** (Play Console → App content → Accessibility API): VoiceControl's core functionality is
   *operating other apps by voice for users who cannot easily type or touch the screen* (motor, visual and literacy
   accessibility). Describe: reading fields/buttons, typing answers, pressing buttons, scrolling, going back.
2. **`isAccessibilityTool`** is `false` (the app also serves general convenience use), so the full declaration and
   in-app disclosure are required.
3. **In-app prominent disclosure** before enabling the service: the Home screen card explains what is read and why,
   and links to the privacy policy; the service description (`vc_accessibility_description`) repeats it in system
   settings.
4. **Video**: record a short demo for the declaration (enable service → open a form → fill by voice → submit).
5. Sensitive fields (password/OTP/PIN/CVV) are never read or filled; mention this in the declaration.

## Permissions

| Permission | Why | Notes |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Read and operate the foreground app | Declaration above |
| `RECORD_AUDIO` | Speech recognition during sessions | Runtime permission, requested from Home |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Keep the mic available while another app is in front | Foreground service type `microphone`; declare in Play Console "Foreground service permissions" with the demo video |
| `POST_NOTIFICATIONS` | "VoiceControl is listening" notice | Runtime on Android 13+ |
| `INTERNET` | Backend sync and AI interpretation | Optional at runtime (local-only mode) |

No `SYSTEM_ALERT_WINDOW`: the floating mic uses `TYPE_ACCESSIBILITY_OVERLAY`.

## Data safety form

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Personal info (name, email, phone, address) | Yes (profile) | No | App functionality | Yes |
| Email (account) | Yes | No | Account management | Required for sync |
| Audio | **No** (processed on device by the speech service, not stored) | No | – | – |
| App interactions (history: field outcomes) | Yes | No | App functionality, analytics for the user | Yes |
| App info / screen content (labels, screenshots for vision fallback) | Yes (ephemeral) | Processed by AI provider on our behalf | App functionality | Yes |

- Data is encrypted in transit: **Yes**. Users can request deletion: **Yes**.
- AI providers act as service providers (processing on our behalf), not "sharing" under Play's definition.

## Store listing

- Short description: "Fill forms and press buttons in any app using your voice — Hindi, English or Hinglish."
- Category: Tools (or Productivity). Content rating: Everyone.
- Screenshots: Home (setup), overlay asking a question, inspector, flows, settings; dashboard editor for the website.
- Privacy policy URL: `https://<dashboard host>/privacy`.

## Pre-launch checks

- [ ] Accessibility declaration approved
- [ ] Foreground service (microphone) declaration approved
- [ ] Data safety form submitted
- [ ] Release built with production URLs over HTTPS
- [ ] Tested on Android 8 (API 26), 13 and 15 devices with Google and Samsung keyboards
