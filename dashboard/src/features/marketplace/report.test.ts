import { describe, expect, it } from "vitest";
import { canSubmitReport, reportedLabel } from "./report";

describe("listing reports", () => {
  it("labels the viewer's report and checks the form", () => {
    expect(reportedLabel("SPAM")).toBe("Spam or advertising");
    expect(reportedLabel(null)).toBeNull();
    expect(canSubmitReport(null, "")).toBe(false);
    expect(canSubmitReport("broken", "")).toBe(true);
    expect(canSubmitReport("other", "hmm")).toBe(false);
    expect(canSubmitReport("other", "Asks for my card number")).toBe(true);
  });
});
