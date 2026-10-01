import { useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Search, Smartphone } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { flowsQuery } from "@/lib/queries";
import { useCurrentOrg } from "@/features/org/use-org";
import type { FlowSummary } from "@/lib/types";

export function groupFlows(flows: FlowSummary[], filter: string) {
  const q = filter.trim().toLowerCase();
  const matching = flows.filter((f) => !q || f.name.toLowerCase().includes(q) || f.appPackage.toLowerCase().includes(q));
  const groups = new Map<string, FlowSummary[]>();
  for (const f of matching) groups.set(f.appPackage, [...(groups.get(f.appPackage) ?? []), f]);
  return [...groups.entries()].map(([appPackage, items]) => ({ appPackage, items }));
}

export function FlowsPage() {
  const { data, isPending, error } = useQuery(flowsQuery());
  const { org } = useCurrentOrg();
  const [filter, setFilter] = useState("");
  const groups = useMemo(() => groupFlows(data?.items ?? [], filter), [data, filter]);

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{org ? `${org.name} flows` : "Flows"}</h1>
          <p className="text-sm text-muted-foreground">
            {org
              ? "Shared with everyone in the organization; their phones use these flows too. Move your own flows here from the flow editor."
              : "Recorded on your phone. Edit a flow and the next run on that screen uses it."}
          </p>
        </div>
        <div className="relative sm:w-72">
          <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
          <Input placeholder="Search apps or flows" className="pl-8" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </div>
      </div>
      {isPending && <Skeleton className="h-40 w-full" />}
      {error && <p className="text-destructive">Could not load flows: {error.message}</p>}
      {data && groups.length === 0 && (
        <Card>
          <CardHeader>
            <CardTitle>No flows yet</CardTitle>
            <CardDescription>
              {org
                ? "Open one of your flows (switch to Personal) and choose “Move” to share it with the organization, or import one from the Marketplace."
                : "Sign in on the VoiceControl app and fill any form by voice. Each screen you complete is saved here automatically."}
            </CardDescription>
          </CardHeader>
        </Card>
      )}
      <div className="grid gap-8">
        {groups.map((group) => (
          <section key={group.appPackage}>
            <h2 className="mb-3 flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <Smartphone className="size-4" /> {group.appPackage}
            </h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {group.items.map((flow) => (
                <Link key={flow.id} to="/flows/$flowId" params={{ flowId: flow.id }}>
                  <Card className="h-full transition-colors hover:border-primary">
                    <CardHeader>
                      <CardTitle className="text-base">{flow.name}</CardTitle>
                      <CardDescription className="flex items-center gap-2">
                        <Badge variant="secondary">v{flow.currentVersion}</Badge>
                        Updated {new Date(flow.updatedAt).toLocaleString()}
                      </CardDescription>
                    </CardHeader>
                  </Card>
                </Link>
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
