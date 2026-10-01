/**
 * Voice shortcut phrases (VOICE triggers). Same rules as the phone's `ShortcutMatcher` and the backend's
 * `VoicePhrases`: 2–60 letters after normalizing, not a VoiceControl command, unique per account.
 */

export const PHRASE_MIN = 2;
export const PHRASE_MAX = 60;

const RESERVED = new Set([
  "stop", "back", "next", "skip", "repeat", "help", "undo", "submit", "scroll", "scroll down", "scroll up",
  "yes", "no", "haan", "nahi", "read screen", "ruko", "band karo", "wapas", "aage", "peeche",
]);

/** Lower case, punctuation removed, single spaces (the uniqueness key). */
export function phraseKey(text: string): string {
  return text
    .normalize("NFC")
    .toLowerCase()
    .replace(/[!-/:-@[-`{-~।॥“”‘’]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Null when the phrase can be saved, otherwise why not. */
export function phraseError(phrase: string, others: string[] = []): string | null {
  const key = phraseKey(phrase);
  if (key.length < PHRASE_MIN) return `Say at least ${PHRASE_MIN} letters`;
  if (key.length > PHRASE_MAX) return `Keep it under ${PHRASE_MAX} letters`;
  if (RESERVED.has(key)) return `"${phrase.trim()}" is already a VoiceControl command`;
  if (others.some((o) => phraseKey(o) === key)) return "This flow already has that phrase";
  return null;
}
