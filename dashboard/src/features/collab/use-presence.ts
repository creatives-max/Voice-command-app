import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { PresenceInfo } from "@/lib/types";

const HEARTBEAT_MS = 15_000;

/**
 * Announces that the user has this flow open (and whether they have unsaved edits) every 15 s, and
 * returns the others who have it open plus the flow's current version on the server.
 */
export function usePresence(flowId: string, editing: boolean): PresenceInfo | null {
  const [info, setInfo] = useState<PresenceInfo | null>(null);
  const editingRef = useRef(editing);
  editingRef.current = editing;

  useEffect(() => {
    let stopped = false;
    const beat = () =>
      api
        .presence(flowId, editingRef.current)
        .then((p) => !stopped && setInfo(p))
        .catch(() => undefined);
    void beat();
    const timer = window.setInterval(beat, HEARTBEAT_MS);
    const onHide = () => document.visibilityState === "visible" && void beat();
    document.addEventListener("visibilitychange", onHide);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onHide);
      void api.leave(flowId).catch(() => undefined);
    };
  }, [flowId]);

  // Tell others promptly when we start or stop editing.
  useEffect(() => {
    void api
      .presence(flowId, editing)
      .then(setInfo)
      .catch(() => undefined);
  }, [flowId, editing]);

  return info;
}

export const initials = (name: string) =>
  name
    .split(/[\s@.]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join("");
