import { boundaryActions, type FlowStep, type Profile } from "@/lib/types";

/** Default variable name of a step: its label in snake_case (same rule as the phone and backend). */
export function slug(label: string, order: number): string {
  const s = label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
  if (!s) return `field_${order}`;
  return /^[0-9]/.test(s) ? `f_${s}` : s;
}

export const variableName = (step: FlowStep) => (step.variable?.trim() ? step.variable.trim() : slug(step.label, step.order));

export const VARIABLE_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Profile values available to expressions as `profile.<name>`. */
export const PROFILE_VARIABLES: { name: string; label: string; get: (p: Profile) => string | null | undefined }[] = [
  { name: "profile.name", label: "Full name", get: (p) => p.fullName },
  { name: "profile.first_name", label: "First name", get: (p) => p.fullName?.trim().split(/\s+/)[0] },
  { name: "profile.last_name", label: "Last name", get: (p) => (p.fullName?.trim().includes(" ") ? p.fullName.trim().split(/\s+/).pop() : null) },
  { name: "profile.email", label: "Email", get: (p) => p.email },
  { name: "profile.phone", label: "Phone", get: (p) => p.phone },
  { name: "profile.address", label: "Address", get: (p) => p.addressLine },
  { name: "profile.city", label: "City", get: (p) => p.city },
  { name: "profile.state", label: "State", get: (p) => p.state },
  { name: "profile.pincode", label: "PIN code", get: (p) => p.pincode },
  { name: "profile.dob", label: "Date of birth", get: (p) => p.dateOfBirth },
];

/** Steps that produce a value other steps can use. */
export const producesValue = (s: FlowStep) => ["FILL", "TOGGLE", "READ", "SET_VARIABLE"].includes(s.action);

/** Variables available to the step at [index]: answers of earlier steps, `index` inside loops, and the profile. */
export function availableVariables(steps: FlowStep[], index: number): string[] {
  const names = new Set<string>();
  steps.slice(0, index).forEach((s) => producesValue(s) && names.add(variableName(s)));
  const current = steps[index];
  if (current && steps.some((s) => s.action === "REPEAT" && s.repeat?.stepIds.includes(current.id))) names.add("index");
  PROFILE_VARIABLES.forEach((v) => names.add(v.name));
  return [...names];
}

/** Splits steps into screens; each NEXT_SCREEN / OPEN_APP step starts a new one. */
export function segments(steps: FlowStep[]): FlowStep[][] {
  const out: FlowStep[][] = [[]];
  for (const s of steps) {
    if (boundaryActions.has(s.action) && out[out.length - 1]!.length > 0) out.push([]);
    out[out.length - 1]!.push(s);
  }
  return out;
}

/** Screen number (0-based) of each step id. */
export function screenIndex(steps: FlowStep[]): Map<string, number> {
  const map = new Map<string, number>();
  segments(steps).forEach((seg, i) => seg.forEach((s) => map.set(s.id, i)));
  return map;
}

/** The REPEAT step that owns [stepId], if any. */
export const loopOf = (steps: FlowStep[], stepId: string) => steps.find((s) => s.action === "REPEAT" && s.repeat?.stepIds.includes(stepId));

export const ACTION_LABELS: Record<string, string> = {
  FILL: "Fill",
  CLICK: "Press button",
  TOGGLE: "Toggle",
  READ: "Read from screen",
  SET_VARIABLE: "Set variable",
  REPEAT: "Repeat for each item",
  NEXT_SCREEN: "Next screen",
  OPEN_APP: "Open app",
};
