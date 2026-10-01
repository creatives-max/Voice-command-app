import { Link } from "@tanstack/react-router";
import { Building2, KeyRound, ScrollText, Webhook } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { ROLE_LABELS, type Org } from "@/lib/types";
import { useT } from "@/lib/i18n";

const TABS = [
  { to: "/org", label: "org.members", icon: Building2, adminOnly: false },
  { to: "/org/api-keys", label: "org.apiKeys", icon: KeyRound, adminOnly: true },
  { to: "/org/webhooks", label: "org.webhooks", icon: Webhook, adminOnly: true },
  { to: "/org/audit", label: "org.audit", icon: ScrollText, adminOnly: true },
] as const;

/** Header and tabs shared by the organization pages. */
export function OrgHeader({ org }: { org: Org }) {
  const t = useT();
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-2xl font-semibold">{org.name}</h1>
        <Badge variant="secondary">{ROLE_LABELS[org.role]}</Badge>
        <span className="text-sm text-muted-foreground">{t("org.memberCount", { count: org.memberCount })}</span>
      </div>
      <nav className="flex flex-wrap gap-2" aria-label="Organization">
        {TABS.filter((tab) => !tab.adminOnly || org.role === "ADMIN").map((tab) => (
          <Link
            key={tab.to}
            to={tab.to}
            activeOptions={{ exact: true }}
            className="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-secondary/60"
            activeProps={{ className: "bg-secondary font-medium !text-foreground" }}
          >
            <tab.icon className="size-4" /> {t(tab.label)}
          </Link>
        ))}
      </nav>
    </div>
  );
}

export function NeedsOrg({ children }: { children?: React.ReactNode }) {
  return (
    <p className="text-sm text-muted-foreground">
      {children ?? "Choose an organization in the sidebar, or create one on the "}
      {!children && (
        <Link to="/org" className="text-primary hover:underline">
          Organization page
        </Link>
      )}
      {!children && "."}
    </p>
  );
}

export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}
