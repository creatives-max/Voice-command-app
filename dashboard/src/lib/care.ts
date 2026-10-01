import { useSyncExternalStore } from "react";

/**
 * The person the signed-in caregiver is currently helping: the id of their care link (null = nobody).
 * Kept in localStorage and sent to the backend as `X-Care-Link`; flows, triggers, runs and devices
 * pages then show and change that person's account (within the permissions they granted).
 */
const STORAGE_KEY = "vc.careLink";
const listeners = new Set<() => void>();

function read(): string | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

let current: string | null = read();

export function getCareLinkId(): string | null {
  return current;
}

export function setCareLinkId(id: string | null) {
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

export function useCareLinkId(): string | null {
  return useSyncExternalStore(subscribe, getCareLinkId, () => null);
}
