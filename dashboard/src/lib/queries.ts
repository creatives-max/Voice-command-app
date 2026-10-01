import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { api, type MarketplaceParams, type PublishInput, type TriggerInput } from "./api";
import type { FlowStep, Profile } from "./types";

export const keys = {
  session: ["session"] as const,
  apps: ["apps"] as const,
  flows: (appPackage?: string) => ["flows", appPackage ?? "all"] as const,
  flow: (id: string) => ["flow", id] as const,
  versions: (id: string) => ["flow", id, "versions"] as const,
  profile: ["profile"] as const,
  runs: (appPackage?: string) => ["runs", appPackage ?? "all"] as const,
  runStats: ["runs", "stats"] as const,
  devices: ["devices"] as const,
  triggers: (flowId: string) => ["flow", flowId, "triggers"] as const,
  runRequests: (flowId?: string) => ["run-requests", flowId ?? "all"] as const,
  marketplace: (p: MarketplaceParams) => ["marketplace", "list", p] as const,
  listing: (id: string) => ["marketplace", "listing", id] as const,
  templates: ["marketplace", "templates"] as const,
  categories: ["marketplace", "categories"] as const,
};

export const marketplaceQuery = (p: MarketplaceParams) => queryOptions({ queryKey: keys.marketplace(p), queryFn: () => api.marketplace(p) });
export const listingQuery = (id: string) => queryOptions({ queryKey: keys.listing(id), queryFn: () => api.listing(id) });
export const templatesQuery = queryOptions({ queryKey: keys.templates, queryFn: api.templates, staleTime: 10 * 60_000 });
export const categoriesQuery = queryOptions({ queryKey: keys.categories, queryFn: api.categories, staleTime: Infinity });

export function usePublishFlow(flowId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: PublishInput) => api.publishFlow(flowId, input),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["marketplace"] }),
  });
}

export function useImportListing() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version?: number }) => api.importListing(id, version),
    onSuccess: (flow) => {
      qc.setQueryData(keys.flow(flow.id), flow);
      void qc.invalidateQueries({ queryKey: ["marketplace"] });
      void qc.invalidateQueries({ queryKey: ["flows"] });
      void qc.invalidateQueries({ queryKey: keys.apps });
    },
  });
}

export function useUpdateFromSource(flowId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.updateFromSource(flowId),
    onSuccess: (flow) => {
      qc.setQueryData(keys.flow(flowId), flow);
      void qc.invalidateQueries({ queryKey: keys.versions(flowId) });
      void qc.invalidateQueries({ queryKey: ["marketplace"] });
    },
  });
}

export function useRateListing(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ stars, review }: { stars: number; review?: string }) => api.rateListing(id, stars, review),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["marketplace"] }),
  });
}

export function useReportListing(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ reason, note }: { reason: string; note?: string }) => api.reportListing(id, reason, note),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["marketplace"] }),
  });
}

export function useUnpublish() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (id: string) => api.unpublish(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["marketplace"] }) });
}

export const devicesQuery = queryOptions({ queryKey: keys.devices, queryFn: api.devices, refetchInterval: 30_000 });
export const triggersQuery = (flowId: string) => queryOptions({ queryKey: keys.triggers(flowId), queryFn: () => api.triggers(flowId) });
export const runRequestsQuery = (flowId?: string) =>
  queryOptions({ queryKey: keys.runRequests(flowId), queryFn: () => api.runRequests(flowId), refetchInterval: 10_000 });

export function useRunNow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ flowId, deviceId }: { flowId: string; deviceId?: string }) => api.runNow(flowId, deviceId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["run-requests"] }),
  });
}

export function useSaveTrigger(flowId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id?: string; input: TriggerInput }) => (id ? api.updateTrigger(id, input) : api.createTrigger(flowId, input)),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.triggers(flowId) }),
  });
}

export function useDeleteTrigger(flowId: string) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (id: string) => api.deleteTrigger(id), onSuccess: () => qc.invalidateQueries({ queryKey: keys.triggers(flowId) }) });
}

export function useUpdateDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: { name?: string; remoteRuns?: boolean } }) => api.updateDevice(id, patch),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.devices }),
  });
}

export function useDeleteDevice() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (id: string) => api.deleteDevice(id), onSuccess: () => qc.invalidateQueries({ queryKey: keys.devices }) });
}

export const sessionQuery = queryOptions({ queryKey: keys.session, queryFn: api.session, retry: false, staleTime: 60_000 });
export const appsQuery = queryOptions({ queryKey: keys.apps, queryFn: api.apps });
export const flowsQuery = (appPackage?: string) => queryOptions({ queryKey: keys.flows(appPackage), queryFn: () => api.flows(appPackage) });
export const flowQuery = (id: string) => queryOptions({ queryKey: keys.flow(id), queryFn: () => api.flow(id) });
export const versionsQuery = (id: string) => queryOptions({ queryKey: keys.versions(id), queryFn: () => api.versions(id) });
export const runsQuery = (appPackage?: string) => queryOptions({ queryKey: keys.runs(appPackage), queryFn: () => api.runs(appPackage) });
export const runStatsQuery = queryOptions({ queryKey: keys.runStats, queryFn: api.runStats });

