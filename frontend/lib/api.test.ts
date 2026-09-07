import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ACCESS_TOKEN_COOKIE_MAX_AGE, ApiError, SESSION_EXPIRED_EVENT, api, authedFetch, broadcastLogout, clearToken, expireSession, getToken, setToken, subscribeAuthChannel, tokenCookie } from "./api";

/**
 * jsdom has no BroadcastChannel, so two "tabs" are simulated with a tiny
 * in-memory bus that delivers postMessage to every other open instance with
 * the same channel name - exactly the browser contract api.ts relies on for
 * cross-tab session coordination (F22).
 */
type FakeMessageEvent = { data: unknown };

class FakeBroadcastChannel {
  static channels: FakeBroadcastChannel[] = [];
  name: string;
  onmessage: ((event: FakeMessageEvent) => void) | null = null;
  private closed = false;

  constructor(name: string) {
    this.name = name;
    FakeBroadcastChannel.channels.push(this);
  }

  postMessage(data: unknown) {
    for (const other of FakeBroadcastChannel.channels) {
      if (other !== this && other.name === this.name && !other.closed) {
        other.onmessage?.({ data });
      }
    }
  }

  close() {
    this.closed = true;
  }
}

function mockFetchOnce(body: unknown, status = 200) {
  global.fetch = vi.fn(async () => new Response(JSON.stringify(body), { status })) as never;
}

