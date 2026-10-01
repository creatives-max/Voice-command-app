import { describe, expect, it } from "vitest";
import { phraseError, phraseKey } from "./voice";

describe("voice shortcut phrases", () => {
  it("normalizes like the phone and the backend", () => {
    expect(phraseKey("  Pay, Electricity   BILL. ")).toBe("pay electricity bill");
    expect(phraseKey("बिजली का बिल।")).toBe("बिजली का बिल");
  });

  it("rejects short, long, reserved and duplicate phrases", () => {
    expect(phraseError("Pay electricity bill")).toBeNull();
    expect(phraseError("a")).toMatch(/at least/);
    expect(phraseError("x".repeat(61))).toMatch(/under 60/);
    expect(phraseError("Stop!")).toMatch(/already a VoiceControl command/);
    expect(phraseError("pay bill", ["Pay bill."])).toMatch(/already has/);
  });
});
