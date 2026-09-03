import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, clearToken, getToken, setToken } from "./api";

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
