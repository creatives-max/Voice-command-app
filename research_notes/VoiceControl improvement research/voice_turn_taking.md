# Making VoiceControl's conversation feel human: turn-taking, latency, barge-in, endpointing, fillers and response style

Context: Android app. It listens with `SpeechRecognizer`, plans with Claude one step at a time, and speaks with TTS. Users speak Hindi or Hinglish and are often elderly or have low literacy. They complained that it talks too much ("New screen", repeated "What can I do for you", narrating every step) and that barge-in made TTS quieter.
Research date: 2026-10-04. Tool docs (LiveKit, Pipecat, Vapi, OpenAI) change often, so check defaults again before relying on them.

## 1. Human response-gap norms, latency budgets, and techniques (streaming, early execution, endpointing, semantic turn detection, backchannels)

### Takeaway
People take turns with gaps of about 200 ms, and this holds across languages. Cascaded STT, LLM and TTS agents aim for under about 800 ms from end of user speech to first audio, and they get there by streaming every stage. The newest technique is semantic or ML turn detection on top of a short VAD silence. It lets the agent respond fast when the utterance is clearly complete and wait longer when it is not. LiveKit's audio turn detector supports Hindi.

### Cited Findings
- Stivers et al. 2009 (PNAS 106(26):10587–10592) studied 10 languages. All of them avoid overlapping talk and keep silence between turns short. The most frequent turn-transition gap is about 200 ms. Language averages fall within about 250 ms of the cross-language mean — [MPI](https://forms.mpi.nl/node/50939); [Magyari, predictions in conversation](https://uis.brage.unit.no/uis-xmlui/bitstream/handle/11250/3136187/magyari_predictions_in_conversation_AMMf45188.pdf?sequence=2); [EMCA wiki](https://emcawiki.net/Stivers-etal2009)
- Industry rule of thumb: under 800 ms from the caller stopping to the first audio byte "still feels smooth". The suggested split is STT 100–200 ms, LLM time-to-first-token (TTFT) 150–300 ms, TTS first chunk 100–200 ms, and network 50–150 ms. Streaming ASR partials, LLM tokens and TTS chunks is "what makes the budget achievable" — [Soniox wiki](https://soniox.com/wiki/voice-agent-latency-budget); [Twig blog](https://www.twig.so/blog/voice-ai-agents-latency-budget-800ms). These are vendor blogs, not peer-reviewed, but they agree with each other.
- Full-duplex speech-to-speech models: Kyutai's Moshi (arXiv 2410.00037, 2024) has a theoretical latency of 160 ms and about 200 ms in practice. It models user and system audio as parallel streams, so it has no explicit turns and handles overlap, interruptions and interjections — [arXiv](https://arxiv.org/abs/2410.00037v1)
- LiveKit turn detector (2025–26 docs): an open-weights model that adds conversational context to VAD. The original text EOU model has 135M parameters (based on SmolLM v2) and reads the last 4 turns. It scores each final STT transcript for end-of-turn probability — [LiveKit blog](https://livekit.com/blog/using-a-transformer-to-improve-end-of-turn-detection); [LiveKit KB](https://kb.livekit.io/articles/6569137565-how-does-end-of-utterance-detection-work-in-conversations)
- LiveKit's current docs describe a text turn detector (396 MB, about 50–160 ms inference per turn) and an audio turn detector. The audio detector supports 14 languages, Hindi among them. Default endpointing delays are 0.5 s min and 3.0 s max. With the audio detector they drop to 0.3 s min and 2.5 s max, because the model gives a confident end-of-turn signal — [LiveKit turn detector docs](https://docs.livekit.io/agents/logic/turns/turn-detector)
- LiveKit v0.4.1-intl cut false-positive interruptions by 39.23% (relative) compared with v0.3.0-intl, with no added latency — [LiveKit blog](https://livekit.com/blog/improved-end-of-turn-model-cuts-voice-ai-interruptions-39)
- Pipecat Smart Turn v3 is an audio-only ONNX model that runs locally on CPU, takes under 100 ms on cheap instances, and covers 23 languages. Configuration:
  - VAD `stop_secs` = 0.2 s, which matches the training data.
  - Smart Turn's own `stop_secs` = 3.0 s. If the model says "incomplete" but silence lasts longer than this, the turn is ended anyway.
  - `max_duration_secs` = 8 s.

  Sources: [Pipecat docs](https://docs.pipecat.ai/server/utilities/turn-detection/smart-turn-overview); [Pipecat speech input](https://docs.pipecat.ai/guides/learn/speech-input). The docs page does not say whether Hindi is among the 23 languages.
- OpenAI Realtime API has `server_vad` (silence-based) and `semantic_vad`. `semantic_vad` has an `eagerness` setting: low, medium, high, or auto (the default, equal to medium). `interrupt_response` and `create_response` apply only in conversation mode — [OpenAI Realtime VAD guide](https://developers.openai.com/api/docs/guides/realtime-vad)
- Vapi `startSpeakingPlan.waitSeconds` defaults to 0.4 s. The transcription-endpointing example uses `onNoPunctuationSeconds` 1.5 s, which means waiting longer when the transcript has no final punctuation. Smart-endpointing providers include LiveKit and Deepgram Flux — [Vapi speech configuration](https://docs.vapi.ai/customization/speech-configuration)
- Android `RecognizerIntent` exposes these extras:
  - `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`, `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`, `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` (silence and length thresholds)
  - `EXTRA_PARTIAL_RESULTS`
  - `EXTRA_PREFER_OFFLINE`
  - `EXTRA_SEGMENTED_SESSION`
  - `EXTRA_ENABLE_LANGUAGE_SWITCH` and `EXTRA_LANGUAGE_DETECTION`

  Recognizer implementations may ignore many of them — [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)

### Inferences
- VoiceControl's end-to-end chain probably runs several seconds: SpeechRecognizer's final result, then a non-streaming Claude call, then whole-utterance TTS. That is far above the 800 ms target, so cutting latency matters more than any wording change. Concrete levers:
  1. Use `EXTRA_PARTIAL_RESULTS` and start a speculative Claude call on a stable partial transcript. Cancel it if the final transcript differs in meaning.
  2. Stream Claude output and send the first sentence or clause to `TextToSpeech.speak()` (QUEUE_ADD) as soon as it arrives.
  3. For UI actions, have Claude emit the tool or action first and the spoken text afterwards (or never), so execution does not wait on speech.
  4. Use local fast paths (regex or voice macros) for common commands so they skip the LLM entirely.
- Elderly speakers pause more often and for longer in the middle of an utterance. Fixed short silence timeouts will cut them off. Prefer an adaptive scheme: a short base silence (about 0.5 s) plus a completeness check (semantic or heuristic: does the utterance end in a verb or a complete Hindi clause?), and up to about 2.5–3 s when it looks incomplete. This is the LiveKit and Pipecat pattern. Hindi is verb-final (SOV), so a trailing verb plus an auxiliary such as "hai", "do", "karo" or "kar do" is a strong completeness cue.
- When a response cannot start within about 1 s, fill the gap with a short non-verbal earcon or a 1–2 word acknowledgement ("ठीक है", "हाँ") instead of a sentence. Never fill it with an LLM-generated narration.

### Gaps
- I could not confirm the current numeric defaults for OpenAI `server_vad` (I remember threshold 0.5, prefix_padding 300 ms, silence 500 ms, but the fetched page did not show them) or for the semantic_vad eagerness timeouts.
- I could not confirm LiveKit defaults for `min_interruption_duration`, `preemptive_generation` or `user_away_timeout`; the reference page returned 404.
- I found no source measuring turn-gap norms for Hindi specifically, or for elderly speakers.
- I did not verify whether Google's on-device recognizer honours the silence-length extras for hi-IN.

## 2. Barge-in and echo cancellation on Android without lowering TTS volume

### Takeaway
Android provides AEC only on specific capture paths (VOICE_COMMUNICATION), and that path is designed for VoIP routing and volume. Running `SpeechRecognizer` during TTS also creates audio-focus and mic contention, which can duck or delay playback. No platform recipe makes "listen while speaking" work cleanly with the stock SpeechRecognizer. Practical options: turn barge-in off by default for short prompts; use a dedicated barge-in path (your own AudioRecord with AEC plus a VAD or keyword spotter) for long readouts; and avoid any audio-focus or communication-mode change that ducks TTS.

### Cited Findings
- AOSP: devices should provide an acoustic echo canceler on the capture path when capturing with `VOICE_COMMUNICATION`. If present, it must be discoverable and controllable through `AcousticEchoCanceler`. Each `AudioSource` (MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, and others) is paired with default pre-processing in `/vendor/etc/audio_effects.xml` — [AOSP pre-processing](https://source.android.com/docs/core/audio/implement-pre-processing)
- `MODE_IN_COMMUNICATION` optimises routing, volume control and echo performance for VoIP. The `STREAM_VOICE_CALL` volume slider is separate from media volume on most devices. With `speakerphoneOn=false` the mode routes audio to the earpiece — [commit notes, wz-phone](https://git.manko.yoga/manawenuz/wz-phone/commit/15c237ceea7934255a9ec29e2a1ede29c4ae355f). This is a secondary source (a project commit), but it matches the AudioManager docs as I remember them.
- A developer report (android/media-samples issue #116) describes these problems when combining TTS, SpeechRecognizer and other media:
  - Requesting `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` makes other audio duck or pause.
  - Requesting focus before STT can cause LOSS_TRANSIENT, which kills STT.
  - Blocking STT during TTS delays the STT restart.
  - Running them at the same time causes mic contention with Google STT/SODA, TTS delay, or "ghost listen".
  - `BEEP_SOUND=false` extras are ignored on some devices.

  The issue lists no definitive solution — [GitHub issue](https://github.com/android/media-samples/issues/116)
- Vapi's stop-speaking (barge-in) plan defaults: `numWords` 0 (react to voice immediately), `voiceSeconds` 0.2 s (how long the user must speak before the bot stops), and `backoffSeconds` 1 s (how long to wait before the bot resumes) — [Vapi](https://docs.vapi.ai/customization/speech-configuration)
- LiveKit interruption options include:
  - `min_duration`: minimum speech duration that counts as an interruption.
  - `min_words`: minimum number of transcribed words.
  - `false_interruption_timeout` (2.0 s in the docs example): how long to wait for words after an interruption before treating it as false.
  - `resume_false_interruption`: when the "interruption" turns out to be noise or silence, the agent resumes speaking.

  Source: [LiveKit turns docs](https://docs.livekit.io/agents/build/turns)

### Inferences
- Likely causes of "barge-in lowered TTS volume" in VoiceControl. These are hypotheses to check in code:
  - **(a)** The app or recognizer requests transient audio focus with ducking while TTS is playing, so the TTS stream is ducked. Remedies: give TTS `USAGE_ASSISTANT` or `USAGE_ASSISTANCE_ACCESSIBILITY` AudioAttributes, do not request focus for listening, and set `setWillPauseWhenDucked`/focus listeners correctly.
  - **(b)** The app switches to `MODE_IN_COMMUNICATION` or a `VOICE_COMMUNICATION` source to get AEC. That moves playback to the voice-call stream or earpiece volume, which is often much quieter. If that is happening, restore `MODE_NORMAL` immediately after use.
  - **(c)** OEM AEC/AGC attenuates the playback reference.
- Recommended policy for elderly users:
  - **Barge-in off** for prompts under about 3 s and for confirmations. Start listening right after TTS `onDone`, with no beep or with a soft earcon.
  - **Barge-in on** only for long readouts (reading screen contents, lists, messages). There, accept a "stop / ruko / bas" keyword or a tap on the screen or volume key, rather than any voice energy. That avoids TV and family noise causing false cuts.
  - Require at least about 0.3–0.5 s of speech or at least one recognised word before stopping TTS. If no words follow within about 1.5–2 s, resume (the LiveKit/Vapi pattern).
- A physical "stop" affordance (big on-screen button, or shake or volume-key gesture) is more reliable for this population than acoustic barge-in on a phone speaker.

### Gaps
- I found no official Android guidance or sample for using `SpeechRecognizer` while TTS is playing. The `SpeechRecognizer` API does not expose the audio source it uses or whether AEC is applied.
- I found no data on how well AEC works across Android OEMs, such as the budget devices common in India.

## 3. Verbosity, confirmation, earcons, progressive disclosure, error recovery, re-prompt escalation

### Takeaway
Design guides agree: speak briefly (Alexa's one-breath test); use implicit confirmation by default and explicit confirmation only for hard-to-undo or high-cost actions; do not confirm what the user obviously said; and on errors, re-prompt quickly with a rephrase, then add examples, then exit after about 2 attempts. VoiceControl's "New screen" and repeated "What can I do for you" break all of these rules.

### Cited Findings
- Google Conversation Design:
  - Implicit confirmation (no user response needed) is the common case. Use it for parameters and for completed actions.
  - Explicit (yes/no) confirmation is rare. Use it for difficult-to-undo actions such as deletions and transactions, and when misunderstanding is costly (names, addresses, message content before sending).
  - Do not "belabor confirmations"; for example, don't say "Ok, yes."

  Source: [Google confirmations](https://developers.google.com/assistant/conversation-design/confirmations)
- Google error handling:
  - **No-input, first time:** assume the user didn't hear the question and re-ask it, rephrased and concise. Avoid saying "I didn't hear you".
  - **No-input, second time:** give one final chance.
  - **No-input limit:** end after 2 no-input attempts.
  - **No-match, first time (rapid reprompt):** a brief apology plus a concise question, without repeating the original prompt verbatim and without over-explaining.
  - **No-match, second time:** escalate with options or examples, since "examples… give the user an implicit model of what to say".
  - **No-match limit:** end after 2 no-match attempts.
  - **Overall:** no more than 3 consecutive errors.

  Source: [Google errors](https://developers.google.com/assistant/conversation-design/errors)
- Alexa "one-breath test": if you can say the response at conversational pace in one breath, the length is about right. For multi-step content, breathe only between ideas. Cut filler words and words that don't change meaning — [Alexa voice design guide](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/); [Alexa blog](https://developer.amazon.com/blogs/alexa/post/531ffdd7-acf3-43ca-9831-9c375b08afe0/things-every-alexa-skill-should-do-pass-the-one-breath-test)
- NN/g's study of 17 experienced voice-assistant users rated voice input "good" but rated voice output and natural-language handling "bad" — summarised via [The Ambient](https://www.the-ambient.com/?p=14574) and [Boing Boing](https://boingboing.net/2018/07/24/sirius-cybernetics-corp.html). This is a secondary summary of a 2018 study; I did not fetch the primary NN/g article.
- Older adults using voice assistants with and without a touchscreen were studied in a real-world deployment (arXiv 2307.07723, 2023) — [arXiv](https://arxiv.org/pdf/2307.07723). I did not extract the detailed findings.

### Inferences (concrete rules for VoiceControl's prompt and TTS layer)
1. **Never speak screen transitions.** Drop "New screen" entirely. If needed, use a subtle earcon or haptic for "screen changed / step done".
2. **Speak at goal boundaries only.** Say one short line when a multi-step task starts ("WhatsApp khol rahi hoon"), stay silent during intermediate steps (earcon or haptic per step at most), and say one short line at the end ("Ho gaya, message bhej diya"). Narrate a step only if it takes more than about 3 s, or if it needs the user's input or attention.
3. **Never re-ask "What can I do for you?" after each step.** Ask once per session. Afterwards, signal readiness with a listening earcon or tone and a visual mic state. If the user says nothing, follow the no-input escalation: one short rephrase, then go quiet or idle. Never loop.
4. **Use the confirmation tiers:**
   - **None** for navigation, scrolling and opening apps.
   - **Implicit**, folded into the result, for parameters: "Rahul ko call kar rahi hoon".
   - **Explicit yes/no** only for sending messages or money, deleting, purchases and calls to unknown numbers. Keep explicit confirmations short, and accept "haan / ha / theek hai / kar do" without echoing them back.
5. **Budget for length.** Keep each utterance to one breath, at most about 12–15 words. Use simple Hindi or Hinglish words matching the user's register, with no English UI jargon. For lists, read at most 3 items and then offer "aur sunoge?" (progressive disclosure).
6. **Read screen contents only on request** ("kya likha hai?") or when the screen needs a decision. Read the decision-relevant part first.
7. **Errors:** first a rephrased short re-ask, then an example of what to say, then a fallback to touch, a caregiver, or exit.
8. **Put the rules in Claude's system prompt and enforce them in code.** Give Claude a `speak` field that is optional and empty by default, with a hard character limit. Add a code-level suppression list (drop utterances that match "new screen", or repeated greetings within N seconds), and dedupe identical consecutive utterances.

### Gaps
- I found no voice design guide specific to low-literacy Hindi speakers. Advice for this group is extrapolated.
- I did not retrieve the Google Conversation Design page on earcons or non-verbal sounds, so the earcon guidance here is inference.

## 4. Production voice agent frameworks: interruptions, silence handling, keep-alive prompts

### Takeaway
LiveKit, Pipecat, Vapi and OpenAI Realtime converge on the same pattern:
- VAD with a short silence window, plus a semantic or ML turn detector.
- Interruptions gated by a minimum speech duration or word count.
- "False interruption" recovery, which resumes TTS if the user didn't actually say anything.
- An idle timer with an escalating 2–3 step keep-alive, ending gracefully at the end.

### Cited Findings
- **Pipecat UserIdleProcessor:** a callback fires after N seconds of user silence. A retry counter supports escalation. The docs example goes 1st "Are you still there?", 2nd "Would you like to continue our conversation?", 3rd "I'll leave you for now" and ends. Monitoring starts after the first interaction and resets whenever the user speaks — [Pipecat idle docs](https://docs.pipecat.ai/guides/fundamentals/detecting-user-idle)
- **LiveKit:**
  - Turn detector plus VAD, with endpointing delays of 0.5–3.0 s (0.3–2.5 s with the audio detector).
  - Interruption gating by `min_duration` and `min_words`.
  - `false_interruption_timeout` with `resume_false_interruption`.

  Sources: [LiveKit turn detector](https://docs.livekit.io/agents/logic/turns/turn-detector); [LiveKit turns](https://docs.livekit.io/agents/build/turns)
- **Vapi:** start speaking after 0.4 s wait by default (plus smart endpointing). Stop speaking after 0.2 s of user voice, then back off 1 s — [Vapi](https://docs.vapi.ai/customization/speech-configuration)
- **OpenAI Realtime:** `semantic_vad` with an eagerness setting; `interrupt_response` controls whether user speech cancels an in-progress response — [OpenAI](https://developers.openai.com/api/docs/guides/realtime-vad)
- **Moshi / full-duplex research:** removes turn segmentation altogether and handles overlap and backchannels natively — [arXiv 2410.00037](https://arxiv.org/abs/2410.00037v1)

### Inferences
- For VoiceControl, copy the patterns, not the frameworks.
  - **Idle handling:** wait about 6–8 s of silence (longer for elderly users) and say one short rephrased prompt. After a second silence, go to a quiet idle state with a visual "tap mic to talk" cue. Never repeat a generic "What can I do for you".
  - **Interruptions:** gate barge-in on speech duration or words and allow resuming.
  - **Endpointing:** a short base silence plus a completeness check.
- An on-device turn detector (Smart Turn ONNX, or LiveKit's audio detector if it can be exported) would need your own AudioRecord pipeline instead of `SpeechRecognizer`. That is a bigger architectural change, worth it only if cut-offs remain a top complaint.

### Gaps
- I did not fetch Retell's or Gemini Live's docs within the tool budget; their interruption-sensitivity and idle-reminder settings are not covered here.
- I could not determine whether Smart Turn v3's 23 languages include Hindi.
