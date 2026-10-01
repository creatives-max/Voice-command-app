import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import type { FlowStep, Profile } from "@/lib/types";
import { simulate, type SimPending } from "./simulator";

/** Runs the shared vectors in docs/spec/simulation.json (also run by the phone engine and the backend). */

interface Expected {
  finished: boolean;
  values?: Record<string, string>;
  vars?: Record<string, string>;
  pending?: SimPending;
  asks?: string[];
  presses?: string[];
  screens?: string[];
  errorCount?: number;
  manualCount?: number;
}

interface Case {
  name: string;
  steps: Partial<FlowStep>[];
  answers: string[];
  profile?: Profile;
  expected: Expected;
}

function loadSpec(): { cases: Case[] } {
  let dir = process.cwd();
  while (!existsSync(path.join(dir, "docs/spec/simulation.json"))) {
    const parent = path.dirname(dir);
    if (parent === dir) throw new Error("docs/spec/simulation.json not found");
    dir = parent;
  }
  return JSON.parse(readFileSync(path.join(dir, "docs/spec/simulation.json"), "utf8")) as { cases: Case[] };
}

const toStep = (s: Partial<FlowStep>): FlowStep => ({
  fieldType: null, action: "FILL", rules: [], skip: false, ...s,
} as FlowStep);

describe("shared simulation vectors", () => {
  const spec = loadSpec();
  it("has vectors", () => expect(spec.cases.length).toBeGreaterThan(10));

  it.each(spec.cases.map((c) => [c.name, c] as const))("%s", (_, c) => {
    const r = simulate(c.steps.map(toStep), { answers: c.answers, profile: c.profile ?? null, today: () => new Date(2026, 9, 1) });
    const e = c.expected;
    const of = (kind: string) => r.transcript.filter((t) => t.kind === kind);
    expect(r.finished).toBe(e.finished);
    if (e.values) expect(r.values).toEqual(e.values);
    if (e.vars) expect(r.vars).toMatchObject(e.vars);
    expect(r.pending).toEqual(e.pending ?? null);
    if (e.asks) expect(of("ask").map((t) => t.text)).toEqual(e.asks);
    if (e.presses) expect(of("press").map((t) => t.text)).toEqual(e.presses);
    if (e.screens) expect(of("screen").map((t) => t.text)).toEqual(e.screens);
    if (e.errorCount !== undefined) expect(of("error")).toHaveLength(e.errorCount);
    if (e.manualCount !== undefined) expect(of("manual")).toHaveLength(e.manualCount);
  });
});
