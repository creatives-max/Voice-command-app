import { describe, expect, it } from "vitest";
import type { CareEvent } from "@/lib/types";
import { describeEvent, isCompleteCode, minutesLeft, normalizeCode } from "./care";

const event = (action: string, details: Record<string, string> = {}, actorEmail = "son@example.com"): CareEvent => ({
  id: 1, action, actorEmail, details, at: "2026-10-01T10:00:00Z",
});

describe("caregiving helpers", () => {
  it("normalizes invite codes as they are typed", () => {
    expect(normalizeCode("k7qm 29px")).toBe("K7QM-29PX");
    expect(normalizeCode("k7q")).toBe("K7Q");
    expect(normalizeCode("K7QM-29PX-EXTRA")).toBe("K7QM-29PX");
    expect(isCompleteCode("K7QM-29PX")).toBe(true);
    expect(isCompleteCode("K7QM-29")).toBe(false);
  });

  it("describes activity from either side", () => {
    expect(describeEvent(event("flow_edited", { flowName: "Pay bill" }))).toBe("son@example.com edited “Pay bill”");
    expect(describeEvent(event("flow_edited", { flowName: "Pay bill" }), "son@example.com")).toBe("You edited “Pay bill”");
    expect(describeEvent(event("permissions_changed", { permissions: "edit_flows,run_flows" }))).toBe(
      "son@example.com changed permissions to edit flows and when they run, run flows on the phone",
    );
    expect(describeEvent(event("invited", { permissions: "" }))).toBe("son@example.com created an invite (only seeing flows)");
    expect(describeEvent(event("something_new"))).toBe("son@example.com: something new");
  });

  it("counts down invites", () => {
    const now = new Date("2026-10-01T10:00:00Z");
    expect(minutesLeft("2026-10-01T10:29:10Z", now)).toBe(30);
    expect(minutesLeft("2026-10-01T09:00:00Z", now)).toBe(0);
    expect(minutesLeft(null, now)).toBe(0);
  });
});
