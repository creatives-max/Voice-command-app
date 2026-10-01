import { Card, CardContent } from "@/components/ui/card";
import { useFormat, useT } from "@/lib/i18n";
import type { Usage } from "@/lib/types";
import { countChange, formatChange, formatDuration, rateChange, type Change } from "./analytics";

/** Headline numbers; with [previous] (the same number of days before) each shows how it changed. */
export function Kpis({ usage, previous, days, showDuration = true }: { usage: Usage; previous?: Usage | null; days?: number; showDuration?: boolean }) {
  const t = useT();
  const fmt = useFormat();
  const items: { label: string; value: string; change?: Change | null; points?: boolean }[] = [
    { label: t("analytics.runs"), value: fmt.number(usage.runs), change: previous && countChange(usage.runs, previous.runs) },
    {
      label: t("analytics.successRate"),
      value: usage.successRate == null ? "–" : fmt.percent(usage.successRate),
      change: previous && rateChange(usage.successRate, previous.successRate),
      points: true,
    },
    { label: t("analytics.people"), value: fmt.number(usage.users), change: previous && countChange(usage.users, previous.users) },
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
            {k.change && (
              <span
                className={`text-xs ${k.change.direction === "up" || k.change.direction === "new" ? "text-emerald-600 dark:text-emerald-400" : k.change.direction === "down" ? "text-destructive" : "text-muted-foreground"}`}
                data-testid={`kpi-change-${k.label}`}
              >
                {k.change.direction === "new" ? t("analytics.new") : formatChange(k.change, k.points)} {t("analytics.vsPrevious", { days: days ?? 0 })}
              </span>
            )}
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
