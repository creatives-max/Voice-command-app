import type { Session } from "@/lib/types";

/** The same rules the server checks, so problems show while typing. Null when the password is fine. */
export function passwordProblem(password: string, current?: string): string | null {
  if (!password) return null;
  if (password.length < 8) return "At least 8 characters.";
  if (password.length > 128) return "At most 128 characters.";
  if (!/\p{L}/u.test(password) || !/\p{N}/u.test(password)) return "Use letters and numbers.";
  if (current && password === current) return "Choose a password different from the current one.";
  return null;
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
export function ago(iso: string, now: number = Date.now()): string {
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return "just now";
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.floor(h / 24);
  return d === 1 ? "1 day ago" : `${d} days ago`;
}

/** This browser first, then the most recently used. */
export const sortSessions = (sessions: Session[]) =>
  [...sessions].sort((a, b) => Number(b.current) - Number(a.current) || b.lastUsedAt.localeCompare(a.lastUsedAt));

export const sessionName = (s: Session) => s.client || "Unknown browser or app";
