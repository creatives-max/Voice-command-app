import { memo, useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type ReactNode } from "react";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type OnNodeDrag,
} from "@xyflow/react";
import { DndContext } from "@dnd-kit/core";
import { SortableContext } from "@dnd-kit/sortable";
import { useQuery } from "@tanstack/react-query";
import { AlertCircle, AppWindow, ArrowRightToLine, LayoutGrid, MessageSquare, Repeat, Variable, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { isDark, prefersDark, useTheme } from "@/lib/theme";
import type { FlowStep } from "@/lib/types";
import type { EditorAction, LogicAction } from "@/features/flows/editor";
import { ACTION_LABELS } from "@/features/flows/logic";
import { StepCard } from "@/features/flows/step-card";
import { END, NODE_H, NODE_W, START, edgesFor, insertAfterIndex, moveAfter, nearestNode, positionsFor, autoLayout, type Point } from "./canvas";

interface StepNodeData extends Record<string, unknown> {
  step?: FlowStep;
  index?: number;
  terminal?: "start" | "end";
  label: string;
  hasErrors?: boolean;
  comments?: number;
  selected?: boolean;
}

const PALETTE: { action: LogicAction; icon: typeof Variable }[] = [
  { action: "SET_VARIABLE", icon: Variable },
  { action: "REPEAT", icon: Repeat },
  { action: "NEXT_SCREEN", icon: ArrowRightToLine },
  { action: "OPEN_APP", icon: AppWindow },
];

const StepNode = memo(function StepNode({ data }: NodeProps<Node<StepNodeData>>) {
  const t = useT();
  if (data.terminal) {
    return (
      <div className="grid h-10 w-[240px] place-items-center rounded-full border bg-secondary text-sm font-medium">
        {data.label}
        {data.terminal === "start" ? <Handle type="source" position={Position.Bottom} /> : <Handle type="target" position={Position.Top} />}
      </div>
    );
  }
  const step = data.step!;
  const logic = !["FILL", "CLICK", "TOGGLE", "READ"].includes(step.action);
  return (
    <div
      className={`grid h-[76px] w-[240px] content-center gap-1 rounded-lg border bg-card px-3 text-card-foreground shadow-sm ${data.selected ? "ring-2 ring-ring" : ""} ${logic ? "border-dashed" : ""} ${step.skip ? "opacity-60" : ""}`}
      data-testid={`node-${step.id}`}
    >
      <Handle type="target" position={Position.Top} />
      <div className="flex items-center gap-1.5 text-sm">
        <span className="text-xs text-muted-foreground">{(data.index ?? 0) + 1}.</span>
        <span className="truncate font-medium">{step.label || ACTION_LABELS[step.action]}</span>
        {data.hasErrors && <AlertCircle className="ml-auto size-4 shrink-0 text-destructive" aria-label="Has problems" />}
      </div>
      <div className="flex items-center gap-1 overflow-hidden">
        <Badge variant={logic ? "warning" : "secondary"} className="shrink-0 text-[10px]">
          {ACTION_LABELS[step.action]}
        </Badge>
        {step.condition && <span className="truncate text-[11px] text-muted-foreground">{t("builder.if", { condition: step.condition })}</span>}
        {!!data.comments && (
          <span className="ml-auto flex shrink-0 items-center gap-0.5 text-[11px] text-muted-foreground">
            <MessageSquare className="size-3" /> {data.comments}
          </span>
        )}
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
});

const nodeTypes = { step: StepNode };

interface CanvasProps {
  flowId: string;
  steps: FlowStep[];
  errors: Record<string, string[]>;
  editable: boolean;
  commentCounts: Record<string, number>;
  dispatch: (action: EditorAction) => void;
  newId: () => string;
  onCommentStep: (stepId: string) => void;
  /** Shown in the side column instead of the step editor (e.g. the comments panel). */
  aside?: ReactNode;
}

/** Visual builder: steps as nodes on a canvas (one column per screen), drag to arrange or reorder. */
export function FlowCanvas(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  );
}

function CanvasInner({ flowId, steps, errors, editable, commentCounts, dispatch, newId, onCommentStep, aside }: CanvasProps) {
  const t = useT();
  const theme = useTheme();
  const flow = useReactFlow();
  const saved = useQuery({ queryKey: ["flow", flowId, "layout"], queryFn: () => api.layout(flowId) });
  const [positions, setPositions] = useState<Record<string, Point> | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const saveTimer = useRef<number | undefined>(undefined);

  // Merge saved positions with defaults whenever steps change (new steps get a default spot).
  useEffect(() => {
    if (saved.isPending) return;
    setPositions((cur) => positionsFor(steps, cur ?? saved.data?.positions));
  }, [steps, saved.isPending, saved.data]);

  const persist = useCallback(
    (next: Record<string, Point>) => {
      if (!editable) return;
      window.clearTimeout(saveTimer.current);
      saveTimer.current = window.setTimeout(() => void api.saveLayout(flowId, next).catch(() => undefined), 800);
    },
    [editable, flowId],
  );

  const nodes: Node<StepNodeData>[] = useMemo(() => {
    if (!positions) return [];
    return [
      { id: START, type: "step", position: positions[START]!, data: { terminal: "start", label: t("builder.start") }, draggable: false, selectable: false },
      ...steps.map((s, i) => ({
        id: s.id,
        type: "step",
        position: positions[s.id]!,
        draggable: editable,
        data: { step: s, index: i, label: s.label, hasErrors: (errors[s.id]?.length ?? 0) > 0, comments: commentCounts[s.id] ?? 0, selected: selected === s.id },
      })),
      { id: END, type: "step", position: positions[END]!, data: { terminal: "end", label: t("builder.end") }, draggable: editable, selectable: false },
    ];
  }, [positions, steps, errors, commentCounts, selected, editable, t]);

  const edges: Edge[] = useMemo(
    () =>
      edgesFor(steps).map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        type: e.kind === "loop" ? "default" : "smoothstep",
        animated: e.kind === "loop",
        label: e.kind === "loop" ? t("builder.loop") : undefined,
        markerEnd: { type: MarkerType.ArrowClosed },
        style: e.kind === "next" ? undefined : { strokeDasharray: "6 4" },
      })),
    [steps, t],
  );

  const onNodeDragStop: OnNodeDrag<Node<StepNodeData>> = (_, node) => {
    if (!positions) return;
    const center = { x: node.position.x + NODE_W / 2, y: node.position.y + NODE_H / 2 };
    // Dropped onto another step: place it right after that step.
    const target = nearestNode(positions, center, node.id, 70);
    const move = target && node.id !== END ? moveAfter(steps, node.id, target) : null;
    const next = { ...positions, [node.id]: node.position };
    if (move && target) next[node.id] = { x: positions[target]!.x + 30, y: positions[target]!.y + NODE_H + 12 };
    setPositions(next);
    persist(next);
    if (move) dispatch({ type: "move", ...move });
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    const action = e.dataTransfer.getData("application/vc-step") as LogicAction;
    if (!action || !positions) return;
    const point = flow.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    const id = newId();
    const after = insertAfterIndex(steps, positions, point);
    const next = { ...positions, [id]: { x: point.x - NODE_W / 2, y: point.y - NODE_H / 2 } };
    setPositions(next);
    persist(next);
    dispatch({ type: "addLogic", action, id, afterIndex: after });
    setSelected(id);
  };

  const tidy = () => {
    const next = autoLayout(steps);
    setPositions(next);
    persist(next);
    window.setTimeout(() => void flow.fitView({ padding: 0.2 }), 0);
  };

  const selectedStep = steps.find((s) => s.id === selected);
  const dark = isDark(theme, prefersDark());

  return (
    <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_380px]">
      <div className="grid gap-2">
        {editable && (
          <div className="flex flex-wrap items-center gap-2" aria-label={t("builder.palette")}>
            <span className="text-xs text-muted-foreground">{t("builder.palette")}:</span>
            {PALETTE.map((p) => (
              <span
                key={p.action}
                draggable
                onDragStart={(e) => {
                  e.dataTransfer.setData("application/vc-step", p.action);
                  e.dataTransfer.effectAllowed = "copy";
                }}
                className="flex cursor-grab items-center gap-1 rounded-md border border-dashed bg-card px-2 py-1 text-xs active:cursor-grabbing"
                data-testid={`palette-${p.action}`}
              >
                <p.icon className="size-3.5" /> {ACTION_LABELS[p.action]}
              </span>
            ))}
            <Button variant="ghost" size="sm" className="ml-auto" onClick={tidy}>
              <LayoutGrid /> {t("builder.autoLayout")}
            </Button>
          </div>
        )}
        <div className="h-[70vh] min-h-[420px] rounded-lg border bg-background" onDragOver={(e) => e.preventDefault()} onDrop={onDrop} data-testid="flow-canvas">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            colorMode={dark ? "dark" : "light"}
            onNodesChange={(changes) => {
              // Live positions while dragging; persisted on drag stop.
              setPositions((cur) => {
                if (!cur) return cur;
                let next = cur;
                for (const c of changes) if (c.type === "position" && c.position) next = { ...next, [c.id]: c.position };
                return next;
              });
            }}
            onNodeDragStop={onNodeDragStop}
            onNodeClick={(_, n) => n.id !== START && n.id !== END && setSelected(n.id)}
            onPaneClick={() => setSelected(null)}
            nodesConnectable={false}
            deleteKeyCode={null}
            fitView
            fitViewOptions={{ padding: 0.2 }}
            minZoom={0.2}
          >
            <Background gap={20} />
            <Controls showInteractive={false} />
            <MiniMap pannable zoomable className="!bg-card" />
          </ReactFlow>
        </div>
        <p className="text-xs text-muted-foreground">{t("builder.hint")}</p>
      </div>
      <div className="lg:sticky lg:top-4 lg:self-start">
        {aside ? (
          aside
        ) : selectedStep ? (
          <div className="grid gap-2">
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={() => onCommentStep(selectedStep.id)}>
                <MessageSquare /> {t("editor.comments")}
              </Button>
              <Button variant="ghost" size="icon" className="ml-auto" aria-label={t("builder.close")} onClick={() => setSelected(null)}>
                <X />
              </Button>
            </div>
            <DndContext>
              <SortableContext items={[selectedStep.id]} disabled>
                <fieldset disabled={!editable} className="min-w-0">
                  <StepCard
                    step={selectedStep}
                    steps={steps}
                    index={steps.indexOf(selectedStep)}
                    count={steps.length}
                    errors={errors[selectedStep.id] ?? []}
                    dispatch={(a) => {
                      if (a.type === "remove") setSelected(null);
                      dispatch(a);
                    }}
                  />
                </fieldset>
              </SortableContext>
            </DndContext>
          </div>
        ) : (
          <p className="rounded-lg border border-dashed p-6 text-sm text-muted-foreground">{t("builder.noSelection")}</p>
        )}
      </div>
    </div>
  );
}
