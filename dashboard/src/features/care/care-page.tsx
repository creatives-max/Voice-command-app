import { useState, type FormEvent } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient, useSuspenseQuery } from "@tanstack/react-query";
import { Copy, HandHeart, History, LogOut, ShieldCheck, Trash2, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { api } from "@/lib/api";
import { useCareLinkId } from "@/lib/care";
import { careEventsQuery, careKeys, careLinksQuery, sessionQuery } from "@/lib/queries";
import { carePermissions, type CareInvite, type CareLink, type CarePermission } from "@/lib/types";
import { copyText } from "@/features/org/org-nav";
import { useSwitchCare } from "@/features/org/use-org";
import { PERMISSION_LABELS, describeEvent, isCompleteCode, minutesLeft, normalizeCode, personName } from "./care";

/**
 * Remote caregiver mode. Caregivers enter the code they were given and then work on that person's
 * flows; people who are helped create invites, choose what helpers may do, see everything they did,
 * and can end it at any time.
 */
export function CarePage() {
  const links = useQuery(careLinksQuery);
  if (links.isPending) return <Skeleton className="mx-auto h-40 max-w-4xl" />;
  const all = links.data ?? [];
  const helping = all.filter((l) => l.role === "caregiver");
  const helpers = all.filter((l) => l.role === "receiver");
  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-semibold">
          <HandHeart className="size-6 text-primary" /> Caregiving
        </h1>
        <p className="text-sm text-muted-foreground">
          Set up and fix flows for a parent or friend on their phone — only with their consent, only what they allow, and they can see and stop it any time.
        </p>
      </div>
      <HelpSomeone helping={helping} />
      <MyHelpers helpers={helpers} />
    </div>
  );
}

