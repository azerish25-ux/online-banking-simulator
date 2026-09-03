export type User = { id: string; email: string; fullName: string; role: string };
export type AuthResponse = { accessToken: string; tokenType: string; expiresInSeconds: number; user: User };

const TOKEN_KEY = "bank_token";

export function getToken(): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(/(?:^|; )bank_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export function setToken(token: string) {
  document.cookie = "bank_token=" + encodeURIComponent(token) + "; path=/; max-age=604800; samesite=lax";
  try { localStorage.setItem(TOKEN_KEY, token); } catch { /* ignore */ }
}

export function clearToken() {
  document.cookie = "bank_token=; path=/; max-age=0";
  try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
}

export async function api(path: string, options: RequestInit = {}) {
  const token = getToken();
  const res = await fetch("/backend" + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(options.headers || {})
    }
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new Error((data && (data.detail || data.title)) || ("Request failed: " + res.status));
  }
  return data;
}
