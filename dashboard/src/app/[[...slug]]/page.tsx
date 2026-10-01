import { ClientApp } from "./client-app";

// Every path is served by the same shell; TanStack Router owns client-side routing.
export function generateStaticParams() {
  return [{ slug: [] }];
}

export default function Page() {
  return <ClientApp />;
}
