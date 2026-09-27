import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The Docker image runs the self-contained server; `next start` (Render, local) needs the default output.
  output: process.env.NEXT_OUTPUT === "standalone" ? "standalone" : undefined,
  devIndicators: false,
  // Dev only: lets the dev server be opened as 127.0.0.1 or from a LAN address, not just localhost.
  allowedDevOrigins: ["127.0.0.1", ...(process.env.ZD_DEV_ORIGINS?.split(",").filter(Boolean) ?? [])],
};

export default nextConfig;
