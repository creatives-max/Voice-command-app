import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { FlowStep, RepeatSpec } from "@/lib/types";
import { availableVariables, loopOf, producesValue, screenIndex, slug } from "./logic";

interface Props {
  step: FlowStep;
  steps: FlowStep[];
  index: number;
  update: (patch: Partial<FlowStep>) => void;
}

const fid = (step: FlowStep, name: string) => `${step.id}-${name}`;

/** Variables the step can use, shown as a hint under expression inputs. */
export function VariableHint({ steps, index }: { steps: FlowStep[]; index: number }) {
  const vars = availableVariables(steps, index).filter((v) => !v.startsWith("profile.") || v === "profile.name");
  return (
    <p className="text-xs text-muted-foreground">
      Variables: {vars.map((v) => <code key={v} className="mr-1 rounded bg-muted px-1">{v}</code>)}
      <span>and profile.*. Functions: yes(), empty(), contains(), concat(), upper(), digits(), if(), today()…</span>
    </p>
  );
}

/** Condition / else / variable / computed value for element steps. */
export function ElementLogicFields({ step, steps, index, update }: Props) {
  const hasLogic = !!(step.condition || step.elseValue || step.valueExpression || step.variable);
  const loop = loopOf(steps, step.id);
  const isToggle = step.action === "TOGGLE";
  return (
    <details className="rounded-md border p-3" open={hasLogic}>
      <summary className="cursor-pointer text-sm font-medium">
        Logic {loop && <Badge variant="secondary" className="ml-2">repeated in “{loop.label}”</Badge>}
      </summary>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <div className="grid gap-1 sm:col-span-2">
          <Label htmlFor={fid(step, "condition")}>Only run if</Label>
          <Input id={fid(step, "condition")} placeholder="e.g. yes(married) and age >= 18" value={step.condition ?? ""} onChange={(e) => update({ condition: e.target.value })} />
        </div>
        {step.action !== "READ" && step.action !== "CLICK" && (
          <div className="grid gap-1">
            <Label htmlFor={fid(step, "else")}>Otherwise fill</Label>
            <Input id={fid(step, "else")} placeholder={isToggle ? "e.g. false" : "e.g. 'N/A'"} value={step.elseValue ?? ""} onChange={(e) => update({ elseValue: e.target.value })} />
          </div>
        )}
        {producesValue(step) && (
          <div className="grid gap-1">
            <Label htmlFor={fid(step, "variable")}>Save answer as</Label>
            <Input id={fid(step, "variable")} placeholder={slug(step.label, step.order)} value={step.variable ?? ""} onChange={(e) => update({ variable: e.target.value })} />
          </div>
        )}
        {(step.action === "FILL" || step.action === "TOGGLE") && (
          <div className="grid gap-1 sm:col-span-2">
            <Label htmlFor={fid(step, "computed")}>Fill with a computed value (no question)</Label>
            <Input
              id={fid(step, "computed")}
              placeholder="e.g. concat(profile.first_name, ' ', last_name)"
              value={step.valueExpression ?? ""}
              onChange={(e) => update({ valueExpression: e.target.value })}
            />
          </div>
        )}
        <div className="sm:col-span-2">
          <VariableHint steps={steps} index={index} />
        </div>
      </div>
    </details>
  );
}

