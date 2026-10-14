# Zetteldraw v2 (greenfield, in-tree)

Fresh rewrite scaffold living at **`v2/`** inside [GreatTA1998/zetteldraw](https://github.com/GreatTA1998/zetteldraw). Legacy `app/` / `server/` / `web/` at the repo root stay untouched.

| Reference tip (behavior only) | |
| --- | --- |
| Tip APK | https://zetteldraw.com/apk |
| Release | [v86](https://github.com/GreatTA1998/zetteldraw/releases/tag/v86) (`3.65`) |
| Role | **Behavior reference only.** Specs + screenshots win when they disagree with incidental tip UI. **Do not port tip bandages or dual `.zdl` / Room ink paths.** |

Canonical pack (also at repo root): [docs/greenfield/](../docs/greenfield/README.md). This tree keeps a copy under `v2/docs/greenfield/` so media paths work when working only inside `v2/`.

---

## Pack docs (source of truth)

| Doc | Purpose |
| --- | --- |
| [docs/greenfield/README.md](./docs/greenfield/README.md) | Pack overview + screenshots |
| [docs/greenfield/product-behavior.md](./docs/greenfield/product-behavior.md) | **Acceptance checklist** — must-not-regress |
| [docs/greenfield/architecture.md](./docs/greenfield/architecture.md) | Module map + structural impossibility laws |
| [docs/greenfield/sync-model.md](./docs/greenfield/sync-model.md) | Single-writer, Prepare / Hand off / claim |
| [docs/greenfield/non-goals.md](./docs/greenfield/non-goals.md) | What **not** to port from the old tree |

---

## What's scaffolded

```
v2/docs/greenfield/   # Spec pack + media (copy)
v2/app/               # Android client (com.zetteldraw.v2) — MainActivity + ink/sync/ui stubs
v2/server/            # Hono sync stub — push/pull/lease/blob routes return 501
v2/web/               # Vite read-only shell (phase-1 pointer to architecture)
```

**Explicitly not included:** RoomBoardRepository, tip-band guards, dual ink SoT, shipped APK.

---

## Build / run

Commands below are from **`v2/`** (this directory).

### Android (Boox)

See [app/README.md](./app/README.md).

```bash
./gradlew :app:assembleDebug
```

Needs Android SDK + JDK 17+. Onyx Maven repos are in `settings.gradle`. Pen path = Onyx `TouchHelper` on device — not an emulator POC.

### Sync server

See [server/README.md](./server/README.md).

```bash
cd server && npm install && npm run dev
# http://127.0.0.1:3847  →  GET /health
```

### Web companion

See [web/README.md](./web/README.md).

```bash
cd web && npm install && npm run dev
# http://127.0.0.1:3848
```

---

## Acceptance

Before calling any slice “done,” walk [docs/greenfield/product-behavior.md](./docs/greenfield/product-behavior.md) and the structural checklist in [architecture.md](./docs/greenfield/architecture.md).

---

## Suggested next slice

**Prefer Boox pen surface first:** `InkSurface` + Onyx `TouchHelper` hello on a Go 7, then `InkSheetStore` + Ready + offline paint. Sync stubs can stay 501 until `InkLogSync` needs a peer.
