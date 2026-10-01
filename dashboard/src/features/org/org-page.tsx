import { useState, type FormEvent } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useQuery, useSuspenseQuery } from "@tanstack/react-query";
import { Copy, LogOut, Plus, Trash2, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { invitationsQuery, membersQuery, sessionQuery, useCreateOrg, useOrgMutation } from "@/lib/queries";
import { ROLE_LABELS, roles, type Org } from "@/lib/types";
import type { Role } from "@/lib/org";
import { OrgHeader, copyText } from "./org-nav";
import { useT } from "@/lib/i18n";
import { useCurrentOrg, useSwitchOrg } from "./use-org";

/** Organizations: create/switch when none is selected; members, invitations and settings for the selected one. */
export function OrgPage() {
  const { org, orgs, loading } = useCurrentOrg();
  if (loading) return <Skeleton className="mx-auto h-40 max-w-4xl" />;
  return <div className="mx-auto grid max-w-4xl gap-6">{org ? <OrgDetails org={org} /> : <OrgList orgs={orgs} />}</div>;
}

function OrgList({ orgs }: { orgs: Org[] }) {
  const create = useCreateOrg();
  const switchOrg = useSwitchOrg();
  const [name, setName] = useState("");
  const t = useT();

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate(name, {
      onSuccess: (org) => {
        setName("");
        switchOrg(org.id);
        toast.success(`Created ${org.name}`);
      },
      onError: (err) => toast.error(err.message),
    });
  }

  return (
    <>
      <div>
        <h1 className="text-2xl font-semibold">{t("org.title")}</h1>
        <p className="text-sm text-muted-foreground">
          Share flows with your team. Admins manage members, API keys and webhooks; editors change flows; viewers use them on their phones.
        </p>
      </div>
      {orgs.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {orgs.map((o) => (
            <Card key={o.id}>
              <CardHeader>
                <CardTitle className="text-base">{o.name}</CardTitle>
                <CardDescription className="flex items-center gap-2">
                  <Badge variant="secondary">{ROLE_LABELS[o.role]}</Badge> {o.memberCount} member{o.memberCount === 1 ? "" : "s"}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <Button size="sm" onClick={() => switchOrg(o.id)}>
                  Open
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
      <Card>
        <CardHeader>
          <CardTitle>New organization</CardTitle>
          <CardDescription>You become its admin. Move flows into it from the flow editor.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="flex gap-2" onSubmit={submit}>
            <Input aria-label="Organization name" placeholder="Acme support team" value={name} onChange={(e) => setName(e.target.value)} required minLength={2} maxLength={80} />
            <Button type="submit" disabled={create.isPending}>
              <Plus /> Create
            </Button>
          </form>
        </CardContent>
      </Card>
    </>
  );
}

function OrgDetails({ org }: { org: Org }) {
  const { data: me } = useSuspenseQuery(sessionQuery);
  const members = useQuery(membersQuery(org.id));
  const isAdmin = org.role === "ADMIN";
  const switchOrg = useSwitchOrg();
  const setRole = useOrgMutation(org.id, ({ userId, role }: { userId: string; role: Role }) => api.setRole(org.id, userId, role));
  const remove = useOrgMutation(org.id, (userId: string) => api.removeMember(org.id, userId));
  const t = useT();

  const leave = () =>
    remove.mutate(me.id, {
      onSuccess: () => {
        switchOrg(null);
        toast.success(`You left ${org.name}`);
      },
      onError: (e) => toast.error(e.message),
    });

  return (
    <>
      <OrgHeader org={org} />
      <Card>
        <CardHeader>
          <CardTitle>{t("org.members")}</CardTitle>
          <CardDescription>Roles: admin (everything), editor (change flows), viewer (read and run flows).</CardDescription>
        </CardHeader>
        <CardContent>
          {members.isPending ? (
            <Skeleton className="h-24" />
          ) : (
            <ul className="divide-y">
              {members.data?.map((m) => (
                <li key={m.userId} className="flex flex-wrap items-center gap-3 py-2 text-sm" data-testid={`member-${m.email}`}>
                  <div className="grid min-w-0 flex-1">
                    <span className="truncate font-medium">{m.name || m.email}</span>
                    <span className="truncate text-xs text-muted-foreground">{m.email}</span>
                  </div>
                  {isAdmin ? (
                    <NativeSelect
                      aria-label={`Role of ${m.email}`}
                      className="w-32"
                      value={m.role}
                      onChange={(e) => setRole.mutate({ userId: m.userId, role: e.target.value as Role }, { onError: (err) => toast.error(err.message) })}
                    >
                      {roles.map((r) => (
                        <option key={r} value={r}>
                          {ROLE_LABELS[r]}
                        </option>
                      ))}
                    </NativeSelect>
                  ) : (
                    <Badge variant="secondary">{ROLE_LABELS[m.role]}</Badge>
                  )}
                  {m.userId === me.id ? (
                    <Button variant="ghost" size="sm" onClick={leave}>
                      <LogOut /> Leave
                    </Button>
                  ) : (
                    isAdmin && (
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Remove ${m.email}`}
                        onClick={() => remove.mutate(m.userId, { onError: (e) => toast.error(e.message), onSuccess: () => toast.success("Member removed") })}
                      >
                        <Trash2 />
                      </Button>
                    )
                  )}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
      {isAdmin && <Invitations org={org} />}
      {isAdmin && <Settings org={org} />}
    </>
  );
}

/** Link people open to join (they must sign in with the invited email). */
export const inviteLink = (token: string, origin = typeof window === "undefined" ? "" : window.location.origin) => `${origin}/invite/${token}`;

function Invitations({ org }: { org: Org }) {
  const invitations = useQuery(invitationsQuery(org.id));
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("EDITOR");
  const [link, setLink] = useState<string | null>(null);
  const invite = useOrgMutation(org.id, () => api.invite(org.id, email, role));
  const revoke = useOrgMutation(org.id, (id: string) => api.revokeInvitation(org.id, id));

  function submit(e: FormEvent) {
    e.preventDefault();
    invite.mutate(undefined, {
      onSuccess: (inv) => {
        setLink(inv.token ? inviteLink(inv.token) : null);
        setEmail("");
      },
      onError: (err) => toast.error(err.message),
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Invite people</CardTitle>
        <CardDescription>Send them the link. It works once, for that email address, for 7 days.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submit}>
          <Input aria-label="Email to invite" type="email" required placeholder="teammate@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
          <NativeSelect aria-label="Role for the invitation" className="sm:w-32" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {roles.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABELS[r]}
              </option>
            ))}
          </NativeSelect>
          <Button type="submit" disabled={invite.isPending}>
            <UserPlus /> Invite
          </Button>
        </form>
        {link && (
          <div className="flex items-center gap-2 rounded-md border bg-secondary/40 p-2 text-sm">
            <code className="min-w-0 flex-1 truncate" data-testid="invite-link">
              {link}
            </code>
            <Button size="sm" variant="outline" onClick={() => void copyText(link).then((ok) => (ok ? toast.success("Link copied") : toast.error("Copy failed")))}>
              <Copy /> Copy
            </Button>
          </div>
        )}
        {invitations.data && invitations.data.length > 0 && (
          <ul className="divide-y text-sm">
            {invitations.data.map((i) => (
              <li key={i.id} className="flex items-center gap-2 py-2">
                <span className="flex-1">{i.email}</span>
                <Badge variant="outline">{ROLE_LABELS[i.role]}</Badge>
                <span className="text-xs text-muted-foreground">expires {new Date(i.expiresAt).toLocaleDateString()}</span>
                <Button variant="ghost" size="sm" onClick={() => revoke.mutate(i.id)}>
                  Revoke
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function Settings({ org }: { org: Org }) {
  const [name, setName] = useState(org.name);
  const rename = useOrgMutation(org.id, () => api.renameOrg(org.id, name));
  const remove = useOrgMutation(org.id, () => api.deleteOrg(org.id));
  const switchOrg = useSwitchOrg();
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState("");

  return (
    <Card>
      <CardHeader>
        <CardTitle>Settings</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-6">
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            rename.mutate(undefined, { onSuccess: () => toast.success("Renamed"), onError: (err) => toast.error(err.message) });
          }}
        >
          <Input aria-label="Organization name" value={name} onChange={(e) => setName(e.target.value)} minLength={2} maxLength={80} />
          <Button type="submit" variant="outline" disabled={rename.isPending || name.trim() === org.name}>
            Rename
          </Button>
        </form>
        <div className="grid gap-2">
          <Label htmlFor="confirm-delete" className="text-destructive">
            Delete organization
          </Label>
          <p className="text-xs text-muted-foreground">Deletes its flows, API keys, webhooks and audit log. Type the organization name to confirm.</p>
          <div className="flex gap-2">
            <Input id="confirm-delete" value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder={org.name} />
            <Button
              variant="destructive"
              disabled={confirm !== org.name || remove.isPending}
              onClick={() =>
                remove.mutate(undefined, {
                  onSuccess: () => {
                    switchOrg(null);
                    toast.success("Organization deleted");
                    void navigate({ to: "/org" });
                  },
                  onError: (err) => toast.error(err.message),
                })
              }
            >
              Delete
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
