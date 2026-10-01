import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Download } from "lucide-react";
import { toast } from "sonner";
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
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [exporting, setExporting] = useState(false);
  const badRange = !!from && !!to && from > to;
  const log = useInfiniteQuery({
    queryKey: orgKeys.audit(org.id, action, from, to),
    enabled: !badRange,
    queryFn: ({ pageParam }) => api.audit(org.id, { action: action || undefined, before: pageParam, from: from || undefined, to: to || undefined }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.length === 50 ? last[last.length - 1]?.id : undefined),
  });
  const entries = log.data?.pages.flat() ?? [];

  async function downloadCsv() {
    setExporting(true);
    try {
      const csv = await api.auditCsv(org.id, { action: action || undefined, from: from || undefined, to: to || undefined });
      const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
      const a = Object.assign(document.createElement("a"), { href: url, download: `audit-${org.name.replace(/[^\w-]+/g, "-")}.csv` });
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not export");
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <div className="flex flex-wrap items-end gap-3">
        <NativeSelect aria-label="Filter activity" className="sm:w-56" value={action} onChange={(e) => setAction(e.target.value)}>
          {AUDIT_FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </NativeSelect>
        <div className="grid gap-1">
          <Label htmlFor="audit-from">From</Label>
          <Input id="audit-from" type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="grid gap-1">
          <Label htmlFor="audit-to">To</Label>
          <Input id="audit-to" type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        </div>
        <Button variant="outline" onClick={() => void downloadCsv()} disabled={exporting || badRange}>
          <Download /> Download CSV
        </Button>
      </div>
      {badRange && <p className="text-sm text-destructive">The start date must be before the end date.</p>}
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
