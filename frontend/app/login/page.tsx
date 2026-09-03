"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, setToken } from "../../lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const data = await api("/v1/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password })
      });
      setToken(data.accessToken);
      router.push("/dashboard");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="container">
      <h1>Log in</h1>
      <form onSubmit={onSubmit} className="card">
        <label>Email<br /><input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <br /><br />
        <label>Password<br /><input type="password" required value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <br /><br />
        <button type="submit" disabled={loading}>{loading ? "Logging in..." : "Log in"}</button>
        {error && <p style={{ color: "#fca5a5" }}>{error}</p>}
      </form>
      <p>No account? <Link href="/register">Register</Link></p>
      <style jsx>{`input { width: 100%; padding: 8px; margin-top: 4px; border-radius: 6px; border: 1px solid #2a4d85; background: #0a0f1e; color: #e8eef7; } button { padding: 8px 16px; border-radius: 6px; border: 1px solid #2a4d85; background: #12325b; color: #e8eef7; cursor: pointer; }`}</style>
    </main>
  );
}
