import { cookies } from "next/headers";
import { NextResponse, type NextRequest } from "next/server";
import { handleProxy } from "@/server/proxy";

export const dynamic = "force-dynamic";

const backendUrl = () => process.env.BACKEND_URL ?? "http://localhost:8080";

async function handle(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const jar = await cookies();
  const result = await handleProxy(
    {
      method: request.method,
      path,
      search: request.nextUrl.search,
      body: request.method === "GET" || request.method === "HEAD" ? null : await request.text(),
      cookies: { get: (name) => jar.get(name)?.value },
      orgId: request.headers.get("x-org-id"),
      careLink: request.headers.get("x-care-link"),
      userAgent: request.headers.get("user-agent"),
    },
    backendUrl(),
  );
  const response = new NextResponse(result.body, {
    status: result.status,
    headers: result.body ? { "Content-Type": "application/json" } : undefined,
  });
  for (const c of result.setCookies) {
    response.cookies.set(c.name, c.value, {
      httpOnly: true,
      sameSite: "strict",
      secure: process.env.NODE_ENV === "production",
      path: "/",
      maxAge: c.maxAge,
    });
  }
  return response;
}

export { handle as GET, handle as POST, handle as PUT, handle as DELETE, handle as PATCH };
