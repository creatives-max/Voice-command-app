import type { CareEvent, CareLink, CarePermission } from "@/lib/types";

export const PERMISSION_LABELS: Record<CarePermission, string> = {
  edit_flows: "Edit flows and when they run",
  run_flows: "Run flows on the phone",
  view_history: "See run history",
};

/** "abcd efgh" → "ABCD-EFGH" (what the person helped reads out or sends). */
export function normalizeCode(input: string): string {
  const clean = input.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8);
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean;
}

export const isCompleteCode = (code: string) => /^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code);

/** How a link's other person is named. */
export const personName = (link: CareLink) => link.otherName?.trim() || link.otherEmail || "Someone";

const permissionList = (ids: string | undefined) =>
  (ids ?? "")
    .split(",")
    .filter(Boolean)
    .map((p) => PERMISSION_LABELS[p as CarePermission]?.toLowerCase() ?? p)
    .join(", ") || "only seeing flows";

/** One line of a link's activity log. */
export function describeEvent(e: CareEvent, myEmail?: string | null): string {
  const who = e.actorEmail && e.actorEmail === myEmail ? "You" : (e.actorEmail ?? "Someone");
  const flow = e.details.flowName ? ` “${e.details.flowName}”` : " a flow";
  switch (e.action) {
    case "invited":
      return `${who} created an invite (${permissionList(e.details.permissions)})`;
    case "accepted":
      return `${who} accepted the invite`;
    case "permissions_changed":
      return `${who} changed permissions to ${permissionList(e.details.permissions)}`;
    case "flow_edited":
      return `${who} edited${flow}`;
    case "flow_restored":
      return `${who} restored an older version of${flow}`;
    case "trigger_added":
      return `${who} added a trigger to${flow}`;
    case "trigger_changed":
      return `${who} changed a trigger`;
    case "trigger_removed":
      return `${who} removed a trigger`;
    case "flow_added":
      return `${who} added a flow from the marketplace`;
    case "flow_run":
      return `${who} ran a flow on the phone`;
    case "run_stopped":
      return `${who} stopped a run`;
    case "invite_cancelled":
      return `${who} cancelled the invite`;
    case "ended":
      return `${who} ended the link`;
    default:
      return `${who}: ${e.action.replace(/_/g, " ")}`;
  }
}

/** Minutes left on a pending invite (0 when expired). */
export function minutesLeft(expiresAt: string | null | undefined, now: Date = new Date()): number {
  if (!expiresAt) return 0;
  return Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now.getTime()) / 60_000));
}
