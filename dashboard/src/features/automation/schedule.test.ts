import { describe, expect, it } from "vitest";
import { describeCron, fromCron, normalizeZone, timeZones, toCron, type ScheduleSpec } from "./schedule";

describe("schedule builder", () => {
  const cases: [ScheduleSpec, string][] = [
    [{ kind: "daily", time: "09:30" }, "30 9 * * *"],
    [{ kind: "weekdays", time: "18:05" }, "5 18 * * 1-5"],
    [{ kind: "days", days: [6, 0, 6], time: "07:00" }, "0 7 * * 0,6"],
    [{ kind: "monthly", day: 1, time: "00:00" }, "0 0 1 * *"],
    [{ kind: "interval", minutes: 15 }, "*/15 * * * *"],
    [{ kind: "interval", minutes: 60 }, "0 * * * *"],
  ];

  it.each(cases)("%j ⇄ %s", (spec, cron) => {
    expect(toCron(spec)).toBe(cron);
    expect(toCron(fromCron(cron))).toBe(cron);
  });

  it("keeps unusual crons as custom and rejects incomplete specs", () => {
    expect(fromCron("0 9 * 1 *")).toEqual({ kind: "custom", cron: "0 9 * 1 *" });
    expect(fromCron("*/7 * * * *").kind).toBe("custom");
    expect(toCron({ kind: "daily", time: "25:00" })).toBeNull();
    expect(toCron({ kind: "days", days: [], time: "09:00" })).toBeNull();
    expect(toCron({ kind: "custom", cron: "* * *" })).toBeNull();
  });

  it("describes schedules in plain language", () => {
    expect(describeCron("30 9 * * *")).toBe("Every day at 9:30 AM");
    expect(describeCron("5 18 * * 1-5")).toBe("Weekdays at 6:05 PM");
    expect(describeCron("0 7 * * 0,6")).toBe("Every Sun, Sat at 7:00 AM");
    expect(describeCron("0 0 1 * *")).toBe("Monthly on day 1 at 12:00 AM");
    expect(describeCron("*/15 * * * *")).toBe("Every 15 minutes");
    expect(describeCron("0 9 * 1 *")).toBe("Custom schedule (0 9 * 1 *)");
  });

  it("offers current time zone names", () => {
    expect(normalizeZone("Asia/Calcutta")).toBe("Asia/Kolkata");
    const zones = timeZones();
    expect(zones).toContain("Asia/Kolkata");
    expect(zones).not.toContain("Asia/Calcutta");
    expect(new Set(zones).size).toBe(zones.length);
  });
});
