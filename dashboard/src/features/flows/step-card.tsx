import { useState } from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { ArrowDown, ArrowUp, GripVertical, Lock, Plus, Video, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { profileKeys, sensitiveFieldTypes, type FlowStep } from "@/lib/types";
import { RULE_PRESETS, type EditorAction } from "./editor";

interface StepCardProps {
  step: FlowStep;
  index: number;
  count: number;
  errors: string[];
  dispatch: (action: EditorAction) => void;
}

const humanize = (s: string) => s.toLowerCase().replace(/_/g, " ");

export function StepCard({ step, index, count, errors, dispatch }: StepCardProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: step.id });
  const [customRule, setCustomRule] = useState("");
  const sensitive = !!step.fieldType && sensitiveFieldTypes.has(step.fieldType);
  const isButton = step.action === "CLICK";
  const update = (patch: Partial<FlowStep>) => dispatch({ type: "update", id: step.id, patch });
  const fieldId = (name: string) => `${step.id}-${name}`;

  return (
    <Card
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={`p-4 ${isDragging ? "z-10 shadow-lg ring-2 ring-primary" : ""} ${step.skip ? "opacity-70" : ""}`}
      data-testid={`step-${step.id}`}
    >
      <div className="flex items-start gap-3">
        <button
          type="button"
          className="mt-1 cursor-grab touch-none text-muted-foreground active:cursor-grabbing"
          aria-label={`Drag to reorder ${step.label}`}
          {...attributes}
          {...listeners}
        >
          <GripVertical className="size-5" />
        </button>
        <div className="grid min-w-0 flex-1 gap-4">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm text-muted-foreground">{index + 1}.</span>
            <span className="font-medium">{step.label}</span>
            <Badge variant="outline">{isButton ? "button" : humanize(step.fieldType ?? step.kind)}</Badge>
            {sensitive && (
              <Badge variant="warning">
                <Lock className="size-3" /> typed by user
              </Badge>
            )}
            {step.skip && <Badge variant="secondary">skipped</Badge>}
            <div className="ml-auto flex gap-1">
              <Button type="button" variant="ghost" size="icon" aria-label="Move up" disabled={index === 0} onClick={() => dispatch({ type: "move", from: index, to: index - 1 })}>
                <ArrowUp />
              </Button>
              <Button type="button" variant="ghost" size="icon" aria-label="Move down" disabled={index === count - 1} onClick={() => dispatch({ type: "move", from: index, to: index + 1 })}>
                <ArrowDown />
              </Button>
            </div>
          </div>

          <div className="grid gap-2">
            <Label htmlFor={fieldId("question")}>{isButton ? "Confirmation question" : "Question VoiceControl asks"}</Label>
            <Textarea
              id={fieldId("question")}
              rows={2}
              placeholder={isButton ? `Shall I press ${step.label}?` : `Please say ${step.label}.`}
              value={step.question ?? ""}
              onChange={(e) => update({ question: e.target.value })}
            />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            {!isButton && !sensitive && (
              <div className="grid gap-2">
                <Label htmlFor={fieldId("default")}>Default value</Label>
                <Input id={fieldId("default")} value={step.defaultValue ?? ""} placeholder="Offered as “say yes to use …”" onChange={(e) => update({ defaultValue: e.target.value })} />
              </div>
            )}
            {!isButton && !sensitive && (
              <div className="grid gap-2">
                <Label htmlFor={fieldId("profile")}>Fill from profile</Label>
                <NativeSelect
                  id={fieldId("profile")}
                  value={step.profileKey ?? ""}
                  onChange={(e) => update({ profileKey: (e.target.value || null) as FlowStep["profileKey"] })}
                >
                  <option value="">Auto-detect</option>
                  {profileKeys.map((k) => (
                    <option key={k} value={k}>
                      {humanize(k)}
                    </option>
                  ))}
                </NativeSelect>
              </div>
            )}
            <div className="grid gap-2 sm:col-span-2">
              <Label htmlFor={fieldId("video")} className="flex items-center gap-1">
                <Video className="size-4" /> Help video (https link)
              </Label>
              <Input id={fieldId("video")} value={step.helpVideoUrl ?? ""} placeholder="https://…" onChange={(e) => update({ helpVideoUrl: e.target.value })} />
            </div>
          </div>

          {!isButton && (
            <div className="grid gap-2">
              <Label>Validation rules</Label>
              <div className="flex flex-wrap items-center gap-2">
                {step.rules.map((rule, i) => (
                  <Badge key={rule} variant="secondary" className="gap-1">
                    {rule}
                    <button type="button" aria-label={`Remove rule ${rule}`} onClick={() => dispatch({ type: "removeRule", id: step.id, index: i })}>
                      <X className="size-3" />
                    </button>
                  </Badge>
                ))}
                <NativeSelect
                  aria-label="Add a preset rule"
                  className="h-8 w-auto"
                  value=""
                  onChange={(e) => e.target.value && dispatch({ type: "addRule", id: step.id, rule: e.target.value })}
                >
                  <option value="">+ preset</option>
                  {RULE_PRESETS.map((p) => (
                    <option key={p.rule} value={p.rule}>
                      {p.label}
                    </option>
                  ))}
                </NativeSelect>
                <form
                  className="flex gap-1"
                  onSubmit={(e) => {
                    e.preventDefault();
                    dispatch({ type: "addRule", id: step.id, rule: customRule });
                    setCustomRule("");
                  }}
                >
                  <Input aria-label="Custom rule" className="h-8 w-44" placeholder="regex:^[A-Z]{5}\d{4}[A-Z]$" value={customRule} onChange={(e) => setCustomRule(e.target.value)} />
                  <Button type="submit" variant="outline" size="sm" aria-label="Add rule">
                    <Plus />
                  </Button>
                </form>
              </div>
            </div>
          )}

          <div className="flex items-center gap-2">
            <Switch id={fieldId("skip")} checked={step.skip} onCheckedChange={(checked) => update({ skip: checked })} />
            <Label htmlFor={fieldId("skip")} className="font-normal">
              {isButton ? "Press automatically without asking" : "Skip this step (fill the default silently if set)"}
            </Label>
          </div>

          {errors.length > 0 && (
            <ul className="list-inside list-disc text-sm text-destructive" role="alert">
              {errors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </Card>
  );
}
