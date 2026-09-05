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
    "A full-stack online banking demo - accounts, transfers, interest, cards, 2FA and an operator console - built with Next.js and Spring Boot."
};

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
