"use client";

import { useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";

export default function TransfersPage() {
  const [toIban, setToIban] = useState("");
  const [amount, setAmount] = useState("10.00");
  const [memo, setMemo] = useState("");
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null); setResult(null); setLoading(true);
    try {
      const data = await api("/v1/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ toIban, amount, memo: memo || undefined })
      });
      setResult("Sent $" + data.amount + " to " + data.toIban + " (id " + String(data.id).slice(0, 8) + "...).");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Transfer failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="container">
      <h1>Send money</h1>
      <form onSubmit={onSubmit} className="card">
        <label>Recipient IBAN<br /><input required value={toIban} onChange={(e) => setToIban(e.target.value)} placeholder="DE..." style={{ width: "100%" }} /></label>
        <br /><br />
        <label>Amount (USD)<br /><input required value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
        <br /><br />
        <label>Memo (optional)<br /><input value={memo} onChange={(e) => setMemo(e.target.value)} maxLength={140} style={{ width: "100%" }} /></label>
        <br /><br />
        <button type="submit" disabled={loading}>{loading ? "Sending..." : "Send"}</button>
        {result && <p style={{ color: "#86efac" }}>{result}</p>}
        {error && <p style={{ color: "#fca5a5" }}>{error}</p>}
      </form>
      <p><Link href="/dashboard">← Back to dashboard</Link></p>
      <style jsx>{`input { padding: 8px; margin-top: 4px; border-radius: 6px; border: 1px solid #2a4d85; background: #0a0f1e; color: #e8eef7; } button { padding: 8px 16px; border-radius: 6px; border: 1px solid #2a4d85; background: #12325b; color: #e8eef7; cursor: pointer; }`}</style>
    </main>
  );
}
