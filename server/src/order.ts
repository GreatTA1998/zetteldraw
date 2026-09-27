import { generateKeyBetween } from "fractional-indexing";

/**
 * Fractional position keys, same algorithm and alphabet as the app's Positions.java
 * (base-62, byte-order sortable, matching Postgres COLLATE "C").
 */
export function positionBetween(a: string | null, b: string | null): string {
  return generateKeyBetween(a, b);
}

export interface Placed {
  id: string;
  position: string;
}

export type MoveTarget =
  /** Append after the last page. */
  | { kind: "end" }
  /** Place first. */
  | { kind: "start" }
  | { kind: "after"; id: string };

export type MovePlan = { kind: "noop" } | { kind: "write"; position: string } | { kind: "bad_anchor" };

function compare(a: Placed, b: Placed): number {
  if (a.position !== b.position) {
    return a.position < b.position ? -1 : 1;
  }
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/**
 * Where to put `page` in `targetPages` (the live pages of the target notebook, in
 * any order; may include `page` itself). Only the moved page gets a new key.
 *
 * Two pages can share a key (two devices appending offline). A drop between two
 * such pages goes after the whole tie group instead, since no key sorts between them.
 */
export function planMove(
  targetPages: Placed[],
  page: Placed & { inTarget: boolean },
  target: MoveTarget,
): MovePlan {
  const others = targetPages.filter((p) => p.id !== page.id).sort(compare);
  let prevIndex: number;
  if (target.kind === "end") {
    prevIndex = others.length - 1;
  } else if (target.kind === "start") {
    prevIndex = -1;
  } else {
    prevIndex = others.findIndex((p) => p.id === target.id);
    if (prevIndex < 0) {
      return { kind: "bad_anchor" };
    }
  }
  const prev = prevIndex >= 0 ? others[prevIndex] : null;
  let next: Placed | null = others[prevIndex + 1] ?? null;

  if (page.inTarget) {
    const afterPrev = prev === null || compare(prev, page) < 0;
    const beforeNext = next === null || compare(page, next) < 0;
    if (afterPrev && beforeNext) {
      return { kind: "noop" };
    }
  }

  if (prev && next && next.position <= prev.position) {
    next = others.slice(prevIndex + 1).find((p) => p.position > prev.position) ?? null;
  }
  return { kind: "write", position: positionBetween(prev?.position ?? null, next?.position ?? null) };
}
