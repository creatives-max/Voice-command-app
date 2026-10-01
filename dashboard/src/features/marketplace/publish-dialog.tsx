import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { categoriesQuery, marketplaceQuery, usePublishFlow } from "@/lib/queries";
import type { Flow, Listing } from "@/lib/types";
import { humanizeCategory } from "./marketplace-page";

/** Shares the saved version of a flow in the marketplace (a new listing version if already published). */
export function PublishDialog({ flow, open, onOpenChange, dirty }: { flow: Flow; open: boolean; onOpenChange: (o: boolean) => void; dirty: boolean }) {
  const categories = useQuery({ ...categoriesQuery, enabled: open });
  const mine = useQuery({ ...marketplaceQuery({ mine: true, appPackage: flow.appPackage }), enabled: open });
  const existing = mine.data?.items.find((l) => l.name === flow.name) as Listing | undefined;
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("other");
  const [tags, setTags] = useState("");
  const [changelog, setChangelog] = useState("");
  const [published, setPublished] = useState<Listing | null>(null);
  const publish = usePublishFlow(flow.id);
  const close = () => {
    setPublished(null);
    onOpenChange(false);
  };

  async function submit() {
    try {
      const listing = await publish.mutateAsync({
        description,
        category,
        tags: tags.split(",").map((t) => t.trim()).filter(Boolean),
        changelog: changelog || undefined,
      });
      setPublished(listing);
      toast.success(`Published v${listing.latestVersion}`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not publish");
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Publish “{flow.name}”</DialogTitle>
          <DialogDescription>
            Others can find, import and rate it. Default values are removed before sharing because they may be personal; questions, rules
            and logic are shared. Publishing again releases a new version to people who imported it.
          </DialogDescription>
        </DialogHeader>
        {published ? (
          <p className="text-sm">
            Live as version {published.latestVersion}.{" "}
            <Link to="/marketplace/$listingId" params={{ listingId: published.id }} className="text-primary hover:underline">
              View listing
            </Link>
          </p>
        ) : (
          <div className="grid gap-3">
            {dirty && <p className="text-sm text-destructive">You have unsaved changes; the last saved version (v{flow.version}) is published.</p>}
            {existing && <p className="text-sm text-muted-foreground">Already published (v{existing.latestVersion}); this becomes v{existing.latestVersion + 1}.</p>}
            <div className="grid gap-1">
              <Label htmlFor="pub-desc">Description</Label>
              <Textarea id="pub-desc" rows={3} maxLength={1000} value={description} placeholder="What does this flow fill, and in which app?" onChange={(e) => setDescription(e.target.value)} />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label htmlFor="pub-cat">Category</Label>
                <NativeSelect id="pub-cat" value={category} onChange={(e) => setCategory(e.target.value)}>
                  {(categories.data ?? ["other"]).map((c) => (
                    <option key={c} value={c}>
                      {humanizeCategory(c)}
                    </option>
                  ))}
                </NativeSelect>
              </div>
              <div className="grid gap-1">
                <Label htmlFor="pub-tags">Tags (comma separated)</Label>
                <Input id="pub-tags" value={tags} placeholder="train, booking" onChange={(e) => setTags(e.target.value)} />
              </div>
            </div>
            <div className="grid gap-1">
              <Label htmlFor="pub-log">What changed (optional)</Label>
              <Input id="pub-log" value={changelog} maxLength={500} onChange={(e) => setChangelog(e.target.value)} />
            </div>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={close}>
            {published ? "Close" : "Cancel"}
          </Button>
          {!published && (
            <Button onClick={submit} disabled={publish.isPending}>
              Publish
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
