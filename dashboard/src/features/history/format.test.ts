import type { Run } from "@/lib/types";
import { automationRate, formatDuration } from "./format";

const run = (outcomes: Run["screens"][number]["steps"][number]["outcome"][]): Run => ({
  sessionId: "s",
  appPackage: "com.app",
  startedAtMillis: 0,
  endedAtMillis: 1,
  status: "COMPLETED",
  language: "ENGLISH",
  filledCount: 0,
  stepCount: outcomes.length,
  screens: [
    {
      appPackage: "com.app",
      screenSignature: "sig",
      steps: [
        ...outcomes.map((outcome, i) => ({ elementId: `e${i}`, label: "L", kind: "TEXT_FIELD" as const, outcome })),
        { elementId: "b", label: "Go", kind: "BUTTON" as const, outcome: "CLICKED" as const },
      ],
    },
  ],
});

describe("history formatting", () => {
  it("formats durations", () => {
    expect(formatDuration(9_500)).toBe("9s");
    expect(formatDuration(125_000)).toBe("2m 5s");
  });

  it("computes automation rate ignoring buttons", () => {
    expect(automationRate(run(["FILLED", "MANUAL", "SKIPPED", "DEFAULT_FILLED"]))).toBe(50);
    expect(automationRate(run([]))).toBe(0);
  });
});
