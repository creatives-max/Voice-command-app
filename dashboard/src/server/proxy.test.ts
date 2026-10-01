import { ACCESS_COOKIE, REFRESH_COOKIE, handleProxy, type ProxyRequest } from "./proxy";

function req(overrides: Partial<ProxyRequest>): ProxyRequest {
  return { method: "GET", path: ["flows"], search: "", body: null, cookies: { get: () => undefined }, ...overrides };
}

const tokens = { accessToken: "A2", refreshToken: "R2", expiresInSeconds: 900, user: { id: "u", email: "a@b.co" } };

describe("handleProxy", () => {
  it("stores tokens in cookies on login and returns only the user", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify(tokens), { status: 200 }));
    const res = await handleProxy(req({ method: "POST", path: ["auth", "login"], body: "{}" }), "http://b", fetcher);
    expect(res.status).toBe(200);
    expect(JSON.parse(res.body!)).toEqual(tokens.user);
    expect(res.body).not.toContain("A2");
    expect(res.setCookies.map((c) => c.name)).toEqual([ACCESS_COOKIE, REFRESH_COOKIE]);
  });

  it("forwards with bearer token and refreshes once on 401", async () => {
    const calls: { url: string; auth?: string }[] = [];
    const fetcher = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>;
      calls.push({ url: String(url), auth: headers.Authorization });
      if (String(url).endsWith("/v1/auth/refresh")) return new Response(JSON.stringify(tokens), { status: 200 });
      if (headers.Authorization === "Bearer A1") return new Response("{}", { status: 401 });
      return new Response(JSON.stringify({ items: [] }), { status: 200 });
    });
    const cookies = { get: (n: string) => (n === ACCESS_COOKIE ? "A1" : "R1") };
    const res = await handleProxy(req({ path: ["flows"], search: "?limit=5", cookies }), "http://b", fetcher as typeof fetch);
    expect(res.status).toBe(200);
    expect(calls.map((c) => c.url)).toEqual(["http://b/v1/flows?limit=5", "http://b/v1/auth/refresh", "http://b/v1/flows?limit=5"]);
    expect(calls[2]?.auth).toBe("Bearer A2");
    expect(res.setCookies.find((c) => c.name === ACCESS_COOKIE)?.value).toBe("A2");
  });

  it("clears cookies when refresh fails and blocks unknown routes", async () => {
    const fetcher = vi.fn(async (url: string | URL | Request) =>
      String(url).endsWith("/refresh") ? new Response("{}", { status: 401 }) : new Response("{}", { status: 401 }),
    );
    const res = await handleProxy(req({ path: ["profile"], cookies: { get: () => "x" } }), "http://b", fetcher as typeof fetch);
    expect(res.status).toBe(401);
    expect(res.setCookies.every((c) => c.maxAge === 0)).toBe(true);

    const blocked = await handleProxy(req({ path: ["auth", "refresh"] }), "http://b", fetcher as typeof fetch);
    expect(blocked.status).toBe(404);
  });

  it("passes a valid organization id through and drops anything else", async () => {
    const seen: (string | undefined)[] = [];
    const fetcher = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      seen.push(((init?.headers ?? {}) as Record<string, string>)["X-Org-Id"]);
      return new Response("[]", { status: 200 });
    });
    const org = "0b6c3a3e-9d7f-4f43-a1d5-0c1f2a3b4c5d";
    await handleProxy(req({ path: ["orgs"], orgId: org, cookies: { get: () => "A" } }), "http://b", fetcher as typeof fetch);
    await handleProxy(req({ path: ["flows"], orgId: "x\r\nEvil: 1", cookies: { get: () => "A" } }), "http://b", fetcher as typeof fetch);
    await handleProxy(req({ path: ["invitations", "tok"], cookies: { get: () => "A" } }), "http://b", fetcher as typeof fetch);
    expect(seen).toEqual([org, undefined, undefined]);
  });

  it("passes a caregiver's care link through and drops anything else", async () => {
    const seen: (string | undefined)[] = [];
    const fetcher = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      seen.push(((init?.headers ?? {}) as Record<string, string>)["X-Care-Link"]);
      return new Response("[]", { status: 200 });
    });
    const link = "7d1f3a3e-9d7f-4f43-a1d5-0c1f2a3b4c5d";
    await handleProxy(req({ path: ["flows"], careLink: link, cookies: { get: () => "A" } }), "http://b", fetcher as typeof fetch);
    await handleProxy(req({ path: ["flows"], careLink: "nope", cookies: { get: () => "A" } }), "http://b", fetcher as typeof fetch);
    await handleProxy(req({ path: ["care", "links"], cookies: { get: () => "A" } }), "http://b", fetcher as typeof fetch);
    expect(seen).toEqual([link, undefined, undefined]);
    expect(String(fetcher.mock.calls[2]![0])).toBe("http://b/v1/care/links");
  });

  it("returns 502 when the backend is down", async () => {
    const fetcher = vi.fn(async () => {
      throw new Error("ECONNREFUSED");
    });
    const res = await handleProxy(req({}), "http://b", fetcher as typeof fetch);
    expect(res.status).toBe(502);
  });

  it("passes the browser's User-Agent on sign-in and refresh only, without control characters", async () => {
    const seen: { url: string; agent?: string }[] = [];
    const fetcher = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>;
      seen.push({ url: String(url), agent: headers["User-Agent"] });
      if (String(url).endsWith("/v1/auth/refresh") || String(url).endsWith("/v1/auth/login")) return new Response(JSON.stringify(tokens), { status: 200 });
      if (headers.Authorization === "Bearer A1") return new Response("{}", { status: 401 });
      return new Response("[]", { status: 200 });
    });
    const ua = "Mozilla/5.0 Chrome/129\r\nX-Evil: 1";
    await handleProxy(req({ method: "POST", path: ["auth", "login"], body: "{}", userAgent: ua }), "http://b", fetcher as typeof fetch);
    const cookies = { get: (n: string) => (n === ACCESS_COOKIE ? "A1" : "R1") };
    await handleProxy(req({ path: ["me", "sessions"], cookies, userAgent: ua }), "http://b", fetcher as typeof fetch);
    expect(seen.map((s) => [s.url, s.agent])).toEqual([
      ["http://b/v1/auth/login", "Mozilla/5.0 Chrome/129X-Evil: 1"],
      ["http://b/v1/me/sessions", undefined],
      ["http://b/v1/auth/refresh", "Mozilla/5.0 Chrome/129X-Evil: 1"],
      ["http://b/v1/me/sessions", undefined],
    ]);
  });
});
