import { useEffect, useSyncExternalStore } from "react";

export const themes = ["system", "light", "dark"] as const;
export type Theme = (typeof themes)[number];

const STORAGE_KEY = "vc.theme";
const listeners = new Set<() => void>();

function read(): Theme {
  try {
    const v = typeof window === "undefined" ? null : window.localStorage.getItem(STORAGE_KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

let current: Theme = read();

export const prefersDark = () => typeof window !== "undefined" && window.matchMedia?.("(prefers-color-scheme: dark)").matches === true;

/** Whether the page should be dark for [theme]. */
export const isDark = (theme: Theme, systemDark: boolean) => theme === "dark" || (theme === "system" && systemDark);

export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle("dark", isDark(theme, prefersDark()));
  document.documentElement.style.colorScheme = isDark(theme, prefersDark()) ? "dark" : "light";
}

export function setTheme(theme: Theme) {
  current = theme;
  try {
    if (theme === "system") window.localStorage.removeItem(STORAGE_KEY);
    else window.localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // ignore
  }
  applyTheme(theme);
  listeners.forEach((l) => l());
}

const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

/** The chosen theme; follows the system setting live while "system" is chosen. */
export function useTheme(): Theme {
  const theme = useSyncExternalStore(subscribe, () => current, () => "system" as Theme);
  useEffect(() => {
    applyTheme(theme);
    if (theme !== "system") return;
    const mq = window.matchMedia?.("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme("system");
    mq?.addEventListener("change", onChange);
    return () => mq?.removeEventListener("change", onChange);
  }, [theme]);
  return theme;
}
