import { describe, expect, it } from "vitest";
import type { FlowStep } from "@/lib/types";
import { newLogicStep } from "./editor";
import { simulate } from "./simulator";

const field = (id: string, label: string, patch: Partial<FlowStep> = {}): FlowStep => ({
  id, order: 0, elementId: `vid:${id}`, label, kind: "TEXT_FIELD", fieldType: "TEXT", action: "FILL", rules: [], skip: false, ...patch,
});
const ordered = (...steps: FlowStep[]) => steps.map((s, i) => ({ ...s, order: i }));
const today = () => new Date(2026, 9, 1);

describe("simulate", () => {
  const married: FlowStep = { ...field("m", "Married"), kind: "CHECKBOX", fieldType: null, action: "TOGGLE", variable: "married" };
  const spouse = field("s", "Spouse name", { condition: "yes(married)", elseValue: "'N/A'" });
  const submit: FlowStep = { ...field("go", "Submit"), kind: "BUTTON", fieldType: null, action: "CLICK", skip: true };

  it("follows conditions and else values", () => {
    const no = simulate(ordered(married, spouse, submit), { answers: ["no"], today });
    expect(no.finished).toBe(true);
    expect(no.values).toEqual({ m: "no", s: "N/A" });
    expect(no.transcript.at(-2)).toMatchObject({ kind: "press", text: "Pressed Submit" });

    const yes = simulate(ordered(married, spouse, submit), { answers: ["haan", "Priya"], today });
    expect(yes.values.s).toBe("Priya");
  });

  it("stops at the next question when answers run out", () => {
    const r = simulate(ordered(married, spouse), { answers: ["yes"], today });
    expect(r.finished).toBe(false);
    expect(r.pending).toEqual({ stepId: "s", question: "Please say Spouse name.", expects: "text" });
  });

  it("uses templates, computed values, set-variable and the profile", () => {
    const first = field("f", "First name", { variable: "first", valueExpression: "profile.first_name" });
    const last = field("l", "Last name", { question: "{first}, your last name?" });
    const full = { ...newLogicStep("SET_VARIABLE", "v"), variable: "full", valueExpression: "concat(first, ' ', last_name)" };
    const display = field("d", "Display", { valueExpression: "upper(full)" });
    const r = simulate(ordered(first, last, full, display), { answers: ["Sharma"], profile: { fullName: "Rahul Kumar" }, today });
    expect(r.transcript.find((e) => e.kind === "ask")?.text).toBe("Rahul, your last name?");
    expect(r.values.d).toBe("RAHUL SHARMA");
    expect(r.vars.full).toBe("Rahul Sharma");
  });

  it("validates answers with the step's rules", () => {
    const email = field("e", "Email", { fieldType: "EMAIL" });
    const r = simulate([email], { answers: ["not an email", "a@b.co"], today });
    expect(r.transcript.some((e) => e.kind === "error" && e.text.startsWith("That doesn't look like an email"))).toBe(true);
    expect(r.values.e).toBe("a@b.co");
  });

  it("repeats a group per item, asking or counting", () => {
    const item = field("i", "Item", { question: "Item {index}?" });
    const loop = { ...newLogicStep("REPEAT", "r"), repeat: { stepIds: ["i"], maxIterations: 5, addMoreLabel: "Add item", itemLabel: "item" } };
    const asked = simulate(ordered(item, loop), { answers: ["Rice", "yes", "Dal", "no"], today });
    expect(asked.finished).toBe(true);
    expect(asked.vars.item_1 ?? asked.vars["item_1"]).toBe("Rice");
    expect(asked.vars.item_2).toBe("Dal");
    expect(asked.transcript.filter((e) => e.kind === "press").map((e) => e.text)).toEqual(["Pressed Add item"]);

    const kids = field("k", "Kids", { variable: "kids" });
    const counted = { ...loop, repeat: { ...loop.repeat, countExpression: "kids" } };
    const r = simulate(ordered(kids, item, counted), { answers: ["3", "a", "b", "c"], today });
    expect(r.finished).toBe(true);
    expect(r.transcript.filter((e) => e.kind === "ask")).toHaveLength(4);
  });

  it("walks screens and apps, and never asks for sensitive fields", () => {
    const pin: FlowStep = field("p", "PIN", { fieldType: "PIN" });
    const next = { ...newLogicStep("NEXT_SCREEN", "n") };
    const open = { ...newLogicStep("OPEN_APP", "o"), appPackage: "com.pay" };
    const note = field("note", "Note", { valueExpression: "'hi'" });
    const r = simulate(ordered(pin, next, note, open, note), { answers: [], today });
    expect(r.finished).toBe(true);
    expect(r.transcript.filter((e) => e.kind === "screen").map((e) => e.text)).toEqual(["Start", "Wait for the next screen", "Open app com.pay"]);
    expect(r.transcript.some((e) => e.kind === "manual")).toBe(true);
  });
});
