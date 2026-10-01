import { evaluate, evaluateBoolean, evaluateText, isYesWord, template, toNumber, truthy, type Value } from "@/lib/expressions";
import { validateValue } from "@/lib/rules";
import { sensitiveFieldTypes, type FlowStep, type Profile } from "@/lib/types";
import { PROFILE_VARIABLES, segments, variableName } from "./logic";

/**
 * Dry-run of a flow without a phone: walks the steps the way the phone's engine does (conditions,
 * else values, computed values, variables, templates, rules, loops, screen changes) and answers each
 * question from a script. When the script runs out, the result says which question is pending.
 */

export type SimEntryKind = "screen" | "ask" | "answer" | "fill" | "skip" | "press" | "variable" | "error" | "manual" | "done";

export interface SimEntry {
  kind: SimEntryKind;
  text: string;
  stepId?: string;
}

export interface SimPending {
  stepId: string;
  question: string;
  /** "yesno" questions are answered with yes/no; "text" with a value (or "skip"). */
  expects: "text" | "yesno";
}

export interface SimResult {
  transcript: SimEntry[];
  vars: Record<string, string>;
  /** Final value of each element step by step id. */
  values: Record<string, string>;
  pending: SimPending | null;
  finished: boolean;
}

export interface SimOptions {
  profile?: Profile | null;
  answers: string[];
  today?: () => Date;
}

const MAX_ATTEMPTS = 3;
const SKIP_WORDS = new Set(["skip", "next", "chhodo", "aage", "छोड़ो", "आगे"]);
const NO_WORDS = new Set(["no", "n", "nahi", "nahin", "na", "nope", "false", "0", "नहीं", "ना"]);

class NeedsInput extends Error {
  constructor(readonly pending: SimPending) {
    super("needs input");
  }
}

export function defaultQuestion(step: FlowStep): string {
  if (step.kind === "CHECKBOX" || step.kind === "SWITCH" || step.kind === "RADIO" || step.action === "TOGGLE") {
    return `Should I select "${step.label}"? Say yes or no.`;
  }
  if (step.kind === "DROPDOWN") return `Which option for ${step.label}?`;
  if (step.fieldType === "EMAIL") return "What is your email address?";
  if (step.fieldType === "PHONE") return "What is your mobile number?";
  if (step.fieldType === "DATE") return `Please say the ${step.label}, like 12 March 1990.`;
  return `Please say ${step.label}.`;
}

const PROFILE_KEY_VARIABLE: Record<string, string> = {
  FULL_NAME: "profile.name",
  FIRST_NAME: "profile.first_name",
  LAST_NAME: "profile.last_name",
  EMAIL: "profile.email",
  PHONE: "profile.phone",
  ADDRESS_LINE: "profile.address",
  CITY: "profile.city",
  STATE: "profile.state",
  PINCODE: "profile.pincode",
  DATE_OF_BIRTH: "profile.dob",
};

