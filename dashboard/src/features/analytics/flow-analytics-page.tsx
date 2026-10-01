import { useState } from "react";
import { Link, useParams } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { useFormat, useT, type MessageKey } from "@/lib/i18n";
import { flowQuery } from "@/lib/queries";
import { STATUS_LABELS } from "@/features/automation/run-log";
import { Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { OUTCOME_COLORS, browserZone, downloadCsv, flowCsv, stepsByTrouble, troubleRate } from "./analytics";
import { DailyRunsChart, SplitBar } from "./charts";
import { Kpis, PeriodSelect } from "./kpis";

/** One flow: runs per day, success, and how each step went (where voice didn't work). */
export function FlowAnalyticsPage() {
  const { flowId } = useParams({ from: "/app/flows/$flowId/analytics" });
  const t = useT();
  const fmt = useFormat();
  const [days, setDays] = useState(30);
  const zone = browserZone();
  const flow = useQuery(flowQuery(flowId));
  const data = useQuery({ queryKey: ["analytics", "flow", flowId, days, zone], queryFn: () => api.flowAnalytics(flowId, days, zone) });
  const outcomeName = (o: string) => t(`outcome.${o}` as MessageKey);

  return (
    <div className="mx-auto grid max-w-6xl gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <Link to="/flows/$flowId" params={{ flowId }} className="text-sm text-muted-foreground hover:underline">
            ← {t("analytics.open")}
          </Link>
          <h1 className="text-2xl font-semibold">{t("analytics.flowTitle", { name: flow.data?.name ?? "…" })}</h1>
          <p className="text-sm text-muted-foreground">{flow.data?.appPackage}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <PeriodSelect days={days} onChange={setDays} />
          <Button
            variant="outline"
            size="sm"
            disabled={!data.data}
            onClick={() => data.data && downloadCsv(flowCsv(data.data), `analytics-${flow.data?.name ?? flowId}-${days}d`)}
          >
            <Download /> {t("analytics.downloadCsv")}
          </Button>
        </div>
      </div>
      {data.isPending ? (
        <Skeleton className="h-80" />
      ) : data.error ? (
        <p className="text-destructive">{data.error.message}</p>
      ) : (
        <>
          <Kpis usage={data.data.usage} previous={data.data.previous} days={days} />
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("analytics.daily")}</CardTitle>
            </CardHeader>
            <CardContent>
              <DailyRunsChart daily={data.data.daily} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("analytics.steps")}</CardTitle>
              <CardDescription>{t("analytics.stepsHint")}</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3">
              {data.data.steps.length === 0 && <p className="text-sm text-muted-foreground">{t("analytics.noRuns")}</p>}
              {stepsByTrouble(data.data.steps).map((s) => (
                <div key={s.elementId} className="grid gap-1" data-testid="step-stats">
                  <div className="flex items-center gap-2 text-sm">
                    <span className="font-medium">{s.label}</span>
                    {troubleRate(s) >= 0.3 && <Badge variant="warning">{fmt.percent(troubleRate(s))}</Badge>}
                    <span className="ml-auto text-xs text-muted-foreground">{fmt.number(s.total)}</span>
                  </div>
                  <SplitBar
                    label={s.label}
                    parts={Object.keys(OUTCOME_COLORS).map((o) => ({ key: o, value: s.outcomes[o] ?? 0, color: OUTCOME_COLORS[o]!, name: outcomeName(o) }))}
                  />
                </div>
              ))}
              {data.data.steps.length > 0 && (
                <div className="flex flex-wrap gap-3 pt-2 text-xs text-muted-foreground">
                  {Object.entries(OUTCOME_COLORS).map(([o, color]) => (
                    <span key={o} className="flex items-center gap-1">
                      <span className="inline-block size-2.5 rounded-full" style={{ background: color }} /> {outcomeName(o)}
                    </span>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
          <div className="grid gap-4 md:grid-cols-2">
            <Breakdown title={t("analytics.understood")} values={data.data.interpretedBy} />
            <Breakdown
              title={t("analytics.remote")}
              values={Object.fromEntries(Object.entries(data.data.remoteRuns).map(([k, v]) => [STATUS_LABELS[k as keyof typeof STATUS_LABELS] ?? k, v]))}
            />
          </div>
        </>
      )}
    </div>
  );
}

function Breakdown({ title, values }: { title: string; values: Record<string, number> }) {
  const fmt = useFormat();
  const t = useT();
  const entries = Object.entries(values).sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...entries.map((e) => e[1]));
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-2 text-sm">
        {entries.length === 0 && <p className="text-muted-foreground">{t("analytics.noRuns")}</p>}
        {entries.map(([k, v]) => (
          <div key={k} className="grid grid-cols-[8rem_1fr_3rem] items-center gap-2">
            <span className="truncate">{k}</span>
            <span className="h-2 rounded-full bg-primary" style={{ width: `${(v / max) * 100}%` }} />
            <span className="text-right tabular-nums">{fmt.number(v)}</span>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