export function useDeleteRun() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (id: string) => api.deleteRun(id), onSuccess: () => qc.invalidateQueries({ queryKey: ["runs"] }) });
}

export function useClearRuns() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: () => api.clearRuns(), onSuccess: () => qc.invalidateQueries({ queryKey: ["runs"] }) });
}

export const profileQuery = queryOptions({ queryKey: keys.profile, queryFn: api.profile });

export function useUpdateFlow(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { expectedVersion: number; name?: string; steps: FlowStep[]; changeNote?: string }) => api.updateFlow(id, input),
    onSuccess: (flow) => {
      qc.setQueryData(keys.flow(id), flow);
      void qc.invalidateQueries({ queryKey: keys.versions(id) });
      void qc.invalidateQueries({ queryKey: ["flows"] });
      void qc.invalidateQueries({ queryKey: keys.apps });
    },
  });
}

export function useRollback(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (version: number) => api.rollback(id, version),
    onSuccess: (flow) => {
      qc.setQueryData(keys.flow(id), flow);
      void qc.invalidateQueries({ queryKey: keys.versions(id) });
      void qc.invalidateQueries({ queryKey: ["flows"] });
    },
  });
}

export function useDeleteFlow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteFlow(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["flows"] });
      void qc.invalidateQueries({ queryKey: keys.apps });
    },
  });
}

export function useSaveProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (profile: Profile) => api.saveProfile(profile),
    onSuccess: (profile) => qc.setQueryData(keys.profile, profile),
  });
}

// Organizations ------------------------------------------------------------------------------------

export const orgKeys = {
  orgs: ["orgs"] as const,
  members: (orgId: string) => ["orgs", orgId, "members"] as const,
  invitations: (orgId: string) => ["orgs", orgId, "invitations"] as const,
  apiKeys: (orgId: string) => ["orgs", orgId, "api-keys"] as const,
  webhooks: (orgId: string) => ["orgs", orgId, "webhooks"] as const,
  deliveries: (orgId: string, webhookId: string) => ["orgs", orgId, "webhooks", webhookId, "deliveries"] as const,
  audit: (orgId: string, action: string, from = "", to = "") => ["orgs", orgId, "audit", action, from, to] as const,
  usage: (orgId: string) => ["orgs", orgId, "usage"] as const,
};

export const orgUsageQuery = (orgId: string) => queryOptions({ queryKey: orgKeys.usage(orgId), queryFn: () => api.orgUsage(orgId) });
export const orgsQuery = queryOptions({ queryKey: orgKeys.orgs, queryFn: api.orgs, staleTime: 30_000 });
export const membersQuery = (orgId: string) => queryOptions({ queryKey: orgKeys.members(orgId), queryFn: () => api.members(orgId) });
export const invitationsQuery = (orgId: string) => queryOptions({ queryKey: orgKeys.invitations(orgId), queryFn: () => api.invitations(orgId) });
export const apiKeysQuery = (orgId: string) => queryOptions({ queryKey: orgKeys.apiKeys(orgId), queryFn: () => api.apiKeys(orgId) });
export const webhooksQuery = (orgId: string) => queryOptions({ queryKey: orgKeys.webhooks(orgId), queryFn: () => api.webhooks(orgId) });
export const deliveriesQuery = (orgId: string, webhookId: string) =>
  queryOptions({ queryKey: orgKeys.deliveries(orgId, webhookId), queryFn: () => api.deliveries(orgId, webhookId), refetchInterval: 5_000 });

/** Invalidates everything under one organization (members, keys, webhooks, audit…). */
export function useOrgMutation<TVars, TResult>(orgId: string, fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["orgs", orgId] });
      void qc.invalidateQueries({ queryKey: orgKeys.orgs, exact: true });
    },
  });
}

export function useCreateOrg() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (name: string) => api.createOrg(name), onSuccess: () => qc.invalidateQueries({ queryKey: orgKeys.orgs }) });
}

export function useTransferFlow(flowId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (orgId: string | null) => api.transferFlow(flowId, orgId),
    onSuccess: (flow) => {
      qc.setQueryData(keys.flow(flowId), flow);
      void qc.invalidateQueries({ queryKey: ["flows"] });
      void qc.invalidateQueries({ queryKey: keys.apps });
    },
  });
}

export const careKeys = {
  links: ["care", "links"] as const,
  events: (id: string) => ["care", "events", id] as const,
};
export const careLinksQuery = queryOptions({ queryKey: careKeys.links, queryFn: api.careLinks, staleTime: 15_000 });
export const careEventsQuery = (id: string) => queryOptions({ queryKey: careKeys.events(id), queryFn: () => api.careEvents(id) });
