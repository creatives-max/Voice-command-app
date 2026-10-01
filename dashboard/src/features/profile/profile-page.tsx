import { useEffect, useState, type FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Download, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { setOrgId } from "@/lib/org";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { profileQuery, useSaveProfile } from "@/lib/queries";
import type { Profile } from "@/lib/types";

const FIELDS: { key: keyof Profile; label: string; placeholder?: string; type?: string }[] = [
  { key: "fullName", label: "Full name" },
  { key: "email", label: "Email", type: "email" },
  { key: "phone", label: "Mobile number", placeholder: "+91 98765 43210", type: "tel" },
  { key: "addressLine", label: "Address" },
  { key: "city", label: "City" },
  { key: "state", label: "State" },
  { key: "pincode", label: "PIN code", placeholder: "110001" },
  { key: "dateOfBirth", label: "Date of birth", placeholder: "31/12/1990" },
];

export function ProfilePage() {
  const { data, isPending } = useQuery(profileQuery);
  const save = useSaveProfile();
  const [draft, setDraft] = useState<Profile>({});
  useEffect(() => {
    if (data) setDraft(data);
  }, [data]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const cleaned = Object.fromEntries(Object.entries(draft).map(([k, v]) => [k, typeof v === "string" && v.trim() ? v.trim() : null])) as Profile;
    try {
      await save.mutateAsync(cleaned);
      toast.success("Profile saved. Your phone picks it up on its next sync.");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Save failed");
    }
  }

  if (isPending) return <Skeleton className="mx-auto h-96 max-w-2xl" />;
  return (
    <div className="mx-auto grid max-w-2xl gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Your profile</CardTitle>
          <CardDescription>VoiceControl offers these values when a form asks for them, e.g. “Say yes to use rahul@example.com”.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-4 sm:grid-cols-2" onSubmit={submit}>
            {FIELDS.map((f) => (
              <div key={f.key} className={`grid gap-2 ${f.key === "addressLine" ? "sm:col-span-2" : ""}`}>
                <Label htmlFor={f.key}>{f.label}</Label>
                <Input
                  id={f.key}
                  type={f.type ?? "text"}
                  placeholder={f.placeholder}
                  value={draft[f.key] ?? ""}
                  onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}
                />
              </div>
            ))}
            <div className="sm:col-span-2">
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? "Saving…" : "Save profile"}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
      <YourData />
    </div>
  );
}

/** Download a file the browser saves (for the data export). */
function saveFile(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

/** GDPR: download everything we store, or delete the account. */
function YourData() {
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState("");
  const qc = useQueryClient();
  const navigate = useNavigate();

  async function download() {
    setBusy(true);
    try {
      const text = await api.exportData();
      saveFile(`voicecontrol-data-${new Date().toISOString().slice(0, 10)}.json`, text);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Export failed");
    } finally {
      setBusy(false);
    }
  }

  async function remove(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.deleteAccount(password);
      await api.logout().catch(() => undefined);
      setOrgId(null);
      qc.clear();
      toast.success("Your account and data were deleted.");
      await navigate({ to: "/login", search: {} });
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Deletion failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Your data</CardTitle>
        <CardDescription>Download a copy of everything VoiceControl stores about you, or delete your account.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <Button variant="outline" className="justify-self-start" onClick={download} disabled={busy}>
          <Download /> Download my data (JSON)
        </Button>
        {!confirming ? (
          <Button variant="outline" className="justify-self-start text-destructive" onClick={() => setConfirming(true)}>
            <Trash2 /> Delete my account…
          </Button>
        ) : (
          <form className="grid gap-2 rounded-md border border-destructive/50 p-3" onSubmit={remove}>
            <p className="text-sm">
              This deletes your flows, history, profile, phones and comments for good. Organization flows stay with the organization. Enter your password to confirm.
            </p>
            <Label htmlFor="delete-password">Password</Label>
            <Input id="delete-password" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
            <div className="flex gap-2">
              <Button type="submit" variant="destructive" disabled={busy || !password}>
                Delete account
              </Button>
              <Button type="button" variant="ghost" onClick={() => setConfirming(false)}>
                Cancel
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}
