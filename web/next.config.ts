import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  devIndicators: false,
  // Dev only: lets the dev server be opened as 127.0.0.1 or from a LAN address, not just localhost.
  allowedDevOrigins: ["127.0.0.1", ...(process.env.ZD_DEV_ORIGINS?.split(",").filter(Boolean) ?? [])],
};

export default nextConfig;
