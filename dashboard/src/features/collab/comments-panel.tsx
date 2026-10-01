import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient, useSuspenseQuery } from "@tanstack/react-query";
import { Check, MessageSquare, Pencil, RotateCcw, Trash2, X } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/api";
import { useFormat, useT } from "@/lib/i18n";
import { sessionQuery } from "@/lib/queries";
import type { Comment, FlowStep } from "@/lib/types";

const commentsKey = (flowId: string) => ["flow", flowId, "comments"] as const;

/** Splits comments into open and resolved, newest activity last. */
export function partitionComments(comments: Comment[], stepId?: string | null) {
  const scoped = stepId ? comments.filter((c) => c.stepId === stepId) : comments;
  return { open: scoped.filter((c) => !c.resolvedAt), resolved: scoped.filter((c) => c.resolvedAt) };
}

export function useComments(flowId: string) {
  return useQuery({ queryKey: commentsKey(flowId), queryFn: () => api.comments(flowId), refetchInterval: 15_000 });
}

/** Discussion about the flow and its steps; refreshed every 15 s. */
export function CommentsPanel({ flowId, steps, focusStepId, onClose }: { flowId: string; steps: FlowStep[]; focusStepId?: string | null; onClose: () => void }) {
  const t = useT();
  const qc = useQueryClient();
  const comments = useComments(flowId);
  const [body, setBody] = useState("");
  const [stepId, setStepId] = useState<string>(focusStepId ?? "");
  const [showResolved, setShowResolved] = useState(false);
  const invalidate = () => qc.invalidateQueries({ queryKey: commentsKey(flowId) });
  const add = useMutation({ mutationFn: () => api.addComment(flowId, body, stepId || null), onSuccess: () => { setBody(""); void invalidate(); } });
  const { open, resolved } = partitionComments(comments.data ?? []);
  const label = (id?: string | null) => steps.find((s) => s.id === id)?.label;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (body.trim()) add.mutate(undefined, { onError: (err) => toast.error(err.message) });
  }

  return (
    <aside className="grid gap-3 rounded-lg border bg-card p-4" aria-label={t("comments.title")} data-testid="comments-panel">
      <div className="flex items-center gap-2">
        <MessageSquare className="size-4" />
        <h2 className="font-medium">{t("comments.title")}</h2>
        <Button variant="ghost" size="icon" className="ml-auto" aria-label={t("builder.close")} onClick={onClose}>
          <X />
        </Button>
      </div>
      <form className="grid gap-2" onSubmit={submit}>
        <Textarea aria-label={t("comments.placeholder")} placeholder={t("comments.placeholder")} value={body} onChange={(e) => setBody(e.target.value)} maxLength={2000} rows={3} />
        <div className="flex gap-2">
          <NativeSelect aria-label={t("comments.onStep")} className="h-8 text-xs" value={stepId} onChange={(e) => setStepId(e.target.value)}>
            <option value="">{t("comments.wholeFlow")}</option>
            {steps.map((s, i) => (
              <option key={s.id} value={s.id}>
                {i + 1}. {s.label}
              </option>
            ))}
          </NativeSelect>
          <Button size="sm" type="submit" disabled={!body.trim() || add.isPending}>
            {t("comments.send")}
          </Button>
        </div>
      </form>
      {open.length === 0 && !comments.isPending && <p className="text-sm text-muted-foreground">{t("comments.empty")}</p>}
      <ul className="grid gap-3">
        {open.map((c) => (
          <CommentItem key={c.id} comment={c} stepLabel={label(c.stepId)} onChanged={invalidate} />
        ))}
      </ul>
      {resolved.length > 0 && (
        <Button variant="link" size="sm" className="justify-self-start px-0" onClick={() => setShowResolved((v) => !v)}>
          {t("comments.showResolved", { count: resolved.length })}
        </Button>
      )}
      {showResolved && (
        <ul className="grid gap-3 opacity-70">
          {resolved.map((c) => (
            <CommentItem key={c.id} comment={c} stepLabel={label(c.stepId)} onChanged={invalidate} />
          ))}
        </ul>
      )}
    </aside>
  );
}

function CommentItem({ comment, stepLabel, onChanged }: { comment: Comment; stepLabel?: string; onChanged: () => void }) {
  const t = useT();
  const fmt = useFormat();
  const { data: me } = useSuspenseQuery(sessionQuery);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(comment.body);
  const mine = comment.userId === me.id;
  const run = (p: Promise<unknown>) => p.then(onChanged).catch((e: Error) => toast.error(e.message));

  return (
    <li className="grid gap-1 rounded-md border p-3 text-sm" data-testid="comment">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">{comment.authorName || comment.authorEmail || "—"}</span>
        <span>{fmt.dateTime(comment.createdAt)}</span>
        {comment.editedAt && <span>· {t("comments.edited")}</span>}
        {stepLabel && <Badge variant="outline">{stepLabel}</Badge>}
        {comment.resolvedAt && <Badge variant="secondary">{t("comments.resolved")}</Badge>}
      </div>
      {editing ? (
        <div className="grid gap-2">
          <Textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} aria-label={t("comments.edit")} />
          <div className="flex gap-2">
            <Button size="sm" onClick={() => run(api.editComment(comment.id, { body: text })).then(() => setEditing(false))}>
              {t("comments.save")}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
              {t("comments.cancel")}
            </Button>
          </div>
        </div>
      ) : (
        <p className="whitespace-pre-wrap">{comment.body}</p>
      )}
      <div className="flex gap-1">
        <Button size="sm" variant="ghost" onClick={() => run(api.editComment(comment.id, { resolved: !comment.resolvedAt }))}>
          {comment.resolvedAt ? <RotateCcw /> : <Check />} {comment.resolvedAt ? t("comments.reopen") : t("comments.resolve")}
        </Button>
        {mine && !editing && (
          <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
            <Pencil /> {t("comments.edit")}
          </Button>
        )}
        {mine && (
          <Button size="sm" variant="ghost" onClick={() => run(api.deleteComment(comment.id))}>
            <Trash2 /> {t("comments.delete")}
          </Button>
        )}
      </div>
    </li>
  );
}
