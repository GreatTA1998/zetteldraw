import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Privacy · zetteldraw",
  description: "Privacy policy for the Zetteldraw Boox app and web companion.",
};

export default function PrivacyPage() {
  return (
    <main className="mx-auto max-w-2xl px-5 py-12 text-sm leading-relaxed text-foreground">
      <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">zetteldraw</p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight">Privacy policy</h1>
      <p className="mt-2 text-muted-foreground">Last updated 10 October 2026</p>

      <section className="mt-10 space-y-3">
        <h2 className="text-lg font-semibold">What this app is</h2>
        <p>
          Zetteldraw is a handwriting notebook for Boox e-ink devices. Notes are stored first on your device. Optional
          sync backs them up to our servers so you can browse them on the web companion at{" "}
          <Link href="/" className="underline underline-offset-2">
            zetteldraw.com
          </Link>
          . The web companion is read-only.
        </p>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">Account</h2>
        <p>
          Sync uses Google Sign-In. We receive your Google account identifier (subject) and email from Google so we can
          keep your notebooks under your account. We do not receive or store your Google password.
        </p>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">What we store when sync is on</h2>
        <ul className="list-disc space-y-1 pl-5">
          <li>Notebook and page metadata (titles, order, links between pages)</li>
          <li>Ink data and thumbnails you create while writing</li>
          <li>Your Google account id and email</li>
        </ul>
        <p>
          With sync off, everything stays on the device (and in the optional local Documents backup). We do not sell
          your notes or use them for advertising.
        </p>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">Who can see your notes</h2>
        <p>
          After you sign in, only that Google account can sync or view your library. The public web site no longer opens
          a shared library without sign-in.
        </p>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">Contact</h2>
        <p>
          Questions about this policy: use the contact email listed on the Play Store listing for Zetteldraw, or the
          address on your Google Play Console account for this app.
        </p>
      </section>
    </main>
  );
}
