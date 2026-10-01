import { useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { NativeSelect } from "@/components/ui/native-select";
import { useSwitchOrg } from "@/features/org/use-org";
import { useTransferFlow } from "@/lib/queries";
import type { Flow, Org } from "@/lib/types";

/**
 * Where a flow may move: into organizations where the user is an editor or admin, and back to their own
 * flows only for admins of the flow's organization (personal flows can always move into an organization).
 */
export function moveTargets(flow: Pick<Flow, "orgId">, orgs: Org[]): { id: string | null; label: string }[] {
  const current = flow.orgId ? orgs.find((o) => o.id === flow.orgId) : null;
  if (flow.orgId && current?.role !== "ADMIN") return [];
  const targets: { id: string | null; label: string }[] = [];
  if (flow.orgId) targets.push({ id: null, label: "My personal flows" });
  for (const o of orgs) if (o.id !== flow.orgId && o.role !== "VIEWER") targets.push({ id: o.id, label: o.name });
  return targets;
}

export function MoveFlowDialog({ flow, orgs, open, onOpenChange }: { flow: Flow; orgs: Org[]; open: boolean; onOpenChange: (o: boolean) => void }) {
  const targets = moveTargets(flow, orgs);
  const [target, setTarget] = useState<string>(targets[0]?.id ?? "");
  const transfer = useTransferFlow(flow.id);
  const switchOrg = useSwitchOrg();
  const navigate = useNavigate();

  async function move() {
    const to = target || null;
    try {
      const moved = await transfer.mutateAsync(to);
      onOpenChange(false);
      switchOrg(to);
      toast.success(to ? `Moved to ${orgs.find((o) => o.id === to)?.name}` : "Moved to your personal flows");
      await navigate({ to: "/flows/$flowId", params: { flowId: moved.id } });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Move failed");
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Move “{flow.name}”</DialogTitle>
          <DialogDescription>Everyone in an organization can use its flows on their phones; editors and admins can change them.</DialogDescription>
        </DialogHeader>
        <NativeSelect aria-label="Move to" value={target} onChange={(e) => setTarget(e.target.value)}>
          {targets.map((t) => (
            <option key={t.id ?? "personal"} value={t.id ?? ""}>
              {t.label}
            </option>
          ))}
        </NativeSelect>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={move} disabled={transfer.isPending || targets.length === 0}>
            Move
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