beforeEach(() => {
  document.cookie = "bank_token=; max-age=0";
  global.fetch = vi.fn() as never;
  FakeBroadcastChannel.channels = [];
  vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("token cookie", () => {
  it("round-trips through document.cookie", () => {
    expect(getToken()).toBeNull();
    setToken("abc.123");
    expect(getToken()).toBe("abc.123");
    clearToken();
    expect(getToken()).toBeNull();
  });

  it("dies with the access token instead of lingering for a week", () => {
    const cookie = tokenCookie("abc.123");
    // Fallback Max-Age mirrors the 15-minute access-token TTL, not the old
    // 7-day value (the AUTHORITATIVE lifetime comes from the auth response's
    // expiresInSeconds - see the cookie-lifetime tests below).
    expect(cookie).toContain("max-age=" + ACCESS_TOKEN_COOKIE_MAX_AGE);
    expect(cookie.includes("max-age=604800")).toBe(false);
    expect(cookie).toContain("samesite=lax");
    expect(cookie.includes("; Secure")).toBe(false); // dev http stays Secure-less
    // Over HTTPS the cookie must be marked Secure.
    expect(tokenCookie("abc.123", undefined, true)).toContain("; Secure");
  });

  it("mirrors the VALIDATED access-token lifetime when one is provided", () => {
    // section 9: the cookie dies exactly when the JWT does - the auth response's
    // expiresInSeconds, not a client-side guess.
    expect(tokenCookie("abc.123", 900)).toContain("max-age=900");
    expect(tokenCookie("abc.123", 30)).toContain("max-age=30");
    // A nonsensical value falls back instead of writing a broken cookie.
    expect(tokenCookie("abc.123", Number.NaN)).toContain("max-age=" + ACCESS_TOKEN_COOKIE_MAX_AGE);
    expect(tokenCookie("abc.123", -5)).toContain("max-age=" + ACCESS_TOKEN_COOKIE_MAX_AGE);
  });
});

describe("credential adoption ( section 9)", () => {
  it("installs the token from a VALIDATED auth response with its lifetime", async () => {
    setToken("old");
    // jsdom's document.cookie drops attributes, so capture every raw
    // Set-Cookie string the client writes (cookie is an accessor on the
    // Document prototype: shadow it on the instance, then delete the shadow
    // so later tests read through the prototype accessor again).
    const assigned: string[] = [];
    Object.defineProperty(document, "cookie", {
      get: () => assigned.join("; "),
      set: (value: string) => {
        assigned.push(value);
      },
      configurable: true
    });
    try {
      mockFetchOnce({
        accessToken: "session-token",
        tokenType: "Bearer",
        expiresInSeconds: 42,
        user: { id: "1", email: "a@b.c", fullName: "A", role: "CUSTOMER" }
      });
      await api("/v1/auth/login", { method: "POST", body: "{}" });
      expect(getToken()).toBe("session-token");
      // The cookie Max-Age mirrors the VALIDATED lifetime (42s), not the
      // client's 15-minute constant.
      expect(assigned.join(" ")).toContain("max-age=42");
    } finally {
      // Restores the prototype accessor (the beforeEach reset then applies
      // to the real cookie jar again).
      delete (document as { cookie?: unknown }).cookie;
    }
  });

  it("never adopts an accessToken from a resource response", async () => {
    setToken("old");
    // A (misbehaving or malicious) account endpoint echoes an accessToken -
    // the client must not install it: only validated auth responses may
    // plant credentials.
    mockFetchOnce({ id: "acc-1", balance: "10.00", accessToken: "injected" });
    await api("/v1/accounts");
    expect(getToken()).toBe("old");
    expect(document.cookie).not.toContain("injected");
  });

  it("ignores a malformed auth response body (no validated lifetime)", async () => {
    setToken("old");
    mockFetchOnce({ accessToken: "half" }); // no expiresInSeconds
    await api("/v1/auth/login", { method: "POST", body: "{}" });
    expect(getToken()).toBe("old");
    expect(document.cookie).not.toContain("half");
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
      .mockResolvedValueOnce(new Response(JSON.stringify({
        accessToken: "fresh",
        tokenType: "Bearer",
        expiresInSeconds: 900
      }), { status: 200 }))
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

  it("adopts the rotated token with its validated lifetime when refresh is malformed it expires", async () => {
    // A refresh body without a VALIDATED AuthResponse (no expiresInSeconds) is
    // a failed rotation - credentials must never be installed from a partial
    // body, so the session expires instead of limping along.
    setToken("expired-token");
    (global.fetch as ReturnType<typeof vi.fn>) = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: "orphan" }), { status: 200 }));
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    await expect(api("/v1/auth/me")).rejects.toThrow("Session expired");
    expect(getToken()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
  });
});

describe("cross-tab session channel (F22)", () => {
  it("a deliberate logout in one tab reaches a sibling tab's listener", () => {
    const peer = vi.fn();
    const unsubscribe = subscribeAuthChannel(peer);
    broadcastLogout();
    expect(peer).toHaveBeenCalledTimes(1);
    expect(peer).toHaveBeenCalledWith({ type: "logout" });
    unsubscribe();
  });

  it("expireSession clears the local token, fires locally, and tells sibling tabs", () => {
    setToken("tok");
    const local = vi.fn();
    const peer = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, local);
    const unsubscribe = subscribeAuthChannel(peer);
    expireSession();
    expect(getToken()).toBeNull();
    expect(local).toHaveBeenCalledTimes(1);
    expect(peer).toHaveBeenCalledWith({ type: "expired" });
    window.removeEventListener(SESSION_EXPIRED_EVENT, local);
    unsubscribe();
  });

  it("ignores messages that are not the three declared session announcements", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeAuthChannel(listener);
    const tab = new FakeBroadcastChannel("simulator:auth");
    tab.postMessage({ type: "refreshed" });
    tab.postMessage({ type: "logout" });
    tab.postMessage({ type: "expired" });
    // Not one of the declared AuthMessage types (or plain garbage).
    tab.postMessage({ type: "navigate" });
    tab.postMessage("logout");
    tab.postMessage(undefined);
    expect(listener).toHaveBeenCalledTimes(3);
    unsubscribe();
  });
});

describe("TOTP credential rejections keep the session (F02)", () => {
  it("a wrong TOTP code after a successful refresh surfaces the 401 without expiring", async () => {
    setToken("expired-token");
    (global.fetch as ReturnType<typeof vi.fn>) = vi.fn()
      // Enable attempt with an (in fact still valid) access token the server
      // rejects for another reason is indistinguishable from expiry at the
      // client, so the silent refresh runs once...
      .mockResolvedValueOnce(new Response(JSON.stringify({ title: "Unauthorized", detail: "Invalid code" }), { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        accessToken: "fresh",
        tokenType: "Bearer",
        expiresInSeconds: 900
      }), { status: 200 }))
      // ...and the retried submission is refused for the SAME business reason.
      // That is a definitive rejection of a HEALTHY session: the user must
      // see "Invalid code", not be logged out.
      .mockResolvedValueOnce(new Response(JSON.stringify({ title: "Unauthorized", detail: "Invalid code" }), { status: 401 }));
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    const err = await api("/v1/auth/totp/enable", {
      method: "POST",
      body: JSON.stringify({ code: "000000" })
    }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect((err as ApiError).message).toBe("Invalid code");
    // The rotated session is alive - never expired on a credential rejection.
    expect(getToken()).toBe("fresh");
    expect(listener).not.toHaveBeenCalled();
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
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
