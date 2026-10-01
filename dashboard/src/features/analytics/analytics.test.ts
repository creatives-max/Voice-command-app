import { countChange, csvCell, flowCsv, formatChange, formatDuration, overviewCsv, rateChange, stepsByTrouble, troubleRate } from "./analytics";

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

describe("period comparison", () => {
  it("compares counts relative to the previous period", () => {
    expect(countChange(12, 10)).toEqual({ direction: "up", amount: 0.2 });
    expect(countChange(5, 10)).toEqual({ direction: "down", amount: -0.5 });
    expect(countChange(3, 3)).toEqual({ direction: "same", amount: 0 });
    expect(countChange(4, 0)).toEqual({ direction: "new", amount: 0 });
    expect(countChange(0, 0)).toBeNull();
    expect(countChange(4, undefined)).toBeNull();
    expect(formatChange(countChange(12, 10)!)).toBe("+20%");
    expect(formatChange(countChange(5, 10)!)).toBe("−50%");
  });

  it("compares rates in percentage points", () => {
    expect(rateChange(0.8, 0.75)).toEqual({ direction: "up", amount: 5 });
    expect(formatChange(rateChange(0.6, 0.75)!, true)).toBe("−15 pts");
    expect(rateChange(null, 0.5)).toBeNull();
  });
});

describe("CSV export", () => {
  it("quotes cells and never starts one like a formula", () => {
    expect(csvCell('Bill "pay", now')).toBe('"Bill ""pay"", now"');
    expect(csvCell("=HYPERLINK(1)")).toBe("'=HYPERLINK(1)");
    expect(csvCell(-3)).toBe("-3");
    expect(csvCell(null)).toBe("");
  });

  it("writes the overview and a flow's days and steps", () => {
    const usage = { runs: 4, completed: 3, stopped: 1, failed: 0, users: 2, successRate: 0.75, lastRunAt: "2026-09-30T10:00:00Z" };
    const csv = overviewCsv({ flows: [{ flowId: "f", name: "Pay bill", appPackage: "com.bank", usage }], daily: [], totals: usage });
    expect(csv.split("\r\n")).toEqual([
      "flow,app,runs,completed,stopped,failed,success_rate,people,last_run",
      "Pay bill,com.bank,4,3,1,0,0.750,2,2026-09-30T10:00:00Z",
      "",
    ]);
    const flow = flowCsv({
      usage,
      daily: [{ date: "2026-09-30", completed: 3, stopped: 1, failed: 0 }],
      steps: [{ elementId: "vid:pin", label: "PIN code", total: 4, outcomes: { FILLED: 3, MANUAL: 1 } }],
      interpretedBy: {},
      remoteRuns: {},
    });
    const lines = flow.split("\r\n");
    expect(lines[1]).toBe("2026-09-30,3,1,0");
    expect(lines[3]).toBe("step,element,total,filled,default_filled,kept,clicked,toggled,skipped,manual,failed");
    expect(lines[4]).toBe("PIN code,vid:pin,4,3,0,0,0,0,0,1,0");
  });
});
