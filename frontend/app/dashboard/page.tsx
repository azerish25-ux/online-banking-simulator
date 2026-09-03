"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, clearToken, type User } from "../../lib/api";

export default function DashboardPage() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api("/v1/auth/me")
      .then(setUser)
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load profile"));
  }, []);

  function logout() {
    clearToken();
    router.push("/login");
  }

  return (
    <main className="container">
      <h1>Dashboard</h1>
      {user && (
        <div className="card">
          <p>Welcome, <strong>{user.fullName}</strong></p>
          <p>{user.email} · <span className="badge">{user.role}</span></p>
          <p style={{ opacity: 0.7 }}>Accounts and transfers arrive in Part 3.</p>
          <button onClick={logout}>Log out</button>
        </div>
      )}
      {error && <div className="card"><p style={{ color: "#fca5a5" }}>{error}</p><button onClick={logout}>Back to login</button></div>}
      {!user && !error && <p>Loading...</p>}
    </main>
  );
}
