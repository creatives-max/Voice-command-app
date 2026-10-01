"use client";

import dynamic from "next/dynamic";

const App = dynamic(() => import("@/app-root").then((m) => m.AppRoot), { ssr: false });

export function ClientApp() {
  return <App />;
}