export function simulate(steps: FlowStep[], options: SimOptions): SimResult {
  const ordered = [...steps].sort((a, b) => a.order - b.order);
  const transcript: SimEntry[] = [];
  const vars: Record<string, string> = {};
  const values: Record<string, string> = {};
  const today = options.today ?? (() => new Date());
  const profile = options.profile ?? null;
  let cursor = 0;

  const log = (kind: SimEntryKind, text: string, stepId?: string) => transcript.push({ kind, text, stepId });
  const lookup = (name: string): Value | undefined => {
    if (name in vars) return vars[name];
    const p = PROFILE_VARIABLES.find((v) => v.name === name);
    return p && profile ? (p.get(profile) ?? null) : null;
  };
  const sensitive = (s: FlowStep) => !!s.fieldType && sensitiveFieldTypes.has(s.fieldType);
  const remember = (s: FlowStep, value: string) => {
    if (sensitive(s)) return;
    const name = variableName(s);
    vars[name] = value;
    if (vars.index) vars[`${name}_${vars.index}`] = value;
  };
  const safe = <T,>(f: () => T, fallback: T): T => {
    try {
      return f();
    } catch {
      return fallback;
    }
  };

  function ask(stepId: string, question: string, expects: SimPending["expects"]): string {
    log("ask", question, stepId);
    if (cursor >= options.answers.length) {
      transcript.pop();
      throw new NeedsInput({ stepId, question, expects });
    }
    const answer = options.answers[cursor++]!;
    log("answer", answer, stepId);
    return answer;
  }

  const isYes = isYesWord;
  const isNo = (a: string) => NO_WORDS.has(a.trim().toLowerCase());

  function fill(step: FlowStep, value: string, how: "fill" | "computed" | "default") {
    values[step.id] = value;
    remember(step, value);
    const prefix = how === "computed" ? "Computed" : how === "default" ? "Default" : "Filled";
    log("fill", `${prefix}: ${step.label} = ${value}`, step.id);
  }

  function setToggle(step: FlowStep, checked: boolean) {
    values[step.id] = checked ? "yes" : "no";
    remember(step, checked ? "yes" : "no");
    log("fill", `${checked ? "Selected" : "Unselected"}: ${step.label}`, step.id);
  }

  function conditionHolds(step: FlowStep) {
    if (!step.condition?.trim()) return true;
    return safe(() => evaluateBoolean(step.condition!, lookup, today), true);
  }

  function applyElse(step: FlowStep) {
    if (!["FILL", "TOGGLE"].includes(step.action)) {
      log("skip", `Skipped ${step.label || step.action} (condition false)`, step.id);
      return;
    }
    const value = step.elseValue?.trim() ? safe(() => evaluateText(step.elseValue!, lookup, today), "") : "";
    if (!value || sensitive(step)) {
      log("skip", `Skipped ${step.label} (condition false)`, step.id);
      return;
    }
    if (step.action === "TOGGLE") setToggle(step, truthy(value));
    else fill(step, value, "computed");
  }

  function runStep(step: FlowStep, loops: Map<string, FlowStep[]>) {
    switch (step.action) {
      case "SET_VARIABLE": {
        const value = step.valueExpression?.trim() ? safe(() => evaluateText(step.valueExpression!, lookup, today), "") : "";
        if (step.variable?.trim()) {
          vars[step.variable.trim()] = value;
          log("variable", `${step.variable.trim()} = ${value || "(empty)"}`, step.id);
        }
        return;
      }
      case "REPEAT":
        runLoop(step, loops.get(step.id) ?? []);
        return;
      case "READ": {
        const text = ask(step.id, `What does "${step.label}" show on screen?`, "text");
        remember(step, text);
        log("variable", `${variableName(step)} = ${text}`, step.id);
        return;
      }
      case "CLICK":
        log("press", `Pressed ${step.label}`, step.id);
        return;
      case "NEXT_SCREEN":
      case "OPEN_APP":
        return;
    }
    if (sensitive(step)) {
      log("manual", `${step.label}: typed by the user (never by voice)`, step.id);
      return;
    }
    if (step.valueExpression?.trim()) {
      const value = safe(() => evaluateText(step.valueExpression!, lookup, today), "");
      if (value) {
        if (step.action === "TOGGLE") setToggle(step, truthy(value));
        else fill(step, value, "computed");
        return;
      }
    }
    if (step.skip) {
      if (step.defaultValue?.trim() && step.action === "FILL") fill(step, step.defaultValue.trim(), "default");
      else log("skip", `Skipped ${step.label}`, step.id);
      return;
    }
    const base = template(step.question?.trim() || defaultQuestion(step), lookup, today);
    if (step.action === "TOGGLE") {
      for (let i = 0; i < MAX_ATTEMPTS; i++) {
        const a = ask(step.id, base, "yesno");
        if (isYes(a)) return setToggle(step, true);
        if (isNo(a)) return setToggle(step, false);
        if (SKIP_WORDS.has(a.trim().toLowerCase())) break;
        log("error", "Sorry, I didn't catch that.", step.id);
      }
      log("skip", `Skipped ${step.label}`, step.id);
      return;
    }
    const profileVar = step.profileKey ? PROFILE_KEY_VARIABLE[step.profileKey] : undefined;
    let suggestion = step.defaultValue?.trim() || (profileVar ? toTextOrNull(lookup(profileVar)) : null);
    for (let i = 0; i < MAX_ATTEMPTS; i++) {
      const question = suggestion ? `${base} Say yes to use ${suggestion}.` : base;
      const a = ask(step.id, question, "text");
      const lower = a.trim().toLowerCase();
      if (SKIP_WORDS.has(lower)) {
        log("skip", `Skipped ${step.label}`, step.id);
        return;
      }
      if (suggestion && isYes(a)) return fill(step, suggestion, "fill");
      if (suggestion && isNo(a)) {
        suggestion = null;
        i--;
        continue;
      }
      const error = validateValue(a, step.fieldType, step.rules);
      if (error) {
        log("error", `${error}. Please try again.`, step.id);
        continue;
      }
      return fill(step, a.trim(), "fill");
    }
    log("skip", `Skipped ${step.label} after ${MAX_ATTEMPTS} tries`, step.id);
  }

  function runLoop(loop: FlowStep, body: FlowStep[]) {
    const spec = loop.repeat;
    if (!spec || body.length === 0) return;
    const item = spec.itemLabel?.trim() || loop.label || "item";
    const max = Math.min(Math.max(spec.maxIterations, 1), 50);
    try {
      for (let index = 1; index <= max; index++) {
        vars.index = String(index);
        log("screen", `${item} ${index}`, loop.id);
        runSteps(body, new Map());
        const more = spec.countExpression?.trim()
          ? index < Math.trunc(toNumber(safe(() => evaluate(spec.countExpression!, lookup, today), null)) ?? 1)
          : isYes(ask(loop.id, `Add another ${item}?`, "yesno"));
        if (!more || index === max) break;
        if (spec.addMoreLabel?.trim() || spec.addMoreElementId) log("press", `Pressed ${spec.addMoreLabel?.trim() || "add more"}`, loop.id);
      }
    } finally {
      delete vars.index;
    }
  }

  function runSteps(list: FlowStep[], loops: Map<string, FlowStep[]>) {
    for (const step of list) {
      if (!conditionHolds(step)) applyElse(step);
      else runStep(step, loops);
    }
  }

  try {
    segments(ordered).forEach((segment, i) => {
      const first = segment[0];
      if (first?.action === "OPEN_APP") log("screen", `Open app ${first.appPackage || "?"}`, first.id);
      else if (first?.action === "NEXT_SCREEN") log("screen", `Wait for the next screen${first.appPackage ? ` in ${first.appPackage}` : ""}`, first.id);
      else if (i === 0) log("screen", "Start", undefined);
      const bodyIds = new Set(segment.filter((s) => s.action === "REPEAT").flatMap((s) => s.repeat?.stepIds ?? []));
      const loops = new Map(
        segment.filter((s) => s.action === "REPEAT").map((s) => [s.id, segment.filter((b) => s.repeat?.stepIds.includes(b.id) && b.action !== "REPEAT")] as const),
      );
      const submit = [...segment].reverse().find((s) => s.action === "CLICK" && !s.condition?.trim() && !bodyIds.has(s.id));
      runSteps(
        segment.filter((s) => !bodyIds.has(s.id) && s !== submit),
        loops,
      );
      if (submit) {
        if (submit.skip) log("press", `Pressed ${submit.label}`, submit.id);
        else {
          const a = ask(submit.id, template(submit.question?.trim() || `All done. Shall I press ${submit.label}?`, lookup, today), "yesno");
          if (isYes(a) || ["submit", "next"].includes(a.trim().toLowerCase())) log("press", `Pressed ${submit.label}`, submit.id);
          else log("skip", `Did not press ${submit.label}`, submit.id);
        }
      }
    });
    log("done", "All done.");
    return { transcript, vars, values, pending: null, finished: true };
  } catch (e) {
    if (e instanceof NeedsInput) return { transcript, vars, values, pending: e.pending, finished: false };
    throw e;
  }
}

function toTextOrNull(v: Value | undefined): string | null {
  if (v === null || v === undefined) return null;
  const s = String(v).trim();
  return s || null;
}
