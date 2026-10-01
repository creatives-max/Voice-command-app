import { useState } from "react";
import { useCurrentOrg } from "@/features/org/use-org";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Flag, RefreshCw, Star, Trash2, Wand2 } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { flowQuery, flowsQuery, listingQuery, useImportListing, useRateListing, useReportListing, useUnpublish, useUpdateFlow, useUpdateFromSource } from "@/lib/queries";
import { REPORT_REASONS, canSubmitReport, reportedLabel, type ReportReasonId } from "./report";
import type { Flow, ListingDetail } from "@/lib/types";
import { ACTION_LABELS } from "@/features/flows/logic";
import { Stars, humanizeCategory } from "./marketplace-page";
import { applyTemplate, type TemplateChange } from "./template-match";

/** One marketplace listing: its steps, versions and reviews; import, update, rate or apply (templates). */
export function ListingPage() {
  const { listingId } = useParams({ from: "/app/marketplace/$listingId" });
  const detail = useQuery(listingQuery(listingId));
  if (detail.isPending) return <Skeleton className="mx-auto h-96 max-w-4xl" />;
  if (!detail.data) return <p className="text-destructive">This listing is not available.</p>;
  return <ListingView detail={detail.data} />;
}

function ListingView({ detail }: { detail: ListingDetail }) {
  const { listing } = detail;
  const navigate = useNavigate();
  const importListing = useImportListing();
  const updateFromSource = useUpdateFromSource(detail.importedFlowId ?? "");
  const unpublish = useUnpublish();
  const [applyOpen, setApplyOpen] = useState(false);
  const { org } = useCurrentOrg();

  async function doImport() {
    try {
      const flow = await importListing.mutateAsync({ id: listing.id });
      toast.success(
        org
          ? `Imported “${listing.name}” into ${org.name}. Members' phones use it on ${listing.appPackage}.`
          : `Imported “${listing.name}”. Your phone uses it on ${listing.appPackage}.`,
      );
      await navigate({ to: "/flows/$flowId", params: { flowId: flow.id } });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Import failed");
    }
  }

  async function doUpdate() {
    try {
      await updateFromSource.mutateAsync();
      toast.success(`Your copy now matches v${listing.latestVersion}. The previous steps stay in its history.`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Update failed");
    }
  }

  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <Link to="/marketplace" className="text-sm text-muted-foreground hover:underline">
        ← Marketplace
      </Link>
      {listing.hidden && listing.mine && (
        <div role="status" className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm">
          Hidden from the marketplace after several people reported it. Fix the flow and publish a new version to show it again.
        </div>
      )}
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{humanizeCategory(listing.category)}</Badge>
            {listing.isTemplate && <Badge>Template</Badge>}
            {listing.tags.map((t) => (
              <Badge key={t} variant="outline">
                #{t}
              </Badge>
            ))}
          </div>
          <CardTitle className="text-xl">{listing.name}</CardTitle>
          <CardDescription>{listing.description || "No description."}</CardDescription>
          <div className="flex flex-wrap items-center gap-3 pt-1 text-sm text-muted-foreground">
            {!listing.isTemplate && <span className="font-mono">{listing.appPackage}</span>}
            <Stars value={listing.ratingAverage} count={listing.ratingCount} />
            {!listing.isTemplate && <span>{listing.installCount} imports</span>}
            <span>Version {listing.latestVersion}</span>
            {listing.ownerName && <span>by {listing.ownerName}</span>}
          </div>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {listing.isTemplate ? (
            <Button onClick={() => setApplyOpen(true)}>
              <Wand2 /> Apply to one of my flows
            </Button>
          ) : listing.mine ? (
            <Button
              variant="outline"
              onClick={() =>
                unpublish.mutate(listing.id, {
                  onSuccess: () => {
                    toast.success("Unpublished. People who imported it keep their copy.");
                    void navigate({ to: "/marketplace" });
                  },
                })
              }
            >
              <Trash2 /> Unpublish
            </Button>
          ) : detail.importedFlowId ? (
            <>
              <Button variant="outline" asChild>
                <Link to="/flows/$flowId" params={{ flowId: detail.importedFlowId }}>
                  Open my copy (v{detail.importedVersion})
                </Link>
              </Button>
              {detail.updateAvailable && (
                <Button onClick={doUpdate} disabled={updateFromSource.isPending}>
                  <RefreshCw /> Update my copy to v{listing.latestVersion}
                </Button>
              )}
            </>
          ) : (
            <Button onClick={doImport} disabled={importListing.isPending || org?.role === "VIEWER"}>
              <Download /> {org ? `Import to ${org.name}` : "Import to my flows"}
            </Button>
          )}
          {!listing.mine && <ReportButton detail={detail} />}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Steps</CardTitle>
          <CardDescription>What VoiceControl asks and does, in order.</CardDescription>
        </CardHeader>
        <CardContent>
          <ol className="grid gap-2 text-sm">
            {detail.steps.map((s, i) => (
              <li key={s.id} className="flex flex-wrap items-baseline gap-2">
                <span className="w-6 text-muted-foreground">{i + 1}.</span>
                <span className="font-medium">{s.label || ACTION_LABELS[s.action]}</span>
                <Badge variant="outline">{ACTION_LABELS[s.action]}</Badge>
                {s.question && <span className="text-muted-foreground">“{s.question}”</span>}
                {s.rules.length > 0 && <span className="text-xs text-muted-foreground">rules: {s.rules.join(", ")}</span>}
                {s.condition && <span className="text-xs text-muted-foreground">only if {s.condition}</span>}
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>

      {!listing.isTemplate && (
        <div className="grid gap-6 md:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Versions</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="grid gap-2 text-sm">
                {detail.versions.map((v) => (
                  <li key={v.version}>
                    <span className="font-medium">v{v.version}</span> · {new Date(v.createdAt).toLocaleDateString()} · {v.stepCount} steps
                    {v.changelog && <p className="text-muted-foreground">{v.changelog}</p>}
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
          <Reviews detail={detail} />
        </div>
      )}

      {listing.isTemplate && <ApplyTemplateDialog open={applyOpen} onOpenChange={setApplyOpen} detail={detail} />}
    </div>
  );
}

function Reviews({ detail }: { detail: ListingDetail }) {
  const rate = useRateListing(detail.listing.id);
  const [stars, setStars] = useState(detail.myRating?.stars ?? 0);
  const [review, setReview] = useState(detail.myRating?.review ?? "");
  const qc = useQueryClient();

  return (
    <Card>
      <CardHeader>
        <CardTitle>Reviews</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4">
        {!detail.listing.mine && (
          <form
            className="grid gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              rate.mutate(
                { stars, review },
                {
                  onSuccess: () => {
                    toast.success("Thanks for rating");
                    void qc.invalidateQueries({ queryKey: ["marketplace", "listing", detail.listing.id] });
                  },
                  onError: (err) => toast.error(err.message),
                },
              );
            }}
          >
            <div className="flex gap-1" role="radiogroup" aria-label="Your rating">
              {[1, 2, 3, 4, 5].map((n) => (
                <button key={n} type="button" role="radio" aria-checked={stars === n} aria-label={`${n} stars`} onClick={() => setStars(n)}>
                  <Star className={`size-6 ${n <= stars ? "fill-amber-500 text-amber-500" : "text-muted-foreground"}`} />
                </button>
              ))}
            </div>
            <Textarea aria-label="Review" rows={2} maxLength={500} placeholder="What worked? (optional)" value={review} onChange={(e) => setReview(e.target.value)} />
            <Button type="submit" size="sm" className="justify-self-start" disabled={!stars || rate.isPending}>
              {detail.myRating ? "Update rating" : "Rate"}
            </Button>
          </form>
        )}
        {detail.reviews.length === 0 && <p className="text-sm text-muted-foreground">No reviews yet.</p>}
        <ul className="grid gap-3 text-sm">
          {detail.reviews.map((r, i) => (
            <li key={i}>
              <span className="flex items-center gap-1">
                {Array.from({ length: r.stars }, (_, k) => (
                  <Star key={k} className="size-3.5 fill-amber-500 text-amber-500" />
                ))}
                <span className="ml-1 text-muted-foreground">{r.userName}</span>
              </span>
              {r.review && <p>{r.review}</p>}
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}

/** Report a listing that doesn't work, is unsafe or is spam; after a few reports it is hidden. */
function ReportButton({ detail }: { detail: ListingDetail }) {
  const report = useReportListing(detail.listing.id);
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<ReportReasonId | null>(null);
  const [note, setNote] = useState("");
  const already = reportedLabel(detail.myReport);
  return (
    <>
      <Button variant="ghost" onClick={() => setOpen(true)} aria-label="Report this flow">
        <Flag /> {already ? `Reported: ${already}` : "Report"}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Report “{detail.listing.name}”</DialogTitle>
            <DialogDescription>Flows reported by several people are hidden until their author fixes them.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-2" role="radiogroup" aria-label="Reason">
            {REPORT_REASONS.map((r) => (
              <label key={r.id} className="flex cursor-pointer items-start gap-2 rounded-md border p-2 text-sm has-[:checked]:border-primary">
                <input type="radio" name="report-reason" className="mt-1" checked={reason === r.id} onChange={() => setReason(r.id)} />
                <span>
                  <span className="font-medium">{r.label}</span>
                  <span className="block text-muted-foreground">{r.hint}</span>
                </span>
              </label>
            ))}
            <Textarea aria-label="Details" rows={2} maxLength={500} placeholder="What happened? (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!canSubmitReport(reason, note) || report.isPending}
              onClick={() =>
                reason &&
                report.mutate(
                  { reason, note },
                  {
                    onSuccess: (res) => {
                      setOpen(false);
                      toast.success(res.hidden ? "Thanks. This flow is now hidden while its author fixes it." : "Thanks for the report.");
                      void qc.invalidateQueries({ queryKey: ["marketplace", "listing", detail.listing.id] });
                    },
                    onError: (e) => toast.error(e.message),
                  },
                )
              }
            >
              Send report
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

/** Previews and applies a starter template's questions, rules and profile suggestions to one of your flows. */
function ApplyTemplateDialog({ open, onOpenChange, detail }: { open: boolean; onOpenChange: (o: boolean) => void; detail: ListingDetail }) {
  const flows = useQuery({ ...flowsQuery(), enabled: open });
  const [flowId, setFlowId] = useState("");
  const flow = useQuery({ ...flowQuery(flowId), enabled: !!flowId });
  const update = useUpdateFlow(flowId);
  const navigate = useNavigate();
  const preview: { steps: Flow["steps"]; changes: TemplateChange[] } | null = flow.data
    ? applyTemplate(flow.data.steps, { steps: detail.steps, keywords: detail.keywords })
    : null;

  async function apply() {
    if (!flow.data || !preview) return;
    try {
      await update.mutateAsync({
        expectedVersion: flow.data.version,
        steps: preview.steps,
        changeNote: `Applied template “${detail.listing.name}”`,
      });
      toast.success("Template applied as a new version");
      onOpenChange(false);
      await navigate({ to: "/flows/$flowId", params: { flowId } });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not apply the template");
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Apply “{detail.listing.name}”</DialogTitle>
          <DialogDescription>
            Matching fields get the template&apos;s question (if they have none), its validation rules and profile suggestions. Your own edits are kept.
            The phone also uses templates by itself on screens that have no saved flow.
          </DialogDescription>
        </DialogHeader>
        <Label htmlFor="apply-flow">Flow</Label>
        <NativeSelect id="apply-flow" value={flowId} onChange={(e) => setFlowId(e.target.value)}>
          <option value="">Choose a flow</option>
          {flows.data?.items.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name} — {f.appPackage}
            </option>
          ))}
        </NativeSelect>
        {preview &&
          (preview.changes.length ? (
            <ul className="grid max-h-60 gap-1 overflow-y-auto text-sm" aria-label="Changes">
              {preview.changes.map((c) => (
                <li key={c.stepId}>
                  <span className="font-medium">{c.label}</span>: {c.changes.join("; ")}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">This template doesn&apos;t add anything to that flow.</p>
          ))}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={apply} disabled={!preview?.changes.length || update.isPending}>
            Apply
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Used by the editor: shows when an imported flow has a newer version in the marketplace. */
export function SourceBanner({ flow }: { flow: Flow }) {
  const source = useQuery({ ...listingQuery(flow.sourcePublishedId ?? ""), enabled: !!flow.sourcePublishedId, retry: false });
  const update = useUpdateFromSource(flow.id);
  if (!flow.sourcePublishedId || !source.data) return null;
  const latest = source.data.listing.latestVersion;
  const behind = (flow.sourceVersion ?? 0) < latest;
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border bg-secondary/40 p-3 text-sm" role="status">
      <Download className="size-4" />
      Imported from{" "}
      <Link to="/marketplace/$listingId" params={{ listingId: flow.sourcePublishedId }} className="font-medium text-primary hover:underline">
        {source.data.listing.name}
      </Link>{" "}
      v{flow.sourceVersion}.
      {behind ? (
        <Button
          size="sm"
          className="ml-auto"
          disabled={update.isPending}
          onClick={() =>
            update.mutate(undefined, {
              onSuccess: () => toast.success(`Updated to v${latest}`),
              onError: (e) => toast.error(e.message),
            })
          }
        >
          <RefreshCw /> Update to v{latest}
        </Button>
      ) : (
        <span className="ml-auto text-muted-foreground">Up to date</span>
      )}
    </div>
  );
}
