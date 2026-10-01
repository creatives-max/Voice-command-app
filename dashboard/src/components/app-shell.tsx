import { useEffect } from "react";
import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import { useQueryClient, useSuspenseQuery } from "@tanstack/react-query";
import { BarChart3, Building2, HandHeart, History, Languages, LogOut, Mic, Moon, Smartphone, Store, Sun, SunMoon, UserRound, Workflow } from "lucide-react";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { useQuery } from "@tanstack/react-query";
import { personName } from "@/features/care/care";
import { useCurrentOrg, useSwitchCare, useSwitchOrg } from "@/features/org/use-org";
import { setCareLinkId, useCareLinkId } from "@/lib/care";
import { api } from "@/lib/api";
import { LOCALE_NAMES, locales, setLocale, useLocale, useT, type Locale } from "@/lib/i18n";
import { setOrgId } from "@/lib/org";
import { careLinksQuery, sessionQuery } from "@/lib/queries";
import { setTheme, themes, useTheme, type Theme } from "@/lib/theme";

/** People the user helps as a caregiver (active links). */
function useHelping() {
  const links = useQuery(careLinksQuery);
  return { helping: (links.data ?? []).filter((l) => l.role === "caregiver" && l.status === "ACTIVE"), loading: links.isPending };
}

/**
 * Personal flows, one of the user's organizations, or a person they help as a caregiver; every page
 * then works in that context.
 */
function OrgSwitcher() {
  const { orgId, orgs, loading } = useCurrentOrg();
  const { helping, loading: loadingCare } = useHelping();
  const careLink = useCareLinkId();
  const switchOrg = useSwitchOrg();
  const switchCare = useSwitchCare();
  const navigate = useNavigate();
  const t = useT();
  // A remembered organization the user no longer belongs to falls back to personal flows.
  useEffect(() => {
    if (!loading && orgId && !orgs.some((o) => o.id === orgId)) switchOrg(null);
  }, [loading, orgId, orgs, switchOrg]);
  // Likewise for someone who stopped being helped.
  useEffect(() => {
    if (!loadingCare && careLink && !helping.some((l) => l.id === careLink)) switchCare(null);
  }, [loadingCare, careLink, helping, switchCare]);

  return (
    <NativeSelect
      aria-label={t("nav.workspace")}
      className="mb-2"
      value={careLink ? `care:${careLink}` : (orgId ?? "")}
      onChange={(e) => {
        const value = e.target.value;
        if (value === "__new") {
          switchCare(null);
          switchOrg(null);
          void navigate({ to: "/org" });
          return;
        }
        if (value.startsWith("care:")) {
          switchCare(value.slice(5));
        } else {
          switchCare(null);
          switchOrg(value || null);
        }
        void navigate({ to: "/" });
      }}
    >
      <option value="">{t("nav.personal")}</option>
      {orgs.map((o) => (
        <option key={o.id} value={o.id}>
          {o.name}
        </option>
      ))}
      {helping.length > 0 && (
        <optgroup label={t("nav.helpingGroup")}>
          {helping.map((l) => (
            <option key={l.id} value={`care:${l.id}`}>
              {personName(l)}
            </option>
          ))}
        </optgroup>
      )}
      <option value="__new">{t("nav.newOrg")}</option>
    </NativeSelect>
  );
}

/** Shown on every page while a caregiver works on someone else's account. */
function HelpingBanner() {
  const careLink = useCareLinkId();
  const { helping } = useHelping();
  const switchCare = useSwitchCare();
  const t = useT();
  const link = helping.find((l) => l.id === careLink);
  if (!careLink || !link) return null;
  return (
    <div role="status" className="mb-4 flex flex-wrap items-center gap-3 rounded-md border border-primary/40 bg-primary/10 px-4 py-2 text-sm">
      <HandHeart className="size-4 text-primary" aria-hidden />
      <span className="flex-1">{t("care.banner", { name: personName(link) })}</span>
      <Button size="sm" variant="outline" onClick={() => switchCare(null)}>
        {t("care.stop")}
      </Button>
    </div>
  );
}

