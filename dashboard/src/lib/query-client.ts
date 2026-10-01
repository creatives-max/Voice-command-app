import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./api";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
});

// A 401 anywhere means the session ended: drop it so the router guard sends the user to /login.
queryClient.getQueryCache().subscribe((event) => {
  const error = event.query.state.error;
  if (event.type === "updated" && error instanceof ApiError && error.isUnauthorized && event.query.queryKey[0] !== "session") {
    queryClient.removeQueries({ queryKey: ["session"] });
    if (typeof window !== "undefined" && window.location.pathname !== "/login") window.location.assign("/login");
  }
});
