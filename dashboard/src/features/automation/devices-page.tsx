import { useState } from "react";
import { useT } from "@/lib/i18n";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Pencil, Smartphone, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { devicesQuery, runRequestsQuery, useDeleteDevice, useUpdateDevice } from "@/lib/queries";
import type { Device } from "@/lib/types";
import { SOURCE_LABELS, STATUS_LABELS, statusTone } from "./run-log";

/** Phones signed in to this account, and recent remote runs across all flows. */
export function DevicesPage() {
  const t = useT();
  const devices = useQuery(devicesQuery);
  const runs = useQuery(runRequestsQuery());

  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("devices.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("devices.subtitle")}</p>
      </div>
      {devices.isPending ? (
        <Skeleton className="h-32" />
      ) : devices.data?.length ? (
        <div className="grid gap-3">
          {devices.data.map((d) => (
            <DeviceRow key={d.id} device={d} />
          ))}
        </div>
      ) : (
        <Card>
          <CardContent className="p-6 text-sm text-muted-foreground">No phones yet. Sign in on the VoiceControl Android app; it registers itself.</CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Remote runs</CardTitle>
          <CardDescription>The latest runs started from the dashboard, schedules and app-open triggers.</CardDescription>
        </CardHeader>
        <CardContent>
          {runs.data?.length ? (
            <ul className="divide-y">
              {runs.data.map((r) => (
                <li key={r.id} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                  <Badge variant={statusTone(r.status)}>{STATUS_LABELS[r.status]}</Badge>
                  <span className="font-medium">{r.flowName}</span>
                  <span className="text-muted-foreground">
                    {SOURCE_LABELS[r.source]} · {new Date(r.createdAt).toLocaleString()}
                  </span>
                  <Link to="/runs/$requestId" params={{ requestId: r.id }} className="ml-auto text-primary hover:underline">
                    View log
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">No remote runs yet.</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function DeviceRow({ device }: { device: Device }) {
  const update = useUpdateDevice();
  const remove = useDeleteDevice();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(device.name);

  const save = (patch: { name?: string; remoteRuns?: boolean }) =>
    update.mutate({ id: device.id, patch }, { onError: (e) => toast.error(e.message), onSuccess: () => setEditing(false) });

  return (
    <Card data-testid={`device-${device.id}`}>
      <CardContent className="flex flex-wrap items-center gap-3 p-4">
        <Smartphone className="size-6 text-muted-foreground" />
        <div className="grid min-w-0 flex-1 gap-0.5">
          {editing ? (
            <form
              className="flex gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                save({ name });
              }}
            >
              <Input aria-label="Phone name" value={name} onChange={(e) => setName(e.target.value)} className="h-8 max-w-xs" autoFocus />
              <Button size="sm" type="submit">
                Save
              </Button>
            </form>
          ) : (
            <span className="flex items-center gap-2 font-medium">
              {device.name}
              <Badge variant={device.online ? "default" : "secondary"}>{device.online ? "online" : "offline"}</Badge>
            </span>
          )}
          <span className="text-xs text-muted-foreground">
            Last seen {new Date(device.lastSeenAt).toLocaleString()}
            {device.appVersion ? ` · app ${device.appVersion}` : ""}
          </span>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <Switch checked={device.remoteRuns} onCheckedChange={(remoteRuns) => save({ remoteRuns })} aria-label="Allow remote runs" />
          Remote runs
        </label>
        <Button variant="ghost" size="icon" aria-label="Rename phone" onClick={() => setEditing((v) => !v)}>
          <Pencil />
        </Button>
        <Button variant="ghost" size="icon" aria-label="Remove phone" onClick={() => remove.mutate(device.id, { onSuccess: () => toast.success("Phone removed") })}>
          <Trash2 />
        </Button>
      </CardContent>
    </Card>
  );
}
