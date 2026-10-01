import type { AuditEntry } from "@/lib/types";

/** Filters offered on the audit page (prefixes match every action of that kind). */
export const AUDIT_FILTERS = [
  { value: "", label: "All activity" },
  { value: "flow", label: "Flows" },
  { value: "member", label: "Members" },
  { value: "api_key", label: "API keys" },
  { value: "webhook", label: "Webhooks" },
  { value: "org", label: "Organization" },
] as const;

/** One readable sentence per audit entry. */
export function describeAudit(e: AuditEntry): string {
  const d = e.details;
  const flow = d.name ? `“${d.name}”` : "a flow";
  switch (e.action) {
    case "org.created":
      return `created the organization${d.name ? ` “${d.name}”` : ""}`;
    case "org.renamed":
      return `renamed the organization to “${d.name}”`;
    case "member.invited":
      return `invited ${d.email} as ${(d.role ?? "").toLowerCase()}`;
    case "member.invitation_revoked":
      return "revoked an invitation";
    case "member.joined":
      return `joined as ${(d.role ?? "").toLowerCase()}`;
    case "member.left":
      return "left the organization";
    case "member.removed":
      return "removed a member";
    case "member.role_changed":
      return `changed a member's role from ${(d.from ?? "").toLowerCase()} to ${(d.to ?? "").toLowerCase()}`;
    case "flow.updated":
      return `saved ${flow}${d.version ? ` (version ${d.version})` : ""}`;
    case "flow.rolled_back":
      return `rolled back ${flow}${d.version ? ` (now version ${d.version})` : ""}`;
    case "flow.deleted":
      return `deleted ${flow}`;
    case "flow.imported":
      return `imported ${flow} from the marketplace`;
    case "flow.moved_in":
      return `moved ${flow} into the organization`;
    case "flow.moved_out":
      return `moved ${flow} out of the organization`;
    case "api_key.created":
      return `created API key “${d.name}” (${d.scopes ?? ""})`;
    case "api_key.revoked":
      return "revoked an API key";
    case "api_key.rotated":
      return `rotated API key “${d.name}”`;
    case "audit.exported":
      return `exported the audit log (${d.entries ?? "0"} entries)`;
    case "webhook.created":
      return `added a webhook to ${d.url}`;
    case "webhook.updated":
      return `updated the webhook ${d.url ?? ""}${d.active === "false" ? " (paused)" : ""}`.trim();
    case "webhook.deleted":
      return "deleted a webhook";
    case "webhook.secret_rotated":
      return "rotated a webhook secret";
    default:
      return e.action;
  }
}

/** Who did it: a person's email, an API key, or a removed account. */
export function auditActor(e: AuditEntry): string {
  if (e.actorApiKeyId) return `API key (${e.actorEmail ?? "removed user"})`;
  return e.actorEmail ?? "Removed user";
}

/** Expiry choices for new API keys (days; null = never). */
export const KEY_EXPIRY_OPTIONS = [
  { days: null, label: "Never expires" },
  { days: 30, label: "30 days" },
  { days: 90, label: "90 days" },
  { days: 365, label: "1 year" },
] as const;

/** "expires 31 Oct 2026", "expired", or null for keys that never expire. */
export function keyExpiry(k: { expiresAt?: string | null; expired?: boolean }, now: Date = new Date()): string | null {
  if (!k.expiresAt) return null;
  if (k.expired || new Date(k.expiresAt) <= now) return "expired";
  return `expires ${new Date(k.expiresAt).toLocaleDateString(undefined, { dateStyle: "medium" })}`;
}

/** "3 / 25"; flags usage at or above 90% of the limit. */
export function usageLine(used: number, max: number): { text: string; nearLimit: boolean } {
  return { text: `${used} / ${max}`, nearLimit: max > 0 && used >= Math.ceil(max * 0.9) };
}
