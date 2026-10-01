import type { z } from "zod";
import {
  apiErrorSchema,
  appSummarySchema,
  flowPageSchema,
  flowSchema,
  flowVersionSchema,
  profileSchema,
  userSchema,
  type Flow,
  type FlowStep,
  type Profile,
} from "./types";

/** Error from the backend (or the proxy) with its machine-readable code. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
  get isUnauthorized() {
    return this.status === 401;
  }
  get isConflict() {
    return this.status === 409;
  }
}

type Fetcher = typeof fetch;

/** Calls the same-origin `/api` proxy (which holds the tokens in httpOnly cookies). */
export function createApi(fetcher: Fetcher = (...args) => fetch(...args)) {
  async function request<S extends z.ZodTypeAny>(schema: S | null, path: string, init?: RequestInit): Promise<z.output<S>> {
    const response = await fetcher(`/api${path}`, {
      ...init,
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    });
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      const parsed = apiErrorSchema.safeParse(body);
      throw new ApiError(response.status, parsed.success ? parsed.data.error : "http_error", parsed.success ? parsed.data.message : response.statusText);
    }
    if (schema === null || response.status === 204) return undefined as z.output<S>;
    return schema.parse(await response.json());
  }
  const json = (body: unknown) => JSON.stringify(body);

  return {
    session: () => request(userSchema, "/session"),
    login: (email: string, password: string) => request(userSchema, "/auth/login", { method: "POST", body: json({ email, password }) }),
    register: (email: string, password: string, name?: string) =>
      request(userSchema, "/auth/register", { method: "POST", body: json({ email, password, name: name || undefined }) }),
    logout: () => request(null, "/auth/logout", { method: "POST", body: json({}) }),

    apps: () => request(appSummarySchema.array(), "/flows/apps"),
    flows: (appPackage?: string) =>
      request(flowPageSchema, `/flows?limit=200${appPackage ? `&appPackage=${encodeURIComponent(appPackage)}` : ""}`),
    flow: (id: string) => request(flowSchema, `/flows/${id}`),
    updateFlow: (id: string, input: { expectedVersion: number; name?: string; steps: FlowStep[]; changeNote?: string }) =>
      request(flowSchema, `/flows/${id}`, { method: "PUT", body: json(input) }),
    deleteFlow: (id: string) => request(null, `/flows/${id}`, { method: "DELETE" }),
    versions: (id: string) => request(flowVersionSchema.array(), `/flows/${id}/versions`),
    rollback: (id: string, version: number) => request(flowSchema, `/flows/${id}/rollback`, { method: "POST", body: json({ version }) }),

    profile: () => request(profileSchema, "/profile"),
    saveProfile: (profile: Profile) => request(profileSchema, "/profile", { method: "PUT", body: json(profile) }),
  };
}

export const api = createApi();
export type Api = ReturnType<typeof createApi>;
export type { Flow };
