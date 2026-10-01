import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import type { FlowStep } from "@/lib/types";
import { applyTemplate, bestTemplate, matchTemplate, type ElementLike } from "./template-match";

function repoFile(rel: string) {
  let dir = process.cwd();
  while (!existsSync(path.join(dir, rel))) {
    const parent = path.dirname(dir);
    if (parent === dir) throw new Error(`${rel} not found`);
    dir = parent;
  }
  return JSON.parse(readFileSync(path.join(dir, rel), "utf8"));
}

interface RawStep extends Partial<FlowStep> {
  id: string;
  keywords: string[];
}

const templates = (repoFile("backend/application/src/main/resources/templates/starter-templates.json").templates as { id: string; steps: RawStep[] }[]).map(
  (t) => ({
    id: t.id,
    steps: t.steps.map((raw) => {
      const s: Partial<RawStep> = { ...raw };
      delete s.keywords;
      return { action: "FILL", rules: [], skip: false, ...s } as FlowStep;
    }),
    keywords: Object.fromEntries(t.steps.map((s) => [s.id, s.keywords])),
  }),
);
const spec = repoFile("docs/spec/template-matching.json") as {
  screens: { name: string; elements: [string, string, string | null][]; expected: string | null; mapping: Record<string, string> }[];
};

describe("template matching (shared vectors)", () => {
  it.each(spec.screens.map((s) => [s.name, s] as const))("%s", (_name, screen) => {
    const elements: ElementLike[] = screen.elements.map(([kind, label, fieldType], i) => ({
      id: `e${i}`,
      kind: kind as FlowStep["kind"],
      label,
      fieldType: fieldType as FlowStep["fieldType"],
    }));
    const best = bestTemplate(templates, elements);
    expect(best?.id ?? null).toBe(screen.expected);
    if (!best) return;
    const mapping = Object.fromEntries([...matchTemplate(best, elements)].map(([k, v]) => [k, v.label]));
    expect(mapping).toEqual(screen.mapping);
  });

  it("applies a template without overwriting the user's own edits", () => {
    const address = templates.find((t) => t.id.endsWith("3"))!;
    const steps: FlowStep[] = [
      { id: "a", order: 0, elementId: "v:a", label: "City", kind: "TEXT_FIELD", fieldType: "TEXT", action: "FILL", rules: [], skip: false, question: "Shehar?" },
      { id: "b", order: 1, elementId: "v:b", label: "Pincode", kind: "TEXT_FIELD", fieldType: "PINCODE", action: "FILL", rules: ["required"], skip: false },
    ];
    const { steps: next, changes } = applyTemplate(steps, address);
    expect(next[0]!.question).toBe("Shehar?");
    expect(next[0]!.profileKey).toBe("CITY");
    expect(next[1]!.rules).toEqual(["required", "pincode"]);
    expect(next[1]!.question).toBe("What is the 6 digit PIN code?");
    expect(changes.map((c) => c.label)).toEqual(["City", "Pincode"]);
  });
});
