import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, SESSION_EXPIRED_EVENT, api, authedFetch, clearToken, expireSession, getToken, setToken } from "./api";

function mockFetchOnce(body: unknown, status = 200) {
  global.fetch = vi.fn(async () => new Response(JSON.stringify(body), { status })) as never;
}

beforeEach(() => {
  document.cookie = "bank_token=; max-age=0";
  global.fetch = vi.fn() as never;
});

describe("token cookie", () => {
  it("round-trips through document.cookie", () => {
    expect(getToken()).toBeNull();
    setToken("abc.123");
    expect(getToken()).toBe("abc.123");
    clearToken();
    expect(getToken()).toBeNull();
  });
});

describe("api", () => {
  it("prefixes the backend proxy and sends the bearer token", async () => {
    setToken("tok-1");
    mockFetchOnce({ ok: true });
    await api("/v1/auth/me");
    const [url, init] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/backend/v1/auth/me");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok-1");
  });

  it("throws ApiError with status on HTTP errors", async () => {
    mockFetchOnce({ title: "Unauthorized", detail: "Full authentication required" }, 401);
    const err = await api("/v1/auth/me").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
  });

  it("survives non-JSON error bodies", async () => {
    global.fetch = vi.fn(async () => new Response("<html>boom</html>", { status: 500 })) as never;
    await expect(api("/v1/auth/me")).rejects.toThrow("Request failed: 500");
  });

  it("surfaces the server RFC-7807 detail on errors", async () => {
    mockFetchOnce({ title: "Bad Request", detail: "Amount must be positive" }, 400);
    await expect(api("/v1/transfers", { method: "POST", body: "{}" })).rejects.toThrow(
      "Amount must be positive"
    );
  });
});

describe("silent refresh", () => {
  it("persists the rotated access token and retries with it", async () => {
    setToken("expired-token");
    (global.fetch as ReturnType<typeof vi.fn>) = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ title: "x", detail: "expired" }), { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        accessToken: "brand-new-token",
        tokenType: "Bearer",
        expiresInSeconds: 900,
        user: { id: "1", email: "a@b.c", fullName: "A", role: "CUSTOMER" }
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "1" }), { status: 200 }));
    const data = await api<{ id: string }>("/v1/auth/me");
    expect(data).toEqual({ id: "1" });
    const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls[1][0]).toBe("/backend/v1/auth/refresh");
    // The retried request must carry the rotated token, not the expired one.
    const retried = calls[2] as [string, RequestInit];
    expect((retried[1].headers as Record<string, string>).Authorization).toBe("Bearer brand-new-token");
    expect(getToken()).toBe("brand-new-token");
  });

  it("rotates exactly once under parallel 401s (single flight)", async () => {
    setToken("expired-token");
    (global.fetch as ReturnType<typeof vi.fn>) = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ detail: "expired" }), { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ detail: "expired" }), { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: "fresh" }), { status: 200 }))
      .mockResolvedValueOnce(new Response("{}", { status: 200 }))
      .mockResolvedValueOnce(new Response("{}", { status: 200 }));
    await Promise.all([api("/v1/a"), api("/v1/b")]);
    const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls as [string][];
    expect(calls.filter((c) => c[0] === "/backend/v1/auth/refresh")).toHaveLength(1);
  });

  it("clears the session and broadcasts expiry when refresh fails", async () => {
    setToken("expired-token");
    (global.fetch as ReturnType<typeof vi.fn>) = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 401 }))
      .mockResolvedValueOnce(new Response("{}", { status: 401 }));
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    const err = await api("/v1/auth/me").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect(getToken()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
  });

  it("does not retry token-minting endpoints", async () => {
    setToken("whatever");
    (global.fetch as ReturnType<typeof vi.fn>) = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ title: "Bad", detail: "nope" }), { status: 401 }));
    await expect(api("/v1/auth/login", { method: "POST", body: "{}" })).rejects.toThrow("nope");
    expect((global.fetch as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(1);
  });
});

describe("authedFetch", () => {
  it("returns the raw response for downloads on success", async () => {
    setToken("tok");
    global.fetch = vi.fn(async () => new Response("a,b\n1,2", { status: 200 })) as never;
    const res = await authedFetch("/v1/accounts/1/statement.csv");
    expect(res.status).toBe(200);
    expect(await res.text()).toBe("a,b\n1,2");
  });

  it("surfaces RFC-7807 errors without touching the blob path", async () => {
    setToken("tok");
    global.fetch = vi.fn(async () => new Response(
      JSON.stringify({ title: "Not Found", detail: "No such account" }), { status: 404 }
    )) as never;
    await expect(authedFetch("/v1/accounts/1/statement.csv")).rejects.toThrow("No such account");
  });
});

describe("expireSession", () => {
  it("clears the token and dispatches the event exactly once", () => {
    setToken("tok");
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    expireSession();
    expect(getToken()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
  });
});
