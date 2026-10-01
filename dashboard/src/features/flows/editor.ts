import { templateErrors, validate as validateExpression } from "@/lib/expressions";
import { boundaryActions, elementActions, sensitiveFieldTypes, type Flow, type FlowStep, type FlowVersion } from "@/lib/types";
import { ACTION_LABELS, screenIndex, VARIABLE_NAME } from "./logic";

export type LogicAction = "SET_VARIABLE" | "REPEAT" | "NEXT_SCREEN" | "OPEN_APP";

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
  | { type: "note"; text: string }
  /** Adds a logic step after [afterIndex] (or at the end). */
  | { type: "addLogic"; action: LogicAction; id: string; afterIndex?: number }
  | { type: "remove"; id: string }
  /** Appends another flow's steps as the next screen (or in another app). */
  | { type: "appendFlow"; flow: Flow; boundary: "NEXT_SCREEN" | "OPEN_APP"; idPrefix: string; sameApp: boolean };

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
    case "addLogic": {
      const step = newLogicStep(action.action, action.id);
      const at = action.afterIndex === undefined ? state.steps.length : action.afterIndex + 1;
      const steps = [...state.steps];
      steps.splice(Math.max(0, Math.min(at, steps.length)), 0, step);
      return { ...state, dirty: true, steps: renumber(steps) };
    }
    case "remove": {
      const steps = state.steps
        .filter((s) => s.id !== action.id)
        .map((s) => (s.repeat?.stepIds.includes(action.id) ? { ...s, repeat: { ...s.repeat, stepIds: s.repeat.stepIds.filter((x) => x !== action.id) } } : s));
      return { ...state, dirty: true, steps: renumber(steps) };
    }
    case "appendFlow": {
      const source = [...action.flow.steps].sort((a, b) => a.order - b.order);
      const rename = (id: string) => `${action.idPrefix}${id}`;
      const boundary: FlowStep = {
        ...newLogicStep(action.boundary, rename("boundary")),
        label: action.boundary === "OPEN_APP" ? action.flow.name : `Next: ${action.flow.name}`,
        appPackage: action.boundary === "OPEN_APP" || !action.sameApp ? action.flow.appPackage : null,
      };
      const imported = source.map((s) => ({
        ...s,
        id: rename(s.id),
        repeat: s.repeat ? { ...s.repeat, stepIds: s.repeat.stepIds.map(rename) } : s.repeat,
      }));
      return { ...state, dirty: true, steps: renumber([...state.steps, boundary, ...imported]) };
    }
    case "removeRule":
      return {
        ...state,
        dirty: true,
        steps: state.steps.map((s) => (s.id === action.id ? { ...s, rules: s.rules.filter((_, i) => i !== action.index) } : s)),
      };
  }
}

/** A new logic step with sensible defaults. */
export function newLogicStep(action: LogicAction, id: string): FlowStep {
  return {
    id,
    order: 0,
    elementId: "",
    label: ACTION_LABELS[action] ?? action,
    kind: "BUTTON",
    fieldType: null,
    action,
    question: null,
    rules: [],
    defaultValue: null,
    skip: false,
    helpVideoUrl: null,
    profileKey: null,
    variable: action === "SET_VARIABLE" ? "" : null,
    valueExpression: action === "SET_VARIABLE" ? "" : null,
    repeat: action === "REPEAT" ? { stepIds: [], maxIterations: 10, addMoreLabel: "", countExpression: "", itemLabel: "item" } : null,
    appPackage: action === "OPEN_APP" ? "" : null,
    waitSeconds: boundaryActions.has(action) ? 20 : null,
  };
}

const PACKAGE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$/;

