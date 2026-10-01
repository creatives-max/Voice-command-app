import { Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useT } from "@/lib/i18n";
import type { PresenceInfo } from "@/lib/types";
import { initials } from "./use-presence";

/** Avatars of others who have the flow open, and a notice when the server has a newer version. */
export function PresenceBar({ info, loadedVersion, onReload }: { info: PresenceInfo | null; loadedVersion: number; onReload: () => void }) {
  const t = useT();
  if (!info) return null;
  const newer = info.currentVersion > loadedVersion;
  const editor = info.others.find((o) => o.editing);
  return (
    <div className="flex flex-wrap items-center gap-3">
      {info.others.length > 0 && (
        <div className="flex items-center gap-2" data-testid="presence">
          <div className="flex -space-x-2">
            {info.others.map((o) => (
              <span
                key={o.userId}
                title={t(o.editing ? "presence.editing" : "presence.viewing", { name: o.name })}
                className={`relative grid size-8 place-items-center rounded-full border-2 border-background text-xs font-semibold ${o.editing ? "bg-accent text-accent-foreground" : "bg-secondary"}`}
              >
                {initials(o.name)}
                {o.editing && <Pencil className="absolute -right-1 -bottom-1 size-3 rounded-full bg-background p-px" />}
              </span>
            ))}
          </div>
          <span className="text-xs text-muted-foreground">
            {t(editor ? "presence.editing" : "presence.viewing", { name: (editor ?? info.others[0]!).name })}
            {info.others.length > 1 ? ` +${info.others.length - 1}` : ""}
          </span>
        </div>
      )}
      {newer && (
        <div role="status" className="flex items-center gap-2 rounded-md border border-amber-500/50 bg-amber-500/10 px-3 py-1.5 text-sm" data-testid="newer-version">
          {t("editor.newerVersion", { name: editor?.name ?? info.others[0]?.name ?? "—", version: info.currentVersion })}
          <Button size="sm" variant="outline" onClick={onReload}>
            {t("editor.reload")}
          </Button>
        </div>
      )}
    </div>
  );
}
