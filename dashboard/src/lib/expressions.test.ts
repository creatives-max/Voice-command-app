import { readFileSync, existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { evaluate, template, templateErrors, toNumber, toText, validate, variables, type Value } from "./expressions";

interface Spec {
  vars: Record<string, string>;
  cases: { expr: string; expected: Value }[];
  errors: { expr: string }[];
}

function loadSpec(): Spec {
  let dir = process.cwd();
  while (!existsSync(path.join(dir, "docs/spec/expressions.json"))) {
    const parent = path.dirname(dir);
    if (parent === dir) throw new Error("docs/spec/expressions.json not found");
    dir = parent;
  }
  return JSON.parse(readFileSync(path.join(dir, "docs/spec/expressions.json"), "utf8")) as Spec;
}

const spec = loadSpec();
const vars = (name: string) => spec.vars[name];
const today = () => new Date(2026, 9, 1);

describe("expressions (shared vectors)", () => {
  it.each(spec.cases.map((c) => [c.expr, c.expected] as const))("%s", (expr, expected) => {
    const actual = evaluate(expr, vars, today);
    if (typeof expected === "string") expect(toText(actual)).toBe(expected);
    else if (typeof expected === "boolean") expect(actual).toBe(expected);
    else if (typeof expected === "number") expect(toNumber(actual)).toBeCloseTo(expected, 9);
    else expect(actual).toBeNull();
  });

  it.each(spec.errors.map((e) => [e.expr] as const))("rejects %s", (expr) => {
    expect(validate(expr)).not.toBeNull();
  });

  it("templates and variable discovery", () => {
    expect(template("Hello {first}, you have {kids} kids", vars, today)).toBe("Hello Rahul, you have 2 kids");
    expect([...variables("age > 18 && kids == 2")]).toEqual(["age", "kids"]);
    expect(template("unchanged {bad(}", () => null, today)).toBe("unchanged {bad(}");
    expect(templateErrors("Hi {first} {upper(}")).toHaveLength(1);
  });
});
