# Indian-language ASR and TTS options for VoiceControl (Hindi, Hinglish, Marathi, Tamil, Telugu, Bengali, Gujarati)

Research date: 2026-10-04. Prices are as listed on the vendor pages when fetched (Oct 2026), unless a different date is given. USD/INR conversions use an assumed rate of about ₹88 = $1. That rate is my assumption, not a sourced figure.

## ASR: accuracy, latency, streaming, on-device vs cloud, cost, licensing, Android effort. Which handles Hinglish best?

### Takeaway
On published Indic benchmarks, Indic-specialised models beat the general cloud APIs by a wide margin. On Vistaar Hindi, AI4Bharat IndicWhisper averages 13.6% WER, against 23.9% for Google STT (the 2023-era API) and 20.0% for Azure. Sarvam's Saarika v2.5 reports 11.81% Hindi WER on Vistaar. Sarvam (Saaras v3 and v4) is the strongest cloud option for Hinglish and Indic code-mixing. It costs ₹30/hour (about $0.34/h, or $0.0057/min), roughly a third of Google STT v2's $0.016/min. For on-device use, Android's built-in recognizer is the zero-cost baseline. AI4Bharat IndicConformer-600M (int8 ONNX, about 131 MB per language) is the most practical open-weight offline upgrade.

