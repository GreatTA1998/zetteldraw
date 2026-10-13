import type { Metadata } from "next";
import Link from "next/link";

const DELETE_EMAIL = "eltonlin1998@gmail.com";
const DELETE_SUBJECT = "Delete my Zetteldraw account";

export const metadata: Metadata = {
  title: "Delete account · zetteldraw",
  description: "Request deletion of your Zetteldraw account and the notes stored on our servers.",
};

export default function DeleteAccountPage() {
  const mailto = `mailto:${DELETE_EMAIL}?subject=${encodeURIComponent(DELETE_SUBJECT)}`;

  return (
    <main className="mx-auto max-w-2xl px-5 py-12 text-sm leading-relaxed text-foreground">
      <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">zetteldraw</p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight">Delete your Zetteldraw account</h1>
      <p className="mt-3 text-muted-foreground">
        App: <strong className="font-medium text-foreground">Zetteldraw</strong>. Developer:{" "}
        <strong className="font-medium text-foreground">Elton Lin</strong>. No sign-in is required to
        read this page.
      </p>

      <section className="mt-10 space-y-3 rounded-md border border-border bg-muted/30 p-4">
        <h2 className="text-lg font-semibold">How to request deletion</h2>
        <ol className="list-decimal space-y-3 pl-5">
          <li>
            Email{" "}
            <a href={mailto} className="underline underline-offset-2">
              {DELETE_EMAIL}
            </a>{" "}
            from the Google account you use to sign in to Zetteldraw.
          </li>
          <li>
            Subject: <strong className="font-medium">{DELETE_SUBJECT}</strong>.
          </li>
          <li>We confirm within 7 days and delete within 30 days.</li>
        </ol>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">Data we delete</h2>
        <ul className="list-disc space-y-1 pl-5">
          <li>Your Google account id and email</li>
          <li>All synced notebooks, pages, and page links</li>
          <li>Ink files and thumbnails</li>
          <li>Device and live-writer records on our servers</li>
        </ul>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">Data we keep</h2>
        <p>
          Nothing. Server logs and provider backups may persist for up to 30 days.
        </p>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-lg font-semibold">What stays on your device</h2>
        <p>
          Notes stored on the device, and the copy in Documents/zetteldraw, stay there until you uninstall
          the app or delete that folder. Signing out stops sync. It does not delete data on our servers.
        </p>
      </section>

      <p className="mt-10">
        <Link href="/privacy" className="underline underline-offset-2">
          Privacy policy
        </Link>
        {" · "}
        <Link href="/" className="underline underline-offset-2">
          Web companion
        </Link>
      </p>
    </main>
  );
}
