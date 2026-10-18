/**
 * Row apply for sync push. Product model is **single live writer**
 * (lease in writer.ts): live Boox uploads; others read-only until Hand off.
 * This is not CRDT / peer merge.
 *
 * Main path: base_rev matches → apply. Idempotent same-body retry → noop.
 * Exception path (Hand off race, lost-device, buggy history): stale base_rev
 * uses updated_at; loser ink may be shelved whole (conflict_of). Same rule
 * as app Lww.java. Refuse-shorter on logs is backup integrity, not co-edit.
 */

export interface NotebookRow {
  id: string;
  title: string;
  position: string;
  /** Null is top-level. Absent on an incoming push means "leave the stored parent". */
  parent_id: string | null;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
  rev: number;
}

export interface BoardRow {
  id: string;
  notebook_id: string | null;
  position: string;
  ink_hash: string | null;
  ink_bytes: number;
  thumb_hash: string | null;
  conflict_of: string | null;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
  rev: number;
}

export interface LogRow {
  id: string;
  notebook_id: string | null;
  ink_hash: string | null;
  ink_bytes: number;
  slice_height: number;
  conflict_of: string | null;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
  rev: number;
}

export interface LinkRow {
  id: string;
  source_id: string;
  target_id: string;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
  rev: number;
}

export type Incoming<T> = Omit<T, "rev"> & { base_rev: number };

export type Status = "applied" | "conflict_won" | "conflict_lost";

export interface Decision<T> {
  status: Status;
  /** Row content to store as the main row with a fresh rev. */
  write: Omit<T, "rev"> | null;
  /** Keep the stored row but give it a fresh rev so every device pulls it again. */
  restamp: boolean;
  /** Conflict copy to insert with a fresh rev. A log copy is the other log, whole. */
  copy: Omit<T, "rev"> | null;
}

function stripBase<T>(incoming: Incoming<T>): Omit<T, "rev"> {
  const { base_rev: _ignored, ...row } = incoming;
  return row as unknown as Omit<T, "rev">;
}

function withoutRev<T extends object>(row: T): Omit<T, "rev"> {
  const { rev: _rev, ...rest } = row as T & { rev?: number };
  return rest as Omit<T, "rev">;
}

/** Stale-rev LWW shelf for notebook_log ink (winner write / loser conflict_of). */
function inkConflictDecision<
  T extends { id: string; updated_at: number; ink_hash: string | null; deleted_at: number | null },
>(existing: T, row: Omit<T, "rev">, newId: () => string): Decision<T> {
  const incomingWins = row.updated_at > existing.updated_at;
  const winner = incomingWins ? row : existing;
  const loser = incomingWins ? existing : row;
  const copy: Omit<T, "rev"> | null = needsConflictCopy(loser, winner)
    ? ({ ...withoutRev(loser), id: newId(), conflict_of: existing.id, deleted_at: null } as unknown as Omit<T, "rev">)
    : null;
  return incomingWins
    ? { status: "conflict_won", write: row, restamp: false, copy }
    : { status: "conflict_lost", write: null, restamp: true, copy };
}

function sameNotebook(a: Omit<NotebookRow, "rev">, b: Omit<NotebookRow, "rev">): boolean {
  return (
    a.title === b.title &&
    a.position === b.position &&
    a.parent_id === b.parent_id &&
    a.deleted_at === b.deleted_at &&
    a.updated_at === b.updated_at
  );
}

/** Shelve loser ink when it still has a body the winner does not share. */
export function needsConflictCopy(
  loser: { ink_hash: string | null; deleted_at: number | null },
  winner: { ink_hash: string | null; deleted_at: number | null },
): boolean {
  if (loser.ink_hash === null || loser.deleted_at !== null) {
    return false;
  }
  return winner.deleted_at !== null || loser.ink_hash !== winner.ink_hash;
}

