/**
 * Backend-for-frontend proxy used by `app/api/[...path]/route.ts`.
 *
 * - `/api/auth/login|register` → backend; tokens are stored in httpOnly cookies, never exposed to JS.
 * - `/api/auth/logout` → revokes the refresh session and clears cookies.
 * - `/api/session` → current user (`/v1/me`).
 * - everything else `/api/<path>` → `/v1/<path>` with `Authorization: Bearer <access>`; on 401 the
 *   refresh token is rotated once and the request retried.
 */

export const ACCESS_COOKIE = "vc_at";
export const REFRESH_COOKIE = "vc_rt";
const REFRESH_MAX_AGE = 30 * 24 * 3600;

export interface CookieJar {
  get(name: string): string | undefined;
}

export interface ProxyRequest {
  method: string;
  path: string[];
  search: string;
  body: string | null;
  cookies: CookieJar;
}

export interface SetCookie {
  name: string;
  value: string;
  maxAge: number;
}

export interface ProxyResponse {
  status: number;
  body: string | null;
  setCookies: SetCookie[];
}

interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: unknown;
}

const json = (status: number, body: unknown, setCookies: SetCookie[] = []): ProxyResponse => ({
  status,
  body: JSON.stringify(body),
  setCookies,
});

function tokenCookies(t: TokenResponse): SetCookie[] {
  return [
    { name: ACCESS_COOKIE, value: t.accessToken, maxAge: Math.max(60, t.expiresInSeconds) },
    { name: REFRESH_COOKIE, value: t.refreshToken, maxAge: REFRESH_MAX_AGE },
  ];
}

const clearCookies: SetCookie[] = [
  { name: ACCESS_COOKIE, value: "", maxAge: 0 },
  { name: REFRESH_COOKIE, value: "", maxAge: 0 },
];

const ALLOWED_PREFIXES = ["flows", "profile", "me", "ai", "runs", "devices", "triggers", "run-requests"];

export async function handleProxy(req: ProxyRequest, backendUrl: string, fetcher: typeof fetch = fetch): Promise<ProxyResponse> {
  const base = backendUrl.replace(/\/$/, "");
  const call = (path: string, init: RequestInit) =>
    fetcher(`${base}${path}`, { ...init, headers: { "Content-Type": "application/json", ...(init.headers ?? {}) }, cache: "no-store" });
  const [head, ...rest] = req.path;

  try {
    if (head === "auth" && (rest[0] === "login" || rest[0] === "register") && req.method === "POST") {
      const res = await call(`/v1/auth/${rest[0]}`, { method: "POST", body: req.body ?? "{}" });
      const body = await res.text();
      if (!res.ok) return { status: res.status, body, setCookies: [] };
      const tokens = JSON.parse(body) as TokenResponse;
      return json(res.status, tokens.user, tokenCookies(tokens));
    }

    if (head === "auth" && rest[0] === "logout" && req.method === "POST") {
      const access = req.cookies.get(ACCESS_COOKIE);
      const refresh = req.cookies.get(REFRESH_COOKIE);
      if (access) {
        await call("/v1/auth/logout", {
          method: "POST",
          headers: { Authorization: `Bearer ${access}` },
          body: JSON.stringify({ refreshToken: refresh }),
        }).catch(() => undefined);
      }
      return { status: 204, body: null, setCookies: clearCookies };
    }

    const target = head === "session" ? "/v1/me" : ALLOWED_PREFIXES.includes(head ?? "") ? `/v1/${req.path.map(encodeURIComponent).join("/")}${req.search}` : null;
    if (!target) return json(404, { error: "not_found", message: "Unknown API route" });

    let access = req.cookies.get(ACCESS_COOKIE);
    const refresh = req.cookies.get(REFRESH_COOKIE);
    const setCookies: SetCookie[] = [];

    const forward = (token: string | undefined) =>
      call(target, {
        method: req.method,
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: req.method === "GET" || req.method === "HEAD" ? undefined : (req.body ?? undefined),
      });

    let res = await forward(access);
    if (res.status === 401 && refresh) {
      const refreshed = await call("/v1/auth/refresh", { method: "POST", body: JSON.stringify({ refreshToken: refresh }) });
      if (!refreshed.ok) return json(401, { error: "unauthorized", message: "Session expired, please sign in again" }, clearCookies);
      const tokens = (await refreshed.json()) as TokenResponse;
      setCookies.push(...tokenCookies(tokens));
      access = tokens.accessToken;
      res = await forward(access);
    }
    const body = res.status === 204 ? null : await res.text();
    return { status: res.status, body, setCookies };
  } catch {
    return json(502, { error: "backend_unreachable", message: "The VoiceControl backend is not reachable" });
  }
}
