import type { FlowVersion } from "@/lib/types";
import { useState } from "react";
import { Link, useParams } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { RotateCcw } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { flowQuery, useRollback, versionsQuery } from "@/lib/queries";
import { describeChanges } from "./editor";

/** How each version was made. */
export const VERSION_SOURCE_LABELS: Record<FlowVersion["source"], string> = {
  DEVICE: "recorded by voice",
  DASHBOARD: "edited on dashboard",
  ROLLBACK: "rollback",
  IMPORT: "imported",
  RECORDED: "taught on phone",
  CAREGIVER: "by caregiver",
};

export function VersionsPage() {
  const { flowId } = useParams({ from: "/app/flows/$flowId/versions" });
  const flow = useQuery(flowQuery(flowId));
  const versions = useQuery(versionsQuery(flowId));
  const rollback = useRollback(flowId);
  const [expanded, setExpanded] = useState<number | null>(null);

  if (versions.isPending || flow.isPending) return <Skeleton className="mx-auto h-96 max-w-3xl" />;
  if (versions.error || flow.error) return <p className="text-destructive">Could not load history.</p>;
  const list = versions.data;

  async function restore(version: number) {
    const saved = await rollback.mutateAsync(version);
    toast.success(`Restored version ${version} as version ${saved.version}`);
  }

  return (
    <div className="mx-auto max-w-3xl">
      <Link to="/flows/$flowId" params={{ flowId }} className="text-sm text-muted-foreground hover:underline">
        ← Back to editor
      </Link>
      <h1 className="mb-1 mt-2 text-2xl font-semibold">History · {flow.data.name}</h1>
      <p className="mb-6 text-sm text-muted-foreground">Every save creates a version. Restoring an old one creates a new version with its steps.</p>
      <div className="grid gap-3">
        {list.map((v, i) => {
          const changes = describeChanges(list[i + 1], v);
          const current = v.version === flow.data.version;
          return (
            <Card key={v.version}>
              <CardHeader className="flex-row items-start justify-between gap-4 space-y-0">
                <div className="grid gap-1">
                  <CardTitle className="flex items-center gap-2 text-base">
                    Version {v.version}
                    {current && <Badge>current</Badge>}
                    <Badge variant="outline">{VERSION_SOURCE_LABELS[v.source]}</Badge>
                  </CardTitle>
                  <CardDescription>
                    {new Date(v.createdAt).toLocaleString()}
                    {v.changeNote ? ` · ${v.changeNote}` : ""}
                  </CardDescription>
                </div>
                <div className="flex gap-2">
                  <Button variant="ghost" size="sm" onClick={() => setExpanded(expanded === v.version ? null : v.version)}>
                    {expanded === v.version ? "Hide steps" : "View steps"}
                  </Button>
                  {!current && (
                    <Button variant="outline" size="sm" onClick={() => restore(v.version)} disabled={rollback.isPending}>
                      <RotateCcw /> Restore
                    </Button>
                  )}
                </div>
              </CardHeader>
              <CardContent className="grid gap-2">
                <ul className="list-inside list-disc text-sm text-muted-foreground">
                  {changes.map((c) => (
                    <li key={c}>{c}</li>
                  ))}
                </ul>
                {expanded === v.version && (
                  <ol className="grid gap-1 rounded-md bg-muted p-3 text-sm">
                    {v.steps.map((s) => (
                      <li key={s.id}>
                        <span className="font-medium">{s.order + 1}. {s.label}</span>
                        {s.question ? ` — “${s.question}”` : ""}
                        {s.skip ? " (skipped)" : ""}
                      </li>
                    ))}
                  </ol>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
