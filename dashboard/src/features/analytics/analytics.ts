import type { Daily, StepStats } from "@/lib/types";

export const PERIODS = [7, 30, 90] as const;

/** The viewer's time zone, so "per day" means their days. */
export const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";

/** "1 min 20 s", "45 s", "2 h 5 min". */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return "–";
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return s % 60 ? `${m} min ${s % 60} s` : `${m} min`;
  const h = Math.floor(m / 60);
  return m % 60 ? `${h} h ${m % 60} min` : `${h} h`;
}

/** Chart rows with a short day label. */
export const chartDays = (daily: Daily[], localeTag?: string) =>
  daily.map((d) => ({ ...d, label: new Date(`${d.date}T00:00:00`).toLocaleDateString(localeTag, { day: "numeric", month: "short" }) }));

/** Outcomes that mean the voice question didn't work for the person. */
const TROUBLE = ["MANUAL", "SKIPPED", "FAILED"];

/** Share of a step's runs where people typed by hand, skipped or it failed (0–1). */
export const troubleRate = (s: StepStats) => (s.total ? TROUBLE.reduce((n, o) => n + (s.outcomes[o] ?? 0), 0) / s.total : 0);

/** Steps that need attention first: highest trouble rate, then most runs. */
export const stepsByTrouble = (steps: StepStats[]) => [...steps].sort((a, b) => troubleRate(b) - troubleRate(a) || b.total - a.total);

/** Display order and colors of outcomes (CSS colors that work in light and dark mode). */
export const OUTCOME_COLORS: Record<string, string> = {
  FILLED: "oklch(0.62 0.15 150)",
  DEFAULT_FILLED: "oklch(0.7 0.12 175)",
  KEPT: "oklch(0.72 0.08 220)",
  CLICKED: "oklch(0.6 0.13 265)",
  TOGGLED: "oklch(0.66 0.12 300)",
  SKIPPED: "oklch(0.78 0.14 80)",
  MANUAL: "oklch(0.68 0.17 50)",
  FAILED: "oklch(0.6 0.21 27)",
};

export const STATUS_COLORS = { completed: "oklch(0.62 0.15 150)", stopped: "oklch(0.78 0.14 80)", failed: "oklch(0.6 0.21 27)" };
