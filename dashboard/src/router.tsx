import { createRootRouteWithContext, createRoute, createRouter, Outlet, redirect } from "@tanstack/react-router";
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
import { queryClient } from "@/lib/query-client";
import { sessionQuery } from "@/lib/queries";

interface RouterContext {
  queryClient: QueryClient;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: () => <Outlet />,
  notFoundComponent: () => <p className="p-8 text-muted-foreground">Page not found.</p>,
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  component: LoginPage,
});

const privacyRoute = createRoute({ getParentRoute: () => rootRoute, path: "/privacy", component: PrivacyPage });

/** Authenticated area: redirects to /login when there is no valid session. */
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "app",
  beforeLoad: async ({ context }) => {
    try {
      await context.queryClient.ensureQueryData(sessionQuery);
    } catch {
      throw redirect({ to: "/login" });
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
const liveRunRoute = createRoute({ getParentRoute: () => appRoute, path: "runs/$requestId", component: LiveRunPage });

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
  ])]);

export const router = createRouter({ routeTree, context: { queryClient }, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
