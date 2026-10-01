import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useCurrentOrg } from "@/features/org/use-org";
import { api } from "@/lib/api";
import { useFormat, useT } from "@/lib/i18n";
import { useOrgId } from "@/lib/org";
import { STATUS_COLORS, browserZone } from "./analytics";
import { DailyRunsChart, SplitBar } from "./charts";
import { Kpis, PeriodSelect } from "./kpis";

/** Usage and success of every flow in the workspace. */
export function AnalyticsPage() {
  const t = useT();
  const fmt = useFormat();
  const [days, setDays] = useState(30);
  const orgId = useOrgId();
  const { org } = useCurrentOrg();
  const zone = browserZone();
  const data = useQuery({ queryKey: ["analytics", "overview", orgId, days, zone], queryFn: () => api.analyticsOverview(days, zone) });

  return (
    <div className="mx-auto grid max-w-6xl gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">
            {t("analytics.title")}
            {org ? ` · ${org.name}` : ""}
          </h1>
          <p className="text-sm text-muted-foreground">{t("analytics.subtitle")}</p>
        </div>
        <PeriodSelect days={days} onChange={setDays} />
      </div>
      {data.isPending ? (
        <Skeleton className="h-80" />
      ) : data.error ? (
        <p className="text-destructive">{data.error.message}</p>
      ) : (
        <>
          <Kpis usage={data.data.totals} showDuration={false} />
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("analytics.daily")}</CardTitle>
            </CardHeader>
            <CardContent>
              <DailyRunsChart daily={data.data.daily} />
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-0">
              {data.data.flows.length === 0 ? (
                <p className="p-6 text-sm text-muted-foreground">{t("analytics.noRuns")}</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="text-left text-xs text-muted-foreground">
                      <tr className="border-b">
                        <th className="p-3 font-medium">{t("analytics.flow")}</th>
                        <th className="p-3 font-medium">{t("analytics.runs")}</th>
                        <th className="w-48 p-3 font-medium">{t("analytics.successRate")}</th>
                        <th className="p-3 font-medium">{t("analytics.people")}</th>
                        <th className="p-3 font-medium">{t("analytics.lastRun")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.data.flows.map((f) => (
                        <tr key={f.flowId} className="border-b last:border-0" data-testid="analytics-row">
                          <td className="p-3">
                            <Link to="/flows/$flowId/analytics" params={{ flowId: f.flowId }} className="font-medium hover:underline">
                              {f.name}
                            </Link>
                            <div className="text-xs text-muted-foreground">{f.appPackage}</div>
                          </td>
                          <td className="p-3">{fmt.number(f.usage.runs)}</td>
                          <td className="p-3">
                            <div className="flex items-center gap-2">
                              <SplitBar
                                label={f.name}
                                parts={[
                                  { key: "c", value: f.usage.completed, color: STATUS_COLORS.completed, name: t("analytics.completed") },
                                  { key: "s", value: f.usage.stopped, color: STATUS_COLORS.stopped, name: t("analytics.stopped") },
                                  { key: "f", value: f.usage.failed, color: STATUS_COLORS.failed, name: t("analytics.failed") },
                                ]}
                              />
                              <span className="w-10 text-right tabular-nums">{f.usage.successRate == null ? "–" : fmt.percent(f.usage.successRate)}</span>
                            </div>
                          </td>
                          <td className="p-3">{fmt.number(f.usage.users)}</td>
                          <td className="p-3 text-muted-foreground">{f.usage.lastRunAt ? fmt.dateTime(f.usage.lastRunAt) : t("analytics.never")}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
