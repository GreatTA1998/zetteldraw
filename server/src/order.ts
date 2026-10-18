import { generateKeyBetween } from "fractional-indexing";

/**
 * Fractional position keys, same algorithm and alphabet as the app's Positions.java
 * (base-62, byte-order sortable, matching Postgres COLLATE "C").
 *
 * Page Move/order lives on Boox Ready `.zdl` only — web Move is 405; this helper
 * remains for demo/seed notebook and historical board position keys.
 */
export function positionBetween(a: string | null, b: string | null): string {
  return generateKeyBetween(a, b);
}
