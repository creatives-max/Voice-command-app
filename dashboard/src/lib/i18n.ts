import { useCallback, useEffect, useSyncExternalStore } from "react";

/**
 * Dashboard translations (English and Hindi). Hindi must define every English key (checked by the
 * type) and keep the same `{placeholders}` (checked by a test).
 */
const en = {
  "nav.flows": "Flows",
  "nav.analytics": "Analytics",
  "nav.history": "History",
  "nav.marketplace": "Marketplace",
  "nav.devices": "Devices",
  "nav.organization": "Organization",
  "nav.profile": "Profile",
  "nav.signOut": "Sign out",
  "nav.workspace": "Workspace",
  "nav.personal": "Personal",
  "nav.newOrg": "+ New organization…",
  "prefs.theme": "Theme",
  "prefs.theme.system": "System",
  "prefs.theme.light": "Light",
  "prefs.theme.dark": "Dark",
  "prefs.language": "Language",

  "login.signInTitle": "Sign in to VoiceControl",
  "login.registerTitle": "Create your account",
  "login.description": "Edit the voice flows your phone recorded: questions, rules, defaults, order and help videos.",
  "login.name": "Name",
  "login.email": "Email",
  "login.password": "Password",
  "login.wait": "Please wait…",
  "login.signIn": "Sign in",
  "login.create": "Create account",
  "login.toRegister": "New here? Create an account",
  "login.toLogin": "Already have an account? Sign in",
  "login.privacy": "Privacy policy",
  "common.error": "Something went wrong",

  "flows.title": "Flows",
  "flows.orgTitle": "{org} flows",
  "flows.subtitle": "Recorded on your phone. Edit a flow and the next run on that screen uses it.",
  "flows.orgSubtitle": "Shared with everyone in the organization; their phones use these flows too. Move your own flows here from the flow editor.",
  "flows.search": "Search apps or flows",
  "flows.emptyTitle": "No flows yet",
  "flows.empty": "Sign in on the VoiceControl app and fill any form by voice. Each screen you complete is saved here automatically.",
  "flows.emptyOrg": "Open one of your flows (switch to Personal) and choose “Move” to share it with the organization, or import one from the Marketplace.",
  "flows.updated": "Updated {when}",
  "flows.loadError": "Could not load flows: {message}",

  "editor.allFlows": "← All flows",
  "editor.history": "History",
  "editor.triggers": "Run & triggers",
  "editor.analytics": "Analytics",
  "editor.publish": "Publish",
  "editor.move": "Move",
  "editor.test": "Test run",
  "editor.delete": "Delete",
  "editor.list": "List",
  "editor.canvas": "Canvas",
  "editor.comments": "Comments",
  "editor.note": "What changed? (optional)",
  "editor.discard": "Discard",
  "editor.save": "Save new version",
  "editor.saving": "Saving…",
  "editor.saved": "Saved version {version}. The phone will use it on the next run.",
  "editor.viewer": "You are a viewer in this organization: you can read and test this flow and run it on your phone, but not change it.",
  "editor.newerVersion": "{name} saved version {version} while you were here. Reload to see it before saving.",
  "editor.reload": "Reload",
  "presence.viewing": "{name} is viewing",
  "presence.editing": "{name} is editing",

  "builder.palette": "Drag a step onto the canvas",
  "builder.start": "Start",
  "builder.end": "End",
  "builder.autoLayout": "Tidy layout",
  "builder.hint": "Drop a step on another to place it right after it. Click a step to edit it.",
  "builder.loop": "repeats",
  "builder.if": "if {condition}",
  "builder.noSelection": "Select a step to edit it here.",
  "builder.close": "Close",

  "comments.title": "Comments",
  "comments.placeholder": "Write a comment…",
  "comments.onStep": "On step",
  "comments.wholeFlow": "Whole flow",
  "comments.send": "Comment",
  "comments.resolve": "Resolve",
  "comments.reopen": "Reopen",
  "comments.edit": "Edit",
  "comments.delete": "Delete",
  "comments.save": "Save",
  "comments.cancel": "Cancel",
  "comments.empty": "No comments yet. Ask a question or leave a note for your team.",
  "comments.showResolved": "Show resolved ({count})",
  "comments.edited": "edited",
  "comments.resolved": "resolved",

  "analytics.title": "Analytics",
  "analytics.subtitle": "How often flows run and how often they finish, from your phones' history. Spoken values are never included.",
  "analytics.flowTitle": "Analytics · {name}",
  "analytics.period": "Period",
  "analytics.days": "Last {days} days",
  "analytics.runs": "Runs",
  "analytics.successRate": "Success rate",
  "analytics.people": "People",
  "analytics.avgDuration": "Average time",
  "analytics.lastRun": "Last run",
  "analytics.never": "Never",
  "analytics.daily": "Runs per day",
  "analytics.completed": "Completed",
  "analytics.stopped": "Stopped",
  "analytics.failed": "Failed",
  "analytics.flow": "Flow",
  "analytics.noRuns": "No runs in this period. Runs appear after your phone uploads its history.",
  "analytics.steps": "Steps",
  "analytics.stepsHint": "Where people type by hand or skip shows which questions need work.",
  "analytics.understood": "Answers understood by",
  "analytics.remote": "Remote runs",
  "analytics.open": "Open flow",

  "outcome.FILLED": "Filled by voice",
  "outcome.DEFAULT_FILLED": "Default used",
  "outcome.KEPT": "Kept",
  "outcome.SKIPPED": "Skipped",
  "outcome.MANUAL": "Typed by hand",
  "outcome.CLICKED": "Pressed",
  "outcome.TOGGLED": "Toggled",
  "outcome.FAILED": "Failed",

  "history.title": "History",
  "history.subtitle": "Voice sessions from your phone. Spoken values are never stored.",
  "devices.title": "Devices",
  "devices.subtitle": "Phones signed in to your account. Flows run on them from “Run now”, schedules and app-open triggers.",
  "marketplace.title": "Marketplace",
  "org.title": "Organizations",
  "org.members": "Members",
  "org.apiKeys": "API keys",
  "org.webhooks": "Webhooks",
  "org.audit": "Audit log",
  "org.memberCount": "{count} member(s)",
} as const;

