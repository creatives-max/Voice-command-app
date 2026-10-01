import { z } from "zod";
import { getOrgId } from "./org";
import {
  analyticsOverviewSchema,
  commentSchema,
  flowAnalyticsSchema,
  layoutSchema,
  presenceSchema,
  apiKeySchema,
  auditEntrySchema,
  deliverySchema,
  invitationPreviewSchema,
  invitationSchema,
  memberSchema,
  orgSchema,
  webhookSchema,
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
  type Layout,
  type Profile,
} from "./types";
import type { Role } from "./org";

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
export function createApi(fetcher: Fetcher = (...args) => fetch(...args), orgId: () => string | null = getOrgId) {
  async function request<S extends z.ZodTypeAny>(schema: S | null, path: string, init?: RequestInit): Promise<z.output<S>> {
    const org = orgId();
    const response = await fetcher(`/api${path}`, {
      ...init,
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", ...(org ? { "X-Org-Id": org } : {}), ...(init?.headers ?? {}) },
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

    transferFlow: (id: string, orgId: string | null) => request(flowSchema, `/flows/${id}/transfer`, { method: "POST", body: json({ orgId }) }),

    orgs: () => request(orgSchema.array(), "/orgs"),
    createOrg: (name: string) => request(orgSchema, "/orgs", { method: "POST", body: json({ name }) }),
    renameOrg: (orgId: string, name: string) => request(null, `/orgs/${orgId}`, { method: "PATCH", body: json({ name }) }),
    deleteOrg: (orgId: string) => request(null, `/orgs/${orgId}`, { method: "DELETE" }),
    members: (orgId: string) => request(memberSchema.array(), `/orgs/${orgId}/members`),
    setRole: (orgId: string, userId: string, role: Role) => request(null, `/orgs/${orgId}/members/${userId}`, { method: "PATCH", body: json({ role }) }),
    removeMember: (orgId: string, userId: string) => request(null, `/orgs/${orgId}/members/${userId}`, { method: "DELETE" }),
    invitations: (orgId: string) => request(invitationSchema.array(), `/orgs/${orgId}/invitations`),
    invite: (orgId: string, email: string, role: Role) => request(invitationSchema, `/orgs/${orgId}/invitations`, { method: "POST", body: json({ email, role }) }),
    revokeInvitation: (orgId: string, id: string) => request(null, `/orgs/${orgId}/invitations/${id}`, { method: "DELETE" }),
    invitationPreview: (token: string) => request(invitationPreviewSchema, `/invitations/${encodeURIComponent(token)}`),
    acceptInvitation: (token: string) => request(orgSchema, `/invitations/${encodeURIComponent(token)}/accept`, { method: "POST", body: json({}) }),

    apiKeys: (orgId: string) => request(apiKeySchema.array(), `/orgs/${orgId}/api-keys`),
    createApiKey: (orgId: string, input: { name: string; scopes: string[]; rateLimitPerMinute: number }) =>
      request(apiKeySchema, `/orgs/${orgId}/api-keys`, { method: "POST", body: json(input) }),
    revokeApiKey: (orgId: string, id: string) => request(null, `/orgs/${orgId}/api-keys/${id}`, { method: "DELETE" }),

    webhooks: (orgId: string) => request(webhookSchema.array(), `/orgs/${orgId}/webhooks`),
    createWebhook: (orgId: string, input: { url: string; events: string[] }) =>
      request(webhookSchema, `/orgs/${orgId}/webhooks`, { method: "POST", body: json(input) }),
    updateWebhook: (orgId: string, id: string, patch: { url?: string; events?: string[]; active?: boolean }) =>
      request(webhookSchema, `/orgs/${orgId}/webhooks/${id}`, { method: "PATCH", body: json(patch) }),
    deleteWebhook: (orgId: string, id: string) => request(null, `/orgs/${orgId}/webhooks/${id}`, { method: "DELETE" }),
    rotateWebhookSecret: (orgId: string, id: string) => request(webhookSchema, `/orgs/${orgId}/webhooks/${id}/rotate-secret`, { method: "POST", body: json({}) }),
    pingWebhook: (orgId: string, id: string) => request(deliverySchema, `/orgs/${orgId}/webhooks/${id}/ping`, { method: "POST", body: json({}) }),
    deliveries: (orgId: string, id: string) => request(deliverySchema.array(), `/orgs/${orgId}/webhooks/${id}/deliveries?limit=50`),
    redeliver: (orgId: string, deliveryId: string) =>
      request(deliverySchema, `/orgs/${orgId}/webhooks/deliveries/${deliveryId}/redeliver`, { method: "POST", body: json({}) }),

    audit: (orgId: string, params: { action?: string; before?: number }) => {
      const qs = new URLSearchParams({ limit: "50" });
      if (params.action) qs.set("action", params.action);
      if (params.before) qs.set("before", String(params.before));
      return request(auditEntrySchema.array(), `/orgs/${orgId}/audit?${qs.toString()}`);
    },

    analyticsOverview: (days: number, tz: string) =>
      request(analyticsOverviewSchema, `/analytics/flows?days=${days}&tz=${encodeURIComponent(tz)}`),
    flowAnalytics: (id: string, days: number, tz: string) =>
      request(flowAnalyticsSchema, `/flows/${id}/analytics?days=${days}&tz=${encodeURIComponent(tz)}`),

    comments: (flowId: string) => request(commentSchema.array(), `/flows/${flowId}/comments`),
    addComment: (flowId: string, body: string, stepId?: string | null) =>
      request(commentSchema, `/flows/${flowId}/comments`, { method: "POST", body: json({ body, stepId: stepId || undefined }) }),
    editComment: (id: string, patch: { body?: string; resolved?: boolean }) => request(commentSchema, `/comments/${id}`, { method: "PATCH", body: json(patch) }),
    deleteComment: (id: string) => request(null, `/comments/${id}`, { method: "DELETE" }),
    presence: (flowId: string, editing: boolean) => request(presenceSchema, `/flows/${flowId}/presence`, { method: "POST", body: json({ editing }) }),
    leave: (flowId: string) => request(null, `/flows/${flowId}/presence`, { method: "DELETE", keepalive: true }),
    layout: (flowId: string) => request(layoutSchema, `/flows/${flowId}/layout`),
    saveLayout: (flowId: string, positions: Layout["positions"]) => request(null, `/flows/${flowId}/layout`, { method: "PUT", body: json({ positions }) }),

    profile: () => request(profileSchema, "/profile"),
    saveProfile: (profile: Profile) => request(profileSchema, "/profile", { method: "PUT", body: json(profile) }),
  };
}

export const api = createApi();
export type Api = ReturnType<typeof createApi>;
export type { Flow };
