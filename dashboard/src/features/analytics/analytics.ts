import type { AnalyticsOverview, Daily, FlowAnalytics, StepStats } from "@/lib/types";

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

export interface Change {
  direction: "up" | "down" | "same" | "new";
  /** Relative change (0.2 = +20%) for counts; percentage points for rates. */
  amount: number;
}

/** How a count changed from the previous period; null when there is nothing to compare. */
export function countChange(current: number, previous: number | undefined | null): Change | null {
  if (previous == null) return null;
  if (previous === 0) return current > 0 ? { direction: "new", amount: 0 } : null;
  const amount = (current - previous) / previous;
  return { direction: amount > 0 ? "up" : amount < 0 ? "down" : "same", amount };
}

/** How a rate (0–1) changed, in percentage points; null when either period has no runs. */
export function rateChange(current: number | null | undefined, previous: number | null | undefined): Change | null {
  if (current == null || previous == null) return null;
  const amount = Math.round((current - previous) * 100);
  return { direction: amount > 0 ? "up" : amount < 0 ? "down" : "same", amount };
}

/** "+20%", "−5 pts", "new"; [points] for rates. */
export function formatChange(c: Change, points = false): string {
  if (c.direction === "new") return "new";
  const n = points ? Math.abs(c.amount) : Math.round(Math.abs(c.amount) * 100);
  const sign = c.direction === "up" ? "+" : c.direction === "down" ? "−" : "±";
  return points ? `${sign}${n} pts` : `${sign}${n}%`;
}

/** One CSV cell: quoted when needed, and never starting like a spreadsheet formula. */
export function csvCell(value: string | number | null | undefined): string {
  if (value == null) return "";
  let s = String(value);
  if (typeof value === "string" && /^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export const toCsv = (rows: (string | number | null | undefined)[][]) => rows.map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";

/** Every flow's usage in the period, for spreadsheets. */
export function overviewCsv(overview: AnalyticsOverview): string {
  return toCsv([
    ["flow", "app", "runs", "completed", "stopped", "failed", "success_rate", "people", "last_run"],
    ...overview.flows.map((f) => [
      f.name,
      f.appPackage,
      f.usage.runs,
      f.usage.completed,
      f.usage.stopped,
      f.usage.failed,
      f.usage.successRate == null ? null : f.usage.successRate.toFixed(3),
      f.usage.users,
      f.usage.lastRunAt ?? null,
    ]),
  ]);
}

/** A flow's runs per day and its steps' outcomes, for spreadsheets. */
export function flowCsv(analytics: FlowAnalytics): string {
  const outcomes = Object.keys(OUTCOME_COLORS);
  return toCsv([
    ["date", "completed", "stopped", "failed"],
    ...analytics.daily.map((d) => [d.date, d.completed, d.stopped, d.failed]),
    [],
    ["step", "element", "total", ...outcomes.map((o) => o.toLowerCase())],
    ...analytics.steps.map((s) => [s.label, s.elementId, s.total, ...outcomes.map((o) => s.outcomes[o] ?? 0)]),
  ]);
}

/** Saves [csv] as a file in the browser. */
export function downloadCsv(csv: string, name: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const a = Object.assign(document.createElement("a"), { href: url, download: `${name.replace(/[^\w-]+/g, "-")}.csv` });
  a.click();
  URL.revokeObjectURL(url);
}
