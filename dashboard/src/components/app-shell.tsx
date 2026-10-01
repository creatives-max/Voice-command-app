import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import { useQueryClient, useSuspenseQuery } from "@tanstack/react-query";
import { History, LogOut, Mic, Smartphone, UserRound, Workflow } from "lucide-react";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { sessionQuery } from "@/lib/queries";

export function AppShell() {
  const { data: user } = useSuspenseQuery(sessionQuery);
  const qc = useQueryClient();
  const navigate = useNavigate();

  async function signOut() {
    await api.logout().catch(() => undefined);
    qc.clear();
    await navigate({ to: "/login" });
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
        <Link to="/" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }} activeOptions={{ exact: true }}>
          <Workflow className="size-4" /> Flows
        </Link>
        <Link to="/history" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <History className="size-4" /> History
        </Link>
        <Link to="/devices" className={nav} activeProps={{ className: "bg-secondary !text-foreground" }}>
          <Smartphone className="size-4" /> Devices
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
