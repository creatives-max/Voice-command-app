import { createRootRoute, createRoute, createRouter, Outlet } from "@tanstack/react-router";

const rootRoute = createRootRoute({
  component: () => (
    <div className="min-h-screen">
      <Outlet />
    </div>
  ),
});

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: () => (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-3xl font-semibold">VoiceControl Dashboard</h1>
      <p className="text-muted-foreground mt-2">Manage voice flows for your apps.</p>
    </main>
  ),
});

export const routeTree = rootRoute.addChildren([indexRoute]);
export const router = createRouter({ routeTree });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
