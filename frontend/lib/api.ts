export type User = { id: string; email: string; fullName: string; role: string };
export type AuthResponse = { accessToken: string; tokenType: string; expiresInSeconds: number; user: User };

export class ApiError extends Error {
  status: number;
  title: string;

  constructor(status: number, title: string, detail: string) {
    super(detail || title || "Request failed: " + status);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
  }
}

export function getToken(): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(/(?:^|; )bank_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export function setToken(token: string) {
  document.cookie = "bank_token=" + encodeURIComponent(token) + "; path=/; max-age=604800; samesite=lax";
}

export function clearToken() {
  document.cookie = "bank_token=; path=/; max-age=0";
}

function safeJson(text: string): { detail?: string; title?: string } | null {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export async function api(path: string, options: RequestInit = {}): Promise<any> {
  const token = getToken();
  const res = await fetch("/backend" + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(options.headers || {})
    }
  });
  const data = safeJson(await res.text());
  if (!res.ok) {
    throw new ApiError(
      res.status,
      (data && data.title) || "Error",
      (data && data.detail) || "Request failed: " + res.status
    );
  }
  return data;
}
