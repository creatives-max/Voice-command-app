import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AlertCircle, ArrowRightLeft, Bot, Check, CircleSlash, Hand, MousePointerClick, RotateCcw, User, Variable, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { profileQuery } from "@/lib/queries";
import type { FlowStep } from "@/lib/types";
import { simulate, type SimEntryKind } from "./simulator";

const ICONS: Record<SimEntryKind, typeof Bot> = {
  screen: ArrowRightLeft,
  ask: Bot,
  answer: User,
  fill: Check,
  skip: CircleSlash,
  press: MousePointerClick,
  variable: Variable,
  error: AlertCircle,
  manual: Hand,
  done: Check,
};

const TONES: Partial<Record<SimEntryKind, string>> = {
  screen: "font-medium text-primary",
  answer: "text-foreground",
  fill: "text-green-700 dark:text-green-400",
  error: "text-destructive",
  skip: "text-muted-foreground",
  done: "font-medium text-green-700 dark:text-green-400",
};

/**
 * Test mode: runs the flow being edited (unsaved changes included) without a phone. The tester types
 * what the user would say; the panel shows what VoiceControl would ask, fill and press.
 */
export function SimulatorPanel({ steps, onClose }: { steps: FlowStep[]; onClose: () => void }) {
  const profile = useQuery({ ...profileQuery, retry: false });
  const [answers, setAnswers] = useState<string[]>([]);
  const [draft, setDraft] = useState("");
  const result = useMemo(() => simulate(steps, { answers, profile: profile.data ?? null }), [steps, answers, profile.data]);

  const answer = (text: string) => {
    if (!text.trim()) return;
    setAnswers((a) => [...a, text.trim()]);
    setDraft("");
  };

  return (
    <Card className="flex max-h-[calc(100vh-8rem)] flex-col gap-3 p-4" aria-label="Test run">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">Test run</h2>
        <Badge variant="secondary">no phone needed</Badge>
        <div className="ml-auto flex gap-1">
          <Button variant="ghost" size="sm" onClick={() => setAnswers([])} disabled={answers.length === 0}>
            <RotateCcw /> Restart
          </Button>
          <Button variant="ghost" size="icon" aria-label="Close test run" onClick={onClose}>
            <X />
          </Button>
        </div>
      </div>
      <ol className="grid flex-1 gap-1 overflow-y-auto text-sm" aria-live="polite">
        {result.transcript.map((entry, i) => {
          const Icon = ICONS[entry.kind];
          return (
            <li key={i} className={`flex items-start gap-2 ${TONES[entry.kind] ?? ""} ${entry.kind === "answer" ? "pl-6" : ""}`}>
              <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
              <span>
                <span className="sr-only">{entry.kind}: </span>
                {entry.text}
              </span>
            </li>
          );
        })}
      </ol>
      {result.pending && (
        <form
          className="grid gap-2 border-t pt-3"
          onSubmit={(e) => {
            e.preventDefault();
            answer(draft);
          }}
        >
          <p className="text-sm">
            <Bot className="mr-1 inline size-4" />
            {result.pending.question}
          </p>
          <div className="flex gap-2">
            <Input autoFocus aria-label="Your answer" placeholder="Type what the user says…" value={draft} onChange={(e) => setDraft(e.target.value)} />
            <Button type="submit" disabled={!draft.trim()}>
              Answer
            </Button>
          </div>
          <div className="flex flex-wrap gap-1">
            {(result.pending.expects === "yesno" ? ["yes", "no"] : ["skip"]).map((q) => (
              <Button key={q} type="button" variant="outline" size="sm" onClick={() => answer(q)}>
                {q}
              </Button>
            ))}
          </div>
        </form>
      )}
      {Object.keys(result.vars).length > 0 && (
        <details className="border-t pt-2 text-sm">
          <summary className="cursor-pointer font-medium">Variables</summary>
          <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            {Object.entries(result.vars).map(([k, v]) => (
              <div key={k} className="contents">
                <dt>
                  <code>{k}</code>
                </dt>
                <dd className="break-all">{v || "(empty)"}</dd>
              </div>
            ))}
          </dl>
        </details>
      )}
      <p className="text-xs text-muted-foreground">
        Uses your saved profile for profile values. Passwords, OTPs and PINs are never asked by voice.
      </p>
    </Card>
  );
}
