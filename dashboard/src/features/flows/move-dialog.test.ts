import { moveTargets } from "./move-dialog";
import type { Org } from "@/lib/types";

const org = (id: string, role: Org["role"]): Org => ({ id, name: id.toUpperCase(), role, memberCount: 2, createdAt: "" });

describe("moveTargets", () => {
  const orgs = [org("a", "ADMIN"), org("e", "EDITOR"), org("v", "VIEWER")];

  it("lets personal flows move into organizations you can edit", () => {
    expect(moveTargets({ orgId: null }, orgs)).toEqual([
      { id: "a", label: "A" },
      { id: "e", label: "E" },
    ]);
  });

  it("lets admins move organization flows out or elsewhere", () => {
    expect(moveTargets({ orgId: "a" }, orgs)).toEqual([
      { id: null, label: "My personal flows" },
      { id: "e", label: "E" },
    ]);
  });

  it("offers nothing to editors and viewers of the flow's organization", () => {
    expect(moveTargets({ orgId: "e" }, orgs)).toEqual([]);
    expect(moveTargets({ orgId: "v" }, orgs)).toEqual([]);
  });
});
