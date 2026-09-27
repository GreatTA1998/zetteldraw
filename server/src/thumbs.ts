import { decodeInk } from "./ink.js";
import { renderPng, type PageSize, DEFAULT_PAGE } from "./render.js";
import type { InkStorage, ObjectStore } from "./storage.js";

export type ImageKind = "thumb" | "render";

/** Output widths in pixels. `render` is the page at device resolution. */
export const IMAGE_WIDTH: Record<ImageKind, number> = { thumb: 480, render: DEFAULT_PAGE.width };

/**
 * Derived images are keyed by the ink hash, not stored on the board row: ink is
 * immutable per hash, so an image never goes stale, identical pages share one, and
 * the server never has to write `thumb_hash` into a row the device also writes.
 */
export function imageKey(kind: ImageKind, inkHash: string): string {
  return `${kind}/${inkHash}.png`;
}

export class Thumbnailer {
  private queue: Promise<void> = Promise.resolve();
  private readonly pending = new Set<string>();
  private readonly inflight = new Map<string, Promise<Buffer | null>>();

  constructor(
    private readonly store: InkStorage & ObjectStore,
    private readonly page: PageSize = DEFAULT_PAGE,
    private readonly onError: (err: unknown, inkHash: string) => void = () => {},
  ) {}

  /** Returns the PNG, rendering and storing it on a miss; null when the ink itself is missing. */
  async image(kind: ImageKind, inkHash: string): Promise<Buffer | null> {
    const key = imageKey(kind, inkHash);
    const existing = this.inflight.get(key);
    if (existing) {
      return existing;
    }
    const job = (async () => {
      const cached = await this.store.getObject(key);
      if (cached) {
        return cached;
      }
      if (!(await this.store.has(inkHash))) {
        return null;
      }
      const png = renderPng(decodeInk(await this.store.get(inkHash)), IMAGE_WIDTH[kind], this.page);
      await this.store.putObject(key, png, "image/png");
      return png;
    })().finally(() => this.inflight.delete(key));
    this.inflight.set(key, job);
    return job;
  }

  /** Renders the thumbnail if it does not exist yet. Returns true when one was rendered. */
  async ensureThumb(inkHash: string): Promise<boolean> {
    if (await this.store.hasObject(imageKey("thumb", inkHash))) {
      return false;
    }
    return (await this.image("thumb", inkHash)) !== null;
  }

  /** Background thumbnail rendering after a push; serial so a large push cannot starve requests. */
  enqueue(inkHash: string): void {
    if (this.pending.has(inkHash)) {
      return;
    }
    this.pending.add(inkHash);
    this.queue = this.queue
      .then(() => this.ensureThumb(inkHash))
      .then(
        () => undefined,
        (err) => this.onError(err, inkHash),
      )
      .finally(() => this.pending.delete(inkHash));
  }

  /** Resolves once everything enqueued so far has been handled. */
  idle(): Promise<void> {
    return this.queue;
  }
}
