/**
 * Last-write-wins per row. Same rule as the app's Lww.java:
 * - a push whose base_rev matches the stored rev applies directly;
 * - otherwise the newer updated_at wins and the stored row wins a tie;
 * - a losing board that still carries ink the winner lacks is kept as a
 *   hidden conflict copy (new id, conflict_of = original id).
 */

export interface NotebookRow {
  id: string;
  title: string;
  position: string;
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

function withoutRev(row: Omit<BoardRow, "rev">): Omit<BoardRow, "rev"> {
  const { rev: _rev, ...rest } = row as BoardRow;
  return rest;
}

function sameBoard(a: Omit<BoardRow, "rev">, b: Omit<BoardRow, "rev">): boolean {
  return (
    a.notebook_id === b.notebook_id &&
    a.position === b.position &&
    a.ink_hash === b.ink_hash &&
    a.conflict_of === b.conflict_of &&
    a.deleted_at === b.deleted_at &&
    a.updated_at === b.updated_at
  );
}

function sameNotebook(a: Omit<NotebookRow, "rev">, b: Omit<NotebookRow, "rev">): boolean {
  return (
    a.title === b.title &&
    a.position === b.position &&
    a.deleted_at === b.deleted_at &&
    a.updated_at === b.updated_at
  );
}

export function needsConflictCopy(
  loser: Omit<BoardRow, "rev">,
  winner: Omit<BoardRow, "rev">,
): boolean {
  if (loser.ink_hash === null || loser.deleted_at !== null) {
    return false;
  }
  return winner.deleted_at !== null || loser.ink_hash !== winner.ink_hash;
}

export function resolveBoard(
  existing: BoardRow | null,
  incoming: Incoming<BoardRow>,
  newId: () => string,
): Decision<BoardRow> {
  const row = stripBase(incoming);
  if (existing === null || incoming.base_rev === existing.rev) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  if (sameBoard(existing, row)) {
    // A retried push whose first attempt already landed.
    return { status: "applied", write: null, restamp: false, copy: null };
  }
  const incomingWins = row.updated_at > existing.updated_at;
  const winner = incomingWins ? row : existing;
  const loser = incomingWins ? existing : row;
  const copy = needsConflictCopy(loser, winner)
    ? { ...withoutRev(loser), id: newId(), conflict_of: existing.id, deleted_at: null }
    : null;
  return incomingWins
    ? { status: "conflict_won", write: row, restamp: false, copy }
    : { status: "conflict_lost", write: null, restamp: true, copy };
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

function needsLogCopy(loser: Omit<LogRow, "rev">, winner: Omit<LogRow, "rev">): boolean {
  if (loser.ink_hash === null || loser.deleted_at !== null) {
    return false;
  }
  return winner.deleted_at !== null || loser.ink_hash !== winner.ink_hash;
}

/**
 * Last-write-wins for a whole notebook log. The loser is shelved entire.
 * Nothing here splits a stroke.
 */
export function resolveLog(
  existing: LogRow | null,
  incoming: Incoming<LogRow>,
  newId: () => string,
): Decision<LogRow> {
  const row = stripBase(incoming);
  if (existing === null || incoming.base_rev === existing.rev) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  if (sameLog(existing, row)) {
    return { status: "applied", write: null, restamp: false, copy: null };
  }
  const incomingWins = row.updated_at > existing.updated_at;
  const winner = incomingWins ? row : existing;
  const loser = incomingWins ? existing : row;
  const copy = needsLogCopy(loser, winner)
    ? { ...withoutLogRev(loser), id: newId(), conflict_of: existing.id, deleted_at: null }
    : null;
  return incomingWins
    ? { status: "conflict_won", write: row, restamp: false, copy }
    : { status: "conflict_lost", write: null, restamp: true, copy };
}

function withoutLogRev(row: Omit<LogRow, "rev">): Omit<LogRow, "rev"> {
  const { rev: _rev, ...rest } = row as LogRow;
  return rest;
}

export function resolveNotebook(
  existing: NotebookRow | null,
  incoming: Incoming<NotebookRow>,
): Decision<NotebookRow> {
  const row = stripBase(incoming);
  if (existing === null || incoming.base_rev === existing.rev) {
    return { status: "applied", write: row, restamp: false, copy: null };
  }
  if (sameNotebook(existing, row)) {
    return { status: "applied", write: null, restamp: false, copy: null };
  }
  return row.updated_at > existing.updated_at
    ? { status: "conflict_won", write: row, restamp: false, copy: null }
    : { status: "conflict_lost", write: null, restamp: true, copy: null };
}
