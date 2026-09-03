"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, clearToken, type User } from "../../lib/api";

type Account = { id: string; iban: string; type: string; balance: string; status: string };
type Tx = { id: string; fromIban: string | null; toIban: string | null; amount: string; currency: string; memo: string | null; status: string; createdAt: string };

export default function DashboardPage() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [recent, setRecent] = useState<Tx[]>([]);
  const [depositAmount, setDepositAmount] = useState("100.00");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function load() {
    try {
      const me = await api("/v1/auth/me");
      setUser(me);
      const accs: Account[] = await api("/v1/accounts");
      setAccounts(accs);
      if (accs.length > 0) {
        const page = await api("/v1/transactions?accountId=" + accs[0].id + "&size=5");
        setRecent(page.content ?? []);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load dashboard");
    }
  }

  useEffect(() => { load(); }, []);

  async function deposit() {
    setError(null); setNotice(null);
    try {
      await api("/v1/accounts/" + accounts[0].id + "/deposit", {
        method: "POST",
        body: JSON.stringify({ amount: depositAmount })
      });
      setNotice("Deposited $" + depositAmount + " (simulated rail).");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Deposit failed");
    }
  }

  function logout() {
    clearToken();
    router.push("/login");
  }

  const total = accounts.reduce((sum, a) => sum + parseFloat(a.balance), 0);

  return (
    <main className="container">
      <h1>Dashboard</h1>
      {user && <p>Welcome, <strong>{user.fullName}</strong> · <span className="badge">{user.role}</span></p>}
      {notice && <div className="card"><p style={{ color: "#86efac" }}>{notice}</p></div>}
      {error && <div className="card"><p style={{ color: "#fca5a5" }}>{error}</p></div>}

      <div className="card">
        <h2>Total balance: ${total.toFixed(2)}</h2>
        {accounts.map((a) => (
          <p key={a.id}><code>{a.iban}</code> · {a.type} · <strong>${parseFloat(a.balance).toFixed(2)}</strong> · {a.status}</p>
        ))}
        {accounts.length > 0 && (
          <p>
            <input value={depositAmount} onChange={(e) => setDepositAmount(e.target.value)} style={{ width: 100 }} />{" "}
            <button onClick={deposit}>Simulate deposit</button>
          </p>
        )}
        <p><Link href="/transfers">Send money →</Link></p>
      </div>

      <div className="card">
        <h2>Recent activity</h2>
        {recent.length === 0 && <p style={{ opacity: 0.7 }}>No transactions yet.</p>}
        {recent.map((t) => (
          <p key={t.id}>
            {t.fromIban ? <code>{t.fromIban.slice(-6)}</code> : "DEPOSIT"} → {t.toIban ? <code>{t.toIban.slice(-6)}</code> : "?"} ·{" "}
            <strong>${parseFloat(t.amount).toFixed(2)}</strong> {t.currency}
            {t.memo ? " · " + t.memo : ""}
          </p>
        ))}
      </div>

      <p><button onClick={logout}>Log out</button></p>
      <style jsx>{`input { padding: 6px; border-radius: 6px; border: 1px solid #2a4d85; background: #0a0f1e; color: #e8eef7; } button { padding: 6px 12px; border-radius: 6px; border: 1px solid #2a4d85; background: #12325b; color: #e8eef7; cursor: pointer; }`}</style>
    </main>
  );
}
