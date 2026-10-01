import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Copy, RefreshCw, Send, Trash2, Webhook as WebhookIcon } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { api } from "@/lib/api";
import { deliveriesQuery, useOrgMutation, webhooksQuery } from "@/lib/queries";
import { WEBHOOK_EVENT_LABELS, webhookEvents, type Delivery, type Org, type Webhook } from "@/lib/types";
import { NeedsOrg, OrgHeader, copyText } from "./org-nav";
import { useCurrentOrg } from "./use-org";

/** Organization webhooks with their recent deliveries (admins). */
export function WebhooksPage() {
  const { org, loading } = useCurrentOrg();
  if (loading) return <Skeleton className="mx-auto h-40 max-w-4xl" />;
  if (!org) return <NeedsOrg />;
  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <OrgHeader org={org} />
      {org.role === "ADMIN" ? <Webhooks org={org} /> : <NeedsOrg>Only admins can manage webhooks.</NeedsOrg>}
    </div>
  );
}

function SecretNotice({ secret }: { secret: string }) {
  return (
    <div className="grid gap-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-3 text-sm">
      <span className="font-medium">Signing secret — store it now, it won&apos;t be shown again.</span>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate" data-testid="webhook-secret">
          {secret}
        </code>
        <Button size="sm" variant="outline" onClick={() => void copyText(secret).then((ok) => ok && toast.success("Copied"))}>
          <Copy /> Copy
        </Button>
      </div>
      <span className="text-xs text-muted-foreground">
        Verify <code>X-VoiceControl-Signature: t=…,v1=…</code>: v1 is HMAC-SHA256 of <code>&quot;t.body&quot;</code> with this secret.
      </span>
    </div>
  );
}

function Webhooks({ org }: { org: Org }) {
  const hooks = useQuery(webhooksQuery(org.id));
  const [url, setUrl] = useState("");
  const [events, setEvents] = useState<string[]>([...webhookEvents]);
  const [secret, setSecret] = useState<string | null>(null);
  const create = useOrgMutation(org.id, () => api.createWebhook(org.id, { url, events }));

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate(undefined, {
      onSuccess: (hook) => {
        setSecret(hook.secret ?? null);
        setUrl("");
      },
      onError: (err) => toast.error(err.message),
    });
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>New webhook</CardTitle>
          <CardDescription>
            We POST JSON to your https URL when these events happen. Failed deliveries are retried for about 5 hours (30 s, 2 min, 10 min, 30 min, 1 h, 3 h).
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <form className="grid gap-3" onSubmit={submit}>
            <div className="grid gap-2">
              <Label htmlFor="hook-url">Endpoint URL</Label>
              <Input id="hook-url" type="url" required placeholder="https://example.com/hooks/voicecontrol" value={url} onChange={(e) => setUrl(e.target.value)} />
            </div>
            <EventPicker value={events} onChange={setEvents} />
            <Button type="submit" className="justify-self-start" disabled={create.isPending || events.length === 0}>
              <WebhookIcon /> Add webhook
            </Button>
          </form>
          {secret && <SecretNotice secret={secret} />}
        </CardContent>
      </Card>
      {hooks.isPending ? (
        <Skeleton className="h-24" />
      ) : hooks.data?.length ? (
        hooks.data.map((h) => <WebhookCard key={h.id} org={org} hook={h} />)
      ) : (
        <p className="text-sm text-muted-foreground">No webhooks yet.</p>
      )}
    </>
  );
}

