# Android app (`com.zetteldraw.v2`)

Greenfield Boox client. **Do not port** legacy `CanvasActivity` / `RoomBoardRepository`.

## Target

- Device: **Boox Go 7 Color II** (Android 13, Kaleido 3, arm64, stylus)
- Ink path: Onyx `TouchHelper` raw/scribble (not Android `View` drawing)
- Package: `com.zetteldraw.v2`

## Modules (stubs)

| Package | Purpose |
| --- | --- |
| `ink/` | `InkSheetStore`, `InkLogApply`, `InkLogSync`, `NotebookMeta` |
| `sync/` | `AccountSession`, `LiveWriterLease` |
| `ui/` | `CollectionNav`, `ToolController`, `InkSurface` |

See [docs/greenfield/architecture.md](../docs/greenfield/architecture.md).

## Onyx / Boox Maven

Root `settings.gradle` includes:

- `http://repo.boox.com/repository/maven-public/`
- `http://repo.boox.com/repository/proxy-public/`

Dependencies (support matrix — pin per firmware):

- `com.onyx.android.sdk:onyxsdk-pen:1.5.5`
- `com.onyx.android.sdk:onyxsdk-device:1.3.6`

## Build

Requires Android SDK + JDK 17+. From repo root:

```bash
./gradlew :app:assembleDebug
```

Sideload the debug APK to a Boox Go 7. Emulator builds need an x86_64 override and will not exercise the pen path.

## Next slice

Prefer **InkSurface + TouchHelper hello** on device before sync ceremony work.
