import { useEffect, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
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
    <div className="mx-auto max-w-2xl">
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
    </div>
  );
}
