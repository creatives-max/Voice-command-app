import { sensitiveFieldTypes, type Flow, type FlowStep, type FlowVersion } from "@/lib/types";

export interface EditorState {
  baseVersion: number;
  name: string;
  steps: FlowStep[];
  changeNote: string;
  dirty: boolean;
}

export type EditorAction =
  | { type: "reset"; flow: Flow }
  | { type: "rename"; name: string }
  | { type: "update"; id: string; patch: Partial<FlowStep> }
  | { type: "move"; from: number; to: number }
  | { type: "addRule"; id: string; rule: string }
  | { type: "removeRule"; id: string; index: number }
  | { type: "note"; text: string };

export const initialEditor = (flow: Flow): EditorState => ({
  baseVersion: flow.version,
  name: flow.name,
  steps: [...flow.steps].sort((a, b) => a.order - b.order),
  changeNote: "",
  dirty: false,
});

const renumber = (steps: FlowStep[]) => steps.map((s, i) => ({ ...s, order: i }));

export function editorReducer(state: EditorState, action: EditorAction): EditorState {
  switch (action.type) {
    case "reset":
      return initialEditor(action.flow);
    case "rename":
      return { ...state, name: action.name, dirty: true };
    case "note":
      return { ...state, changeNote: action.text };
    case "update":
      return { ...state, dirty: true, steps: state.steps.map((s) => (s.id === action.id ? { ...s, ...action.patch } : s)) };
    case "move": {
      if (action.from === action.to || action.to < 0 || action.to >= state.steps.length) return state;
      const steps = [...state.steps];
      const [moved] = steps.splice(action.from, 1);
      if (!moved) return state;
      steps.splice(action.to, 0, moved);
      return { ...state, dirty: true, steps: renumber(steps) };
    }
    case "addRule": {
      const rule = action.rule.trim();
      if (!rule) return state;
      return {
        ...state,
        dirty: true,
        steps: state.steps.map((s) => (s.id === action.id && !s.rules.includes(rule) ? { ...s, rules: [...s.rules, rule] } : s)),
      };
    }
    case "removeRule":
      return {
        ...state,
        dirty: true,
        steps: state.steps.map((s) => (s.id === action.id ? { ...s, rules: s.rules.filter((_, i) => i !== action.index) } : s)),
      };
  }
}

const KNOWN_RULES = new Set(["required", "email", "phone", "pincode", "digits", "min", "max", "regex", "oneof"]);

/** Rule presets offered in the editor (same syntax the phone and backend validate). */
export const RULE_PRESETS = [
  { label: "Required", rule: "required" },
  { label: "Email", rule: "email" },
  { label: "10-digit mobile", rule: "digits:10" },
  { label: "6-digit PIN code", rule: "pincode" },
  { label: "At least 3 characters", rule: "min:3" },
  { label: "At most 50 characters", rule: "max:50" },
] as const;

/** Mirrors backend validation so errors show before saving. Returns errors per step id. */
export function validateSteps(steps: FlowStep[]): Record<string, string[]> {
  const errors: Record<string, string[]> = {};
  const add = (id: string, msg: string) => (errors[id] ??= []).push(msg);
  for (const s of steps) {
    for (const rule of s.rules) {
      const name = rule.split(":")[0]!.trim().toLowerCase();
      const arg = rule.includes(":") ? rule.slice(rule.indexOf(":") + 1).trim() : "";
      if (!KNOWN_RULES.has(name)) add(s.id, `Unknown rule "${name}"`);
      else if (["digits", "min", "max"].includes(name) && !/^\d{1,4}$/.test(arg)) add(s.id, `Rule "${name}" needs a number, e.g. ${name}:10`);
      else if (name === "regex") {
        try {
          new RegExp(arg);
          if (!arg) add(s.id, "Regex rule needs a pattern");
        } catch {
          add(s.id, "Invalid regular expression");
        }
      } else if (name === "oneof" && !arg.split("|").some((o) => o.trim())) add(s.id, "oneOf needs options like a|b|c");
    }
    if (s.helpVideoUrl?.trim()) {
      try {
        const url = new URL(s.helpVideoUrl.trim());
        if (url.protocol !== "https:") add(s.id, "Help video must be an https:// link");
      } catch {
        add(s.id, "Help video must be a valid https:// link");
      }
    }
    if (s.defaultValue?.trim() && s.fieldType && sensitiveFieldTypes.has(s.fieldType)) add(s.id, "Password/OTP/PIN fields cannot have a default value");
    if (s.defaultValue?.trim() && s.action === "CLICK") add(s.id, "Button steps cannot have a default value");
    if ((s.question?.length ?? 0) > 500) add(s.id, "Question is too long (max 500)");
  }
  return errors;
}

/** Payload for PUT /flows/:id (empty strings become nulls). */
export function toUpdatePayload(state: EditorState) {
  const blank = (v: string | null | undefined) => (v && v.trim() ? v.trim() : null);
  return {
    expectedVersion: state.baseVersion,
    name: state.name.trim(),
    changeNote: state.changeNote.trim() || undefined,
    steps: state.steps.map((s, i) => ({
      ...s,
      order: i,
      question: blank(s.question),
      defaultValue: blank(s.defaultValue),
      helpVideoUrl: blank(s.helpVideoUrl),
    })),
  };
}

/** Human-readable differences between two versions (for the history page). */
export function describeChanges(previous: FlowVersion | undefined, current: FlowVersion): string[] {
  if (!previous) return ["Initial version"];
  const changes: string[] = [];
  const before = new Map(previous.steps.map((s) => [s.id, s]));
  const prevOrder = previous.steps.map((s) => s.id).join(",");
  const currOrder = current.steps.map((s) => s.id).join(",");
  if (prevOrder !== currOrder) changes.push("Step order changed");
  for (const s of current.steps) {
    const p = before.get(s.id);
    if (!p) {
      changes.push(`Added "${s.label}"`);
      continue;
    }
    if ((p.question ?? "") !== (s.question ?? "")) changes.push(`Question for "${s.label}" changed`);
    if ((p.defaultValue ?? "") !== (s.defaultValue ?? "")) changes.push(`Default for "${s.label}" changed`);
    if (p.skip !== s.skip) changes.push(`"${s.label}" ${s.skip ? "is now skipped" : "is no longer skipped"}`);
    if (p.rules.join("|") !== s.rules.join("|")) changes.push(`Rules for "${s.label}" changed`);
    if ((p.helpVideoUrl ?? "") !== (s.helpVideoUrl ?? "")) changes.push(`Help video for "${s.label}" changed`);
  }
  for (const p of previous.steps) if (!current.steps.some((s) => s.id === p.id)) changes.push(`Removed "${p.label}"`);
  return changes.length ? changes : ["No step changes"];
}
