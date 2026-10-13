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
  /** Live Boox called Prepare transfer (final sync + release ack). */
  transfer_prepared: boolean;
  transfer_prepared_at: number | null;
  /** Web secondary path: user confirmed they no longer have the old device. */
  lost_device_ack: boolean;
  lost_device_ack_at: number | null;
  /**
   * True when web may offer Hand off: backup ready + live +
   * (Prepare transfer ack OR lost-device ack) + not already open.
   */
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
  transfer_prepared_at: number | null;
  lost_device_ack_at: number | null;
}

async function load(pool: pg.Pool | pg.PoolClient, userId: string): Promise<Row | null> {
  const res = await pool.query<Row>("SELECT * FROM live_writers WHERE user_id = $1", [userId]);
  return res.rows[0] ?? null;
}

function handoffOpen(row: Row | null, now: number): boolean {
  return row !== null && row.handoff_until !== null && row.handoff_until > now;
}

function releaseReady(row: Row): boolean {
  return row.transfer_prepared_at != null || row.lost_device_ack_at != null;
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
      transfer_prepared: false,
      transfer_prepared_at: null,
      lost_device_ack: false,
      lost_device_ack_at: null,
      can_hand_off: false,
      backup_ready: backupReady,
      note: "No live device yet. The first Boox that signs in and syncs becomes the live writer.",
    };
  }
  const prepared = row.transfer_prepared_at != null;
  const lostAck = row.lost_device_ack_at != null;
  if (open) {
    return {
      state: "handoff_open",
      device_id: row.device_id,
      device_label: row.device_label || shortId(row.device_id),
      claimed_at: row.claimed_at,
      last_seen_at: row.last_seen_at,
      handoff_until: row.handoff_until,
      handoff_open: true,
      transfer_prepared: prepared,
      transfer_prepared_at: row.transfer_prepared_at,
      lost_device_ack: lostAck,
      lost_device_ack_at: row.lost_device_ack_at,
      can_hand_off: false,
      backup_ready: backupReady,
      note: "Hand off is open. Install the app on the new device, sign in with the same Google account, and wait for sync. The old device cannot upload until you cancel or a new device claims.",
    };
  }
  const canHandOff = backupReady && releaseReady(row);
  let note: string;
  if (!backupReady) {
    note =
      "Cloud backup is not ready yet. On the live Boox, finish sync (outbox empty), then Settings → Prepare transfer.";
  } else if (prepared) {
    note =
      "Transfer prepared on the live Boox. Tap Hand off to release the writer lease for a new device.";
  } else if (lostAck) {
    note =
      "Lost-device confirmed. Tap Hand off when cloud backup is ready — the new Boox will claim from the cloud backup.";
  } else {
    note =
      "On the live Boox: Settings → Prepare transfer (final sync). Web Hand off stays locked until that device ack arrives.";
  }
  return {
    state: "live",
    device_id: row.device_id,
    device_label: row.device_label || shortId(row.device_id),
    claimed_at: row.claimed_at,
    last_seen_at: row.last_seen_at,
    handoff_until: null,
    handoff_open: false,
    transfer_prepared: prepared,
    transfer_prepared_at: row.transfer_prepared_at,
    lost_device_ack: lostAck,
    lost_device_ack_at: row.lost_device_ack_at,
    can_hand_off: canHandOff,
    backup_ready: backupReady,
    note,
  };
}

/**
 * Live Boox: final sync ack. Marks transfer prepared so web Hand off can open.
 * Does not open the Hand off window itself — web Hand off (or lost-device path) does.
 */
export async function prepareTransfer(
  pool: pg.Pool,
  userId: string,
  deviceId: string,
  deviceLabel: string,
  backupReady: boolean,
  now = Date.now(),
): Promise<WriterStatus> {
  if (!deviceId || deviceId.length < 8) {
    throw new Error("device_id is required");
  }
  if (!backupReady) {
    throw new Error(
      "Cloud backup is not ready. Wait until every notebook log is in the cloud, then try Prepare transfer again.",
    );
  }
  const row = await load(pool, userId);
  if (!row) {
    throw new NotLiveWriter("No live writer yet. Sync this device first so it becomes the live writer.");
  }
  if (handoffOpen(row, now)) {
    throw new Error("Hand off is already open. Cancel on the web companion if you need to prepare again.");
  }
  if (row.device_id !== deviceId) {
    throw new NotLiveWriter(
      "This device is not the live writer. Only the live Boox can Prepare transfer.",
    );
  }
  const label = (deviceLabel || "").slice(0, 120);
  await pool.query(
    `UPDATE live_writers
     SET transfer_prepared_at = $2,
         last_seen_at = $2,
         device_label = COALESCE(NULLIF($3, ''), device_label),
         lost_device_ack_at = NULL
     WHERE user_id = $1`,
    [userId, now, label],
  );
  return getWriterStatus(pool, userId, backupReady, now);
}

/**
 * Web secondary path when the old Boox is gone. Higher friction than Prepare transfer.
 * Does not open Hand off by itself — user still taps Hand off after confirm.
 */
export async function ackLostDevice(
  pool: pg.Pool,
  userId: string,
  backupReady: boolean,
  now = Date.now(),
): Promise<WriterStatus> {
  const row = await load(pool, userId);
  if (!row) {
    throw new Error("No live device on record. Sign in on a Boox first, or restore from cloud on a new device.");
  }
  if (handoffOpen(row, now)) {
    throw new Error("Hand off is already open.");
  }
  await pool.query(
    `UPDATE live_writers SET lost_device_ack_at = $2 WHERE user_id = $1`,
    [userId, now],
  );
  return getWriterStatus(pool, userId, backupReady, now);
}

/** Open a hand-off window. Requires backup ready + Prepare transfer OR lost-device ack. */
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
  if (!status.transfer_prepared && !status.lost_device_ack) {
    throw new Error(
      "Hand off stays locked until the live Boox taps Prepare transfer (or you confirm you no longer have the old device).",
    );
  }
  const until = now + HANDOFF_MS;
  await pool.query(`UPDATE live_writers SET handoff_until = $2 WHERE user_id = $1`, [userId, until]);
  return getWriterStatus(pool, userId, backupReady, now);
}

export async function cancelHandOff(pool: pg.Pool, userId: string, backupReady: boolean, now = Date.now()) {
  await pool.query(
    `UPDATE live_writers
     SET handoff_until = NULL, transfer_prepared_at = NULL, lost_device_ack_at = NULL
     WHERE user_id = $1`,
    [userId],
  );
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
        `INSERT INTO live_writers (user_id, device_id, device_label, claimed_at, last_seen_at, handoff_until, transfer_prepared_at, lost_device_ack_at)
         VALUES ($1, $2, $3, $4, $4, NULL, NULL, NULL)`,
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
         SET device_id = $2, device_label = $3, claimed_at = $4, last_seen_at = $4,
             handoff_until = NULL, transfer_prepared_at = NULL, lost_device_ack_at = NULL
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
      "This device is not the live writer. On the old Boox: Settings → Prepare transfer, then on the web: Hand off.",
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
      "This device is not the live writer. Prepare transfer on the old Boox, then Hand off on the web.",
    );
  }
  await pool.query(`UPDATE live_writers SET last_seen_at = $2 WHERE user_id = $1`, [scope.userId, now]);
}

function shortId(id: string): string {
  return id.length <= 8 ? id : id.slice(0, 8);
}
