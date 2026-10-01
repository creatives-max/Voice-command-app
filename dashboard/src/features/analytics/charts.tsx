import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useLocale, useT } from "@/lib/i18n";
import type { Daily } from "@/lib/types";
import { STATUS_COLORS, chartDays } from "./analytics";

const axis = { stroke: "var(--muted-foreground)", fontSize: 12 };
const tooltip = {
  contentStyle: { background: "var(--popover)", border: "1px solid var(--border)", borderRadius: 8, color: "var(--popover-foreground)" },
  cursor: { fill: "var(--secondary)" },
};

/** Stacked bars of completed / stopped / failed runs per day. */
export function DailyRunsChart({ daily }: { daily: Daily[] }) {
  const t = useT();
  const locale = useLocale();
  return (
    <div className="h-64 w-full" data-testid="daily-chart">
      <ResponsiveContainer>
        <BarChart data={chartDays(daily, locale === "hi" ? "hi-IN" : "en-IN")} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="label" tick={axis} tickLine={false} axisLine={false} minTickGap={16} />
          <YAxis allowDecimals={false} tick={axis} tickLine={false} axisLine={false} />
          <Tooltip {...tooltip} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Bar dataKey="completed" name={t("analytics.completed")} stackId="runs" fill={STATUS_COLORS.completed} />
          <Bar dataKey="stopped" name={t("analytics.stopped")} stackId="runs" fill={STATUS_COLORS.stopped} />
          <Bar dataKey="failed" name={t("analytics.failed")} stackId="runs" fill={STATUS_COLORS.failed} radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/** One horizontal bar split by parts, with an accessible text summary. */
export function SplitBar({ parts, label }: { parts: { key: string; value: number; color: string; name: string }[]; label: string }) {
  const total = parts.reduce((n, p) => n + p.value, 0);
  return (
    <div className="flex h-3 w-full overflow-hidden rounded-full bg-secondary" role="img" aria-label={`${label}: ${parts.filter((p) => p.value).map((p) => `${p.name} ${p.value}`).join(", ")}`}>
      {total > 0 &&
        parts
          .filter((p) => p.value > 0)
          .map((p) => <span key={p.key} title={`${p.name}: ${p.value}`} style={{ width: `${(p.value / total) * 100}%`, background: p.color }} />)}
    </div>
  );
}
