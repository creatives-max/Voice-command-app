import type { Run } from "@/lib/types";

export const OUTCOME_LABELS: Record<Run["screens"][number]["steps"][number]["outcome"], string> = {
  FILLED: "Filled by voice",
  DEFAULT_FILLED: "Filled with default",
  KEPT: "Kept existing value",
  SKIPPED: "Skipped",
  MANUAL: "Typed by user",
  CLICKED: "Pressed",
  TOGGLED: "Toggled",
  FAILED: "Failed",
};

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

/** Share of fields that needed no manual work, as a whole percentage. */
export function automationRate(run: Run): number {
  const steps = run.screens.flatMap((s) => s.steps).filter((s) => s.kind !== "BUTTON" && s.kind !== "LINK");
  if (steps.length === 0) return 0;
  const automated = steps.filter((s) => ["FILLED", "DEFAULT_FILLED", "KEPT", "TOGGLED"].includes(s.outcome)).length;
  return Math.round((automated / steps.length) * 100);
}