function sameLog(a: Omit<LogRow, "rev">, b: Omit<LogRow, "rev">): boolean {
  return (
    a.notebook_id === b.notebook_id &&
    a.ink_hash === b.ink_hash &&
    a.ink_bytes === b.ink_bytes &&
    a.slice_height === b.slice_height &&
    a.conflict_of === b.conflict_of &&
    a.deleted_at === b.deleted_at &&
    a.updated_at === b.updated_at
  );
}

/**
 * True when a live incoming log would shrink the live primary by ink_bytes.
 * Length is monotonic: truncated clients must not clobber a longer cloud log.
 */
function incomingShrinksLog(existing: LogRow, incoming: Omit<LogRow, "rev">): boolean {
  if (existing.deleted_at !== null || incoming.deleted_at !== null) {
    return false;
  }
  if (existing.ink_hash === null || incoming.ink_hash === null) {
    return false;
  }
  if (existing.ink_hash === incoming.ink_hash) {
    return false;
  }
  return incoming.ink_bytes < existing.ink_bytes;
}

/**
 * Whole notebook log apply. Nothing here splits a stroke.
 *
 * Main: matching base_rev from the live writer. Exception: stale base_rev
 * shelves the loser log entire (Hand off / history — not day-to-day co-edit).
 * A shorter live incoming never replaces a longer live primary (restamp +
 * shelf short) — backup integrity under the lease.
 */
export function resolveLog(
  existing: LogRow | null,
  incoming: Incoming<LogRow>,
  newId: () => string,
): Decision<LogRow> {
  const row = stripBase(incoming);
  if (existing !== null && incomingShrinksLog(existing, row)) {
    const copy = needsConflictCopy(row, existing)
      ? { ...withoutRev(row), id: newId(), conflict_of: existing.id, deleted_at: null }
      : null;
    return { status: "conflict_lost", write: null, restamp: true, copy };
  }
  if (existing === null || incoming.base_rev === existing.rev) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  if (sameLog(existing, row)) {
    return { status: "applied", write: null, restamp: false, copy: null };
  }
  return inkConflictDecision(existing, row, newId);
}

function sameLink(a: Omit<LinkRow, "rev">, b: Omit<LinkRow, "rev">): boolean {
  return (
    a.source_id === b.source_id &&
    a.target_id === b.target_id &&
    a.deleted_at === b.deleted_at &&
    a.updated_at === b.updated_at
  );
}

/** Page-link apply (no ink shelf). Main = matching base_rev; stale rev = exception clocks. */
export function resolveLink(existing: LinkRow | null, incoming: Incoming<LinkRow>): Decision<LinkRow> {
  const row = stripBase(incoming);
  if (existing === null || incoming.base_rev === existing.rev) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  if (sameLink(existing, row)) {
    return { status: "applied", write: null, restamp: false, copy: null };
  }
  return row.updated_at > existing.updated_at
    ? { status: "conflict_won", write: row, restamp: false, copy: null }
    : { status: "conflict_lost", write: null, restamp: true, copy: null };
}

export type NotebookPush = Omit<Incoming<NotebookRow>, "parent_id"> & { parent_id?: string | null };

export function resolveNotebook(
  existing: NotebookRow | null,
  incoming: NotebookPush,
): Decision<NotebookRow> {
  const row = stripBase(incoming) as Omit<NotebookRow, "rev"> & { parent_id?: string | null };
  // A flat client that does not send parent_id must not clear the tree.
  if (row.parent_id === undefined) {
    row.parent_id = existing?.parent_id ?? null;
  }
  if (existing === null) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  if (sameNotebook(existing, row)) {
    return { status: "applied", write: null, restamp: false, copy: null };
  }
  // Untouched install placeholders (updated_at === 0) must never overwrite a
  // server notebook. Fresh devices seed Comedy/Journal locally and used to
  // push them before pull with base_rev matching a never-edited server seed.
  if (row.updated_at === 0) {
    return { status: "conflict_lost", write: null, restamp: true, copy: null };
  }
  if (incoming.base_rev === existing.rev) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  return row.updated_at > existing.updated_at
    ? { status: "conflict_won", write: row, restamp: false, copy: null }
    : { status: "conflict_lost", write: null, restamp: true, copy: null };
}
