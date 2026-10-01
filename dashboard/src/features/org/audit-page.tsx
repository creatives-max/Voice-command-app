import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { orgKeys } from "@/lib/queries";
import type { Org } from "@/lib/types";
import { AUDIT_FILTERS, auditActor, describeAudit } from "./audit";
import { NeedsOrg, OrgHeader } from "./org-nav";
import { useCurrentOrg } from "./use-org";

/** Who did what in the organization (admins), newest first, 50 at a time. */
export function AuditPage() {
  const { org, loading } = useCurrentOrg();
  if (loading) return <Skeleton className="mx-auto h-40 max-w-4xl" />;
  if (!org) return <NeedsOrg />;
  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <OrgHeader org={org} />
      {org.role === "ADMIN" ? <AuditLog org={org} /> : <NeedsOrg>Only admins can see the audit log.</NeedsOrg>}
    </div>
  );
}

function AuditLog({ org }: { org: Org }) {
  const [action, setAction] = useState("");
  const log = useInfiniteQuery({
    queryKey: orgKeys.audit(org.id, action),
    queryFn: ({ pageParam }) => api.audit(org.id, { action: action || undefined, before: pageParam }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.length === 50 ? last[last.length - 1]?.id : undefined),
  });
  const entries = log.data?.pages.flat() ?? [];

  return (
    <>
      <NativeSelect aria-label="Filter activity" className="sm:w-56" value={action} onChange={(e) => setAction(e.target.value)}>
        {AUDIT_FILTERS.map((f) => (
          <option key={f.value} value={f.value}>
            {f.label}
          </option>
        ))}
      </NativeSelect>
      <Card>
        <CardContent className="p-0">
          {log.isPending ? (
            <Skeleton className="m-4 h-24" />
          ) : entries.length ? (
            <ul className="divide-y">
              {entries.map((e) => (
                <li key={e.id} className="flex flex-wrap items-baseline gap-x-2 px-4 py-2 text-sm" data-testid="audit-entry">
                  <span className="font-medium">{auditActor(e)}</span>
                  <span>{describeAudit(e)}</span>
                  <time className="ml-auto text-xs text-muted-foreground" dateTime={e.at}>
                    {new Date(e.at).toLocaleString()}
                  </time>
                </li>
              ))}
            </ul>
          ) : (
            <p className="p-4 text-sm text-muted-foreground">Nothing recorded yet.</p>
          )}
        </CardContent>
      </Card>
      {log.hasNextPage && (
        <Button variant="outline" className="justify-self-center" onClick={() => void log.fetchNextPage()} disabled={log.isFetchingNextPage}>
          Load older activity
        </Button>
      )}
    </>
  );
}
