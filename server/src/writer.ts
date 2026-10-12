import type pg from "pg";
import type { SyncScope } from "./sync.js";

/** How long a Hand off window stays open for a new device to claim. */
export const HANDOFF_MS = 48 * 60 * 60 * 1000;

export class NotLiveWriter extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotLiveWriter";
  }
}

export interface WriterStatus {
  /** Short verb for UI: live | handoff_open | none */
  state: "live" | "handoff_open" | "none";
  device_id: string | null;
  device_label: string;
  claimed_at: number | null;
  last_seen_at: number | null;
  handoff_until: number | null;
  handoff_open: boolean;
  /** True when web may offer Hand off (backup ready + currently live, not already open). */
  can_hand_off: boolean;
  backup_ready: boolean;
  note: string;
}

interface Row {
  user_id: string;
  device_id: string;
  device_label: string;
  claimed_at: number;
  last_seen_at: number;
  handoff_until: number | null;
}

async function load(pool: pg.Pool | pg.PoolClient, userId: string): Promise<Row | null> {
  const res = await pool.query<Row>("SELECT * FROM live_writers WHERE user_id = $1", [userId]);
  return res.rows[0] ?? null;
}

function handoffOpen(row: Row | null, now: number): boolean {
  return row !== null && row.handoff_until !== null && row.handoff_until > now;
}

export async function getWriterStatus(
  pool: pg.Pool,
  userId: string,
  backupReady: boolean,
  now = Date.now(),
): Promise<WriterStatus> {
  const row = await load(pool, userId);
  const open = handoffOpen(row, now);
  if (!row) {
    return {
      state: "none",
      device_id: null,
      device_label: "",
      claimed_at: null,
      last_seen_at: null,
      handoff_until: null,
      handoff_open: false,
      can_hand_off: false,
      backup_ready: backupReady,
      note: "No live device yet. The first Boox that signs in and syncs becomes the live writer.",
    };
  }
  if (open) {
    return {
      state: "handoff_open",
      device_id: row.device_id,
      device_label: row.device_label || shortId(row.device_id),
      claimed_at: row.claimed_at,
      last_seen_at: row.last_seen_at,
      handoff_until: row.handoff_until,
      handoff_open: true,
      can_hand_off: false,
      backup_ready: backupReady,
      note: "Hand off is open. Install the app on the new device, sign in with the same Google account, and wait for sync. The old device cannot upload until you cancel or a new device claims.",
    };
  }
  return {
    state: "live",
    device_id: row.device_id,
    device_label: row.device_label || shortId(row.device_id),
    claimed_at: row.claimed_at,
    last_seen_at: row.last_seen_at,
    handoff_until: null,
    handoff_open: false,
    can_hand_off: backupReady,
    backup_ready: backupReady,
    note: backupReady
      ? "This account has one live Boox. Hand off before moving to a new device (Play cutover)."
      : "Cloud backup is not ready yet. Finish sync on the live Boox before you can Hand off.",
  };
}

/** Open a hand-off window. Fails if backup is not ready or handoff already open. */
export async function handOff(
  pool: pg.Pool,
  userId: string,
  backupReady: boolean,
  now = Date.now(),
): Promise<WriterStatus> {
  const status = await getWriterStatus(pool, userId, backupReady, now);
  if (status.state !== "live") {
    throw new Error(status.state === "handoff_open" ? "Hand off is already open." : "No live device to hand off from.");
  }
  if (!status.backup_ready) {
    throw new Error("Cloud backup is not ready. Wait until the backup banner says ready.");
  }
  const until = now + HANDOFF_MS;
  await pool.query(`UPDATE live_writers SET handoff_until = $2 WHERE user_id = $1`, [userId, until]);
  return getWriterStatus(pool, userId, backupReady, now);
}

export async function cancelHandOff(pool: pg.Pool, userId: string, backupReady: boolean, now = Date.now()) {
  await pool.query(`UPDATE live_writers SET handoff_until = NULL WHERE user_id = $1`, [userId]);
  return getWriterStatus(pool, userId, backupReady, now);
}

/**
 * Claim (or refresh) live-writer status for this device.
 * Allowed when: no writer yet, already this device, or handoff is open.
 */
export async function claimWriter(
  pool: pg.Pool,
  userId: string,
  deviceId: string,
  deviceLabel: string,
  now = Date.now(),
): Promise<{ claimed: boolean; status: "already" | "claimed" | "first" }> {
  if (!deviceId || deviceId.length < 8) {
    throw new Error("device_id is required");
  }
  const label = (deviceLabel || "").slice(0, 120);
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock(7144)");
    const row = await load(client, userId);
    if (!row) {
      await client.query(
        `INSERT INTO live_writers (user_id, device_id, device_label, claimed_at, last_seen_at, handoff_until)
         VALUES ($1, $2, $3, $4, $4, NULL)`,
        [userId, deviceId, label, now],
      );
      await client.query("COMMIT");
      return { claimed: true, status: "first" };
    }
    const open = handoffOpen(row, now);
    if (open) {
      if (row.device_id === deviceId) {
        await client.query("COMMIT");
        throw new NotLiveWriter(
          "Hand off is open. Cancel Hand off on the web to keep writing from this device, or finish signing in on the new device.",
        );
      }
      await client.query(
        `UPDATE live_writers
         SET device_id = $2, device_label = $3, claimed_at = $4, last_seen_at = $4, handoff_until = NULL
         WHERE user_id = $1`,
        [userId, deviceId, label || shortId(deviceId), now],
      );
      await client.query("COMMIT");
      return { claimed: true, status: "claimed" };
    }
    if (row.device_id === deviceId) {
      await client.query(
        `UPDATE live_writers SET last_seen_at = $2, device_label = COALESCE(NULLIF($3, ''), device_label)
         WHERE user_id = $1`,
        [userId, now, label],
      );
      await client.query("COMMIT");
      return { claimed: true, status: "already" };
    }
    await client.query("COMMIT");
    throw new NotLiveWriter(
      "This device is not the live writer. On the web companion open Settings → Hand off to a new device first.",
    );
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

/**
 * Gate for /sync/push. Pull is always allowed.
 * Device-token (unowned) sync skips the lease (legacy); Google users must be the live writer.
 */
export async function assertCanPush(
  pool: pg.Pool,
  scope: SyncScope,
  deviceId: string,
  now = Date.now(),
): Promise<void> {
  if (scope.kind !== "user") {
    return;
  }
  const row = await load(pool, scope.userId);
  if (!row) {
    // First push claims — SyncEngine should call claim-writer first; allow empty-library bootstrap.
    if (!deviceId) {
      throw new NotLiveWriter("device_id is required to become the live writer.");
    }
    await claimWriter(pool, scope.userId, deviceId, "", now);
    return;
  }
  if (handoffOpen(row, now)) {
    throw new NotLiveWriter(
      "Hand off is open. Sign in on the new device so it can claim live-writer status (or cancel Hand off on the web).",
    );
  }
  if (row.device_id !== deviceId) {
    throw new NotLiveWriter(
      "This device is not the live writer. Hand off from the web companion before using a new Boox.",
    );
  }
  await pool.query(`UPDATE live_writers SET last_seen_at = $2 WHERE user_id = $1`, [scope.userId, now]);
}

function shortId(id: string): string {
  return id.length <= 8 ? id : id.slice(0, 8);
}
