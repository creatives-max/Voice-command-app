import { useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { setCareLinkId } from "@/lib/care";
import { setOrgId, useOrgId } from "@/lib/org";
import { orgsQuery } from "@/lib/queries";
import type { Org } from "@/lib/types";

/** The selected organization (null = personal) with the caller's role, once the list has loaded. */
export function useCurrentOrg(): { orgId: string | null; org: Org | null; orgs: Org[]; loading: boolean } {
  const orgId = useOrgId();
  const orgs = useQuery(orgsQuery);
  const list = orgs.data ?? [];
  return { orgId, org: list.find((o) => o.id === orgId) ?? null, orgs: list, loading: orgs.isPending };
}

/** Switches organization and drops data loaded for the previous one. */
export function useSwitchOrg() {
  const qc = useQueryClient();
  return useCallback(
    (id: string | null) => {
      setOrgId(id);
      if (id) setCareLinkId(null);
      void qc.resetQueries({ predicate: (q) => !["session", "orgs", "care"].includes(String(q.queryKey[0])) });
    },
    [qc],
  );
}

/** Starts or stops working for a person the caller helps (null = their own account). */
export function useSwitchCare() {
  const qc = useQueryClient();
  return useCallback(
    (linkId: string | null) => {
      setCareLinkId(linkId);
      if (linkId) setOrgId(null);
      void qc.resetQueries({ predicate: (q) => !["session", "orgs", "care"].includes(String(q.queryKey[0])) });
    },
    [qc],
  );
}
