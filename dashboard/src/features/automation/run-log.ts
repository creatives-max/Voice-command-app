import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { finalRunStatuses, type RunEvent, type RunRequest } from "@/lib/types";

export const STATUS_LABELS: Record<RunRequest["status"], string> = {
  PENDING: "Waiting for the phone",
  DELIVERED: "Sent to the phone",
  RUNNING: "Running",
  COMPLETED: "Completed",
  FAILED: "Failed",
  STOPPED: "Stopped",
  CANCEL_REQUESTED: "Stopping…",
  CANCELLED: "Cancelled",
  EXPIRED: "Phone didn't respond",
};

export const SOURCE_LABELS: Record<RunRequest["source"], string> = {
  MANUAL: "Run now",
  SCHEDULE: "Schedule",
  APP_OPEN: "App opened",
};

export const statusTone = (status: RunRequest["status"]): "default" | "secondary" | "destructive" | "outline" =>
  status === "COMPLETED" ? "default" : status === "FAILED" || status === "EXPIRED" ? "destructive" : finalRunStatuses.has(status) ? "secondary" : "outline";

/** Appends new events, ignoring any already seen (long-poll responses can overlap after a retry). */
export function mergeEvents(current: RunEvent[], incoming: RunEvent[]): RunEvent[] {
  const last = current.at(-1)?.id ?? 0;
  const fresh = incoming.filter((e) => e.id > last);
  return fresh.length ? [...current, ...fresh] : current;
}

export interface RunLogState {
  request: RunRequest | null;
  events: RunEvent[];
  error: string | null;
}

/** Follows a run's live log by long-polling until the run is finished. */
export function useRunLog(id: string): RunLogState {
  const [state, setState] = useState<RunLogState>({ request: null, events: [], error: null });
  useEffect(() => {
    const abort = new AbortController();
    let after = 0;
    let stopped = false;
    void (async () => {
      let failures = 0;
      while (!stopped) {
        try {
          const res = await api.runEvents(id, after, after === 0 ? 0 : 20, abort.signal);
          failures = 0;
          after = res.events.at(-1)?.id ?? after;
          setState((s) => ({ request: res.request, events: mergeEvents(s.events, res.events), error: null }));
          if (finalRunStatuses.has(res.request.status) && res.events.length === 0) break;
        } catch (e) {
          if (abort.signal.aborted) return;
          failures++;
          setState((s) => ({ ...s, error: e instanceof Error ? e.message : "Connection lost" }));
          await new Promise((r) => setTimeout(r, Math.min(15_000, 1_000 * 2 ** failures)));
        }
      }
    })();
    return () => {
      stopped = true;
      abort.abort();
    };
  }, [id]);
  return state;
}
