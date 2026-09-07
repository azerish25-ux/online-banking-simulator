import type { Metadata } from "next";
import { Cormorant_Garamond, Inter } from "next/font/google";
import { BrandName } from "../lib/brand";
import "./globals.css";
import { ToastProvider } from "../components/feedback/toast";
import { QueryProvider } from "../components/providers/query-provider";

const inter = Inter({ subsets: ["latin"], variable: "--font-sans" });
// Display serif: figures and headlines. next/font self-hosts at build time -
// no runtime requests to Google, no layout shift (size-adjusted fallback).
const display = Cormorant_Garamond({
  subsets: ["latin"],
  weight: ["500", "600", "700"],
  variable: "--font-display"
});

export const metadata: Metadata = {
  title: BrandName,
  description:
    "A full-stack online banking demo: accounts, transfers, interest, cards, 2FA, and an operator console, built with Next.js and Spring Boot."
};

// F23: the nonce-based CSP (proxy.ts, Next 16's renamed middleware) can only be applied to scripts
// during a real per-request render - statically prerendered pages have no
// request headers to read the nonce from, so every route renders dynamically.
// A banking app has no genuinely static content worth caching at the edge;
// correctness of the strict policy wins over prerendering here.
export const dynamic = "force-dynamic";

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${inter.variable} ${display.variable}`}>
      <body>
        <ToastProvider>
          <QueryProvider>{children}</QueryProvider>
        </ToastProvider>
      </body>
    </html>
  );
}