/** Settings of logic steps (set variable, repeat, next screen, open app). */
export function LogicStepFields({ step, steps, index, update }: Props) {
  switch (step.action) {
    case "SET_VARIABLE":
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="grid gap-1">
            <Label htmlFor={fid(step, "variable")}>Variable</Label>
            <Input id={fid(step, "variable")} placeholder="full_name" value={step.variable ?? ""} onChange={(e) => update({ variable: e.target.value })} />
          </div>
          <div className="grid gap-1">
            <Label htmlFor={fid(step, "value")}>Value</Label>
            <Input id={fid(step, "value")} placeholder="concat(first_name, ' ', last_name)" value={step.valueExpression ?? ""} onChange={(e) => update({ valueExpression: e.target.value })} />
          </div>
          <div className="grid gap-1 sm:col-span-2">
            <Label htmlFor={fid(step, "condition")}>Only if (optional)</Label>
            <Input id={fid(step, "condition")} value={step.condition ?? ""} onChange={(e) => update({ condition: e.target.value })} />
          </div>
          <div className="sm:col-span-2">
            <VariableHint steps={steps} index={index} />
          </div>
        </div>
      );
    case "REPEAT":
      return <RepeatFields step={step} steps={steps} index={index} update={update} />;
    case "NEXT_SCREEN":
    case "OPEN_APP":
      return (
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="grid gap-1 sm:col-span-2">
            <Label htmlFor={fid(step, "package")}>{step.action === "OPEN_APP" ? "App package to open" : "Expected app (optional)"}</Label>
            <Input id={fid(step, "package")} placeholder="com.example.app" value={step.appPackage ?? ""} onChange={(e) => update({ appPackage: e.target.value })} />
          </div>
          <div className="grid gap-1">
            <Label htmlFor={fid(step, "wait")}>Wait up to (seconds)</Label>
            <Input
              id={fid(step, "wait")}
              type="number"
              min={1}
              max={120}
              value={step.waitSeconds ?? ""}
              onChange={(e) => update({ waitSeconds: e.target.value === "" ? null : Number(e.target.value) })}
            />
          </div>
          <p className="text-xs text-muted-foreground sm:col-span-3">
            {step.action === "OPEN_APP"
              ? "The phone opens this app, waits for it, then continues with the steps below. Answers given so far stay available."
              : "The phone waits until the screen changes (e.g. after pressing Next), then continues with the steps below."}
          </p>
        </div>
      );
    default:
      return null;
  }
}

function RepeatFields({ step, steps, update }: Props) {
  const spec: RepeatSpec = step.repeat ?? { stepIds: [], maxIterations: 10 };
  const screens = screenIndex(steps);
  const candidates = steps.filter(
    (s) => s.id !== step.id && screens.get(s.id) === screens.get(step.id) && ["FILL", "TOGGLE", "READ", "CLICK", "SET_VARIABLE"].includes(s.action),
  );
  const buttons = steps.filter((s) => s.action === "CLICK" && screens.get(s.id) === screens.get(step.id));
  const set = (patch: Partial<RepeatSpec>) => update({ repeat: { ...spec, ...patch } });
  const toggle = (id: string, on: boolean) => set({ stepIds: on ? [...spec.stepIds, id] : spec.stepIds.filter((x) => x !== id) });
  return (
    <div className="grid gap-3">
      <fieldset className="grid gap-1">
        <legend className="mb-1 text-sm font-medium">Steps to repeat for each item</legend>
        {candidates.length === 0 && <p className="text-sm text-muted-foreground">No steps on this screen yet.</p>}
        {candidates.map((s) => (
          <label key={s.id} className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={spec.stepIds.includes(s.id)} onChange={(e) => toggle(s.id, e.target.checked)} />
            {s.label || s.action}
          </label>
        ))}
      </fieldset>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="grid gap-1">
          <Label htmlFor={fid(step, "item")}>Item name</Label>
          <Input id={fid(step, "item")} placeholder="item" value={spec.itemLabel ?? ""} onChange={(e) => set({ itemLabel: e.target.value })} />
        </div>
        <div className="grid gap-1">
          <Label htmlFor={fid(step, "max")}>At most</Label>
          <Input id={fid(step, "max")} type="number" min={1} max={50} value={spec.maxIterations} onChange={(e) => set({ maxIterations: Number(e.target.value) || 1 })} />
        </div>
        <div className="grid gap-1">
          <Label htmlFor={fid(step, "add")}>“Add another” button</Label>
          <Input
            id={fid(step, "add")}
            list={fid(step, "buttons")}
            placeholder="Add item"
            value={spec.addMoreLabel ?? ""}
            onChange={(e) => {
              const match = buttons.find((b) => b.label === e.target.value);
              set({ addMoreLabel: e.target.value, addMoreElementId: match?.elementId ?? null });
            }}
          />
          <datalist id={fid(step, "buttons")}>
            {buttons.map((b) => (
              <option key={b.id} value={b.label} />
            ))}
          </datalist>
        </div>
        <div className="grid gap-1">
          <Label htmlFor={fid(step, "count")}>Number of items (optional)</Label>
          <Input id={fid(step, "count")} placeholder="e.g. kids — otherwise asks “add another?”" value={spec.countExpression ?? ""} onChange={(e) => set({ countExpression: e.target.value })} />
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        Inside the loop, <code>index</code> is the item number, and each answer is also saved as <code>name_1</code>, <code>name_2</code>…
      </p>
    </div>
  );
}
