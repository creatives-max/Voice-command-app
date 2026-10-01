import { useEffect, useMemo, useReducer, useState } from "react";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { ArrowRightLeft, BarChart3, CalendarClock, Eye, FlaskConical, History, LayoutList, Layers, MessageSquare, Save, Share2, Trash2, Undo2, Workflow } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError } from "@/lib/api";
import { flowQuery, flowsQuery, useDeleteFlow, useUpdateFlow } from "@/lib/queries";
import type { Flow } from "@/lib/types";
import { editorReducer, initialEditor, toUpdatePayload, validateSteps, type LogicAction } from "./editor";
import { ACTION_LABELS } from "./logic";
import { PublishDialog } from "@/features/marketplace/publish-dialog";
import { SourceBanner } from "@/features/marketplace/listing-page";
import { SimulatorPanel } from "./simulator-panel";
import { StepCard } from "./step-card";
import { MoveFlowDialog, moveTargets } from "./move-dialog";
import { useCurrentOrg } from "@/features/org/use-org";
import { can } from "@/lib/org";
import { useT } from "@/lib/i18n";
import { FlowCanvas } from "@/features/builder/flow-canvas";
import { CommentsPanel, useComments } from "@/features/collab/comments-panel";
import { PresenceBar } from "@/features/collab/presence-bar";
import { usePresence } from "@/features/collab/use-presence";

