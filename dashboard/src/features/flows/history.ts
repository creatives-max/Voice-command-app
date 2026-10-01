import type { Flow } from "@/lib/types";
import { editorReducer, initialEditor, type EditorAction, type EditorState } from "./editor";

/** How many edits the editor can undo. */
export const MAX_UNDO = 100;

export interface EditorHistory {
  past: EditorState[];
  present: EditorState;
  future: EditorState[];
  /** Edits with the same key in a row (typing in one field) are undone together. */
  lastKey: string | null;
}

export type HistoryAction = EditorAction | { type: "undo" } | { type: "redo" };

export const initialHistory = (flow: Flow): EditorHistory => ({ past: [], present: initialEditor(flow), future: [], lastKey: null });

/** Edits that change a single text value repeatedly while typing are merged into one undo step. */
function coalesceKey(action: EditorAction): string | null {
  switch (action.type) {
    case "rename":
      return "rename";
    case "update": {
      const fields = Object.keys(action.patch).sort().join(",");
      return `update:${action.id}:${fields}`;
    }
    default:
      return null;
  }
}

export function historyReducer(state: EditorHistory, action: HistoryAction): EditorHistory {
  switch (action.type) {
    case "undo": {
      const previous = state.past[state.past.length - 1];
      if (!previous) return state;
      // The change note is not part of the flow, so it survives undo.
      return {
        past: state.past.slice(0, -1),
        present: { ...previous, changeNote: state.present.changeNote },
        future: [state.present, ...state.future],
        lastKey: null,
      };
    }
    case "redo": {
      const [next, ...future] = state.future;
      if (!next) return state;
      return {
        past: [...state.past, state.present].slice(-MAX_UNDO),
        present: { ...next, changeNote: state.present.changeNote },
        future,
        lastKey: null,
      };
    }
    case "reset":
      return { past: [], present: editorReducer(state.present, action), future: [], lastKey: null };
    case "note":
      return { ...state, present: editorReducer(state.present, action) };
    default: {
      const present = editorReducer(state.present, action);
      if (present === state.present) return state;
      const key = coalesceKey(action);
      if (key !== null && key === state.lastKey && state.past.length) {
        return { ...state, present, future: [], lastKey: key };
      }
      return { past: [...state.past, state.present].slice(-MAX_UNDO), present, future: [], lastKey: key };
    }
  }
}

export const canUndo = (h: EditorHistory) => h.past.length > 0;
export const canRedo = (h: EditorHistory) => h.future.length > 0;

/** Ctrl/⌘+Z undoes, Ctrl/⌘+Shift+Z or Ctrl+Y redoes; null for any other key. Text fields keep their own undo. */
export function shortcutFor(e: { key: string; ctrlKey: boolean; metaKey: boolean; shiftKey: boolean; altKey: boolean }, inTextField: boolean): "undo" | "redo" | null {
  if (inTextField || e.altKey || !(e.ctrlKey || e.metaKey)) return null;
  const key = e.key.toLowerCase();
  if (key === "z") return e.shiftKey ? "redo" : "undo";
  if (key === "y" && e.ctrlKey && !e.shiftKey) return "redo";
  return null;
}
