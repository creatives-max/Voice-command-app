import { cn } from "./utils";

describe("cn", () => {
  it("merges tailwind classes, last wins", () => {
    expect(cn("p-2", "p-4", false && "hidden")).toBe("p-4");
  });
});
