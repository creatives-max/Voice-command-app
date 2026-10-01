import { ago, passwordProblem, sessionName, sortSessions } from "./security";

describe("account security helpers", () => {
  it("checks new passwords like the server", () => {
    expect(passwordProblem("")).toBeNull();
    expect(passwordProblem("abc12")).toBe("At least 8 characters.");
    expect(passwordProblem("onlyletters")).toBe("Use letters and numbers.");
    expect(passwordProblem("12345678")).toBe("Use letters and numbers.");
    expect(passwordProblem("पासवर्ड1234")).toBeNull();
    expect(passwordProblem("secret123", "secret123")).toBe("Choose a password different from the current one.");
    expect(passwordProblem("newsecret456", "secret123")).toBeNull();
  });

  it("says how long ago a session was used", () => {
    const now = Date.parse("2026-10-01T12:00:00Z");
    expect(ago("2026-10-01T11:59:30Z", now)).toBe("just now");
    expect(ago("2026-10-01T11:55:00Z", now)).toBe("5 min ago");
    expect(ago("2026-10-01T09:00:00Z", now)).toBe("3 h ago");
    expect(ago("2026-09-30T11:00:00Z", now)).toBe("1 day ago");
    expect(ago("2026-09-21T12:00:00Z", now)).toBe("10 days ago");
  });

  it("lists this browser first, then by last use", () => {
    const s = (id: string, lastUsedAt: string, current = false) => ({ id, client: null, createdAt: lastUsedAt, lastUsedAt, current });
    const sorted = sortSessions([s("old", "2026-09-01T00:00:00Z"), s("me", "2026-09-02T00:00:00Z", true), s("new", "2026-09-30T00:00:00Z")]);
    expect(sorted.map((x) => x.id)).toEqual(["me", "new", "old"]);
    expect(sessionName(sorted[0]!)).toBe("Unknown browser or app");
  });
});
