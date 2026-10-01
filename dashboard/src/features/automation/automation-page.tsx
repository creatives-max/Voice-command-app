import { useState } from "react";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { AppWindow, CalendarClock, Mic, Play, Plus, Smartphone, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import type { TriggerInput } from "@/lib/api";
import { devicesQuery, flowQuery, runRequestsQuery, triggersQuery, useDeleteTrigger, useRunNow, useSaveTrigger } from "@/lib/queries";
import type { Device, Trigger } from "@/lib/types";
import { SOURCE_LABELS, STATUS_LABELS, statusTone } from "./run-log";
import { DAY_NAMES, INTERVALS, describeCron, timeZones, toCron, type ScheduleSpec } from "./schedule";
import { PHRASE_MAX, phraseError } from "./voice";

const formatWhen = (iso: string, timeZone?: string | null) => {
  try {
    return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short", timeZone: timeZone ?? undefined });
  } catch {
    return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
  }
};

/** "Triggers & runs" for one flow: run it now, when its app opens, on a schedule, or when a phrase is said. */
export function AutomationPage() {
  const { flowId } = useParams({ from: "/app/flows/$flowId/automation" });
  const flow = useQuery(flowQuery(flowId));
  const triggers = useQuery(triggersQuery(flowId));
  const devices = useQuery(devicesQuery);
  const runs = useQuery(runRequestsQuery(flowId));
  const [adding, setAdding] = useState(false);

  if (flow.isPending) return <Skeleton className="mx-auto h-96 max-w-4xl" />;
  if (!flow.data) return <p className="text-destructive">Could not load this flow.</p>;

  return (
    <div className="mx-auto grid max-w-4xl gap-6">
      <div className="flex flex-col gap-1">
        <Link to="/flows/$flowId" params={{ flowId }} className="text-sm text-muted-foreground hover:underline">
          ← Back to editor
        </Link>
        <h1 className="text-2xl font-semibold">Triggers &amp; runs · {flow.data.name}</h1>
        <p className="text-sm text-muted-foreground">{flow.data.appPackage}</p>
      </div>

      <RunNowCard flowId={flowId} devices={devices.data ?? []} />

      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div>
            <CardTitle>Triggers</CardTitle>
            <CardDescription>Start this flow by itself when its app opens, at set times, or when you say a phrase on the phone.</CardDescription>
          </div>
          {!adding && (
            <Button variant="outline" onClick={() => setAdding(true)}>
              <Plus /> Add trigger
            </Button>
          )}
        </CardHeader>
        <CardContent className="grid gap-3">
          {adding && <TriggerForm flowId={flowId} devices={devices.data ?? []} onDone={() => setAdding(false)} />}
          {triggers.data?.length === 0 && !adding && <p className="text-sm text-muted-foreground">No triggers yet.</p>}
          {triggers.data?.map((t) => <TriggerRow key={t.id} flowId={flowId} trigger={t} devices={devices.data ?? []} />)}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Recent runs</CardTitle>
          <CardDescription>Runs started from the dashboard, by a schedule, when the app opened, or by a voice shortcut.</CardDescription>
        </CardHeader>
        <CardContent>
          {runs.data?.length ? (
            <ul className="divide-y">
              {runs.data.map((r) => (
                <li key={r.id} className="flex flex-wrap items-center gap-2 py-2 text-sm">
                  <Badge variant={statusTone(r.status)}>{STATUS_LABELS[r.status]}</Badge>
                  <span className="text-muted-foreground">{SOURCE_LABELS[r.source]}</span>
                  <span>{formatWhen(r.createdAt)}</span>
                  <Link to="/runs/$requestId" params={{ requestId: r.id }} className="ml-auto text-primary hover:underline">
                    View log
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">No runs yet.</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function DeviceSelect({ id, devices, value, onChange, anyLabel }: { id: string; devices: Device[]; value: string; onChange: (v: string) => void; anyLabel: string }) {
  return (
    <NativeSelect id={id} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{anyLabel}</option>
      {devices.map((d) => (
        <option key={d.id} value={d.id} disabled={!d.remoteRuns}>
          {d.name} {d.online ? "· online" : "· offline"} {d.remoteRuns ? "" : "· remote runs off"}
        </option>
      ))}
    </NativeSelect>
  );
}

export function RunNowCard({ flowId, devices }: { flowId: string; devices: Device[] }) {
  const [device, setDevice] = useState("");
  const runNow = useRunNow();
  const navigate = useNavigate();

  async function run() {
    try {
      const request = await runNow.mutateAsync({ flowId, deviceId: device || undefined });
      await navigate({ to: "/runs/$requestId", params: { requestId: request.id } });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not start the run");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Run now</CardTitle>
        <CardDescription>
          The phone opens the app and runs this flow by voice. Its accessibility service must be on and the phone signed in.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="grid flex-1 gap-1">
          <Label htmlFor="run-device">Phone</Label>
          <DeviceSelect id="run-device" devices={devices} value={device} onChange={setDevice} anyLabel="Most recently used phone" />
        </div>
        <Button onClick={run} disabled={runNow.isPending || devices.length === 0}>
          <Play /> {runNow.isPending ? "Starting…" : "Run now"}
        </Button>
      </CardContent>
      {devices.length === 0 && (
        <CardContent className="pt-0 text-sm text-muted-foreground">
          No phone yet: sign in on the VoiceControl app and it appears under{" "}
          <Link to="/devices" className="text-primary hover:underline">
            Devices
          </Link>
          .
        </CardContent>
      )}
    </Card>
  );
}

function TriggerRow({ flowId, trigger, devices }: { flowId: string; trigger: Trigger; devices: Device[] }) {
  const save = useSaveTrigger(flowId);
  const remove = useDeleteTrigger(flowId);
  const device = devices.find((d) => d.id === trigger.deviceId);
  const input: TriggerInput = {
    type: trigger.type, enabled: trigger.enabled, cron: trigger.cron, timezone: trigger.timezone, deviceId: trigger.deviceId, phrase: trigger.phrase,
  };
  const isSchedule = trigger.type === "SCHEDULE";
  const isVoice = trigger.type === "VOICE";
  const Icon = isSchedule ? CalendarClock : isVoice ? Mic : AppWindow;

  return (
    <div className="flex flex-wrap items-start gap-3 rounded-md border p-3" data-testid={`trigger-${trigger.id}`}>
      <Icon className="mt-0.5 size-5 text-primary" />
      <div className="grid min-w-0 flex-1 gap-1 text-sm">
        <span className="font-medium">
          {isSchedule ? describeCron(trigger.cron ?? "") : isVoice ? `When I say “${trigger.phrase ?? ""}”` : "When the app opens"}
        </span>
        <span className="text-muted-foreground">
          {isSchedule && `${trigger.timezone} · `}
          <Smartphone className="inline size-3" /> {device?.name ?? (isSchedule ? "Most recently used phone" : "Any of my phones")}
        </span>
        {isSchedule && trigger.enabled && trigger.upcoming.length > 0 && (
          <span className="text-muted-foreground">Next: {trigger.upcoming.map((t) => formatWhen(t, trigger.timezone)).join(" · ")}</span>
        )}
        {trigger.lastRunAt && <span className="text-muted-foreground">Last run {formatWhen(trigger.lastRunAt, trigger.timezone)}</span>}
      </div>
      <div className="flex items-center gap-2">
        <Switch
          aria-label="Enabled"
          checked={trigger.enabled}
          onCheckedChange={(enabled) =>
            save.mutate({ id: trigger.id, input: { ...input, enabled } }, { onError: (e) => toast.error(e.message) })
          }
        />
        <Button variant="ghost" size="icon" aria-label="Delete trigger" onClick={() => remove.mutate(trigger.id)}>
          <Trash2 />
        </Button>
      </div>
    </div>
  );
}

const TYPE_LABELS: Record<TriggerInput["type"], string> = { SCHEDULE: "On a schedule", APP_OPEN: "When the app opens", VOICE: "When I say…" };
const TYPE_ICONS = { SCHEDULE: CalendarClock, APP_OPEN: AppWindow, VOICE: Mic } as const;

function TriggerForm({ flowId, devices, onDone }: { flowId: string; devices: Device[]; onDone: () => void }) {
  const zones = timeZones();
  const [type, setType] = useState<TriggerInput["type"]>("SCHEDULE");
  const [phrase, setPhrase] = useState("");
  const phraseProblem = type === "VOICE" && phrase.trim() ? phraseError(phrase) : null;
  const [spec, setSpec] = useState<ScheduleSpec>({ kind: "daily", time: "09:00" });
  const [timezone, setTimezone] = useState(zones[0]!);
  const [device, setDevice] = useState("");
  const save = useSaveTrigger(flowId);
  const cron = type === "SCHEDULE" ? toCron(spec) : null;

  async function submit() {
    try {
      await save.mutateAsync({
        input: {
          type, enabled: true, cron, timezone: type === "SCHEDULE" ? timezone : null, deviceId: device || null,
          phrase: type === "VOICE" ? phrase.trim() : null,
        },
      });
      toast.success(
        type === "SCHEDULE" ? "Schedule saved" : type === "VOICE" ? `Say “${phrase.trim()}” on the phone to run this flow` : "The phone will run this flow when the app opens",
      );
      onDone();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not save the trigger");
    }
  }

  const setKind = (kind: ScheduleSpec["kind"]) => {
    const time = "time" in spec ? spec.time : "09:00";
    const next: Record<ScheduleSpec["kind"], ScheduleSpec> = {
      daily: { kind: "daily", time },
      weekdays: { kind: "weekdays", time },
      days: { kind: "days", days: [1], time },
      monthly: { kind: "monthly", day: 1, time },
      interval: { kind: "interval", minutes: 30 },
      custom: { kind: "custom", cron: toCron(spec) ?? "0 9 * * *" },
    };
    setSpec(next[kind]);
  };

  return (
    <div className="grid gap-4 rounded-md border border-dashed p-4" aria-label="New trigger">
      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Trigger type">
        {(["SCHEDULE", "APP_OPEN", "VOICE"] as const).map((t) => {
          const TypeIcon = TYPE_ICONS[t];
          return (
            <Button key={t} type="button" role="radio" aria-checked={type === t} variant={type === t ? "default" : "outline"} size="sm" onClick={() => setType(t)}>
              <TypeIcon /> {TYPE_LABELS[t]}
            </Button>
          );
        })}
      </div>

      {type === "SCHEDULE" && (
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="grid gap-1">
            <Label htmlFor="sched-kind">Repeat</Label>
            <NativeSelect id="sched-kind" value={spec.kind} onChange={(e) => setKind(e.target.value as ScheduleSpec["kind"])}>
              <option value="daily">Every day</option>
              <option value="weekdays">Weekdays (Mon–Fri)</option>
              <option value="days">On chosen days</option>
              <option value="monthly">Monthly</option>
              <option value="interval">Every few minutes</option>
              <option value="custom">Custom (cron)</option>
            </NativeSelect>
          </div>
          {"time" in spec && (
            <div className="grid gap-1">
              <Label htmlFor="sched-time">Time</Label>
              <Input id="sched-time" type="time" value={spec.time} onChange={(e) => setSpec({ ...spec, time: e.target.value })} />
            </div>
          )}
          {spec.kind === "days" && (
            <fieldset className="flex flex-wrap gap-1 sm:col-span-2">
              <legend className="sr-only">Days</legend>
              {DAY_NAMES.map((name, d) => {
                const on = spec.days.includes(d);
                return (
                  <Button
                    key={name}
                    type="button"
                    size="sm"
                    variant={on ? "default" : "outline"}
                    aria-pressed={on}
                    onClick={() => setSpec({ ...spec, days: on ? spec.days.filter((x) => x !== d) : [...spec.days, d] })}
                  >
                    {name}
                  </Button>
                );
              })}
            </fieldset>
          )}
          {spec.kind === "monthly" && (
            <div className="grid gap-1">
              <Label htmlFor="sched-day">Day of month</Label>
              <Input id="sched-day" type="number" min={1} max={31} value={spec.day} onChange={(e) => setSpec({ ...spec, day: Number(e.target.value) })} />
            </div>
          )}
          {spec.kind === "interval" && (
            <div className="grid gap-1">
              <Label htmlFor="sched-every">Every</Label>
              <NativeSelect id="sched-every" value={spec.minutes} onChange={(e) => setSpec({ kind: "interval", minutes: Number(e.target.value) })}>
                {INTERVALS.map((m) => (
                  <option key={m} value={m}>
                    {m === 60 ? "hour" : `${m} minutes`}
                  </option>
                ))}
              </NativeSelect>
            </div>
          )}
          {spec.kind === "custom" && (
            <div className="grid gap-1 sm:col-span-2">
              <Label htmlFor="sched-cron">Cron (minute hour day month weekday)</Label>
              <Input id="sched-cron" value={spec.cron} placeholder="0 9 * * 1-5" onChange={(e) => setSpec({ kind: "custom", cron: e.target.value })} />
            </div>
          )}
          <div className="grid gap-1">
            <Label htmlFor="sched-tz">Time zone</Label>
            <NativeSelect id="sched-tz" value={timezone} onChange={(e) => setTimezone(e.target.value)}>
              {zones.map((z) => (
                <option key={z} value={z}>
                  {z}
                </option>
              ))}
            </NativeSelect>
          </div>
          <p className="self-end text-sm text-muted-foreground sm:col-span-2">{cron ? describeCron(cron) : "Finish the schedule to save it."}</p>
        </div>
      )}
      {type === "APP_OPEN" && (
        <p className="text-sm text-muted-foreground">
          Whenever this app comes to the front on the phone, VoiceControl starts this flow (once every two minutes at most). It also works offline
          once the phone has synced.
        </p>
      )}

      {type === "VOICE" && (
        <div className="grid gap-1 sm:max-w-md">
          <Label htmlFor="trigger-phrase">Phrase</Label>
          <Input
            id="trigger-phrase"
            value={phrase}
            maxLength={PHRASE_MAX + 10}
            placeholder="Pay electricity bill"
            aria-invalid={!!phraseProblem}
            aria-describedby="trigger-phrase-help"
            onChange={(e) => setPhrase(e.target.value)}
          />
          <p id="trigger-phrase-help" className={phraseProblem ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
            {phraseProblem ??
              "On the phone, tap the mic (or say the wake word) on a screen without a form and say this to run the flow. Any language works; each phrase can run one flow."}
          </p>
        </div>
      )}

      <div className="grid gap-1 sm:max-w-sm">
        <Label htmlFor="trigger-device">Phone</Label>
        <DeviceSelect
          id="trigger-device"
          devices={devices}
          value={device}
          onChange={setDevice}
          anyLabel={type === "SCHEDULE" ? "Most recently used phone" : "Any of my phones"}
        />
      </div>

      <div className="flex gap-2">
        <Button onClick={submit} disabled={save.isPending || (type === "SCHEDULE" && !cron) || (type === "VOICE" && (!phrase.trim() || !!phraseProblem))}>
          Save trigger
        </Button>
        <Button variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </div>
  );
}
