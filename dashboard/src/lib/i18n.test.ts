import { messages, translate, type MessageKey } from "./i18n";

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe("i18n", () => {
  it("Hindi has every key with the same placeholders", () => {
    for (const key of Object.keys(messages.en) as MessageKey[]) {
      expect(messages.hi[key], key).toBeTruthy();
      expect(placeholders(messages.hi[key]), key).toEqual(placeholders(messages.en[key]));
    }
    expect(Object.keys(messages.hi).sort()).toEqual(Object.keys(messages.en).sort());
  });

  it("interpolates and leaves unknown placeholders", () => {
    expect(translate("en", "editor.saved", { version: 4 })).toBe("Saved version 4. The phone will use it on the next run.");
    expect(translate("hi", "analytics.days", { days: 30 })).toBe("पिछले 30 दिन");
    expect(translate("en", "flows.orgTitle")).toBe("{org} flows");
  });
});
