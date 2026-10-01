import type { FlowStep } from "@/lib/types";
import { END, START, autoLayout, edgesFor, insertAfterIndex, moveAfter, positionsFor, screensOf } from "./canvas";

const step = (id: string, action: FlowStep["action"] = "FILL", extra: Partial<FlowStep> = {}): FlowStep => ({
  id,
  order: 0,
  elementId: `vid:${id}`,
  label: id,
  kind: action === "CLICK" ? "BUTTON" : "TEXT_FIELD",
  action,
  rules: [],
  skip: false,
  ...extra,
});

const steps = [step("a"), step("b"), step("loop", "REPEAT", { repeat: { stepIds: ["b"], maxIterations: 5 } }), step("next", "NEXT_SCREEN"), step("c")];

describe("canvas", () => {
  it("splits screens at boundaries and lays out one column per screen", () => {
    expect(screensOf(steps)).toEqual([0, 0, 0, 1, 1]);
    const pos = autoLayout(steps);
    expect(pos[START]).toEqual({ x: 0, y: 0 });
    expect(pos.a).toEqual({ x: 0, y: 110 });
    expect(pos.next).toEqual({ x: 320, y: 110 });
    expect(pos.c).toEqual({ x: 320, y: 220 });
    expect(pos[END]).toEqual({ x: 320, y: 330 });
  });

  it("keeps saved positions and lays out new steps", () => {
    const pos = positionsFor(steps, { a: { x: 5, y: 6 } });
    expect(pos.a).toEqual({ x: 5, y: 6 });
    expect(pos.b).toEqual(autoLayout(steps).b);
  });

  it("draws order, screen-change and loop edges", () => {
    const e = edgesFor(steps);
    expect(e.filter((x) => x.kind !== "loop").map((x) => `${x.source}>${x.target}`)).toEqual([
      `${START}>a`, "a>b", "b>loop", "loop>next", "next>c", `c>${END}`,
    ]);
    expect(e.find((x) => x.target === "next")?.kind).toBe("screen");
    expect(e.filter((x) => x.kind === "loop").map((x) => x.target)).toEqual(["b"]);
  });

  it("moves a dropped step right after the target", () => {
    expect(moveAfter(steps, "c", "a")).toEqual({ from: 4, to: 1 });
    expect(moveAfter(steps, "a", "b")).toEqual({ from: 0, to: 1 });
    expect(moveAfter(steps, "b", "a")).toBeNull();
    expect(moveAfter(steps, "c", START)).toEqual({ from: 4, to: 0 });
    expect(moveAfter(steps, "a", END)).toEqual({ from: 0, to: 4 });
  });

  it("inserts palette drops after the nearest node", () => {
    const pos = autoLayout(steps);
    expect(insertAfterIndex(steps, pos, { x: 120, y: 150 })).toBe(0);
    expect(insertAfterIndex(steps, pos, { x: 120, y: 10 })).toBe(-1);
    expect(insertAfterIndex(steps, pos, { x: 440, y: 370 })).toBe(4);
  });
});