function EventPicker({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  return (
    <fieldset className="grid gap-1">
      <legend className="mb-1 text-sm font-medium">Events</legend>
      {webhookEvents.map((ev) => (
        <label key={ev} className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={value.includes(ev)} onChange={(e) => onChange(e.target.checked ? [...value, ev] : value.filter((x) => x !== ev))} />
          <code className="text-xs">{ev}</code> {WEBHOOK_EVENT_LABELS[ev]}
        </label>
      ))}
    </fieldset>
  );
}

function WebhookCard({ org, hook }: { org: Org; hook: Webhook }) {
  const [open, setOpen] = useState(false);
  const [secret, setSecret] = useState<string | null>(null);
  const update = useOrgMutation(org.id, (patch: { active?: boolean; events?: string[] }) => api.updateWebhook(org.id, hook.id, patch));
  const remove = useOrgMutation(org.id, () => api.deleteWebhook(org.id, hook.id));
  const rotate = useOrgMutation(org.id, () => api.rotateWebhookSecret(org.id, hook.id));
  const ping = useOrgMutation(org.id, () => api.pingWebhook(org.id, hook.id));

  return (
    <Card data-testid={`webhook-${hook.id}`}>
      <CardHeader className="gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <code className="min-w-0 flex-1 truncate text-sm">{hook.url}</code>
          <label className="flex items-center gap-2 text-sm">
            <Switch checked={hook.active} onCheckedChange={(active) => update.mutate({ active })} aria-label="Webhook active" /> Active
          </label>
        </div>
        <div className="flex flex-wrap gap-1">
          {hook.events.map((e) => (
            <Badge key={e} variant="outline">
              {e}
            </Badge>
          ))}
        </div>
      </CardHeader>
      <CardContent className="grid gap-3">
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" onClick={() => ping.mutate(undefined, { onSuccess: () => { setOpen(true); toast.success("Ping queued"); } })}>
            <Send /> Send test ping
          </Button>
          <Button size="sm" variant="outline" onClick={() => rotate.mutate(undefined, { onSuccess: (h) => setSecret(h.secret ?? null) })}>
            <RefreshCw /> Rotate secret
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
            {open ? <ChevronDown /> : <ChevronRight />} Deliveries
          </Button>
          <Button size="sm" variant="ghost" className="ml-auto" aria-label="Delete webhook" onClick={() => remove.mutate(undefined, { onSuccess: () => toast.success("Webhook deleted") })}>
            <Trash2 />
          </Button>
        </div>
        {secret && <SecretNotice secret={secret} />}
        {open && <Deliveries org={org} hook={hook} />}
      </CardContent>
    </Card>
  );
}

const statusTone = (d: Delivery): "default" | "destructive" | "secondary" =>
  d.status === "SUCCEEDED" ? "default" : d.status === "FAILED" ? "destructive" : "secondary";

function Deliveries({ org, hook }: { org: Org; hook: Webhook }) {
  const deliveries = useQuery(deliveriesQuery(org.id, hook.id));
  const redeliver = useOrgMutation(org.id, (id: string) => api.redeliver(org.id, id));
  const [shown, setShown] = useState<string | null>(null);
  if (deliveries.isPending) return <Skeleton className="h-16" />;
  if (!deliveries.data?.length) return <p className="text-sm text-muted-foreground">No deliveries yet.</p>;
  return (
    <ul className="divide-y rounded-md border text-sm">
      {deliveries.data.map((d) => (
        <li key={d.id} className="grid gap-1 p-2" data-testid="delivery">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={statusTone(d)}>{d.status.toLowerCase()}</Badge>
            <code className="text-xs">{d.eventType}</code>
            <span className="text-xs text-muted-foreground">
              {new Date(d.createdAt).toLocaleString()} · {d.attempts} attempt{d.attempts === 1 ? "" : "s"}
              {d.lastStatusCode ? ` · HTTP ${d.lastStatusCode}` : ""}
              {d.lastError && !d.lastStatusCode ? ` · ${d.lastError}` : ""}
              {d.status === "PENDING" && d.attempts > 0 ? ` · next try ${new Date(d.nextAttemptAt).toLocaleTimeString()}` : ""}
            </span>
            <span className="ml-auto flex gap-1">
              <Button size="sm" variant="ghost" onClick={() => setShown(shown === d.id ? null : d.id)}>
                {shown === d.id ? "Hide" : "Payload"}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => redeliver.mutate(d.id, { onSuccess: () => toast.success("Queued again") })}>
                Redeliver
              </Button>
            </span>
          </div>
          {shown === d.id && <pre className="overflow-x-auto rounded bg-secondary/50 p-2 text-xs">{JSON.stringify(JSON.parse(d.payload), null, 2)}</pre>}
        </li>
      ))}
    </ul>
  );
}