export type MessageKey = keyof typeof en;

const hi: Record<MessageKey, string> = {
  "nav.flows": "फ़्लो",
  "nav.analytics": "विश्लेषण",
  "nav.history": "इतिहास",
  "nav.marketplace": "मार्केटप्लेस",
  "nav.devices": "डिवाइस",
  "nav.organization": "संगठन",
  "nav.profile": "प्रोफ़ाइल",
  "nav.signOut": "साइन आउट",
  "nav.workspace": "कार्यक्षेत्र",
  "nav.personal": "व्यक्तिगत",
  "nav.newOrg": "+ नया संगठन…",
  "prefs.theme": "थीम",
  "prefs.theme.system": "सिस्टम",
  "prefs.theme.light": "लाइट",
  "prefs.theme.dark": "डार्क",
  "prefs.language": "भाषा",

  "login.signInTitle": "VoiceControl में साइन इन करें",
  "login.registerTitle": "अपना खाता बनाएँ",
  "login.description": "आपके फ़ोन द्वारा रिकॉर्ड किए गए वॉइस फ़्लो संपादित करें: सवाल, नियम, डिफ़ॉल्ट, क्रम और सहायता वीडियो।",
  "login.name": "नाम",
  "login.email": "ईमेल",
  "login.password": "पासवर्ड",
  "login.wait": "कृपया प्रतीक्षा करें…",
  "login.signIn": "साइन इन",
  "login.create": "खाता बनाएँ",
  "login.toRegister": "नए हैं? खाता बनाएँ",
  "login.toLogin": "पहले से खाता है? साइन इन करें",
  "login.privacy": "गोपनीयता नीति",
  "common.error": "कुछ गलत हो गया",

  "flows.title": "फ़्लो",
  "flows.orgTitle": "{org} के फ़्लो",
  "flows.subtitle": "आपके फ़ोन पर रिकॉर्ड किए गए। फ़्लो बदलें, उस स्क्रीन पर अगली बार वही इस्तेमाल होगा।",
  "flows.orgSubtitle": "संगठन के सभी सदस्यों के साथ साझा; उनके फ़ोन भी ये फ़्लो इस्तेमाल करते हैं। अपने फ़्लो यहाँ फ़्लो एडिटर से लाएँ।",
  "flows.search": "ऐप या फ़्लो खोजें",
  "flows.emptyTitle": "अभी कोई फ़्लो नहीं",
  "flows.empty": "VoiceControl ऐप में साइन इन करें और कोई भी फ़ॉर्म बोलकर भरें। हर पूरी की गई स्क्रीन यहाँ अपने-आप सेव होती है।",
  "flows.emptyOrg": "अपना कोई फ़्लो खोलें (व्यक्तिगत चुनें) और संगठन से साझा करने के लिए “ले जाएँ” चुनें, या मार्केटप्लेस से इम्पोर्ट करें।",
  "flows.updated": "{when} को अपडेट हुआ",
  "flows.loadError": "फ़्लो लोड नहीं हो सके: {message}",

  "editor.allFlows": "← सभी फ़्लो",
  "editor.history": "इतिहास",
  "editor.triggers": "चलाएँ और ट्रिगर",
  "editor.analytics": "विश्लेषण",
  "editor.publish": "प्रकाशित करें",
  "editor.move": "ले जाएँ",
  "editor.test": "टेस्ट रन",
  "editor.delete": "हटाएँ",
  "editor.list": "सूची",
  "editor.canvas": "कैनवास",
  "editor.comments": "टिप्पणियाँ",
  "editor.note": "क्या बदला? (वैकल्पिक)",
  "editor.discard": "छोड़ें",
  "editor.save": "नया वर्शन सेव करें",
  "editor.saving": "सेव हो रहा है…",
  "editor.saved": "वर्शन {version} सेव हुआ। फ़ोन अगली बार इसे इस्तेमाल करेगा।",
  "editor.viewer": "आप इस संगठन में दर्शक हैं: आप यह फ़्लो पढ़, टेस्ट और अपने फ़ोन पर चला सकते हैं, पर बदल नहीं सकते।",
  "editor.newerVersion": "आपके यहाँ रहते {name} ने वर्शन {version} सेव किया। सेव करने से पहले रीलोड करें।",
  "editor.reload": "रीलोड",
  "presence.viewing": "{name} देख रहे हैं",
  "presence.editing": "{name} बदल रहे हैं",

  "builder.palette": "कोई स्टेप कैनवास पर खींचें",
  "builder.start": "शुरू",
  "builder.end": "अंत",
  "builder.autoLayout": "व्यवस्थित करें",
  "builder.hint": "किसी स्टेप को दूसरे पर छोड़ें तो वह उसके ठीक बाद आएगा। बदलने के लिए स्टेप पर क्लिक करें।",
  "builder.loop": "दोहराता है",
  "builder.if": "अगर {condition}",
  "builder.noSelection": "यहाँ बदलने के लिए कोई स्टेप चुनें।",
  "builder.close": "बंद करें",

  "comments.title": "टिप्पणियाँ",
  "comments.placeholder": "टिप्पणी लिखें…",
  "comments.onStep": "स्टेप पर",
  "comments.wholeFlow": "पूरा फ़्लो",
  "comments.send": "टिप्पणी करें",
  "comments.resolve": "हल करें",
  "comments.reopen": "फिर खोलें",
  "comments.edit": "बदलें",
  "comments.delete": "हटाएँ",
  "comments.save": "सेव करें",
  "comments.cancel": "रद्द करें",
  "comments.empty": "अभी कोई टिप्पणी नहीं। अपनी टीम के लिए सवाल या नोट लिखें।",
  "comments.showResolved": "हल की गई दिखाएँ ({count})",
  "comments.edited": "बदली गई",
  "comments.resolved": "हल",

  "analytics.title": "विश्लेषण",
  "analytics.subtitle": "फ़ोन के इतिहास से: फ़्लो कितनी बार चले और कितनी बार पूरे हुए। बोले गए मान कभी शामिल नहीं होते।",
  "analytics.flowTitle": "विश्लेषण · {name}",
  "analytics.period": "अवधि",
  "analytics.days": "पिछले {days} दिन",
  "analytics.runs": "रन",
  "analytics.successRate": "सफलता दर",
  "analytics.people": "लोग",
  "analytics.avgDuration": "औसत समय",
  "analytics.lastRun": "पिछला रन",
  "analytics.never": "कभी नहीं",
  "analytics.daily": "प्रति दिन रन",
  "analytics.completed": "पूरे हुए",
  "analytics.stopped": "रोके गए",
  "analytics.failed": "विफल",
  "analytics.flow": "फ़्लो",
  "analytics.noRuns": "इस अवधि में कोई रन नहीं। फ़ोन के इतिहास अपलोड करने के बाद रन दिखते हैं।",
  "analytics.steps": "स्टेप",
  "analytics.stepsHint": "जहाँ लोग हाथ से टाइप करते हैं या छोड़ते हैं, वहाँ सवाल सुधारने चाहिए।",
  "analytics.understood": "जवाब किसने समझे",
  "analytics.remote": "रिमोट रन",
  "analytics.open": "फ़्लो खोलें",

  "outcome.FILLED": "बोलकर भरा",
  "outcome.DEFAULT_FILLED": "डिफ़ॉल्ट इस्तेमाल",
  "outcome.KEPT": "रखा",
  "outcome.SKIPPED": "छोड़ा",
  "outcome.MANUAL": "हाथ से टाइप",
  "outcome.CLICKED": "दबाया",
  "outcome.TOGGLED": "बदला",
  "outcome.FAILED": "विफल",

  "history.title": "इतिहास",
  "history.subtitle": "आपके फ़ोन के वॉइस सेशन। बोले गए मान कभी सेव नहीं होते।",
  "devices.title": "डिवाइस",
  "devices.subtitle": "आपके खाते से साइन इन फ़ोन। “अभी चलाएँ”, शेड्यूल और ऐप खुलने पर फ़्लो इन पर चलते हैं।",
  "marketplace.title": "मार्केटप्लेस",
  "org.title": "संगठन",
  "org.members": "सदस्य",
  "org.apiKeys": "API कुंजियाँ",
  "org.webhooks": "वेबहुक",
  "org.audit": "ऑडिट लॉग",
  "org.memberCount": "{count} सदस्य",
};

