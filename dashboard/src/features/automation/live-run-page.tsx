import { Link, useParams } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { AlertCircle, ArrowRightLeft, Bot, Check, CircleDot, MousePointerClick, Square } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { devicesQuery } from "@/lib/queries";
import { finalRunStatuses } from "@/lib/types";
import { SOURCE_LABELS, STATUS_LABELS, statusTone, useRunLog } from "./run-log";

const ICONS: Record<string, typeof Bot> = { ask: Bot, step: Check, press: MousePointerClick, screen: ArrowRightLeft, error: AlertCircle };

/** Live log of one remote run, updated as the phone reports progress. */
export function LiveRunPage() {
  const { requestId } = useParams({ from: "/app/runs/$requestId" });
  const { request, events, error } = useRunLog(requestId);
  const devices = useQuery(devicesQuery);

  if (!request) {
    return error ? <p className="text-destructive">Could not load this run: {error}</p> : <Skeleton className="mx-auto h-80 max-w-3xl" />;
  }
  const finished = finalRunStatuses.has(request.status);
  const device = devices.data?.find((d) => d.id === request.deviceId);

  async function stop() {
    try {
      await api.cancelRun(requestId);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not stop the run");
    }
  }

  return (
    <div className="mx-auto grid max-w-3xl gap-4">
      {request.flowId && (
        <Link to="/flows/$flowId/automation" params={{ flowId: request.flowId }} className="text-sm text-muted-foreground hover:underline">
          ← Triggers &amp; runs
        </Link>
      )}
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle>{request.flowName}</CardTitle>
            <CardDescription>
              {SOURCE_LABELS[request.source]} · {device?.name ?? "phone"} · {new Date(request.createdAt).toLocaleString()}
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant={statusTone(request.status)} data-testid="run-status">
              {!finished && <CircleDot className="size-3 animate-pulse" />}
              {STATUS_LABELS[request.status]}
            </Badge>
            {!finished && request.status !== "CANCEL_REQUESTED" && (
              <Button variant="outline" size="sm" onClick={stop}>
                <Square /> Stop
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          <ol className="grid gap-1 text-sm" aria-live="polite" aria-label="Run log">
            {events.map((e) => {
              const Icon = ICONS[e.kind] ?? CircleDot;
              return (
                <li key={e.id} className={`flex items-start gap-2 ${e.kind === "error" ? "text-destructive" : ""}`}>
                  <time className="w-20 shrink-0 tabular-nums text-muted-foreground" dateTime={e.at}>
                    {new Date(e.at).toLocaleTimeString()}
                  </time>
                  <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
                  <span>{e.message}</span>
                </li>
              );
            })}
          </ol>
          {!finished && <p className="mt-3 text-xs text-muted-foreground">Live — updates as the phone works. Spoken and typed values are never sent.</p>}
          {error && !finished && <p className="mt-2 text-xs text-destructive">Reconnecting… ({error})</p>}
        </CardContent>
      </Card>
    </div>
  );
}
