import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { ToastProvider } from "../components/feedback/toast";

const inter = Inter({ subsets: ["latin"], variable: "--font-sans" });

export const metadata: Metadata = {
  title: "Northbank - Enterprise Banking",
  description: "Portfolio enterprise banking platform: Next.js + Spring Boot + PostgreSQL"
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body>
        <ToastProvider>{children}</ToastProvider>
      </body>
    </html>
  );
}
