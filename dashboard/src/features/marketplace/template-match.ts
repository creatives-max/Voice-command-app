import type { FlowStep } from "@/lib/types";

/**
 * Fits a starter template to a flow's fields — the same algorithm as the phone's TemplateMatcher
 * (shared vectors: docs/spec/template-matching.json).
 */

export interface TemplateLike {
  steps: FlowStep[];
  keywords: Record<string, string[]>;
}

/** What the matcher needs to know about an on-screen element (or a recorded flow step). */
export interface ElementLike {
  id: string;
  kind: FlowStep["kind"];
  label: string;
  fieldType?: FlowStep["fieldType"];
}

const DISTINCTIVE = new Set(["EMAIL", "PHONE", "PINCODE", "PASSWORD", "DATE"]);
const isButton = (k: string) => k === "BUTTON" || k === "LINK";
const isToggle = (k: string) => k === "CHECKBOX" || k === "SWITCH" || k === "RADIO";
const isInput = (k: string) => k === "TEXT_FIELD" || k === "DROPDOWN";

/** Same normalization as the phone's LabelText.normalize: lowercase letters only, single spaces. */
export function normalizeLabel(raw: string | null | undefined): string {
  return (raw ?? "")
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N} ]/gu, " ")
    .replace(/\p{N}+/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function compatible(stepKind: string, elementKind: string) {
  if (isButton(stepKind)) return isButton(elementKind);
  if (isToggle(stepKind)) return isToggle(elementKind);
  return isInput(elementKind);
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

function score(step: FlowStep, keywords: string[], element: ElementLike): number {
  if (!compatible(step.kind, element.kind)) return 0;
  const label = normalizeLabel(element.label);
  const words = keywords.map(normalizeLabel).filter(Boolean);
  const hits = words.filter((k) => new RegExp(`(^|\\s)${escape(k)}(\\s|$)`, "u").test(label));
  const hit = hits.reduce<string | null>((best, k) => (best === null || k.length > best.length ? k : best), null);
  const typeAgrees = !!step.fieldType && element.fieldType === step.fieldType;
  if (hit !== null) return 2 + (label === hit ? 1 : 0) + (typeAgrees ? 1 : 0) + Math.floor(hit.length / 8);
  return typeAgrees && DISTINCTIVE.has(step.fieldType!) ? 1 : 0;
}

/** Template step id → matched element. Best pairs win first (so "Confirm password" doesn't steal "Password"). */
export function matchTemplate(template: TemplateLike, elements: ElementLike[]): Map<string, ElementLike> {
  const pairs: { step: FlowStep; element: ElementLike; score: number }[] = [];
  for (const step of template.steps) {
    for (const element of elements) {
      const s = score(step, template.keywords[step.id] ?? [], element);
      if (s > 0) pairs.push({ step, element, score: s });
    }
  }
  pairs.sort((a, b) => b.score - a.score || a.step.order - b.step.order);
  const result = new Map<string, ElementLike>();
  const used = new Set<string>();
  for (const p of pairs) {
    if (result.has(p.step.id) || used.has(p.element.id)) continue;
    result.set(p.step.id, p.element);
    used.add(p.element.id);
  }
  return result;
}

/** Whether the template fits: at least 2 fields and half of its fields matched. Returns the matched field count. */
export function fitScore(template: TemplateLike, elements: ElementLike[]): number {
  const matched = matchTemplate(template, elements);
  const fields = template.steps.filter((s) => !isButton(s.kind));
  const n = fields.filter((s) => matched.has(s.id)).length;
  return n < 2 || n * 2 < fields.length ? 0 : n;
}

/** Best-fitting template (by matched fields, then share of the template matched), or null. */
export function bestTemplate<T extends TemplateLike & { id: string }>(templates: T[], elements: ElementLike[]): T | null {
  let best: { t: T; n: number; ratio: number } | null = null;
  for (const t of templates) {
    const n = fitScore(t, elements);
    if (!n) continue;
    const ratio = n / t.steps.length;
    if (!best || n > best.n || (n === best.n && ratio > best.ratio)) best = { t, n, ratio };
  }
  return best?.t ?? null;
}

export interface TemplateChange {
  stepId: string;
  label: string;
  changes: string[];
}

/**
 * Applies a template to a flow's steps: matched steps get the template's question (when they have
 * none), its validation rules (added) and its profile suggestion (when unset). Nothing is removed.
 */
export function applyTemplate(steps: FlowStep[], template: TemplateLike): { steps: FlowStep[]; changes: TemplateChange[] } {
  const elements: ElementLike[] = steps.map((s) => ({ id: s.id, kind: s.kind, label: s.label, fieldType: s.fieldType }));
  const matched = matchTemplate(template, elements);
  const byStep = new Map<string, FlowStep>();
  for (const [templateStepId, element] of matched) byStep.set(element.id, template.steps.find((t) => t.id === templateStepId)!);
  const changes: TemplateChange[] = [];
  const next = steps.map((s) => {
    const t = byStep.get(s.id);
    if (!t) return s;
    const list: string[] = [];
    const patch: Partial<FlowStep> = {};
    if (!s.question?.trim() && t.question) {
      patch.question = t.question;
      list.push(`asks “${t.question}”`);
    }
    const newRules = t.rules.filter((r) => !s.rules.includes(r));
    if (newRules.length && !isButton(s.kind)) {
      patch.rules = [...s.rules, ...newRules];
      list.push(`adds rules ${newRules.join(", ")}`);
    }
    if (!s.profileKey && t.profileKey) {
      patch.profileKey = t.profileKey;
      list.push(`suggests your ${t.profileKey.toLowerCase().replace(/_/g, " ")}`);
    }
    if (!list.length) return s;
    changes.push({ stepId: s.id, label: s.label, changes: list });
    return { ...s, ...patch };
  });
  return { steps: next, changes };
}