### Cited Findings
**Vistaar benchmark (AI4Bharat). Hindi WER %, per dataset**
- Google STT: Kathbath 14.3, Kathbath-Hard 16.7, FLEURS 19.4, CommonVoice 20.8, IndicTTS 18.3, MUCS 17.8, Gramvaani 59.9; **avg 23.9** — [AI4Bharat Vistaar GitHub](https://github.com/AI4Bharat/vistaar)
- Azure STT: 13.6 / 15.1 / 24.3 / 14.6 / 15.2 / 15.1 / 42.3; **avg 20.0** — [Vistaar](https://github.com/AI4Bharat/vistaar)
- IndicWav2vec: avg 21.0; Nvidia-medium avg 19.4; Nvidia-large avg 18.6 (best on MUCS, 11.8) — [Vistaar](https://github.com/AI4Bharat/vistaar)
- IndicWhisper: 10.3 / 12.0 / 11.4 / 15.0 / 7.6 / 12.0 / 26.8; **avg 13.6**, best on 6 of 7 Hindi sets — [Vistaar](https://github.com/AI4Bharat/vistaar)
- IndicWhisper WER on Kathbath for the other target languages: bn 16.6, gu 17.8, mr 19.9, ta 24.2, te 25.0 (12-language average 22.3). MUCS (the Interspeech 2021 code-switching/multilingual challenge set): gu 33.2, hi 12.0, mr 12.8, ta 28.3, te 32.1 — [Vistaar](https://github.com/AI4Bharat/vistaar)
- Note: the commercial-API numbers in Vistaar come from the original paper, which dates to around 2023. Chirp 2/3 and current Azure models were not part of that comparison, so treat the Google and Azure numbers as outdated. I found no newer independent Vistaar run of Chirp 3.

**Sarvam AI (Saarika / Saaras)**
- Saarika v2.5 Vistaar WER: 18.32% average across 11 languages, English 8.26%, **Hindi 11.81%**, the other 9 languages 20.15%. Sarvam markets it for telephony, multi-speaker and **code-mixed speech**, with language auto-ID via `language_code="unknown"` — [Sarvam docs: Saarika](https://docs.sarvam.ai/api-reference-docs/models/saarika)
- Saarika v2.5 is now marked deprecated. The docs point users to **Saaras v3 with `mode="transcribe"`**. The real-time REST endpoint takes at most 30 s of audio; a Batch API handles up to 2 h — [Sarvam docs: Saarika](https://docs.sarvam.ai/api-reference-docs/models/saarika)
- **Saaras V4** (blog dated 25 Sep 2026) covers 22 Indian languages plus English. Sarvam claims streaming "time to first token below 150 ms", explicit code-mixing support, 16.03% WER on IndicContextEval with keyword prompting (L5 setting), and 5.22% language-ID error across 22 languages (2.9% across the top 10). The post compares outputs only qualitatively against ElevenLabs Scribe v2, Deepgram Nova-3 and GPT-4o Transcribe, and gives **no numeric WER against competitors** — [Sarvam blog: Introducing Saaras V4](https://www.sarvam.ai/blogs/introducing-saaras-v4)
- Search snippets of the Sarvam ASR evaluation material rank "Sarvam Audio" first, with Saarika 2.5 and Gemini 3 Pro next. The fetched blog (2 Apr 2026) contains methodology only: it argues that raw WER/CER penalise valid spelling variants in Indic and code-mixed text and proposes LLM-WER/LLM-CER. It has no cross-vendor table — [Sarvam blog: Evaluating Indian-language ASR](https://www.sarvam.ai/blogs/evaluating-indian-language-asr)
- Pricing: **Speech-to-Text ₹30/hour**, billed per second. ₹100 free credit on signup — [Sarvam pricing docs](https://docs.sarvam.ai/api/pricing). With diarization ₹45/h; STT+translate ₹30/h (per search snippet of the same pricing pages and a third-party summary) — [smallest.ai summary](https://smallest.ai/blog/sarvam-ai-speech-to-text-pricing)

**Google Cloud Speech-to-Text v2 (Chirp 3)**
- Feature support by language: hi-IN on **chirp_3** has auto-punctuation, diarization and **model adaptation**; it does not list word-level confidence. hi-IN `short` and `telephony_short` have model adaptation **and** word-level confidence. mr/ta/te-IN have chirp_3 (punctuation, model adaptation) plus `short`/`long`. **bn-IN and gu-IN are listed only on chirp_3** — [Google Cloud STT v2 supported languages](https://docs.cloud.google.com/speech-to-text/v2/docs/speech-to-text-supported-languages)
- Pricing: $0.016/min for 0–500k min/month (≈ ₹1.41/min, ≈ ₹84/h), falling to $0.004/min above 2M min. Dynamic batch costs $0.003/min. Chirp has no surcharge — [Google STT pricing](https://cloud.google.com/speech-to-text/pricing/)

**Deepgram Nova-3**
- Nova-3 Multilingual (`language=multi`) code-switches in real time across 10 languages including Hindi. The Hinglish output keeps the speaker's script (Hindi in Devanagari, English in Latin). An update reported about 34% relative batch and 21% streaming mean-WER reduction, with the largest gains in code-switching. Developers have reported Hinglish being misidentified as Spanish in multi mode — [Deepgram: Nova-3 multilingual WER improvements](https://deepgram.com/learn/nova-3-multilingual-major-wer-improvements-across-languages); [Deepgram: Hinglish voice AI](https://deepgram.com/learn/hinglish-voice-ai-speech-recognition)
- Nova-3 multi covers only Hindi among Indian languages, so Marathi, Tamil, Telugu, Bengali and Gujarati are not code-switch capable there — [Deepgram](https://deepgram.com/learn/nova-3-multilingual-major-wer-improvements-across-languages)

**AI4Bharat IndicConformer (open weights, on-device candidate)**
- Multilingual ASR for all 22 official Indian languages. The model is published on HF as `ai4bharat/indic-conformer-600m-multilingual` (about 92k downloads) — [HF collection](https://huggingface.co/collections/ai4bharat/indicconformer); [GitHub](https://github.com/AI4Bharat/IndicConformerASR)
- A community int8 ONNX export shrinks the encoder from 2.4 GB fp32 to **0.62 GB**, with peak RSS around 1.1 GB and about 3x faster load. Measured WER cost was ≈ +0.5 on 1 of 6 spot-checked clips — [HF: indic-conformer-600m-int8-onnx](https://huggingface.co/yashwork-byte/indic-conformer-600m-int8-onnx)
- Per-language int8 ONNX packs are **131 MB each**, against 622 MB for the 22-language model — [HF: nmukthap/vertexvoice-indic](https://huggingface.co/nmukthap/vertexvoice-indic)

**New benchmark: Voice of India (Interspeech 2026)**
- Bhogale, Dhir et al., arXiv April 2026 (revised July 2026): 306,230 utterances, 536 h, 36,691 speakers, 15 languages and 139 regional clusters. The audio is **unscripted telephone conversation**, and the references account for spelling variants, including code-mixed English words. The paper analyses error by district, audio quality, speaking rate, gender and device. The abstract does not give per-system numbers — [arXiv 2604.19151](https://arxiv.org/abs/2604.19151)

**Android SpeechRecognizer (current stack)**
- RecognizerIntent offers EXTRA_BIASING_STRINGS, EXTRA_PREFER_OFFLINE, EXTRA_SEGMENTED_SESSION, EXTRA_ENABLE_LANGUAGE_DETECTION / LANGUAGE_DETECTION_ALLOWED_LANGUAGES, EXTRA_ENABLE_LANGUAGE_SWITCH, EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, EXTRA_MAX_RESULTS, EXTRA_ENABLE_FORMATTING, EXTRA_AUDIO_SOURCE and EXTRA_PARTIAL_RESULTS. The docs warn that **recognizers may ignore these extras** — [Android RecognizerIntent reference](https://developer.android.com/reference/android/speech/RecognizerIntent)
- **API-level conflict:** the summarised fetch listed EXTRA_BIASING_STRINGS and EXTRA_SEGMENTED_SESSION as API 29 and the language-detection extras as API 21. My recollection is that BIASING_STRINGS, SEGMENTED_SESSION, ENABLE_FORMATTING and AUDIO_SOURCE are **API 33** and the language detection/switch extras are **API 34**. Also from recollection: `createOnDeviceSpeechRecognizer` is API 31, and `checkRecognitionSupport`/`triggerModelDownload` are API 33. I could not verify any of these against the raw docs, so check them in the IDE or the docs before relying on them — [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)

### Inferences
- **Best for Hinglish:** Sarvam Saaras v3/v4 is built for Indic code-mixing, auto language ID and all six target languages, at the lowest cloud price found. Deepgram Nova-3 multi handles Hindi–English but no other Indic language and has reported language-ID failures. Google Chirp 3 has the widest Indic coverage with model adaptation, but I found no published Hinglish WER for it.
- At ₹30/h, a 3-second command costs about ₹0.025, roughly $0.0003, but sub-second billing granularity and network round-trip (REST is capped at 30 s) matter more than price for a command app. Sarvam's claimed streaming TTFT under 150 ms (V4) makes it viable as a cloud fallback.
- A reasonable stack: Android SpeechRecognizer stays primary because it is free, streaming, and on-device on Android 13+ with packs. When confidence is low, or for languages where the device lacks a pack, re-send the buffered audio to Sarvam Saaras or Chirp 3, or decode offline with an IndicConformer per-language ONNX pack (131 MB, needs about 1 GB RAM for the 600M model, so mid- and high-end phones only).
- Whisper large-v3/turbo via whisper.cpp: no Indic WER numbers were retrieved in this session. IndicWhisper, a Whisper fine-tune, is the evidence that Whisper-family models fine-tuned on Indic data do well, so a vanilla Whisper is likely worse than IndicWhisper on Indic speech. This is inference, not sourced.

### Gaps
- No current independent WER for Google Chirp 2/3, Azure (2025+), Bhashini, AssemblyAI (Universal) or OpenAI gpt-4o-transcribe on Vistaar, Kathbath or MUCS was retrieved. The Vistaar commercial numbers are about 3 years old.
- Whisper-large-v3 and turbo Hindi WER, and whisper.cpp on-device latency on Android, were not retrieved.
- Bhashini ASR APIs (pricing, terms, accuracy) were not researched within budget.
- Azure STT per-hour price, AssemblyAI Hindi support and pricing, and Deepgram pricing were not retrieved.
- Saaras v3/v4 per-version and streaming price were not broken out on the pricing page, which shows only "Speech to Text ₹30/h".
- Per-language availability of on-device Android language packs (for example, whether gu or bn on-device exist) was not verified.

## Techniques to improve recognition of short commands and app/button names

### Takeaway
Use every biasing hook available: EXTRA_BIASING_STRINGS on device, and model adaptation or phrase sets on Chirp 3 or `short` hi-IN. Then always request n-best (EXTRA_MAX_RESULTS) with confidences, and rescore the alternatives yourself against on-screen labels using a transliteration-aware fuzzy match. Sarvam V4's keyword prompting (16.03% WER on IndicContextEval) shows that context injection measurably helps Indic ASR.

### Cited Findings
- Android exposes biasing strings, n-best (EXTRA_MAX_RESULTS), partial results, and endpointing controls (COMPLETE_SILENCE_LENGTH_MILLIS, MINIMUM_LENGTH_MILLIS). These are hints that recognizers may ignore — [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)
- Google STT v2 "model adaptation" is supported for hi/mr/ta/te/bn/gu on chirp_3, and for hi/mr/ta/te on `short`. Word-level confidence is available on `short`/`long`/telephony for hi-IN, but it is **not listed for chirp_3** — [Google STT v2 languages](https://docs.cloud.google.com/speech-to-text/v2/docs/speech-to-text-supported-languages)
- Sarvam Saaras V4 supports keyword prompting and reports 16.03% WER on IndicContextEval (L5) with it — [Sarvam Saaras V4 blog](https://www.sarvam.ai/blogs/introducing-saaras-v4)
- Single-reference WER penalises valid spelling variants of Indic and code-mixed words, such as English loanwords in Devanagari versus Latin script. Sarvam and the Voice of India benchmark both build in spelling-variant handling — [Sarvam eval blog](https://www.sarvam.ai/blogs/evaluating-indian-language-asr); [arXiv 2604.19151](https://arxiv.org/abs/2604.19151)
- Deepgram's Hinglish output mixes scripts (Devanagari for Hindi words, Latin for English words) — [Deepgram Hinglish](https://deepgram.com/learn/hinglish-voice-ai-speech-recognition)

### Inferences
- The matcher should normalise both the ASR output and the on-screen labels into a common form. For example, transliterate Devanagari, Tamil and other scripts to Latin (ICU `Any-Latin` / `Latin-ASCII`, or IndicXlit), then compare with phonetic-aware fuzzy matching, so that "व्हाट्सएप", "whatsapp" and "वॉट्सऐप" all match the label "WhatsApp".
- Rescoring: score = ASR confidence × label-match similarity, taken over all n-best alternatives. Accept above a threshold, ask for confirmation in a middle band, and re-prompt below it. Some recognizers return empty or -1 confidence arrays, so the threshold logic needs a fallback.
- Pass the current screen's labels, plus a learned app-name list, into EXTRA_BIASING_STRINGS on each listen session. Keep the list short and current; large static lists dilute the bias. This is general practice, not sourced here.
- Short commands are vulnerable to endpointing that fires too early or too late. Tune COMPLETE_SILENCE_LENGTH to about 700–1000 ms for commands. Values may be ignored by Google's recognizer, so test on real devices.

### Gaps
- No published quantitative study on n-best label rescoring for Indic mobile commands was found.
- No vendor docs on Sarvam's keyword prompt API parameters were retrieved.

## TTS: natural Hindi/Indian voices (quality, latency, cost, offline)

### Takeaway
Cloud options price out as follows: Sarvam Bulbul v3 at ₹30 per 10k characters (₹3,000/M, ≈ $34/M characters at ₹88/$), Google Chirp 3 HD at $30/M characters, and Azure neural at about $15/M characters. Bulbul and Chirp 3 HD cost about the same per character, Azure costs about half as much, and Bulbul is Indic-specialised. Free, offline options are Android's local Google TTS voices and the open models Indic Parler-TTS (Apache-2.0, 22 languages) and IndicF5 (11 languages). The open models are server-class and not realistic on-phone. For an assistant that speaks short confirmations, Android TTS with Google network/local hi-IN voices plus caching remains the pragmatic default.

### Cited Findings
- Sarvam Bulbul v3: **₹30 per 10k characters**; Bulbul v2 ₹15 per 10k (snippet, older pricing) — [Sarvam pricing](https://docs.sarvam.ai/api/pricing)
- Google Cloud TTS Chirp 3 HD: **$30 per 1M characters** after the free tier (search-snippet summary of the pricing page) — [Google TTS pricing](https://cloud.google.com/text-to-speech/pricing/)
- Azure neural TTS: about $15 per 1M characters, with a 500k characters/month free (F0) tier. Neural HD and custom voices cost extra (third-party summary) — [costbench summary](https://costbench.com/software/ai-voice-tools/microsoft-speech/); official page: [Azure Speech pricing](https://azure.microsoft.com/en-au/pricing/details/cognitive-services/speech-services/)
- Indic Parler-TTS (AI4Bharat): 22 languages, **Apache-2.0** (gated on HF), fine-tuned from Parler-TTS Mini on 1,806 h. Voice and style are controllable via a text description — [HF: ai4bharat/indic-parler-tts](https://huggingface.co/ai4bharat/indic-parler-tts)
- IndicF5: a polyglot F5-TTS model trained on 1,417 h (Rasa, IndicTTS, LIMMITS, IndicVoices-R) covering 11 Indian languages. A separate SPRINGLab F5-Hindi model is CC-BY-4.0 — [IndicF5 space](https://joshiyash666-indicf5.hf.space/); [SPRINGLab F5-Hindi](https://huggingface.co/SPRINGLab/F5-Hindi-24KHz/blob/main/README.md?code=true)
- Dataset/MOS methodology for 22-language Indic TTS — [arXiv 2410.14197](https://arxiv.org/pdf/2410.14197)

### Inferences
- Cost per typical confirmation (about 40 characters): Bulbul ≈ ₹0.12; Google Chirp 3 HD ≈ $0.0012; Azure ≈ $0.0006. Caching repeated phrases ("ठीक है, खोल रहा हूँ") removes most of this cost.
- Parler-TTS Mini (about 880M params) and F5-TTS are diffusion or autoregressive models that need a GPU for real-time synthesis. They suit a self-hosted backend, not on-device.

### Gaps
- No MOS figures were retrieved for Bulbul, Google Chirp 3 HD hi-IN, Azure hi-IN (for example, SwaraNeural or MadhurNeural), Indic Parler-TTS or ElevenLabs Hindi.
- ElevenLabs Hindi pricing and latency, and Google Neural2/WaveNet hi-IN prices, were not retrieved.
- TTS latency (time to first audio) for each vendor was not retrieved.
- Android local versus network Google TTS voice quality for hi-IN and other Indic languages has no published comparison.

## Volume and audio-routing pitfalls on Android for assistant TTS

### Takeaway
USAGE_ASSISTANCE_ACCESSIBILITY routes TTS to the separate accessibility volume (STREAM_ACCESSIBILITY), which Android raises or lowers independently of media volume. This is a likely cause of "TTS too quiet" or "volume keys don't change it". I found little authoritative documentation, so most of this section is engineering inference.

### Cited Findings
- USAGE_ASSISTANCE_ACCESSIBILITY (value 11) corresponds to the separate STREAM_ACCESSIBILITY (value 10). Usage attributes let the platform's routing and volume policy refine behaviour — [CSDN explainer (Chinese)](https://adg.csdn.net/695337d15b9f5f31781be1dd.html)
- AOSP has had audio-policy changes specifically to "improve accessibility volume" — [AOSP commit 28d09f0](https://android.googlesource.com/platform/frameworks/av/+/28d09f0)

### Inferences (not source-verified; validate on devices)
- The accessibility volume is generally adjustable by the user only while an accessibility service is active or accessibility audio is playing. If VoiceControl runs as an AccessibilityService, this works. Otherwise the user may be unable to raise TTS volume, so consider USAGE_ASSISTANT (API 26+) or USAGE_MEDIA as user-selectable alternatives.
- Capturing with VOICE_COMMUNICATION, or starting a communication-mode session, can switch the device into in-call/communication audio mode. That lowers or redirects other output (earpiece routing, ducking). For command capture, prefer AudioSource VOICE_RECOGNITION, or let SpeechRecognizer manage the mic.
- Request transient audio focus (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) around TTS. Don't start the recognizer until UtteranceProgressListener.onDone fires, so the assistant does not hear itself.

### Gaps
- No official Android developer page on accessibility-stream volume behaviour or VOICE_COMMUNICATION ducking was retrieved within budget.
