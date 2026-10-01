import { useEffect } from "react";
import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import { useQueryClient, useSuspenseQuery } from "@tanstack/react-query";
import { Building2, History, LogOut, Mic, Smartphone, Store, UserRound, Workflow } from "lucide-react";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { useCurrentOrg, useSwitchOrg } from "@/features/org/use-org";
import { api } from "@/lib/api";
import { setOrgId } from "@/lib/org";
import { sessionQuery } from "@/lib/queries";

/** Personal flows or one of the user's organizations; every page then works in that context. */
function OrgSwitcher() {
  const { orgId, orgs, loading } = useCurrentOrg();
  const switchOrg = useSwitchOrg();
  const navigate = useNavigate();
  // A remembered organization the user no longer belongs to falls back to personal flows.
  useEffect(() => {
    if (!loading && orgId && !orgs.some((o) => o.id === orgId)) switchOrg(null);
  }, [loading, orgId, orgs, switchOrg]);

  return (
    <NativeSelect
      aria-label="Workspace"
      className="mb-2"
      value={orgId ?? ""}
      onChange={(e) => {
        if (e.target.value === "__new") {
          switchOrg(null);
          void navigate({ to: "/org" });
          return;
        }
        switchOrg(e.target.value || null);
        void navigate({ to: "/" });
      }}
    >
      <option value="">Personal</option>
      {orgs.map((o) => (
        <option key={o.id} value={o.id}>
          {o.name}
        </option>
      ))}
      <option value="__new">+ New organization…</option>
    </NativeSelect>
  );
}

export function AppShell() {
  const { data: user } = useSuspenseQuery(sessionQuery);
  const qc = useQueryClient();
  const navigate = useNavigate();

  async function signOut() {
    await api.logout().catch(() => undefined);
    setOrgId(null);
    qc.clear();
    await navigate({ to: "/login", search: {} });
  }

  const nav = "flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-secondary hover:text-foreground";
  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col gap-1 border-b p-4 md:w-60 md:border-b-0 md:border-r">
        <div className="mb-4 flex items-center gap-2 px-2 text-lg font-semibold">
          <span className="grid size-8 place-items-center rounded-lg bg-primary text-primary-foreground">
            <Mic className="size-4" />
          </span>
          VoiceControl
        </div>
        <OrgSwitcher />
        <Link to="/" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }} activeOptions={{ exact: true }}>
          <Workflow className="size-4" /> Flows
        </Link>
        <Link to="/history" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <History className="size-4" /> History
        </Link>
        <Link to="/marketplace" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <Store className="size-4" /> Marketplace
        </Link>
        <Link to="/devices" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <Smartphone className="size-4" /> Devices
        </Link>
        <Link to="/org" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <Building2 className="size-4" /> Organization
        </Link>
        <Link to="/profile" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <UserRound className="size-4" /> Profile
        </Link>
        <div className="mt-auto hidden px-3 pt-6 text-xs text-muted-foreground md:block">{user.email}</div>
        <Button variant="ghost" className="justify-start text-muted-foreground" onClick={signOut}>
          <LogOut /> Sign out
        </Button>
      </aside>
      <main className="min-w-0 flex-1 p-4 md:p-8">
        <Outlet />
      </main>
    </div>
  );
}
