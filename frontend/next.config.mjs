const backend = process.env.BACKEND_URL || "http://localhost:8080";

/**
 * Baseline security headers for EVERY response. These can be static:
 * they carry no per-request state. The nonce-based Content-Security-Policy is
 * deliberately NOT here - a nonce must be unique per request, so the CSP
 * lives in middleware.ts where the HTML document is actually produced.
 */
const SECURITY_HEADERS = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" }
];

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: "standalone",
  async headers() {
    return [
      {
        // All routes - including _next/static assets the middleware matcher
        // skips, so they still get nosniff and friends.
        source: "/(.*)",
        headers: SECURITY_HEADERS
      }
    ];
  },
  async rewrites() {
    // Local dev: Next.js proxies API calls to Spring Boot to avoid CORS.
    // In compose/CI set BACKEND_URL=http://backend:8080.
    return [
      { source: "/backend/:path*", destination: backend + "/api/:path*" }
    ];
  }
};
export default nextConfig;
