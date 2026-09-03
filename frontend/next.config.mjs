/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    // In Part 2+ this proxies API calls to Spring Boot to avoid CORS in dev.
    return [
      { source: '/backend/:path*', destination: 'http://localhost:8080/api/:path*' }
    ];
  }
};
export default nextConfig;
