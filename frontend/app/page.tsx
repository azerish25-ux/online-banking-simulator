"use client";

import { useEffect, useState } from "react";

type Health = { status: string; checks?: Record<string, string> };

export default function Home() {
  const [backend, setBackend] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // In Part 1 the backend may not be running - that is fine.
    // Uses the Next.js rewrite proxy (/backend -> :8080) when available.
    fetch("/backend/health")
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        setBackend(await r.json());
      })
      .catch((e) => setError(e.message));
  }, []);

  return (
    <main className="container">
      <span className="badge">Part 1 - Foundation</span>
      <h1>Enterprise Banking Platform</h1>
      <p>
        Monorepo scaffold is live. Frontend (Next.js + TypeScript) is serving this page.
        Backend (Spring Boot) exposes <code>GET /api/health</code>.
      </p>

      <div className="card">
        <h2>System status</h2>
        <p>Frontend: <strong>UP</strong> (you are reading this page)</p>
        {backend && <p>Backend: <strong>{backend.status}</strong></p>}
        {error && <p>Backend: <strong>not reachable yet</strong> ({error}) - start it with <code>.\mvnw.cmd spring-boot:run</code></p>}
      </div>

      <div className="card">
        <h2>What is next (Part 2)</h2>
        <ul>
          <li>Postgres + Flyway migrations</li>
          <li>Register / login with JWT</li>
          <li>Protected dashboard route</li>
        </ul>
      </div>
    </main>
  );
}
