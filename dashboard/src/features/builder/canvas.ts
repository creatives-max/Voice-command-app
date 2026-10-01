import { boundaryActions, type FlowStep } from "@/lib/types";

export interface Point {
  x: number;
  y: number;
}

export const START = "__start";
export const END = "__end";
export const NODE_W = 240;
export const NODE_H = 76;
const COL_GAP = 320;
const ROW_GAP = 110;

/** Screens of the flow: a new one starts at every next-screen / open-app step. */
export function screensOf(steps: FlowStep[]): number[] {
  let screen = 0;
  return steps.map((s, i) => {
    if (i > 0 && boundaryActions.has(s.action)) screen++;
    return screen;
  });
}

/** Default positions: one column per screen, steps top to bottom, Start above and End below. */
export function autoLayout(steps: FlowStep[]): Record<string, Point> {
  const screens = screensOf(steps);
  const rows = new Map<number, number>();
  const out: Record<string, Point> = { [START]: { x: 0, y: 0 } };
  let maxRow = 0;
  steps.forEach((s, i) => {
    const col = screens[i]!;
    const row = rows.get(col) ?? 0;
    rows.set(col, row + 1);
    maxRow = Math.max(maxRow, row + 1);
    out[s.id] = { x: col * COL_GAP, y: (row + 1) * ROW_GAP };
  });
  const lastCol = screens.at(-1) ?? 0;
  out[END] = { x: lastCol * COL_GAP, y: ((rows.get(lastCol) ?? 0) + 1) * ROW_GAP };
  return out;
}

/** Saved positions where they exist, default layout for new steps. */
export function positionsFor(steps: FlowStep[], saved: Record<string, Point> | undefined): Record<string, Point> {
  const auto = autoLayout(steps);
  if (!saved) return auto;
  const out: Record<string, Point> = {};
  for (const id of [START, END, ...steps.map((s) => s.id)]) out[id] = saved[id] ?? auto[id]!;
  return out;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  kind: "next" | "screen" | "loop";
}

/** Order edges (Start → steps → End; screen changes marked) and loop edges from repeat steps to the steps they repeat. */
export function edgesFor(steps: FlowStep[]): GraphEdge[] {
  const ids = [START, ...steps.map((s) => s.id), END];
  const edges: GraphEdge[] = [];
  for (let i = 0; i < ids.length - 1; i++) {
    const target = steps[i];
    const kind = target && boundaryActions.has(target.action) && i > 0 ? "screen" : "next";
    edges.push({ id: `seq-${ids[i]}-${ids[i + 1]}`, source: ids[i]!, target: ids[i + 1]!, kind });
  }
  for (const s of steps) {
    if (s.action !== "REPEAT") continue;
    for (const id of s.repeat?.stepIds ?? []) if (steps.some((x) => x.id === id)) edges.push({ id: `loop-${s.id}-${id}`, source: s.id, target: id, kind: "loop" });
  }
  return edges;
}

/** The `move` that places [draggedId] right after [targetId] (or first, for Start), or null if nothing changes. */
export function moveAfter(steps: FlowStep[], draggedId: string, targetId: string): { from: number; to: number } | null {
  const from = steps.findIndex((s) => s.id === draggedId);
  if (from < 0 || draggedId === targetId) return null;
  let to: number;
  if (targetId === START) to = 0;
  else if (targetId === END) to = steps.length - 1;
  else {
    const target = steps.findIndex((s) => s.id === targetId);
    if (target < 0) return null;
    to = from < target ? target : target + 1;
  }
  return to === from ? null : { from, to };
}

/** Step whose node is closest to [p] (node centers), for "drop onto" and palette drops. */
export function nearestNode(positions: Record<string, Point>, p: Point, exclude?: string, maxDistance = Infinity): string | null {
  let best: string | null = null;
  let bestD = maxDistance;
  for (const [id, pos] of Object.entries(positions)) {
    if (id === exclude) continue;
    const d = Math.hypot(pos.x + NODE_W / 2 - p.x, pos.y + NODE_H / 2 - p.y);
    if (d < bestD) {
      bestD = d;
      best = id;
    }
  }
  return best;
}

/** Index after which a step dropped at [p] is inserted (-1 = first), based on the nearest node. */
export function insertAfterIndex(steps: FlowStep[], positions: Record<string, Point>, p: Point): number {
  const near = nearestNode(positions, p);
  if (near === null || near === END) return steps.length - 1;
  if (near === START) return -1;
  return steps.findIndex((s) => s.id === near);
}
