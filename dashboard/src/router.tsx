import { createRootRouteWithContext, createRoute, createRouter, Outlet, redirect } from "@tanstack/react-router";
import { CarePage } from "@/features/care/care-page";
import type { QueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { LoginPage } from "@/features/auth/login-page";
import { AutomationPage } from "@/features/automation/automation-page";
import { DevicesPage } from "@/features/automation/devices-page";
import { LiveRunPage } from "@/features/automation/live-run-page";
import { FlowEditorPage } from "@/features/flows/flow-editor-page";
import { FlowsPage } from "@/features/flows/flows-page";
import { VersionsPage } from "@/features/flows/versions-page";
import { HistoryPage } from "@/features/history/history-page";
import { ListingPage } from "@/features/marketplace/listing-page";
import { MarketplacePage } from "@/features/marketplace/marketplace-page";
import { PrivacyPage } from "@/features/legal/privacy-page";
import { ProfilePage } from "@/features/profile/profile-page";
import { AnalyticsPage } from "@/features/analytics/analytics-page";
import { FlowAnalyticsPage } from "@/features/analytics/flow-analytics-page";
import { AcceptInvitePage } from "@/features/org/accept-invite-page";
import { ApiKeysPage } from "@/features/org/api-keys-page";
import { AuditPage } from "@/features/org/audit-page";
import { OrgPage } from "@/features/org/org-page";
import { WebhooksPage } from "@/features/org/webhooks-page";
import { queryClient } from "@/lib/query-client";
import { sessionQuery } from "@/lib/queries";

interface RouterContext {
  queryClient: QueryClient;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: () => <Outlet />,
  notFoundComponent: () => <p className="p-8 text-muted-foreground">Page not found.</p>,
});

/** Only same-site paths are accepted as the page to return to after signing in. */
export const safeNext = (next: unknown) => (typeof next === "string" && next.startsWith("/") && !next.startsWith("//") ? next : undefined);

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  validateSearch: (search: Record<string, unknown>): { next?: string } => ({ next: safeNext(search.next) }),
  component: LoginPage,
});

const privacyRoute = createRoute({ getParentRoute: () => rootRoute, path: "/privacy", component: PrivacyPage });

/** Authenticated area: redirects to /login when there is no valid session. */
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "app",
  beforeLoad: async ({ context, location }) => {
    try {
      await context.queryClient.ensureQueryData(sessionQuery);
    } catch {
      throw redirect({ to: "/login", search: { next: location.href === "/" ? undefined : location.href } });
    }
  },
  component: AppShell,
});

const flowsRoute = createRoute({ getParentRoute: () => appRoute, path: "/", component: FlowsPage });
const flowRoute = createRoute({ getParentRoute: () => appRoute, path: "flows/$flowId", component: FlowEditorPage });
const versionsRoute = createRoute({ getParentRoute: () => appRoute, path: "flows/$flowId/versions", component: VersionsPage });
const profileRoute = createRoute({ getParentRoute: () => appRoute, path: "profile", component: ProfilePage });
const historyRoute = createRoute({ getParentRoute: () => appRoute, path: "history", component: HistoryPage });
const automationRoute = createRoute({ getParentRoute: () => appRoute, path: "flows/$flowId/automation", component: AutomationPage });
const devicesRoute = createRoute({ getParentRoute: () => appRoute, path: "devices", component: DevicesPage });
const marketplaceRoute = createRoute({ getParentRoute: () => appRoute, path: "marketplace", component: MarketplacePage });
const listingRoute = createRoute({ getParentRoute: () => appRoute, path: "marketplace/$listingId", component: ListingPage });
const analyticsRoute = createRoute({ getParentRoute: () => appRoute, path: "analytics", component: AnalyticsPage });
const flowAnalyticsRoute = createRoute({ getParentRoute: () => appRoute, path: "flows/$flowId/analytics", component: FlowAnalyticsPage });
const orgRoute = createRoute({ getParentRoute: () => appRoute, path: "org", component: OrgPage });
const apiKeysRoute = createRoute({ getParentRoute: () => appRoute, path: "org/api-keys", component: ApiKeysPage });
const webhooksRoute = createRoute({ getParentRoute: () => appRoute, path: "org/webhooks", component: WebhooksPage });
const auditRoute = createRoute({ getParentRoute: () => appRoute, path: "org/audit", component: AuditPage });
const inviteRoute = createRoute({ getParentRoute: () => appRoute, path: "invite/$token", component: AcceptInvitePage });
const liveRunRoute = createRoute({ getParentRoute: () => appRoute, path: "runs/$requestId", component: LiveRunPage });
const careRoute = createRoute({ getParentRoute: () => appRoute, path: "care", component: CarePage });

export const routeTree = rootRoute.addChildren([loginRoute, privacyRoute, appRoute.addChildren([
    flowsRoute,
    flowRoute,
    versionsRoute,
    automationRoute,
    profileRoute,
    historyRoute,
    devicesRoute,
    liveRunRoute,
    marketplaceRoute,
    listingRoute,
    analyticsRoute,
    flowAnalyticsRoute,
    orgRoute,
    apiKeysRoute,
    webhooksRoute,
    auditRoute,
    inviteRoute,
    careRoute,
  ])]);

export const router = createRouter({ routeTree, context: { queryClient }, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
