import { useNavigate, useParams } from "@tanstack/react-router";
import { useQuery, useSuspenseQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { orgKeys, sessionQuery } from "@/lib/queries";
import { ROLE_LABELS } from "@/lib/types";
import { useQueryClient, useMutation } from "@tanstack/react-query";
import { useSwitchOrg } from "./use-org";

/** Opened from an invitation link: shows what it's for and joins the organization. */
export function AcceptInvitePage() {
  const { token } = useParams({ from: "/app/invite/$token" });
  const { data: me } = useSuspenseQuery(sessionQuery);
  const preview = useQuery({ queryKey: ["invitation", token], queryFn: () => api.invitationPreview(token), retry: false });
  const qc = useQueryClient();
  const switchOrg = useSwitchOrg();
  const navigate = useNavigate();
  const accept = useMutation({
    mutationFn: () => api.acceptInvitation(token),
    onSuccess: async (org) => {
      await qc.invalidateQueries({ queryKey: orgKeys.orgs });
      switchOrg(org.id);
      toast.success(`You joined ${org.name}`);
      await navigate({ to: "/" });
    },
    onError: (e) => toast.error(e.message),
  });

  if (preview.isPending) return <Skeleton className="mx-auto h-40 max-w-md" />;
  if (preview.error || !preview.data) {
    return (
      <Card className="mx-auto max-w-md">
        <CardHeader>
          <CardTitle>Invitation not available</CardTitle>
          <CardDescription>This link has expired, was revoked or was already used. Ask an admin for a new one.</CardDescription>
        </CardHeader>
      </Card>
    );
  }
  const inv = preview.data;
  const wrongAccount = inv.email.toLowerCase() !== me.email.toLowerCase();
  return (
    <Card className="mx-auto max-w-md">
      <CardHeader>
        <CardTitle>Join {inv.orgName}</CardTitle>
        <CardDescription>
          You were invited as <strong>{ROLE_LABELS[inv.role].toLowerCase()}</strong>. The invitation expires {new Date(inv.expiresAt).toLocaleDateString()}.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3">
        {wrongAccount && (
          <p className="text-sm text-destructive">
            This invitation is for {inv.email}, but you are signed in as {me.email}. Sign in with that email to accept it.
          </p>
        )}
        <Button onClick={() => accept.mutate()} disabled={wrongAccount || accept.isPending}>
          Join organization
        </Button>
      </CardContent>
    </Card>
  );
}
