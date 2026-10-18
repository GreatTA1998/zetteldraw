/**
 * Ready notebook ink log ({@code .zdl}) — structure index only.
 * Matches {@code NotebookPaper.replay} header + EXTEND/TEAR slice edits;
 * stroke ops (APPEND/DELETE/TRANSLATE) are skipped by length.
 */

const MAGIC0 = 0x5a; // Z
const MAGIC1 = 0x44; // D
const MAGIC2 = 0x4c; // L
const VERSION = 1;
const OP_APPEND = 1;
const OP_DELETE = 2;
const OP_TRANSLATE = 3;
const OP_TEAR = 4;
const OP_EXTEND = 5;
const TEAR_WIPE = 2;

export interface ZdlSlice {
  id: string;
  height: number;
}

export class ZdlFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ZdlFormatError";
  }
}

function readId(buf: Buffer, offset: number): { id: string; next: number } {
  if (offset + 2 > buf.length) {
    throw new ZdlFormatError("truncated id");
  }
  const n = buf.readUInt16BE(offset);
  const start = offset + 2;
  if (n > buf.length - start) {
    throw new ZdlFormatError("truncated id body");
  }
  return { id: buf.subarray(start, start + n).toString("utf8"), next: start + n };
}

/**
 * Live page order for a notebook: header slices, then EXTEND appends,
 * minus TEAR deletes (wipe keeps the empty slice).
 */
export function readSliceIndex(bytes: Buffer): ZdlSlice[] {
  if (bytes.length < 12 || bytes[0] !== MAGIC0 || bytes[1] !== MAGIC1 || bytes[2] !== MAGIC2) {
    throw new ZdlFormatError("not a notebook ink log");
  }
  if (bytes[3] !== VERSION) {
    throw new ZdlFormatError(`unsupported notebook log ${bytes[3]}`);
  }
  let o = 4;
  o += 4; // sliceHeight — frozen default; per-slice heights follow
  const sliceCount = bytes.readInt32BE(o);
  o += 4;
  if (sliceCount < 0 || sliceCount > 100_000) {
    throw new ZdlFormatError(`bad slice count ${sliceCount}`);
  }
  const slices: ZdlSlice[] = [];
  for (let i = 0; i < sliceCount; i++) {
    if (o + 4 > bytes.length) {
      throw new ZdlFormatError("truncated slice height");
    }
    const height = bytes.readInt32BE(o);
    o += 4;
    if (height <= 0) {
      throw new ZdlFormatError("bad slice height");
    }
    const id = readId(bytes, o);
    o = id.next;
    slices.push({ id: id.id, height });
  }

  while (o + 5 <= bytes.length) {
    const op = bytes[o];
    o += 1;
    const len = bytes.readInt32BE(o);
    o += 4;
    if (len < 0 || o + len > bytes.length) {
      // Crash mid-append: keep good prefix (same as NotebookPaper.replay).
      break;
    }
    const payload = bytes.subarray(o, o + len);
    o += len;
    if (op === OP_APPEND || op === OP_DELETE || op === OP_TRANSLATE) {
      continue;
    }
    if (op === OP_EXTEND) {
      let p = 0;
      const id = readId(payload, p);
      p = id.next;
      if (p + 4 > payload.length) {
        throw new ZdlFormatError("truncated extend");
      }
      const height = payload.readInt32BE(p);
      if (height <= 0) {
        throw new ZdlFormatError("bad extend height");
      }
      slices.push({ id: id.id, height });
      continue;
    }
    if (op === OP_TEAR) {
      if (payload.length < 5) {
        throw new ZdlFormatError("truncated tear");
      }
      const kind = payload[0];
      const slice = payload.readInt32BE(1);
      if (slice < 0 || slice >= slices.length) {
        continue;
      }
      if (kind !== TEAR_WIPE) {
        slices.splice(slice, 1);
      }
      continue;
    }
    throw new ZdlFormatError(`bad log op ${op}`);
  }
  return slices;
}
