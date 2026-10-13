import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { Toaster } from "@/components/ui/sonner";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "zetteldraw",
  description: "Every notebook and page from your Boox, at a glance.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="min-h-full">
        <noscript>
          <main style={{ maxWidth: 40 + "rem", margin: "2rem auto", padding: "0 1.25rem", fontFamily: "system-ui, sans-serif" }}>
            <p style={{ fontSize: 12, letterSpacing: "0.06em", textTransform: "uppercase" }}>zetteldraw</p>
            <h1 style={{ fontSize: 28, marginTop: 8 }}>JavaScript is off</h1>
            <p style={{ marginTop: 12, lineHeight: 1.5 }}>
              The notebook companion needs JavaScript. Prepare transfer happens in the Boox app (Settings → Prepare
              transfer), not in the browser. For Hand off instructions that work without JavaScript, open{" "}
              <a href="/transfer">/transfer</a>.
            </p>
          </main>
        </noscript>
        {children}
        <Toaster position="bottom-right" />
      </body>
    </html>
  );
}