/** Logic checks mirroring the backend (expressions, variables, loops, screens). */
function validateLogic(steps: FlowStep[], add: (id: string, msg: string) => void) {
  const screens = screenIndex(steps);
  const owners = new Map<string, string>();
  const byId = new Map(steps.map((s) => [s.id, s]));
  for (const s of steps) {
    const expr = (value: string | null | undefined, what: string) => {
      if (!value?.trim()) return;
      if (value.length > 500) add(s.id, `${what} is too long (max 500)`);
      const err = validateExpression(value.trim());
      if (err) add(s.id, `${what}: ${err}`);
    };
    expr(s.condition, "Condition");
    expr(s.elseValue, "Else value");
    expr(s.valueExpression, "Computed value");
    if (s.action === "REPEAT") expr(s.repeat?.countExpression, "Repeat count");
    if (s.question) templateErrors(s.question).forEach((e) => add(s.id, `Question placeholder ${e}`));
    const variable = s.variable?.trim();
    if (variable) {
      if (!VARIABLE_NAME.test(variable) || variable.length > 60) add(s.id, "Variable names use letters, digits and _");
      else if (variable === "index") add(s.id, "'index' is reserved for loops");
    }
    if (s.elseValue?.trim() && !s.condition?.trim()) add(s.id, "An else value needs a condition");
    const sensitive = !!s.fieldType && sensitiveFieldTypes.has(s.fieldType);
    if (sensitive && (s.valueExpression?.trim() || s.elseValue?.trim())) add(s.id, "Password/OTP/PIN fields cannot be filled automatically");
    if (s.waitSeconds != null && (s.waitSeconds < 1 || s.waitSeconds > 120)) add(s.id, "Wait must be 1-120 seconds");
    if (s.appPackage?.trim() && !PACKAGE.test(s.appPackage.trim())) add(s.id, "Invalid app package name");
    if (elementActions.has(s.action) && !s.elementId.trim()) add(s.id, "This step has no screen element");
    switch (s.action) {
      case "SET_VARIABLE":
        if (!s.variable?.trim() || !s.valueExpression?.trim()) add(s.id, "Set-variable steps need a variable name and a value");
        break;
      case "OPEN_APP":
        if (!s.appPackage?.trim()) add(s.id, "Open-app steps need an app package");
        break;
      case "REPEAT": {
        const r = s.repeat;
        if (!r || r.stepIds.length === 0) {
          add(s.id, "Choose at least one step to repeat");
          break;
        }
        if (r.maxIterations < 1 || r.maxIterations > 50) add(s.id, "Repeat at most 1-50 times");
        for (const id of r.stepIds) {
          const target = byId.get(id);
          if (!target) add(s.id, "Repeats a step that does not exist");
          else if (target.action === "REPEAT" || boundaryActions.has(target.action)) add(s.id, "Loops can't contain other loops or screen changes");
          else if (screens.get(id) !== screens.get(s.id)) add(s.id, `"${target.label}" is on a different screen`);
          if (owners.has(id)) add(s.id, "A step can belong to only one loop");
          owners.set(id, s.id);
        }
        break;
      }
    }
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
  validateLogic(steps, add);
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
      condition: blank(s.condition),
      elseValue: blank(s.elseValue),
      variable: blank(s.variable),
      valueExpression: blank(s.valueExpression),
      appPackage: blank(s.appPackage),
      repeat:
        s.action === "REPEAT" && s.repeat
          ? {
              ...s.repeat,
              addMoreElementId: blank(s.repeat.addMoreElementId),
              addMoreLabel: blank(s.repeat.addMoreLabel),
              countExpression: blank(s.repeat.countExpression),
              itemLabel: blank(s.repeat.itemLabel),
            }
          : null,
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
    if ((p.condition ?? "") !== (s.condition ?? "") || (p.elseValue ?? "") !== (s.elseValue ?? "")) changes.push(`Condition for "${s.label}" changed`);
    if ((p.valueExpression ?? "") !== (s.valueExpression ?? "") || (p.variable ?? "") !== (s.variable ?? "")) {
      changes.push(`Value or variable for "${s.label}" changed`);
    }
    if (JSON.stringify(p.repeat ?? null) !== JSON.stringify(s.repeat ?? null)) changes.push(`Loop "${s.label}" changed`);
    if ((p.appPackage ?? "") !== (s.appPackage ?? "") || (p.waitSeconds ?? null) !== (s.waitSeconds ?? null)) changes.push(`Screen change "${s.label}" changed`);
  }
  for (const p of previous.steps) if (!current.steps.some((s) => s.id === p.id)) changes.push(`Removed "${p.label}"`);
  return changes.length ? changes : ["No step changes"];
}
