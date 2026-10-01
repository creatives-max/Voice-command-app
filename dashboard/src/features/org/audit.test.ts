import { auditActor, describeAudit } from "./audit";
import type { AuditEntry } from "@/lib/types";

const entry = (action: string, details: Record<string, string> = {}, extra: Partial<AuditEntry> = {}): AuditEntry => ({
  id: 1,
  action,
  targetType: "x",
  details,
  at: "2026-10-01T10:00:00Z",
  actorEmail: "a@b.co",
  ...extra,
});

describe("audit descriptions", () => {
  it("describes flow, member, key and webhook actions", () => {
    expect(describeAudit(entry("flow.updated", { name: "Login", version: "3" }))).toBe("saved “Login” (version 3)");
    expect(describeAudit(entry("member.role_changed", { from: "VIEWER", to: "EDITOR" }))).toBe("changed a member's role from viewer to editor");
    expect(describeAudit(entry("member.invited", { email: "x@y.z", role: "ADMIN" }))).toBe("invited x@y.z as admin");
    expect(describeAudit(entry("api_key.created", { name: "CRM", scopes: "flows:read" }))).toBe("created API key “CRM” (flows:read)");
    expect(describeAudit(entry("webhook.updated", { url: "https://h", active: "false" }))).toBe("updated the webhook https://h (paused)");
    expect(describeAudit(entry("something.new"))).toBe("something.new");
  });

  it("names the actor", () => {
    expect(auditActor(entry("flow.updated"))).toBe("a@b.co");
    expect(auditActor(entry("flow.updated", {}, { actorApiKeyId: "k" }))).toBe("API key (a@b.co)");
    expect(auditActor(entry("flow.updated", {}, { actorEmail: null }))).toBe("Removed user");
  });
});
