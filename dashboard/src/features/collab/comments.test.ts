import { partitionComments } from "./comments-panel";
import { initials } from "./use-presence";
import type { Comment } from "@/lib/types";

const c = (id: string, stepId: string | null, resolved = false): Comment => ({
  id,
  flowId: "f",
  body: id,
  stepId,
  createdAt: "2026-10-01T00:00:00Z",
  resolvedAt: resolved ? "2026-10-01T01:00:00Z" : null,
});

describe("comments", () => {
  it("splits open and resolved, optionally for one step", () => {
    const all = [c("a", null), c("b", "s1", true), c("c", "s1")];
    expect(partitionComments(all).open.map((x) => x.id)).toEqual(["a", "c"]);
    expect(partitionComments(all).resolved.map((x) => x.id)).toEqual(["b"]);
    expect(partitionComments(all, "s1").open.map((x) => x.id)).toEqual(["c"]);
  });

  it("makes initials from names and emails", () => {
    expect(initials("Priya Sharma")).toBe("PS");
    expect(initials("bob@example.com")).toBe("BE");
  });
});
