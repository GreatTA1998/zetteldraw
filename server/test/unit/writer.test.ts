import { describe, expect, it, vi } from "vitest";
import type pg from "pg";
import {
  ackLostDevice,
  assertCanPush,
  cancelHandOff,
  claimWriter,
  getWriterStatus,
  handOff,
  HANDOFF_MS,
  NotLiveWriter,
  prepareTransfer,
} from "../../src/writer.js";

type QueryResult = { rows: unknown[] };

function poolWithState(initial: QueryResult["rows"][0] | null) {
  let row = initial as Record<string, unknown> | null;
  const queries: string[] = [];
  const pool = {
    query: async (sql: string, params?: unknown[]) => {
      queries.push(sql);
      if (sql.includes("SELECT * FROM live_writers")) {
        return { rows: row ? [row] : [] };
      }
      if (sql.startsWith("INSERT INTO live_writers")) {
        row = {
          user_id: params![0],
          device_id: params![1],
          device_label: params![2],
          claimed_at: params![3],
          last_seen_at: params![3],
          handoff_until: null,
          transfer_prepared_at: null,
          lost_device_ack_at: null,
        };
        return { rows: [] };
      }
      if (sql.includes("SET transfer_prepared_at = $2")) {
        if (row) {
          row.transfer_prepared_at = params![1];
          row.last_seen_at = params![1];
          row.lost_device_ack_at = null;
          if (params![2]) row.device_label = params![2];
        }
        return { rows: [] };
      }
      if (sql.includes("SET lost_device_ack_at = $2")) {
        if (row) row.lost_device_ack_at = params![1];
        return { rows: [] };
      }
      if (sql.includes("SET handoff_until = $2")) {
        if (row) row.handoff_until = params![1];
        return { rows: [] };
      }
      if (sql.includes("SET device_id = $2")) {
        row = {
          user_id: params![0],
          device_id: params![1],
          device_label: params![2],
          claimed_at: params![3],
          last_seen_at: params![3],
          handoff_until: null,
          transfer_prepared_at: null,
          lost_device_ack_at: null,
        };
        return { rows: [] };
      }
      if (sql.includes("handoff_until = NULL, transfer_prepared_at = NULL, lost_device_ack_at = NULL")) {
        if (row) {
          row.handoff_until = null;
          row.transfer_prepared_at = null;
          row.lost_device_ack_at = null;
        }
        return { rows: [] };
      }
      if (sql.includes("SET handoff_until = NULL")) {
        if (row) row.handoff_until = null;
        return { rows: [] };
      }
      if (sql.includes("SET last_seen_at")) {
        if (row) row.last_seen_at = params![1];
        return { rows: [] };
      }
      return { rows: [] };
    },
    connect: async () => {
      const client = {
        query: async (sql: string, params?: unknown[]) => {
          if (sql === "BEGIN" || sql === "COMMIT" || sql === "ROLLBACK" || sql.includes("advisory")) {
            return { rows: [] };
          }
          return pool.query(sql, params);
        },
        release: () => {},
      };
      return client;
    },
  } as unknown as pg.Pool;
  return {
    pool,
    get row() {
      return row;
    },
    queries,
  };
}

const liveRow = (overrides: Record<string, unknown> = {}) => ({
  user_id: "u1",
  device_id: "device-aaaa",
  device_label: "Boox",
  claimed_at: 1,
  last_seen_at: 1,
  handoff_until: null,
  transfer_prepared_at: null,
  lost_device_ack_at: null,
  ...overrides,
});

