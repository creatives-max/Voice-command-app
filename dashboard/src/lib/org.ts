import { useSyncExternalStore } from "react";

/**
 * The organization selected in the dashboard (null = personal flows). Kept in localStorage so it
 * survives reloads, and sent to the backend as `X-Org-Id` on every API call.
 */
const STORAGE_KEY = "vc.orgId";
const listeners = new Set<() => void>();

function read(): string | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

let current: string | null = read();

export function getOrgId(): string | null {
  return current;
}

export function setOrgId(id: string | null) {
  if (id === current) return;
  current = id;
  try {
    if (id) window.localStorage.setItem(STORAGE_KEY, id);
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage unavailable (private mode): the choice lasts for this tab only.
  }
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useOrgId(): string | null {
  return useSyncExternalStore(subscribe, getOrgId, () => null);
}

/** What a role may do in the dashboard. */
export const can = {
  edit: (role: Role | null | undefined) => role === "ADMIN" || role === "EDITOR" || role == null,
  manage: (role: Role | null | undefined) => role === "ADMIN" || role == null,
};

export type Role = "ADMIN" | "EDITOR" | "VIEWER";