const THEME_ICONS: Record<Theme, typeof Sun> = { system: SunMoon, light: Sun, dark: Moon };

/** Theme (system/light/dark) and language, remembered on this browser. */
function Preferences() {
  const theme = useTheme();
  const locale = useLocale();
  const t = useT();
  const ThemeIcon = THEME_ICONS[theme];
  return (
    <div className="grid gap-2 px-1 pt-4">
      <label className="flex items-center gap-2 text-xs text-muted-foreground">
        <ThemeIcon className="size-4 shrink-0" aria-hidden />
        <NativeSelect aria-label={t("prefs.theme")} className="h-8 text-xs" value={theme} onChange={(e) => setTheme(e.target.value as Theme)}>
          {themes.map((th) => (
            <option key={th} value={th}>
              {t(`prefs.theme.${th}`)}
            </option>
          ))}
        </NativeSelect>
      </label>
      <label className="flex items-center gap-2 text-xs text-muted-foreground">
        <Languages className="size-4 shrink-0" aria-hidden />
        <NativeSelect aria-label={t("prefs.language")} className="h-8 text-xs" value={locale} onChange={(e) => setLocale(e.target.value as Locale)}>
          {locales.map((l) => (
            <option key={l} value={l}>
              {LOCALE_NAMES[l]}
            </option>
          ))}
        </NativeSelect>
      </label>
    </div>
  );
}

export function AppShell() {
  const { data: user } = useSuspenseQuery(sessionQuery);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const t = useT();
  const acting = useCareLinkId() != null;

  async function signOut() {
    await api.logout().catch(() => undefined);
    setOrgId(null);
    setCareLinkId(null);
    qc.clear();
    await navigate({ to: "/login", search: {} });
  }

  const nav = "flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-secondary hover:text-foreground";
  const active = { className: "bg-secondary !text-foreground" };
  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground md:flex-row">
      <aside className="flex shrink-0 flex-col gap-1 border-b p-4 md:w-60 md:border-b-0 md:border-r">
        <div className="mb-4 flex items-center gap-2 px-2 text-lg font-semibold">
          <span className="grid size-8 place-items-center rounded-lg bg-primary text-primary-foreground">
            <Mic className="size-4" />
          </span>
          VoiceControl
        </div>
        <OrgSwitcher />
        <Link to="/" className={nav} activeProps={active} activeOptions={{ exact: true }}>
          <Workflow className="size-4" /> {t("nav.flows")}
        </Link>
        <Link to="/analytics" className={nav} activeProps={active}>
          <BarChart3 className="size-4" /> {t("nav.analytics")}
        </Link>
        <Link to="/history" className={nav} activeProps={active}>
          <History className="size-4" /> {t("nav.history")}
        </Link>
        <Link to="/marketplace" className={nav} activeProps={active}>
          <Store className="size-4" /> {t("nav.marketplace")}
        </Link>
        <Link to="/devices" className={nav} activeProps={active}>
          <Smartphone className="size-4" /> {t("nav.devices")}
        </Link>
        {!acting && (
          <Link to="/org" className={nav} activeProps={active}>
            <Building2 className="size-4" /> {t("nav.organization")}
          </Link>
        )}
        <Link to="/care" className={nav} activeProps={active}>
          <HandHeart className="size-4" /> {t("nav.caregiving")}
        </Link>
        <Link to="/profile" className={nav} activeProps={active}>
          <UserRound className="size-4" /> {t("nav.profile")}
        </Link>
        <div className="mt-auto">
          <Preferences />
          <div className="hidden px-3 pt-4 text-xs text-muted-foreground md:block">{user.email}</div>
          <Button variant="ghost" className="w-full justify-start text-muted-foreground" onClick={signOut}>
            <LogOut /> {t("nav.signOut")}
          </Button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 p-4 md:p-8">
        <HelpingBanner />
        <Outlet />
      </main>
    </div>
  );
}
