import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, CircleStop, Trash2, TriangleAlert } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { runStatsQuery, runsQuery, useClearRuns, useDeleteRun } from "@/lib/queries";
import { LANGUAGE_NAMES, type Run } from "@/lib/types";
import { OUTCOME_LABELS, automationRate, formatDuration } from "./format";

function StatusIcon({ status }: { status: Run["status"] }) {
  if (status === "COMPLETED") return <CheckCircle2 className="size-5 text-primary" aria-label="Completed" />;
  if (status === "STOPPED") return <CircleStop className="size-5 text-muted-foreground" aria-label="Stopped" />;
  return <TriangleAlert className="size-5 text-destructive" aria-label="Failed" />;
}

export function HistoryPage() {
  const [app, setApp] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);
  const stats = useQuery(runStatsQuery);
  const runs = useQuery(runsQuery(app || undefined));
  const deleteRun = useDeleteRun();
  const clearRuns = useClearRuns();

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">History</h1>
          <p className="text-sm text-muted-foreground">Voice sessions from your phone. Spoken values are never stored.</p>
        </div>
        <div className="flex gap-2">
          <NativeSelect aria-label="Filter by app" className="w-56" value={app} onChange={(e) => setApp(e.target.value)}>
            <option value="">All apps</option>
            {stats.data?.topApps.map((a) => (
              <option key={a.appPackage} value={a.appPackage}>
                {a.appPackage}
              </option>
            ))}
          </NativeSelect>
          <Button variant="outline" onClick={() => setConfirmClear(true)} disabled={!runs.data?.items.length}>
            <Trash2 /> Clear
          </Button>
        </div>
      </div>

      {stats.data && (
        <div className="mb-6 grid gap-3 sm:grid-cols-3">
          {[
            ["Sessions", stats.data.totalRuns],
            ["Completed", stats.data.completedRuns],
            ["Fields filled by voice", stats.data.fieldsFilled],
          ].map(([label, value]) => (
            <Card key={label}>
              <CardHeader>
                <CardDescription>{label}</CardDescription>
                <CardTitle className="text-3xl">{value}</CardTitle>
              </CardHeader>
            </Card>
          ))}
        </div>
      )}

      {runs.isPending && <Skeleton className="h-64 w-full" />}
      {runs.data?.items.length === 0 && <p className="text-muted-foreground">No sessions yet.</p>}
      <div className="grid gap-3">
        {runs.data?.items.map((run) => (
          <Card key={run.sessionId}>
            <CardHeader className="flex-row items-center gap-3 space-y-0">
              <StatusIcon status={run.status} />
              <button type="button" className="flex-1 text-left" onClick={() => setOpen(open === run.sessionId ? null : run.sessionId)}>
                <CardTitle className="text-base">{run.appPackage}</CardTitle>
                <CardDescription>
                  {new Date(run.startedAtMillis).toLocaleString()} · {formatDuration(run.endedAtMillis - run.startedAtMillis)} · {run.filledCount} filled
                </CardDescription>
              </button>
              <Badge variant="secondary">{automationRate(run)}% hands-free</Badge>
              <Badge variant="outline">{LANGUAGE_NAMES[run.language]}</Badge>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Delete session"
                onClick={async () => {
                  await deleteRun.mutateAsync(run.sessionId);
                  toast.success("Session deleted");
                }}
              >
                <Trash2 />
              </Button>
            </CardHeader>
            {open === run.sessionId && (
              <CardContent className="grid gap-3">
                {run.screens.map((screen, i) => (
                  <div key={i} className="rounded-md bg-muted p-3">
                    <div className="mb-2 text-sm font-medium">
                      {screen.screenTitle ?? screen.activityName?.split(".").pop() ?? `Screen ${i + 1}`}
                      {screen.flowId && <Badge className="ml-2">flow v{screen.flowVersion}</Badge>}
                    </div>
                    <ul className="grid gap-1 text-sm">
                      {screen.steps.map((step) => (
                        <li key={step.elementId} className="flex justify-between gap-4">
                          <span>{step.label}</span>
                          <span className="text-muted-foreground">
                            {OUTCOME_LABELS[step.outcome]}
                            {step.interpretedBy ? ` · ${step.interpretedBy}` : ""}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </CardContent>
            )}
          </Card>
        ))}
      </div>

      <Dialog open={confirmClear} onOpenChange={setConfirmClear}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete all history?</DialogTitle>
            <DialogDescription>This removes every synced session from your account. Flows are not affected.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmClear(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={async () => {
                await clearRuns.mutateAsync();
                setConfirmClear(false);
                toast.success("History cleared");
              }}
            >
              Delete all
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
