import { z } from "zod";
import {
  listingDetailSchema,
  listingPageSchema,
  listingSchema,
  templateSchema,
  deviceSchema,
  runEventsSchema,
  runRequestSchema,
  triggerSchema,
  apiErrorSchema,
  appSummarySchema,
  flowPageSchema,
  flowSchema,
  flowVersionSchema,
  profileSchema,
  runPageSchema,
  runStatsSchema,
  userSchema,
  type Flow,
  type FlowStep,
  type Profile,
} from "./types";

export interface MarketplaceParams {
  q?: string;
  category?: string;
  templates?: boolean;
  mine?: boolean;
  sort?: "popular" | "rating" | "recent" | "relevance";
  appPackage?: string;
}

export interface PublishInput {
  name?: string;
  description: string;
  category: string;
  tags: string[];
  changelog?: string;
}

export interface TriggerInput {
  type: "APP_OPEN" | "SCHEDULE";
  enabled: boolean;
  cron?: string | null;
  timezone?: string | null;
  deviceId?: string | null;
}

const zStringArray = z.array(z.string());

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

    runs: (appPackage?: string) =>
      request(runPageSchema, `/runs?limit=100${appPackage ? `&appPackage=${encodeURIComponent(appPackage)}` : ""}`),
    runStats: () => request(runStatsSchema, "/runs/stats"),
    deleteRun: (id: string) => request(null, `/runs/${id}`, { method: "DELETE" }),
    clearRuns: () => request(null, "/runs", { method: "DELETE" }),

    devices: () => request(deviceSchema.array(), "/devices"),
    updateDevice: (id: string, patch: { name?: string; remoteRuns?: boolean }) =>
      request(deviceSchema, `/devices/${id}`, { method: "PATCH", body: json(patch) }),
    deleteDevice: (id: string) => request(null, `/devices/${id}`, { method: "DELETE" }),

    triggers: (flowId: string) => request(triggerSchema.array(), `/flows/${flowId}/triggers`),
    createTrigger: (flowId: string, input: TriggerInput) => request(triggerSchema, `/flows/${flowId}/triggers`, { method: "POST", body: json(input) }),
    updateTrigger: (id: string, input: TriggerInput) => request(triggerSchema, `/triggers/${id}`, { method: "PUT", body: json(input) }),
    deleteTrigger: (id: string) => request(null, `/triggers/${id}`, { method: "DELETE" }),

    runNow: (flowId: string, deviceId?: string) => request(runRequestSchema, "/run-requests", { method: "POST", body: json({ flowId, deviceId: deviceId || undefined }) }),
    runRequests: (flowId?: string) => request(runRequestSchema.array(), `/run-requests?limit=50${flowId ? `&flowId=${flowId}` : ""}`),
    runEvents: (id: string, after: number, wait: number, signal?: AbortSignal) =>
      request(runEventsSchema, `/run-requests/${id}/events?after=${after}&wait=${wait}`, { signal }),
    cancelRun: (id: string) => request(runRequestSchema, `/run-requests/${id}/cancel`, { method: "POST", body: json({}) }),

    marketplace: (p: MarketplaceParams) => {
      const qs = new URLSearchParams({ limit: "60" });
      if (p.q?.trim()) qs.set("q", p.q.trim());
      if (p.category) qs.set("category", p.category);
      if (p.templates !== undefined) qs.set("templates", String(p.templates));
      if (p.mine) qs.set("mine", "true");
      if (p.sort) qs.set("sort", p.sort);
      if (p.appPackage) qs.set("appPackage", p.appPackage);
      return request(listingPageSchema, `/marketplace?${qs.toString()}`);
    },
    categories: () => request(zStringArray, "/marketplace/categories"),
    listing: (id: string) => request(listingDetailSchema, `/marketplace/${id}`),
    templates: () => request(templateSchema.array(), "/marketplace/templates"),
    importListing: (id: string, version?: number) => request(flowSchema, `/marketplace/${id}/import`, { method: "POST", body: json({ version }) }),
    rateListing: (id: string, stars: number, review?: string) =>
      request(listingSchema, `/marketplace/${id}/rating`, { method: "PUT", body: json({ stars, review: review || undefined }) }),
    unpublish: (id: string) => request(null, `/marketplace/${id}`, { method: "DELETE" }),
    publishFlow: (flowId: string, input: PublishInput) => request(listingSchema, `/flows/${flowId}/publish`, { method: "POST", body: json(input) }),
    updateFromSource: (flowId: string) => request(flowSchema, `/flows/${flowId}/update-from-source`, { method: "POST", body: json({}) }),

    profile: () => request(profileSchema, "/profile"),
    saveProfile: (profile: Profile) => request(profileSchema, "/profile", { method: "PUT", body: json(profile) }),
  };
}

export const api = createApi();
export type Api = ReturnType<typeof createApi>;
export type { Flow };
