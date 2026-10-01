import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, LogOut, MonitorSmartphone } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import { sessionQuery } from "@/lib/queries";
import { ago, passwordProblem, sessionName, sortSessions } from "./security";

const sessionsKey = ["me", "sessions"] as const;

/** Password change and the browsers and phones signed in to the account. */
export function SecurityCard() {
  const qc = useQueryClient();
  const me = useQuery(sessionQuery);
  const sessions = useQuery({ queryKey: sessionsKey, queryFn: api.sessions });
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const problem = passwordProblem(next, current);
  const mismatch = !!repeat && next !== repeat;

  const change = useMutation({
    mutationFn: () => api.changePassword(current, next),
    onSuccess: async () => {
      setCurrent("");
      setNext("");
      setRepeat("");
      toast.success("Password changed. Other browsers and phones were signed out.");
      await Promise.all([qc.invalidateQueries({ queryKey: sessionsKey }), qc.invalidateQueries({ queryKey: sessionQuery.queryKey })]);
    },
    onError: (e) => toast.error(e.message),
  });
  const end = useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) await api.endSession(id);
    },
    onSuccess: (_, ids) => toast.success(ids.length === 1 ? "Signed out" : `Signed out ${ids.length} sessions`),
    onError: (e) => toast.error(e.message),
    onSettled: () => qc.invalidateQueries({ queryKey: sessionsKey }),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    if (problem || mismatch) return;
    change.mutate();
  }

  const list = sortSessions(sessions.data ?? []);
  const others = list.filter((s) => !s.current).map((s) => s.id);
  const changedAt = me.data?.passwordChangedAt;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Security</CardTitle>
        <CardDescription>
          Change your password and see where you are signed in.{" "}
          {changedAt ? `Password last changed ${new Date(changedAt).toLocaleDateString()}.` : ""} After 10 wrong passwords, sign-in to your account pauses for 15
          minutes.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6">
        <form className="grid gap-3" onSubmit={submit} aria-label="Change password">
          <div className="grid gap-2">
            <Label htmlFor="current-password">Current password</Label>
            <Input id="current-password" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="grid gap-2">
              <Label htmlFor="new-password">New password</Label>
              <Input
                id="new-password"
                type="password"
                autoComplete="new-password"
                required
                value={next}
                aria-invalid={!!problem}
                aria-describedby="new-password-help"
                onChange={(e) => setNext(e.target.value)}
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="repeat-password">Repeat new password</Label>
              <Input id="repeat-password" type="password" autoComplete="new-password" required value={repeat} aria-invalid={mismatch} onChange={(e) => setRepeat(e.target.value)} />
            </div>
          </div>
          <p id="new-password-help" className={`text-xs ${problem || mismatch ? "text-destructive" : "text-muted-foreground"}`}>
            {problem ?? (mismatch ? "The new passwords don't match." : "At least 8 characters with letters and numbers. Other sessions are signed out.")}
          </p>
          <Button type="submit" className="justify-self-start" disabled={change.isPending || !current || !next || !!problem || mismatch || next !== repeat}>
            <KeyRound /> Change password
          </Button>
        </form>

        <div className="grid gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-medium">Signed in</h3>
            {others.length > 0 && (
              <Button variant="outline" size="sm" disabled={end.isPending} onClick={() => end.mutate(others)}>
                <LogOut /> Sign out everywhere else
              </Button>
            )}
          </div>
          {sessions.isPending ? (
            <Skeleton className="h-16" />
          ) : sessions.error ? (
            <p className="text-sm text-destructive">{sessions.error.message}</p>
          ) : (
            <ul className="divide-y rounded-md border">
              {list.map((s) => (
                <li key={s.id} className="flex flex-wrap items-center gap-2 p-3 text-sm" data-testid="session-row">
                  <MonitorSmartphone className="size-4 text-muted-foreground" aria-hidden />
                  <span className="font-medium">{sessionName(s)}</span>
                  {s.current && <Badge variant="secondary">This browser</Badge>}
                  <span className="text-xs text-muted-foreground">
                    signed in {new Date(s.createdAt).toLocaleDateString()} · used {ago(s.lastUsedAt)}
                  </span>
                  {!s.current && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="ml-auto"
                      aria-label={`Sign out ${sessionName(s)}`}
                      disabled={end.isPending}
                      onClick={() => end.mutate([s.id])}
                    >
                      Sign out
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
