import type { BoardRow, Incoming, LogRow, NotebookPush } from "./lww.js";

export class BadRequest extends Error {}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SHA256 = /^[0-9a-f]{64}$/;
const POSITION = /^[0-9A-Za-z]+$/;

export interface PushBody {
  schema_version: number;
  device_id: string;
  notebooks: NotebookPush[];
  boards: Incoming<BoardRow>[];
  /** Whole notebook logs. Absent on an older body, which means none. */
  logs: Incoming<LogRow>[];
  blobs: Record<string, string>;
}

type Json = Record<string, unknown>;

function obj(v: unknown, what: string): Json {
  if (typeof v !== "object" || v === null || Array.isArray(v)) {
    throw new BadRequest(`${what} must be an object`);
  }
  return v as Json;
}

function int(o: Json, key: string, what: string): number {
  const v = o[key];
  if (typeof v !== "number" || !Number.isSafeInteger(v) || v < 0) {
    throw new BadRequest(`${what}.${key} must be a non-negative integer`);
  }
  return v;
}

function intOrNull(o: Json, key: string, what: string): number | null {
  return o[key] === null || o[key] === undefined ? null : int(o, key, what);
}

function str(o: Json, key: string, what: string, pattern?: RegExp): string {
  const v = o[key];
  if (typeof v !== "string" || (pattern && !pattern.test(v))) {
    throw new BadRequest(`${what}.${key} is invalid`);
  }
  return v;
}

function strOrNull(o: Json, key: string, what: string, pattern?: RegExp): string | null {
  return o[key] === null || o[key] === undefined ? null : str(o, key, what, pattern);
}

function array(o: Json, key: string): unknown[] {
  const v = o[key] ?? [];
  if (!Array.isArray(v)) {
    throw new BadRequest(`${key} must be an array`);
  }
  return v;
}

export function parsePushBody(raw: unknown): PushBody {
  const body = obj(raw, "body");
  const notebooks = array(body, "notebooks").map((v, i) => {
    const w = `notebooks[${i}]`;
    const o = obj(v, w);
    return {
      id: str(o, "id", w, UUID).toLowerCase(),
      title: str(o, "title", w),
      position: str(o, "position", w, POSITION),
      parent_id: Object.prototype.hasOwnProperty.call(o, "parent_id")
        ? strOrNull(o, "parent_id", w, UUID)?.toLowerCase() ?? null
        : undefined,
      created_at: int(o, "created_at", w),
      updated_at: int(o, "updated_at", w),
      deleted_at: intOrNull(o, "deleted_at", w),
      base_rev: int(o, "base_rev", w),
    };
  });
  const boards = array(body, "boards").map((v, i) => {
    const w = `boards[${i}]`;
    const o = obj(v, w);
    return {
      id: str(o, "id", w, UUID).toLowerCase(),
      notebook_id: strOrNull(o, "notebook_id", w, UUID)?.toLowerCase() ?? null,
      position: str(o, "position", w, POSITION),
      ink_hash: strOrNull(o, "ink_hash", w, SHA256),
      ink_bytes: int(o, "ink_bytes", w),
      thumb_hash: strOrNull(o, "thumb_hash", w, SHA256),
      conflict_of: strOrNull(o, "conflict_of", w, UUID)?.toLowerCase() ?? null,
      created_at: int(o, "created_at", w),
      updated_at: int(o, "updated_at", w),
      deleted_at: intOrNull(o, "deleted_at", w),
      base_rev: int(o, "base_rev", w),
    };
  });
  const logs = array(body, "logs").map((v, i) => {
    const w = `logs[${i}]`;
    const o = obj(v, w);
    return {
      id: str(o, "id", w, UUID).toLowerCase(),
      notebook_id: strOrNull(o, "notebook_id", w, UUID)?.toLowerCase() ?? null,
      ink_hash: strOrNull(o, "ink_hash", w, SHA256),
      ink_bytes: int(o, "ink_bytes", w),
      slice_height: (() => {
        const height = int(o, "slice_height", w);
        if (height < 1) {
          throw new BadRequest(`${w}.slice_height is invalid`);
        }
        return height;
      })(),
      conflict_of: strOrNull(o, "conflict_of", w, UUID)?.toLowerCase() ?? null,
      created_at: int(o, "created_at", w),
      updated_at: int(o, "updated_at", w),
      deleted_at: intOrNull(o, "deleted_at", w),
      base_rev: int(o, "base_rev", w),
    };
  });
  const blobsRaw = obj(body.blobs ?? {}, "blobs");
  const blobs: Record<string, string> = {};
  for (const [hash, b64] of Object.entries(blobsRaw)) {
    if (!SHA256.test(hash) || typeof b64 !== "string") {
      throw new BadRequest(`blobs.${hash} is invalid`);
    }
    blobs[hash] = b64;
  }
  return {
    schema_version: int(body, "schema_version", "body"),
    device_id: typeof body.device_id === "string" ? body.device_id : "",
    notebooks,
    boards,
    logs,
    blobs,
  };
}