export const locales = ["en", "hi"] as const;
export type Locale = (typeof locales)[number];
export const LOCALE_NAMES: Record<Locale, string> = { en: "English", hi: "हिन्दी" };
export const messages: Record<Locale, Record<MessageKey, string>> = { en, hi };

export function translate(locale: Locale, key: MessageKey, vars?: Record<string, string | number>): string {
  const template = messages[locale][key] ?? en[key];
  return vars ? template.replace(/\{(\w+)\}/g, (m, name: string) => (name in vars ? String(vars[name]) : m)) : template;
}

// Selected locale (localStorage), shared by all components.
const STORAGE_KEY = "vc.locale";
const listeners = new Set<() => void>();

function initial(): Locale {
  if (typeof window === "undefined") return "en";
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === "en" || saved === "hi") return saved;
  } catch {
    // ignore
  }
  return window.navigator.language?.toLowerCase().startsWith("hi") ? "hi" : "en";
}

let current: Locale = initial();

export function getLocale(): Locale {
  return current;
}

export function setLocale(locale: Locale) {
  if (locale === current) return;
  current = locale;
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // ignore
  }
  listeners.forEach((l) => l());
}

const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export function useLocale(): Locale {
  const locale = useSyncExternalStore(subscribe, getLocale, () => "en" as Locale);
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);
  return locale;
}

/** `t("flows.updated", { when })` in the selected language. */
export function useT() {
  const locale = useLocale();
  return useCallback((key: MessageKey, vars?: Record<string, string | number>) => translate(locale, key, vars), [locale]);
}

/** Dates and numbers formatted for the selected language (Indian digit grouping for Hindi). */
export function useFormat() {
  const locale = useLocale();
  const tag = locale === "hi" ? "hi-IN" : "en-IN";
  return {
    date: (d: string | number | Date) => new Date(d).toLocaleDateString(tag),
    dateTime: (d: string | number | Date) => new Date(d).toLocaleString(tag),
    number: (n: number) => n.toLocaleString(tag),
    percent: (r: number) => `${Math.round(r * 100)}%`,
  };
}