function HelpSomeone({ helping }: { helping: CareLink[] }) {
  const qc = useQueryClient();
  const switchCare = useSwitchCare();
  const navigate = useNavigate();
  const current = useCareLinkId();
  const [code, setCode] = useState("");
  const accept = useMutation({
    mutationFn: (c: string) => api.careAccept(c),
    onSuccess: (link) => {
      setCode("");
      void qc.invalidateQueries({ queryKey: careKeys.links });
      toast.success(`You can now help ${personName(link)}`);
    },
    onError: (e) => toast.error(e.message),
  });
  const end = useMutation({
    mutationFn: (id: string) => api.careEnd(id),
    onSuccess: (_, id) => {
      if (current === id) switchCare(null);
      void qc.invalidateQueries({ queryKey: careKeys.links });
      toast.success("You no longer help this person");
    },
    onError: (e) => toast.error(e.message),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    if (isCompleteCode(code)) accept.mutate(code);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>People I help</CardTitle>
        <CardDescription>Ask them to open VoiceControl → Caregivers → Invite a helper on their phone, and enter the code they read out.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <form className="flex flex-wrap items-end gap-2" onSubmit={submit}>
          <div className="grid gap-1">
            <Label htmlFor="care-code">Code</Label>
            <Input
              id="care-code"
              className="w-40 font-mono tracking-widest"
              placeholder="ABCD-EFGH"
              autoComplete="off"
              value={code}
              onChange={(e) => setCode(normalizeCode(e.target.value))}
            />
          </div>
          <Button type="submit" disabled={!isCompleteCode(code) || accept.isPending}>
            <UserPlus /> Start helping
          </Button>
        </form>
        {helping.length === 0 ? (
          <p className="text-sm text-muted-foreground">You don&apos;t help anyone yet.</p>
        ) : (
          <ul className="grid gap-2">
            {helping.map((link) => (
              <li key={link.id} className="flex flex-wrap items-center gap-3 rounded-md border p-3" data-testid={`helping-${link.id}`}>
                <div className="grid min-w-0 flex-1 gap-1 text-sm">
                  <span className="font-medium">{personName(link)}</span>
                  <span className="text-muted-foreground">
                    {link.permissions.length ? link.permissions.map((p) => PERMISSION_LABELS[p as CarePermission] ?? p).join(" · ") : "Can see flows only"}
                  </span>
                </div>
                {current === link.id ? (
                  <Badge>Helping now</Badge>
                ) : (
                  <Button
                    size="sm"
                    onClick={() => {
                      switchCare(link.id);
                      void navigate({ to: "/" });
                    }}
                  >
                    Open their flows
                  </Button>
                )}
                <Button variant="ghost" size="sm" onClick={() => end.mutate(link.id)} aria-label={`Stop helping ${personName(link)}`}>
                  <LogOut /> Stop helping
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function MyHelpers({ helpers }: { helpers: CareLink[] }) {
  const qc = useQueryClient();
  const [chosen, setChosen] = useState<Set<CarePermission>>(new Set(["edit_flows"]));
  const [invite, setInvite] = useState<CareInvite | null>(null);
  const create = useMutation({
    mutationFn: () => api.careInvite([...chosen]),
    onSuccess: (inv) => {
      setInvite(inv);
      void qc.invalidateQueries({ queryKey: careKeys.links });
    },
    onError: (e) => toast.error(e.message),
  });
  const active = helpers.filter((l) => l.status === "ACTIVE");
  const pending = helpers.filter((l) => l.status === "PENDING");

  return (
    <Card>
      <CardHeader>
        <CardTitle>People who help me</CardTitle>
        <CardDescription>Give someone you trust a code so they can set up flows for you. You choose what they may do and can stop it any time.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <fieldset className="grid gap-2">
          <legend className="mb-1 text-sm font-medium">They may also</legend>
          {carePermissions.map((p) => (
            <label key={p} className="flex items-center gap-2 text-sm">
              <Switch
                checked={chosen.has(p)}
                onCheckedChange={(on) => setChosen((s) => (on ? new Set([...s, p]) : new Set([...s].filter((x) => x !== p))))}
                aria-label={PERMISSION_LABELS[p]}
              />
              {PERMISSION_LABELS[p]}
            </label>
          ))}
          <p className="text-xs text-muted-foreground">They can always see your flows, phones and runs. They can never see your profile, passwords or what you typed.</p>
        </fieldset>
        <div>
          <Button onClick={() => create.mutate()} disabled={create.isPending}>
            <ShieldCheck /> Create invite code
          </Button>
        </div>
        {invite && (
          <div className="rounded-md border border-dashed p-4" aria-live="polite">
            <div className="text-sm text-muted-foreground">Read this code to your helper (valid for {minutesLeft(invite.expiresAt)} minutes, once):</div>
            <div className="mt-1 flex items-center gap-2">
              <span className="font-mono text-2xl tracking-widest" data-testid="care-code">
                {invite.code}
              </span>
              <Button variant="ghost" size="icon" aria-label="Copy code" onClick={() => copyText(invite.code)}>
                <Copy />
              </Button>
            </div>
          </div>
        )}
        {pending.map((link) => (
          <PendingInvite key={link.id} link={link} />
        ))}
        {active.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nobody helps you yet.</p>
        ) : (
          active.map((link) => <HelperRow key={link.id} link={link} />)
        )}
      </CardContent>
    </Card>
  );
}

function PendingInvite({ link }: { link: CareLink }) {
  const qc = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => api.careEnd(link.id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: careKeys.links }),
  });
  return (
    <div className="flex items-center gap-3 rounded-md border p-3 text-sm">
      <span className="flex-1 text-muted-foreground">Open invite · {minutesLeft(link.expiresAt)} minutes left</span>
      <Button variant="ghost" size="sm" onClick={() => cancel.mutate()}>
        Cancel
      </Button>
    </div>
  );
}

function HelperRow({ link }: { link: CareLink }) {
  const qc = useQueryClient();
  const { data: me } = useSuspenseQuery(sessionQuery);
  const [showLog, setShowLog] = useState(false);
  const events = useQuery({ ...careEventsQuery(link.id), enabled: showLog });
  const save = useMutation({
    mutationFn: (permissions: string[]) => api.careSetPermissions(link.id, permissions),
    onSuccess: () => void qc.invalidateQueries({ queryKey: careKeys.links }),
    onError: (e) => toast.error(e.message),
  });
  const end = useMutation({
    mutationFn: () => api.careEnd(link.id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: careKeys.links });
      toast.success(`${personName(link)} can no longer help you`);
    },
  });
  const has = new Set(link.permissions);

  return (
    <div className="grid gap-3 rounded-md border p-3" data-testid={`helper-${link.id}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex-1 font-medium">{personName(link)}</span>
        <Button variant="ghost" size="sm" onClick={() => setShowLog((v) => !v)} aria-expanded={showLog}>
          <History /> Activity
        </Button>
        <Button variant="ghost" size="sm" onClick={() => end.mutate()}>
          <Trash2 /> Remove
        </Button>
      </div>
      <div className="grid gap-1">
        {carePermissions.map((p) => (
          <label key={p} className="flex items-center gap-2 text-sm">
            <Switch
              checked={has.has(p)}
              disabled={save.isPending}
              onCheckedChange={(on) => save.mutate(on ? [...has, p] : [...has].filter((x) => x !== p))}
              aria-label={`${personName(link)}: ${PERMISSION_LABELS[p]}`}
            />
            {PERMISSION_LABELS[p]}
          </label>
        ))}
      </div>
      {showLog && (
        <ul className="grid gap-1 text-sm text-muted-foreground" aria-label="Activity">
          {events.isPending && <Skeleton className="h-6" />}
          {events.data?.map((e) => (
            <li key={e.id}>
              {new Date(e.at).toLocaleString()} — {describeEvent(e, me.email)}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
