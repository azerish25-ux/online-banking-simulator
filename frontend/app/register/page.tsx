"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, setToken } from "../../lib/api";

export default function RegisterPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fullName, setFullName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const data = await api("/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({ email, password, fullName })
      });
      setToken(data.accessToken);
      router.push("/dashboard");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Registration failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="container">
      <h1>Create account</h1>
      <form onSubmit={onSubmit} className="card">
        <label>Full name<br /><input required value={fullName} onChange={(e) => setFullName(e.target.value)} /></label>
        <br /><br />
        <label>Email<br /><input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <br /><br />
        <label>Password (min 8 chars)<br /><input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <br /><br />
        <button type="submit" disabled={loading}>{loading ? "Creating..." : "Register"}</button>
        {error && <p style={{ color: "#fca5a5" }}>{error}</p>}
      </form>
      <p>Have an account? <Link href="/login">Log in</Link></p>
      <style jsx>{`input { width: 100%; padding: 8px; margin-top: 4px; border-radius: 6px; border: 1px solid #2a4d85; background: #0a0f1e; color: #e8eef7; } button { padding: 8px 16px; border-radius: 6px; border: 1px solid #2a4d85; background: #12325b; color: #e8eef7; cursor: pointer; }`}</style>
    </main>
  );
}
