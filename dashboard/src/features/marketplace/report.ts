/** Reasons people can report a marketplace listing for (same as the backend's ReportReason). */
export const REPORT_REASONS = [
  { id: "broken", label: "It doesn't work", hint: "Asks for fields that aren't there, or presses the wrong buttons" },
  { id: "unsafe", label: "Unsafe or misleading", hint: "Sends money, signs up for things or asks for private details without saying so" },
  { id: "spam", label: "Spam or advertising", hint: "Not a real flow, or promotes something" },
  { id: "other", label: "Something else", hint: "Tell us more below" },
] as const;

export type ReportReasonId = (typeof REPORT_REASONS)[number]["id"];

/** Label for the reason the viewer already reported (the backend returns it in capitals). */
export function reportedLabel(myReport: string | null | undefined): string | null {
  if (!myReport) return null;
  return REPORT_REASONS.find((r) => r.id === myReport.toLowerCase())?.label ?? myReport.toLowerCase();
}

/** "other" needs a note so the report is actionable. */
export function canSubmitReport(reason: ReportReasonId | null, note: string): boolean {
  return reason !== null && (reason !== "other" || note.trim().length >= 5);
}
