import { queryOptions, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "./api";
import type { FlowStep, Profile } from "./types";

export const keys = {
  session: ["session"] as const,
  apps: ["apps"] as const,
  flows: (appPackage?: string) => ["flows", appPackage ?? "all"] as const,
  flow: (id: string) => ["flow", id] as const,
  versions: (id: string) => ["flow", id, "versions"] as const,
  profile: ["profile"] as const,
};

export const sessionQuery = queryOptions({ queryKey: keys.session, queryFn: api.session, retry: false, staleTime: 60_000 });
export const appsQuery = queryOptions({ queryKey: keys.apps, queryFn: api.apps });
export const flowsQuery = (appPackage?: string) => queryOptions({ queryKey: keys.flows(appPackage), queryFn: () => api.flows(appPackage) });
export const flowQuery = (id: string) => queryOptions({ queryKey: keys.flow(id), queryFn: () => api.flow(id) });
export const versionsQuery = (id: string) => queryOptions({ queryKey: keys.versions(id), queryFn: () => api.versions(id) });
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
