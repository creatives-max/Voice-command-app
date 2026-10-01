import { describe, expect, it } from "vitest";
import { mergeEvents, statusTone } from "./run-log";

const e = (id: number) => ({ id, at: "2026-10-01T00:00:00Z", kind: "status", message: `m${id}` });

describe("run log", () => {
  it("appends only unseen events", () => {
    expect(mergeEvents([e(1), e(2)], [e(2), e(3)]).map((x) => x.id)).toEqual([1, 2, 3]);
    const same = [e(1)];
    expect(mergeEvents(same, [e(1)])).toBe(same);
  });

  it("maps statuses to badge tones", () => {
    expect(statusTone("COMPLETED")).toBe("default");
    expect(statusTone("EXPIRED")).toBe("destructive");
    expect(statusTone("CANCELLED")).toBe("secondary");
    expect(statusTone("RUNNING")).toBe("outline");
  });
});
