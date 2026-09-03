const backend = process.env.BACKEND_URL || "http://localhost:8080";

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: "standalone",
  async rewrites() {
    // Local dev: Next.js proxies API calls to Spring Boot to avoid CORS.
    // In compose/CI set BACKEND_URL=http://backend:8080.
    return [
      { source: "/backend/:path*", destination: backend + "/api/:path*" }
    ];
  }
};
export default nextConfig;
