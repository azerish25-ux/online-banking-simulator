"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

type Health = { status: string; service?: string; version?: string };

export default function Home() {
  const [backend, setBackend] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch("/backend/health")
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        setBackend(await r.json());
      })
      .catch((e) => setError(e.message));
  }, []);

  return (
    <main className="container">
      <span className="badge">Part 2 - Database + Auth</span>
      <h1>Enterprise Banking Platform</h1>
      <p>
        <Link href="/register">Create account</Link> · <Link href="/login">Log in</Link> · <Link href="/dashboard">Dashboard</Link>
      </p>

      <div className="card">
        <h2>System status</h2>
        <p>Frontend: <strong>UP</strong> (you are reading this page)</p>
        {backend && <p>Backend: <strong>{backend.status}</strong> · {backend.service} {backend.version}</p>}
        {error && <p>Backend: <strong>not reachable yet</strong> ({error}) - start it with <code>.\mvnw.cmd spring-boot:run</code> from the backend folder</p>}
      </div>

      <div className="card">
        <h2>What is next (Part 3)</h2>
        <ul>
          <li>Account balances + transfer API with idempotency keys</li>
          <li>Transaction history + dashboard money movement</li>
        </ul>
      </div>
    </main>
  );
}
