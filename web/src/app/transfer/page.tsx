import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Hand off · zetteldraw",
  description:
    "After Prepare transfer on your Boox, sign in here on a computer and tap Hand off.",
};

/**
 * Static / SSR instructions for device cutover. Kept free of client-only shells so
 * Boox system browsers (and users who accidentally open a link from Settings) still
 * see real text instead of a CSR loading spinner that looks blank on e-ink.
 */
export default function TransferPage() {
  return (
    <main className="mx-auto max-w-2xl px-5 py-12 text-sm leading-relaxed text-foreground">
      <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">zetteldraw</p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight">Move to a new device</h1>
      <p className="mt-3 text-muted-foreground">
        Prepare transfer happens <strong className="font-medium text-foreground">on the Boox app</strong>
        , not in this browser. This page is only for the computer step after that.
      </p>

      <ol className="mt-10 list-decimal space-y-4 pl-5">
        <li>
          On the <strong className="font-medium">live Boox</strong>: Settings →{" "}
          <strong className="font-medium">Prepare transfer</strong>. Wait for the on-device success
          message (sync + server ack). Do not use the Boox browser for that step.
        </li>
        <li>
          On a <strong className="font-medium">computer</strong> (Chrome / Safari / Firefox): open the{" "}
          <Link href="/" className="underline underline-offset-2">
            web companion
          </Link>
          , sign in with the same Google account, then Settings →{" "}
          <strong className="font-medium">Move to a new device</strong> →{" "}
          <strong className="font-medium">Hand off</strong>.
        </li>
        <li>Install Zetteldraw on the new Boox, sign in, wait for sync to finish, then confirm ink.</li>
      </ol>

      <section className="mt-10 space-y-3 rounded-md border border-border bg-muted/30 p-4">
        <h2 className="text-base font-semibold">If the Boox browser looked blank</h2>
        <p>
          The main site needs JavaScript to finish loading. Prefer a computer for Hand off. Prepare
          transfer itself never needs a browser — if Settings opened this page by mistake, go back to
          the app and tap <strong className="font-medium">Prepare transfer</strong> (not a Links
          button).
        </p>
      </section>

      <section className="mt-8 space-y-3">
        <h2 className="text-base font-semibold">Old device is gone</h2>
        <p>
          On the computer companion: Settings → Move to a new device →{" "}
          <em>I no longer have the old device</em> (confirm). That path skips Prepare transfer.
        </p>
      </section>

      <p className="mt-10">
        <Link href="/" className="underline underline-offset-2">
          Open web companion
        </Link>
        {" · "}
        <Link href="/privacy" className="underline underline-offset-2">
          Privacy
        </Link>
      </p>
    </main>
  );
}