describe("live writer lease", () => {
  it("reports none when no row", async () => {
    const { pool } = poolWithState(null);
    const status = await getWriterStatus(pool, "u1", true);
    expect(status.state).toBe("none");
    expect(status.can_hand_off).toBe(false);
  });

  it("blocks Hand off until Prepare transfer (even when backup ready)", async () => {
    const { pool } = poolWithState(liveRow());
    const status = await getWriterStatus(pool, "u1", true);
    expect(status.can_hand_off).toBe(false);
    await expect(handOff(pool, "u1", true)).rejects.toThrow(/Prepare transfer/i);
  });

  it("blocks Hand off until backup is ready even after Prepare transfer", async () => {
    const { pool } = poolWithState(liveRow({ transfer_prepared_at: 100 }));
    await expect(handOff(pool, "u1", false)).rejects.toThrow(/backup is not ready/i);
  });

  it("Prepare transfer unlocks Hand off; new device can claim", async () => {
    const now = 1_700_000_000_000;
    const state = poolWithState(
      liveRow({
        device_id: "device-old1",
        device_label: "Old Boox",
        claimed_at: now - 1000,
        last_seen_at: now - 1000,
      }),
    );
    const prepared = await prepareTransfer(state.pool, "u1", "device-old1", "Old Boox", true, now);
    expect(prepared.transfer_prepared).toBe(true);
    expect(prepared.can_hand_off).toBe(true);
    expect(prepared.state).toBe("live");

    const opened = await handOff(state.pool, "u1", true, now + 1);
    expect(opened.state).toBe("handoff_open");
    expect(opened.handoff_until).toBe(now + 1 + HANDOFF_MS);

    await expect(claimWriter(state.pool, "u1", "device-old1", "Old", now + 2)).rejects.toBeInstanceOf(
      NotLiveWriter,
    );

    const claimed = await claimWriter(state.pool, "u1", "device-new2", "Play Boox", now + 3);
    expect(claimed.status).toBe("claimed");
    expect(state.row?.device_id).toBe("device-new2");
    expect(state.row?.handoff_until).toBeNull();
    expect(state.row?.transfer_prepared_at).toBeNull();
  });

  it("rejects Prepare transfer from a non-live device", async () => {
    const { pool } = poolWithState(liveRow({ device_id: "device-live1" }));
    await expect(prepareTransfer(pool, "u1", "device-other", "Other", true)).rejects.toBeInstanceOf(
      NotLiveWriter,
    );
  });

  it("lost-device ack unlocks Hand off without Prepare transfer", async () => {
    const now = 1_700_000_000_000;
    const state = poolWithState(liveRow({ device_id: "device-gone", claimed_at: now, last_seen_at: now }));
    const acked = await ackLostDevice(state.pool, "u1", true, now);
    expect(acked.lost_device_ack).toBe(true);
    expect(acked.can_hand_off).toBe(true);
    const opened = await handOff(state.pool, "u1", true, now + 1);
    expect(opened.state).toBe("handoff_open");
  });

  it("cancel clears Hand off and prepare / lost-device flags", async () => {
    const now = 1_700_000_000_000;
    const state = poolWithState(
      liveRow({
        device_id: "device-old1",
        device_label: "Old",
        claimed_at: now,
        last_seen_at: now,
        handoff_until: now + HANDOFF_MS,
        transfer_prepared_at: now,
        lost_device_ack_at: null,
      }),
    );
    const status = await cancelHandOff(state.pool, "u1", true, now);
    expect(status.state).toBe("live");
    expect(status.device_id).toBe("device-old1");
    expect(status.transfer_prepared).toBe(false);
    expect(status.can_hand_off).toBe(false);
  });

  it("assertCanPush rejects a second device while live", async () => {
    const { pool } = poolWithState(liveRow({ device_id: "device-live", device_label: "Live" }));
    await expect(
      assertCanPush(pool, { kind: "user", userId: "u1" }, "device-other"),
    ).rejects.toBeInstanceOf(NotLiveWriter);
  });

  it("assertCanPush allows unowned (legacy device token) sync", async () => {
    const { pool } = poolWithState(null);
    await assertCanPush(pool, { kind: "unowned" }, "anything");
  });

  it("first Google push claims the writer", async () => {
    const state = poolWithState(null);
    const spy = vi.spyOn(state.pool, "connect");
    await assertCanPush(state.pool, { kind: "user", userId: "u1" }, "device-first");
    expect(spy).toHaveBeenCalled();
    expect(state.row?.device_id).toBe("device-first");
  });
});