const LOGIC_ACTIONS: LogicAction[] = ["SET_VARIABLE", "REPEAT", "NEXT_SCREEN", "OPEN_APP"];
const newId = () => `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;

export function FlowEditorPage() {
  const { flowId } = useParams({ from: "/app/flows/$flowId" });
  const { data: flow, isPending, error } = useQuery(flowQuery(flowId));
  if (isPending) return <Skeleton className="mx-auto h-96 max-w-4xl" />;
  if (error || !flow) return <p className="text-destructive">Could not load this flow{error ? `: ${error.message}` : ""}.</p>;
  return <FlowEditor key={`${flow.id}-${flow.version}`} flow={flow} />;
}

function FlowEditor({ flow }: { flow: Flow }) {
  const [state, dispatch] = useReducer(editorReducer, flow, initialEditor);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [testing, setTesting] = useState(false);
  const [appendOpen, setAppendOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);
  const [view, setView] = useState<"list" | "canvas">(() => {
    try {
      return window.localStorage.getItem("vc.editorView") === "canvas" ? "canvas" : "list";
    } catch {
      return "list";
    }
  });
  const [commentsOpen, setCommentsOpen] = useState(false);
  const [commentStep, setCommentStep] = useState<string | null>(null);
  const t = useT();
  const qc = useQueryClient();
  const presence = usePresence(flow.id, state.dirty);
  const comments = useComments(flow.id);
  const commentCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const c of comments.data ?? []) if (c.stepId && !c.resolvedAt) counts[c.stepId] = (counts[c.stepId] ?? 0) + 1;
    return counts;
  }, [comments.data]);
  const openComments = (stepId: string | null) => {
    setCommentStep(stepId);
    setCommentsOpen(true);
  };
  const chooseView = (v: "list" | "canvas") => {
    setView(v);
    try {
      window.localStorage.setItem("vc.editorView", v);
    } catch {
      // ignore
    }
  };
  const { orgs } = useCurrentOrg();
  const role = flow.orgId ? (orgs.find((o) => o.id === flow.orgId)?.role ?? "VIEWER") : null;
  const editable = can.edit(role);
  const canMove = moveTargets(flow, orgs).length > 0;
  const update = useUpdateFlow(flow.id);
  const remove = useDeleteFlow();
  const navigate = useNavigate();
  const errors = useMemo(() => validateSteps(state.steps), [state.steps]);
  const hasErrors = Object.keys(errors).length > 0;
  const sensors = useSensors(useSensor(PointerSensor), useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }));

  useEffect(() => {
    if (!state.dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [state.dirty]);

  function onDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const from = state.steps.findIndex((s) => s.id === active.id);
    const to = state.steps.findIndex((s) => s.id === over.id);
    dispatch({ type: "move", from, to });
  }

  async function save() {
    try {
      const saved = await update.mutateAsync(toUpdatePayload(state));
      toast.success(t("editor.saved", { version: saved.version }));
    } catch (e) {
      if (e instanceof ApiError && e.isConflict) toast.error("Someone else changed this flow. Reload to see the latest version.");
      else toast.error(e instanceof Error ? e.message : "Save failed");
    }
  }

  async function deleteFlow() {
    await remove.mutateAsync(flow.id);
    toast.success("Flow deleted");
    await navigate({ to: "/" });
  }

  return (
    <div className={`mx-auto pb-28 ${testing || view === "canvas" || commentsOpen ? "max-w-7xl" : "max-w-4xl"}`}>
      <div className="mb-6 flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <Link to="/" className="text-sm text-muted-foreground hover:underline">
            {t("editor.allFlows")}
          </Link>
          <div className="ml-auto">
            <PresenceBar info={presence} loadedVersion={flow.version} onReload={() => void qc.invalidateQueries({ queryKey: ["flow", flow.id] })} />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Input
            aria-label="Flow name"
            className="h-10 max-w-md text-lg font-semibold"
            readOnly={!editable}
            value={state.name}
            onChange={(e) => dispatch({ type: "rename", name: e.target.value })}
          />
          <Badge variant="secondary">v{flow.version}</Badge>
          <span className="text-sm text-muted-foreground">{flow.appPackage}</span>
          <div className="ml-auto flex gap-2">
            <Button variant="outline" asChild>
              <Link to="/flows/$flowId/versions" params={{ flowId: flow.id }}>
                <History /> {t("editor.history")}
              </Link>
            </Button>
            <Button variant="outline" asChild>
              <Link to="/flows/$flowId/automation" params={{ flowId: flow.id }}>
                <CalendarClock /> {t("editor.triggers")}
              </Link>
            </Button>
            <Button variant="outline" asChild>
              <Link to="/flows/$flowId/analytics" params={{ flowId: flow.id }}>
                <BarChart3 /> {t("editor.analytics")}
              </Link>
            </Button>
            {can.manage(role) && (
              <Button variant="outline" onClick={() => setPublishOpen(true)}>
                <Share2 /> {t("editor.publish")}
              </Button>
            )}
            {canMove && (
              <Button variant="outline" onClick={() => setMoveOpen(true)}>
                <ArrowRightLeft /> {t("editor.move")}
              </Button>
            )}
            <Button variant={testing ? "default" : "outline"} onClick={() => setTesting((t) => !t)} aria-pressed={testing}>
              <FlaskConical /> {t("editor.test")}
            </Button>
            {editable && (
              <Button variant="outline" onClick={() => setConfirmDelete(true)}>
                <Trash2 /> {t("editor.delete")}
              </Button>
            )}
          </div>
        </div>
        <p className={editable ? "text-sm text-muted-foreground" : "hidden"}>
          Drag steps to change the order VoiceControl asks them. Add conditions, computed values, loops for lists, and further screens or
          apps. Password, OTP and PIN fields are always typed by the user.
        </p>
        {editable && <SourceBanner flow={flow} />}
        {!editable && (
          <p className="flex items-center gap-2 rounded-md border bg-secondary/40 p-3 text-sm" data-testid="read-only-banner">
            <Eye className="size-4" /> {t("editor.viewer")}
          </p>
        )}
        <div className={editable ? "flex flex-wrap gap-2" : "hidden"}>
          <NativeSelect
            aria-label="Add a logic step"
            className="h-9 w-auto"
            value=""
            onChange={(e) => e.target.value && dispatch({ type: "addLogic", action: e.target.value as LogicAction, id: newId() })}
          >
            <option value="">+ Add step…</option>
            {LOGIC_ACTIONS.map((a) => (
              <option key={a} value={a}>
                {ACTION_LABELS[a]}
              </option>
            ))}
          </NativeSelect>
          <Button variant="outline" size="sm" className="h-9" onClick={() => setAppendOpen(true)}>
            <Layers /> Add a screen from another flow
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex gap-1 rounded-md bg-secondary p-1" role="radiogroup" aria-label="View">
            <button
              role="radio"
              aria-checked={view === "list"}
              onClick={() => chooseView("list")}
              className={`flex items-center gap-1 rounded px-3 py-1 text-sm ${view === "list" ? "bg-background font-medium shadow-sm" : "text-muted-foreground"}`}
            >
              <LayoutList className="size-4" /> {t("editor.list")}
            </button>
            <button
              role="radio"
              aria-checked={view === "canvas"}
              onClick={() => chooseView("canvas")}
              className={`flex items-center gap-1 rounded px-3 py-1 text-sm ${view === "canvas" ? "bg-background font-medium shadow-sm" : "text-muted-foreground"}`}
            >
              <Workflow className="size-4" /> {t("editor.canvas")}
            </button>
          </div>
          <Button variant={commentsOpen ? "default" : "outline"} size="sm" className="h-9" aria-pressed={commentsOpen} onClick={() => (commentsOpen ? setCommentsOpen(false) : openComments(null))}>
            <MessageSquare /> {t("editor.comments")}
            {(comments.data?.filter((c) => !c.resolvedAt).length ?? 0) > 0 && (
              <Badge variant="secondary" className="ml-1">
                {comments.data!.filter((c) => !c.resolvedAt).length}
              </Badge>
            )}
          </Button>
        </div>
      </div>

      <div className={testing || (commentsOpen && view === "list") ? "grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_400px]" : ""}>
        {view === "canvas" ? (
          <FlowCanvas
            flowId={flow.id}
            steps={state.steps}
            errors={errors}
            editable={editable}
            commentCounts={commentCounts}
            dispatch={dispatch}
            newId={newId}
            onCommentStep={(id) => openComments(id)}
            aside={
              commentsOpen ? (
                <CommentsPanel key={commentStep ?? "all"} flowId={flow.id} steps={state.steps} focusStepId={commentStep} onClose={() => setCommentsOpen(false)} />
              ) : undefined
            }
          />
        ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={state.steps.map((s) => s.id)} strategy={verticalListSortingStrategy} disabled={!editable}>
            <fieldset disabled={!editable} className="grid min-w-0 gap-3" aria-label="Steps">
              {state.steps.map((step, index) => (
                <StepCard key={step.id} step={step} steps={state.steps} index={index} count={state.steps.length} errors={errors[step.id] ?? []} dispatch={dispatch} />
              ))}
            </fieldset>
          </SortableContext>
        </DndContext>
        )}
        {commentsOpen && view === "list" && (
          <div className="lg:sticky lg:top-4">
            <CommentsPanel key={commentStep ?? "all"} flowId={flow.id} steps={state.steps} focusStepId={commentStep} onClose={() => setCommentsOpen(false)} />
          </div>
        )}
        {testing && !(commentsOpen && view === "list") && (
          <div className="lg:sticky lg:top-4">
            <SimulatorPanel steps={state.steps} onClose={() => setTesting(false)} />
          </div>
        )}
      </div>

      <PublishDialog flow={flow} open={publishOpen} onOpenChange={setPublishOpen} dirty={state.dirty} />
      {moveOpen && <MoveFlowDialog flow={flow} orgs={orgs} open={moveOpen} onOpenChange={setMoveOpen} />}

      <AppendFlowDialog
        open={appendOpen}
        onOpenChange={setAppendOpen}
        current={flow}
        onAppend={(source, boundary) => {
          dispatch({ type: "appendFlow", flow: source, boundary, idPrefix: `${newId()}-`, sameApp: source.appPackage === flow.appPackage });
          setAppendOpen(false);
          toast.success(`Added the steps of “${source.name}”. Save to use them on the phone.`);
        }}
      />

      <div className={`fixed inset-x-0 bottom-0 border-t bg-background/95 p-3 backdrop-blur md:left-60 ${editable ? "" : "hidden"}`}>
        <div className="mx-auto flex max-w-4xl flex-col gap-2 sm:flex-row sm:items-center">
          <Label htmlFor="note" className="sr-only">
            Change note
          </Label>
          <Input id="note" placeholder={t("editor.note")} value={state.changeNote} onChange={(e) => dispatch({ type: "note", text: e.target.value })} />
          <div className="flex gap-2">
            <Button variant="ghost" disabled={!state.dirty} onClick={() => dispatch({ type: "reset", flow })}>
              <Undo2 /> {t("editor.discard")}
            </Button>
            <Button disabled={!state.dirty || hasErrors || update.isPending || !state.name.trim()} onClick={save}>
              <Save /> {update.isPending ? t("editor.saving") : t("editor.save")}
            </Button>
          </div>
        </div>
      </div>

      <Dialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete “{flow.name}”?</DialogTitle>
            <DialogDescription>The phone will stop using this flow and will ask default questions on this screen again.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmDelete(false)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={deleteFlow} disabled={remove.isPending}>
              Delete flow
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** Picks another flow whose steps continue this one on the next screen or in another app. */
function AppendFlowDialog({
  open,
  onOpenChange,
  current,
  onAppend,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  current: Flow;
  onAppend: (flow: Flow, boundary: "NEXT_SCREEN" | "OPEN_APP") => void;
}) {
  const flows = useQuery({ ...flowsQuery(), enabled: open });
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState("");
  const [loading, setLoading] = useState(false);
  const options = (flows.data?.items ?? []).filter((f) => f.id !== current.id);
  const chosen = options.find((f) => f.id === selected);
  const boundary = chosen && chosen.appPackage !== current.appPackage ? "OPEN_APP" : "NEXT_SCREEN";

  async function add() {
    if (!chosen) return;
    setLoading(true);
    try {
      onAppend(await queryClient.fetchQuery(flowQuery(chosen.id)), boundary);
      setSelected("");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not load that flow");
    } finally {
      setLoading(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Continue on another screen</DialogTitle>
          <DialogDescription>
            Copies the steps of a recorded flow to the end of this one. The phone waits for that screen (or opens that app) and keeps the
            answers given so far.
          </DialogDescription>
        </DialogHeader>
        <Label htmlFor="append-flow">Flow</Label>
        <NativeSelect id="append-flow" value={selected} onChange={(e) => setSelected(e.target.value)}>
          <option value="">{flows.isPending ? "Loading…" : "Choose a flow"}</option>
          {options.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name} — {f.appPackage}
            </option>
          ))}
        </NativeSelect>
        {chosen && (
          <p className="text-sm text-muted-foreground">
            {boundary === "OPEN_APP" ? `Adds an “Open app ${chosen.appPackage}” step first.` : "Adds a “Next screen” step first."}
          </p>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={add} disabled={!chosen || loading}>
            Add steps
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
