import type { Flow, FlowStep, FlowVersion } from "@/lib/types";
import { describeChanges, editorReducer, initialEditor, toUpdatePayload, validateSteps } from "./editor";

const step = (id: string, order: number, patch: Partial<FlowStep> = {}): FlowStep => ({
  id,
  order,
  elementId: `vid:${id}`,
  label: id.toUpperCase(),
  kind: "TEXT_FIELD",
  fieldType: "TEXT",
  action: "FILL",
  rules: [],
  skip: false,
  ...patch,
});

const flow: Flow = {
  id: "f1",
  version: 3,
  appPackage: "com.shop",
  name: "Signup",
  screenSignature: "sig",
  steps: [step("b", 1), step("a", 0), step("c", 2)],
  updatedAtMillis: 0,
  createdAt: "",
  updatedAt: "",
};

describe("editorReducer", () => {
  it("sorts steps by order and tracks the base version", () => {
    const s = initialEditor(flow);
    expect(s.steps.map((x) => x.id)).toEqual(["a", "b", "c"]);
    expect(s.baseVersion).toBe(3);
    expect(s.dirty).toBe(false);
  });

  it("reorders and renumbers", () => {
    const s = editorReducer(initialEditor(flow), { type: "move", from: 2, to: 0 });
    expect(s.steps.map((x) => `${x.id}${x.order}`)).toEqual(["c0", "a1", "b2"]);
    expect(s.dirty).toBe(true);
    expect(editorReducer(s, { type: "move", from: 0, to: 9 })).toBe(s);
  });

  it("edits fields and rules without duplicates", () => {
    let s = editorReducer(initialEditor(flow), { type: "update", id: "a", patch: { question: "Naam?", skip: true } });
    s = editorReducer(s, { type: "addRule", id: "a", rule: "required" });
    s = editorReducer(s, { type: "addRule", id: "a", rule: "required" });
    s = editorReducer(s, { type: "addRule", id: "a", rule: "min:3" });
    s = editorReducer(s, { type: "removeRule", id: "a", index: 0 });
    const a = s.steps.find((x) => x.id === "a")!;
    expect(a.question).toBe("Naam?");
    expect(a.skip).toBe(true);
    expect(a.rules).toEqual(["min:3"]);
  });

  it("builds the update payload with blanks as null", () => {
    let s = editorReducer(initialEditor(flow), { type: "update", id: "a", patch: { question: "  ", helpVideoUrl: " https://x.dev/v " } });
    s = editorReducer(s, { type: "note", text: " tweak " });
    const p = toUpdatePayload(s);
    expect(p.expectedVersion).toBe(3);
    expect(p.changeNote).toBe("tweak");
    expect(p.steps[0]!.question).toBeNull();
    expect(p.steps[0]!.helpVideoUrl).toBe("https://x.dev/v");
  });
});

describe("validateSteps", () => {
  it("mirrors backend rules", () => {
    const errors = validateSteps([
      step("a", 0, { rules: ["digits:x", "regex:[", "nope"] }),
      step("b", 1, { helpVideoUrl: "http://insecure.dev" }),
      step("c", 2, { fieldType: "PASSWORD", defaultValue: "123" }),
      step("d", 3, { rules: ["required", "digits:10", "oneOf:a|b"], helpVideoUrl: "https://ok.dev/v.mp4" }),
    ]);
    expect(errors.a).toHaveLength(3);
    expect(errors.b?.[0]).toContain("https");
    expect(errors.c?.[0]).toContain("cannot have a default");
    expect(errors.d).toBeUndefined();
  });
});

describe("describeChanges", () => {
  it("summarizes edits between versions", () => {
    const v1: FlowVersion = { version: 1, steps: [step("a", 0), step("b", 1)], source: "DEVICE", createdAt: "" };
    const v2: FlowVersion = { version: 2, steps: [step("b", 0), step("a", 1, { question: "Q", skip: true })], source: "DASHBOARD", createdAt: "" };
    expect(describeChanges(undefined, v1)).toEqual(["Initial version"]);
    expect(describeChanges(v1, v2)).toEqual(["Step order changed", 'Question for "A" changed', '"A" is now skipped']);
  });
});
