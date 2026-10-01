import { Card, CardContent } from "@/components/ui/card";
import { useFormat, useT } from "@/lib/i18n";
import type { Usage } from "@/lib/types";
import { formatDuration } from "./analytics";

export function Kpis({ usage, showDuration = true }: { usage: Usage; showDuration?: boolean }) {
  const t = useT();
  const fmt = useFormat();
  const items = [
    { label: t("analytics.runs"), value: fmt.number(usage.runs) },
    { label: t("analytics.successRate"), value: usage.successRate == null ? "–" : fmt.percent(usage.successRate) },
    { label: t("analytics.people"), value: fmt.number(usage.users) },
    ...(showDuration ? [{ label: t("analytics.avgDuration"), value: formatDuration(usage.avgDurationMillis) }] : []),
    { label: t("analytics.lastRun"), value: usage.lastRunAt ? fmt.dateTime(usage.lastRunAt) : t("analytics.never") },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
      {items.map((k) => (
        <Card key={k.label}>
          <CardContent className="grid gap-1 p-4">
            <span className="text-xs text-muted-foreground">{k.label}</span>
            <span className="text-xl font-semibold" data-testid={`kpi-${k.label}`}>
              {k.value}
            </span>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

export function PeriodSelect({ days, onChange }: { days: number; onChange: (d: number) => void }) {
  const t = useT();
  return (
    <div className="flex gap-1 rounded-md bg-secondary p-1" role="radiogroup" aria-label={t("analytics.period")}>
      {[7, 30, 90].map((d) => (
        <button
          key={d}
          role="radio"
          aria-checked={days === d}
          onClick={() => onChange(d)}
          className={`rounded px-3 py-1 text-sm ${days === d ? "bg-background font-medium shadow-sm" : "text-muted-foreground"}`}
        >
          {t("analytics.days", { days: d })}
        </button>
      ))}
    </div>
  );
}
