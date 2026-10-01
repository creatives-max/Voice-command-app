import { createRootRouteWithContext, createRoute, createRouter, Outlet, redirect } from "@tanstack/react-router";
import type { QueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { LoginPage } from "@/features/auth/login-page";
import { FlowEditorPage } from "@/features/flows/flow-editor-page";
import { FlowsPage } from "@/features/flows/flows-page";
import { VersionsPage } from "@/features/flows/versions-page";
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

export const routeTree = rootRoute.addChildren([loginRoute, appRoute.addChildren([flowsRoute, flowRoute, versionsRoute, profileRoute])]);

export const router = createRouter({ routeTree, context: { queryClient }, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
