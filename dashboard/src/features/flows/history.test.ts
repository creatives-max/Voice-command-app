import type { Flow, FlowStep } from "@/lib/types";
import { MAX_UNDO, canRedo, canUndo, historyReducer, initialHistory, shortcutFor } from "./history";

const step = (id: string, order: number): FlowStep => ({ id, order, label: id, action: "FILL", rules: [] }) as unknown as FlowStep;
const flow = { id: "f", name: "Form", version: 3, steps: [step("a", 0), step("b", 1), step("c", 2)] } as unknown as Flow;

describe("editor undo/redo", () => {
  it("undoes and redoes structural edits", () => {
    let h = initialHistory(flow);
    expect(canUndo(h)).toBe(false);
    h = historyReducer(h, { type: "move", from: 0, to: 2 });
    h = historyReducer(h, { type: "remove", id: "b" });
    expect(h.present.steps.map((s) => s.id)).toEqual(["c", "a"]);
    h = historyReducer(h, { type: "undo" });
    expect(h.present.steps.map((s) => s.id)).toEqual(["b", "c", "a"]);
    h = historyReducer(h, { type: "undo" });
    expect(h.present.steps.map((s) => s.id)).toEqual(["a", "b", "c"]);
    expect(h.present.dirty).toBe(false);
    expect(canUndo(h)).toBe(false);
    h = historyReducer(h, { type: "redo" });
    expect(h.present.steps.map((s) => s.id)).toEqual(["b", "c", "a"]);
    expect(canRedo(h)).toBe(true);
    // A new edit drops what could be redone.
    h = historyReducer(h, { type: "rename", name: "New" });
    expect(canRedo(h)).toBe(false);
  });

  it("merges typing in one field into a single undo step", () => {
    let h = initialHistory(flow);
    for (const label of ["N", "Na", "Nam", "Name"]) h = historyReducer(h, { type: "update", id: "a", patch: { label } });
    h = historyReducer(h, { type: "update", id: "b", patch: { label: "Other" } });
    expect(h.past).toHaveLength(2);
    h = historyReducer(h, { type: "undo" });
    expect(h.present.steps[1]!.label).toBe("b");
    expect(h.present.steps[0]!.label).toBe("Name");
    h = historyReducer(h, { type: "undo" });
    expect(h.present.steps[0]!.label).toBe("a");
  });

  it("keeps the change note, ignores no-op edits, resets and caps history", () => {
    let h = initialHistory(flow);
    h = historyReducer(h, { type: "move", from: 0, to: 0 });
    expect(canUndo(h)).toBe(false);
    h = historyReducer(h, { type: "move", from: 0, to: 1 });
    h = historyReducer(h, { type: "note", text: "Reordered" });
    h = historyReducer(h, { type: "undo" });
    expect(h.present.changeNote).toBe("Reordered");
    h = historyReducer(h, { type: "reset", flow });
    expect(canUndo(h) || canRedo(h)).toBe(false);
    for (let i = 0; i < MAX_UNDO + 20; i++) h = historyReducer(h, { type: "move", from: 0, to: 1 });
    expect(h.past).toHaveLength(MAX_UNDO);
  });

  it("maps keyboard shortcuts outside text fields", () => {
    const key = (k: string, mods: Partial<{ ctrlKey: boolean; metaKey: boolean; shiftKey: boolean; altKey: boolean }> = {}) => ({
      key: k, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ...mods,
    });
    expect(shortcutFor(key("z", { ctrlKey: true }), false)).toBe("undo");
    expect(shortcutFor(key("Z", { metaKey: true, shiftKey: true }), false)).toBe("redo");
    expect(shortcutFor(key("y", { ctrlKey: true }), false)).toBe("redo");
    expect(shortcutFor(key("z", { ctrlKey: true }), true)).toBeNull();
    expect(shortcutFor(key("z"), false)).toBeNull();
  });
});
