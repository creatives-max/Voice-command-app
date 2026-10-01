/**
 * Friendly schedules ⇄ 5-field cron (minute hour day-of-month month day-of-week), the format the
 * backend scheduler runs. Anything the builder can't express stays as a custom cron.
 */

export type ScheduleSpec =
  | { kind: "daily"; time: string }
  | { kind: "weekdays"; time: string }
  | { kind: "days"; days: number[]; time: string }
  | { kind: "monthly"; day: number; time: string }
  | { kind: "interval"; minutes: number }
  | { kind: "custom"; cron: string };

export const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] as const;
export const INTERVALS = [5, 10, 15, 30, 60] as const;

const TIME = /^([01]?\d|2[0-3]):([0-5]\d)$/;

export function parseTime(time: string): { hour: number; minute: number } | null {
  const m = TIME.exec(time.trim());
  return m ? { hour: Number(m[1]), minute: Number(m[2]) } : null;
}

const pad = (n: number) => String(n).padStart(2, "0");

/** Cron for a spec, or null when the spec is incomplete. */
export function toCron(spec: ScheduleSpec): string | null {
  if (spec.kind === "custom") return spec.cron.trim().split(/\s+/).length === 5 ? spec.cron.trim().replace(/\s+/g, " ") : null;
  if (spec.kind === "interval") return spec.minutes >= 60 ? "0 * * * *" : `*/${spec.minutes} * * * *`;
  const t = parseTime(spec.time);
  if (!t) return null;
  const at = `${t.minute} ${t.hour}`;
  switch (spec.kind) {
    case "daily":
      return `${at} * * *`;
    case "weekdays":
      return `${at} * * 1-5`;
    case "days": {
      const days = [...new Set(spec.days)].filter((d) => d >= 0 && d <= 6).sort((a, b) => a - b);
      return days.length ? `${at} * * ${days.join(",")}` : null;
    }
    case "monthly":
      return spec.day >= 1 && spec.day <= 31 ? `${at} ${spec.day} * *` : null;
  }
}

/** The builder's spec for a cron (custom if it isn't one of the simple shapes). */
export function fromCron(cron: string): ScheduleSpec {
  const f = cron.trim().split(/\s+/);
  if (f.length !== 5) return { kind: "custom", cron };
  const [min, hour, dom, mon, dow] = f as [string, string, string, string, string];
  const interval = /^\*\/(\d+)$/.exec(min);
  if (interval && hour === "*" && dom === "*" && mon === "*" && dow === "*" && INTERVALS.includes(Number(interval[1]) as never)) {
    return { kind: "interval", minutes: Number(interval[1]) };
  }
  if (min === "0" && hour === "*" && dom === "*" && mon === "*" && dow === "*") return { kind: "interval", minutes: 60 };
  if (!/^\d+$/.test(min) || !/^\d+$/.test(hour) || mon !== "*") return { kind: "custom", cron };
  const time = `${pad(Number(hour))}:${pad(Number(min))}`;
  if (dom === "*" && dow === "*") return { kind: "daily", time };
  if (dom === "*" && dow === "1-5") return { kind: "weekdays", time };
  if (dom === "*" && /^[0-7](,[0-7])*$/.test(dow)) return { kind: "days", days: [...new Set(dow.split(",").map((d) => Number(d) % 7))].sort(), time };
  if (/^\d+$/.test(dom) && dow === "*") return { kind: "monthly", day: Number(dom), time };
  return { kind: "custom", cron };
}

function formatTime(hour: number, minute: number) {
  const h = hour % 12 === 0 ? 12 : hour % 12;
  return `${h}:${pad(minute)} ${hour < 12 ? "AM" : "PM"}`;
}

/** Plain-language description of a cron. */
export function describeCron(cron: string): string {
  const spec = fromCron(cron);
  const time = "time" in spec ? parseTime(spec.time) : null;
  const at = time ? ` at ${formatTime(time.hour, time.minute)}` : "";
  switch (spec.kind) {
    case "interval":
      return spec.minutes >= 60 ? "Every hour" : `Every ${spec.minutes} minutes`;
    case "daily":
      return `Every day${at}`;
    case "weekdays":
      return `Weekdays${at}`;
    case "days":
      return `Every ${spec.days.map((d) => DAY_NAMES[d]).join(", ")}${at}`;
    case "monthly":
      return `Monthly on day ${spec.day}${at}`;
    case "custom":
      return `Custom schedule (${cron})`;
  }
}

/** Browsers (ICU) still report some zones by their old names; show the current ones. */
const LEGACY_ZONES: Record<string, string> = {
  "Asia/Calcutta": "Asia/Kolkata",
  "Asia/Katmandu": "Asia/Kathmandu",
  "Asia/Saigon": "Asia/Ho_Chi_Minh",
  "Asia/Rangoon": "Asia/Yangon",
  "Europe/Kiev": "Europe/Kyiv",
  "America/Buenos_Aires": "America/Argentina/Buenos_Aires",
};

export const normalizeZone = (zone: string) => LEGACY_ZONES[zone] ?? zone;

/** Time zones offered in the picker (the browser's own first). */
export function timeZones(): string[] {
  const own = normalizeZone(Intl.DateTimeFormat().resolvedOptions().timeZone);
  let all: string[] = [];
  try {
    all = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.("timeZone") ?? [];
  } catch {
    all = [];
  }
  if (!all.length) all = ["UTC", "Asia/Kolkata", "Europe/London", "America/New_York"];
  const zones = [...new Set(all.map(normalizeZone))].sort();
  return [own, ...zones.filter((z) => z !== own)];
}
