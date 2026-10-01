import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Copy, KeyRound } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { apiKeysQuery, useOrgMutation } from "@/lib/queries";
import { SCOPE_LABELS, apiScopes, type Org } from "@/lib/types";
import { NeedsOrg, OrgHeader, copyText } from "./org-nav";
import { useCurrentOrg } from "./use-org";

/** Organization API keys for third parties (admins). */
export function ApiKeysPage() {
  const { org, loading } = useCurrentOrg();
  if (loading) return <Skeleton className="mx-auto h-40 max-w-4xl" />;
  if (!org) return <NeedsOrg />;
  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <OrgHeader org={org} />
      {org.role === "ADMIN" ? <ApiKeys org={org} /> : <NeedsOrg>Only admins can manage API keys.</NeedsOrg>}
    </div>
  );
}

function ApiKeys({ org }: { org: Org }) {
  const keys = useQuery(apiKeysQuery(org.id));
  const [name, setName] = useState("");
  const [scopes, setScopes] = useState<string[]>(["flows:read"]);
  const [rate, setRate] = useState(60);
  const [secret, setSecret] = useState<string | null>(null);
  const create = useOrgMutation(org.id, () => api.createApiKey(org.id, { name, scopes, rateLimitPerMinute: rate }));
  const revoke = useOrgMutation(org.id, (id: string) => api.revokeApiKey(org.id, id));

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate(undefined, {
      onSuccess: (key) => {
        setSecret(key.secret ?? null);
        setName("");
      },
      onError: (err) => toast.error(err.message),
    });
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>New API key</CardTitle>
          <CardDescription>
            Keys work on this organization&apos;s flows only and act with your permissions. Send it as <code>X-Api-Key</code>. If you leave
            the organization, your keys stop working.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-4" onSubmit={submit}>
            <div className="grid gap-2 sm:grid-cols-[1fr_10rem]">
              <div className="grid gap-2">
                <Label htmlFor="key-name">Name</Label>
                <Input id="key-name" required maxLength={60} placeholder="CRM sync" value={name} onChange={(e) => setName(e.target.value)} />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="key-rate">Requests / minute</Label>
                <Input id="key-rate" type="number" min={1} max={10000} value={rate} onChange={(e) => setRate(Number(e.target.value))} />
              </div>
            </div>
            <fieldset className="grid gap-2">
              <legend className="mb-1 text-sm font-medium">Scopes</legend>
              {apiScopes.map((s) => (
                <label key={s} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={scopes.includes(s)}
                    onChange={(e) => setScopes((cur) => (e.target.checked ? [...cur, s] : cur.filter((x) => x !== s)))}
                  />
                  <code className="text-xs">{s}</code> {SCOPE_LABELS[s]}
                </label>
              ))}
            </fieldset>
            <Button type="submit" className="justify-self-start" disabled={create.isPending || scopes.length === 0}>
              <KeyRound /> Create key
            </Button>
          </form>
          {secret && (
            <div className="mt-4 grid gap-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-3 text-sm">
              <span className="font-medium">Copy this key now — it won&apos;t be shown again.</span>
              <div className="flex items-center gap-2">
                <code className="min-w-0 flex-1 truncate" data-testid="api-key-secret">
                  {secret}
                </code>
                <Button size="sm" variant="outline" onClick={() => void copyText(secret).then((ok) => ok && toast.success("Copied"))}>
                  <Copy /> Copy
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Keys</CardTitle>
        </CardHeader>
        <CardContent>
          {keys.isPending ? (
            <Skeleton className="h-20" />
          ) : keys.data?.length ? (
            <ul className="divide-y">
              {keys.data.map((k) => (
                <li key={k.id} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                  <span className="font-medium">{k.name}</span>
                  <code className="text-xs text-muted-foreground">{k.prefix}…</code>
                  {k.scopes.map((s) => (
                    <Badge key={s} variant="outline">
                      {s}
                    </Badge>
                  ))}
                  <span className="text-xs text-muted-foreground">
                    {k.rateLimitPerMinute}/min · {k.lastUsedAt ? `used ${new Date(k.lastUsedAt).toLocaleString()}` : "never used"}
                  </span>
                  <span className="ml-auto">
                    {k.revokedAt ? (
                      <Badge variant="secondary">revoked</Badge>
                    ) : (
                      <Button variant="ghost" size="sm" onClick={() => revoke.mutate(k.id, { onSuccess: () => toast.success("Key revoked") })}>
                        Revoke
                      </Button>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">No API keys yet.</p>
          )}
        </CardContent>
      </Card>
    </>
  );
}
