import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Bank Platform - Part 1",
  description: "Enterprise banking platform portfolio project"
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
