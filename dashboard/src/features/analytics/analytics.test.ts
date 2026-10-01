import { formatDuration, stepsByTrouble, troubleRate } from "./analytics";

describe("analytics helpers", () => {
  it("formats durations", () => {
    expect(formatDuration(null)).toBe("–");
    expect(formatDuration(45_000)).toBe("45 s");
    expect(formatDuration(80_000)).toBe("1 min 20 s");
    expect(formatDuration(120_000)).toBe("2 min");
    expect(formatDuration(7_500_000)).toBe("2 h 5 min");
  });

  it("ranks steps by how often voice didn't work", () => {
    const ok = { elementId: "a", label: "Name", total: 10, outcomes: { FILLED: 10 } };
    const bad = { elementId: "b", label: "PIN code", total: 4, outcomes: { FILLED: 1, MANUAL: 2, SKIPPED: 1 } };
    const some = { elementId: "c", label: "City", total: 20, outcomes: { FILLED: 15, MANUAL: 5 } };
    expect(troubleRate(bad)).toBe(0.75);
    expect(stepsByTrouble([ok, some, bad]).map((s) => s.elementId)).toEqual(["b", "c", "a"]);
  });
});
