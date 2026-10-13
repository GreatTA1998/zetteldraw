# Play Store listing — Zetteldraw

First Google Play release of the Android app. The store identity is `com.zetteldraw.android` (versionCode 67, versionName 3.46). The app only works as a pen notebook on **Boox/Onyx e-ink devices, arm64**. Play will still offer the AAB to other arm64 phones unless you exclude them in the device catalog. Say so in the listing.

Java sources stay in `com.zetteldraw.penpoc`. That namespace is what `R`, `BuildConfig`, Room schemas, and the manifest class names use. Play does not see it. There is no FileProvider, custom permission authority, or deep link whose host would have to change with the id. `https://zetteldraw.com/apk` proxies the latest GitHub release APK and does not embed an application id, so that page keeps working.

A Play install does **not** upgrade a sideload of `com.zetteldraw.penpoc`. They are different apps. Move notes by syncing the old install, **Prepare transfer**, web **Hand off**, then signing in on the new install. `Documents/zetteldraw` survives uninstall and is not auto-imported.

## Build the release AAB

JDK 17+, Android SDK platform 35, and HTTP access to `http://repo.boox.com/repository/maven-public/`.

Generate an upload key once. Do not commit the keystore or the passwords. Java's `keytool` writes a PKCS12 store; the Android Gradle Plugin accepts it.

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

Fingerprints for the Google Cloud Android OAuth client (upload key now, Play App Signing key after the first enrollment):

```bash
keytool -list -v -keystore upload-keystore.jks -alias upload
```

Copy `keystore.properties.example` to `keystore.properties` in the repo root and fill it in, or export `ZETTELDRAW_STORE_FILE`, `ZETTELDRAW_STORE_PASSWORD`, `ZETTELDRAW_KEY_ALIAS`, and `ZETTELDRAW_KEY_PASSWORD`. If none of the four are set, `bundleRelease` still runs and the AAB is unsigned. If only some are set, the build fails.

`-Pzetteldraw.syncToken` is ignored for release. `BuildConfig.SYNC_TOKEN` is empty so a Play build cannot authenticate with the shared device token. Debug builds can still take the property.

Hosted sync URL (from `render.yaml`): `https://zetteldraw-sync.onrender.com`. The web client id is the same value as `NEXT_PUBLIC_GOOGLE_CLIENT_ID` on the web service (not stored in this repo).

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:bundleRelease \
  -Pzetteldraw.syncUrl=https://zetteldraw-sync.onrender.com \
  -Pzetteldraw.googleWebClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

Output: `app/build/outputs/bundle/release/app-release.aab`.

Do not pass `-Pzetteldraw.emulator`. That flag builds x86_64 and drops the arm64 Onyx libraries.

## Google Cloud OAuth (manual)

Create an **Android** OAuth client in the same Google Cloud project as the existing web client:

| Field | Value |
| --- | --- |
| Package name | `com.zetteldraw.android` |
| SHA-1 | Upload keystore SHA-1 **and**, after Play App Signing is enrolled, the **App signing key** SHA-1 from Play Console |

The app sends the **web** client id as `serverClientId`. ID tokens use that web client as `aud`. `GOOGLE_CLIENT_IDS` on the sync server must include that web client id. It is not a package-name allowlist. Add the new Android client id there only if you also accept tokens whose audience is the Android client. An Android client registered for `com.zetteldraw.penpoc` does not authorize this package.

If the OAuth consent screen is in Testing, Play reviewers cannot sign in unless their Google accounts are test users. Publish the consent screen, or add the review accounts, before review.

## Listing copy

**App name:** Zetteldraw

**Category:** Productivity

**Short description** (64 characters):

```
Handwriting notebooks for Boox e-ink only. Optional Google sync.
```

**Full description:**

```
Zetteldraw is a handwriting notebook for Boox and Onyx e-ink devices (arm64 only). It is not a phone drawing app. Live ink uses the Onyx pen controller: the stylus draws, and a finger scrolls. On a phone or a non-Onyx tablet the notebook screens open, but the pen does not lay down e-ink the way it does on a Boox.

Scratchpad plus nested notebooks. Hold a notebook tab to rename it, place it under another notebook, or delete it. Pen, eraser, and lasso (select, move, cut, copy, paste), with undo and redo. Pages are one continuous vertical scroll, with links between pages. Move a page inside a notebook or file it into another one.

Notes stay on the device first. A copy is also written under Documents/zetteldraw, which survives uninstall. That folder is not imported automatically after a reinstall.

Sync is optional. Sign in with Google from Settings to back the library up to your account. The web companion at https://zetteldraw.com is read-only: browse notebooks and pages, and Hand off when you move to another Boox. One device is the live writer. Before a new device or this Play install, on the current Boox use Settings, Prepare transfer, then on the web use Hand off, then sign in here.

This listing is application id com.zetteldraw.android. It does not upgrade an older sideload installed as com.zetteldraw.penpoc. Sync that install first.

Notes are not sold and are not used for advertising. Privacy policy: https://zetteldraw.com/privacy.
```

## Privacy policy

In the app, Settings → Privacy policy opens `https://zetteldraw.com/privacy` (`CanvasActivity`). That path is `web/src/app/privacy/page.tsx`, served by the `zetteldraw-web` service on zetteldraw.com (`render.yaml`). Use that URL in Play Console.

The page describes Google account id and email, notebook and ink data, the random device id, the device model, the diagnostic launch timeline the app sends, and that sign-out does not delete server notes. Deletion requests go to the contact email on the Play listing. There is no in-app or server endpoint that deletes an account. You have to actually answer those requests. Play asks for a data-deletion URL when an app creates accounts; this privacy URL can be that link only if the inbox behind the listing email is monitored.

## Data safety

Answers below match the code in this repo, not a generic notes app. "Collected" means transmitted off the device. With sync off and no Google session, nothing in this table is sent.

The sync server (`server/`) stores, when sync is on:

- `users`: Google subject (`id`) and `email`
- Notebooks, pages, page links, ink blobs, and server-rendered thumbnails
- `live_writers`: app-generated `device_id`, `device_label` (`Build.MODEL`, or `"Boox"` if the model is empty), and timestamps including transfer-prepared

The app also POSTs a launch timeline to `/sync/device-log` after a successful sync (`SyncWorker.uploadDeviceLog`: device id, versionCode, launch text). This repo's server has no route for that path, so the service does not write the log to the database. The bytes still leave the device. Declare them, or remove the call before you answer "not collected".

Not collected: advertising ID, Android ID, IMEI, serial, location, contacts, SMS, photos from the camera or gallery, microphone, payment data, name beyond what Google returns (the app keeps email and subject only), crash SDK, analytics SDK. The lasso clipboard is in-memory inside the app, not the Android clipboard. `allowBackup` is false. `WRITE_EXTERNAL_STORAGE` is limited to Android 9 and below for the public Documents mirror; Android 10+ uses MediaStore for files this app created. No third-party ad or analytics SDK.

| Data | Play type | Collected | Shared | Required? | Ephemeral? | Purpose |
| --- | --- | --- | --- | --- | --- | --- |
| Email address | Personal info → Email address | Yes | No | Optional (sync) | No | Account management, App functionality |
| Google subject | Personal info → User IDs | Yes | No | Optional | No | Account management, App functionality |
| Notebook titles, page order, links, ink, thumbnails | Files and docs | Yes | No | Optional | No | App functionality |
| Random device id | Device or other IDs | Yes | No | Optional | No | App functionality |
| Device model name | Device or other IDs | Yes | No | Optional | No | App functionality |
| Launch timeline | App info and performance → Diagnostics | Yes (sent) | No | Optional | Not stored by app code | App functionality |

**Shared: No.** Data is sent to the first-party sync server (`https://zetteldraw-sync.onrender.com` in the release command above). It is not sold and not sent to advertisers. Google Sign-In runs through Play services; the app then sends the ID token to that server. Google's form treats "shared" as transferred to a third party. A service provider processing data only for you is not "shared". Re-read the console help text on the day you submit, in case the wording around Google Sign-In has changed.

**Encrypted in transit:** Yes, for a build whose `zetteldraw.syncUrl` is `https://`. The manifest still allows cleartext so a debug URL can be `http://` on a LAN. Do not ship a Play build pointed at HTTP. There is no certificate pinning.

**Encrypted at rest:** The app relies on the Android app sandbox. It does not add its own encryption. Server-side disk encryption is whatever Supabase and the object store provide; this repo does not implement a separate layer. Answer the in-transit question from the URL you bake in. Do not claim app-level encryption at rest.

**Data deletion:** Users cannot delete the server account in the app. Sign-out clears the local access token, email, and subject, and turns sync off. Say that deletion is by request to the listing contact, and only after that inbox is real.

**Advertising ID:** No. The app does not use it.

**Data types you can leave off:** name, address, phone, location, photos and videos, audio, calendar, contacts, web browsing history, crash logs, financial info, health, messages, installed apps.

## Content rating

Expected result is Everyone / low age (PEGI 3 or the local equivalent). The app has no violence, sexual content, profanity, substances, gambling, or ads. There are no in-app purchases.

| Question | Answer |
| --- | --- |
| Violence, fear, sexuality, language, controlled substances, crude humor, gambling | None |
| Users interact or exchange content with other users | No. Notes stay on the device and, if sync is on, under that Google account. There is no public gallery, chat, or sharing to other accounts. |
| Shares the user's location with other users | No |
| Digital purchases | No |
| Unrestricted web browsing | No. Settings opens the privacy policy and `https://zetteldraw.com` in an external browser. There is no in-app browser of arbitrary pages. |
| Made for children / Designed for Families | No. Do not enroll in the Families program. |

## App access

Login is **not** required to open the app, draw on a Boox, or edit local notebooks. It **is** required for sync and for the web companion.

In Play Console, choose "some functionality is restricted" and give reviewers these steps. Do not claim the whole app is behind a login; the scratchpad opens with no account.

```
No demo password is built into the app. Any Google account works when:
- the Android OAuth client for com.zetteldraw.android lists the Play App Signing SHA-1, and
- the OAuth consent screen is in production (or the reviewer's Google account is a test user).

1. Open Zetteldraw. The scratchpad and notebooks are usable without an account.
2. Open Settings (gear icon).
3. Tap Sign in with Google and finish the Google prompt.
4. Leave sync on ("Sync when signed in").
5. Optional: open https://zetteldraw.com and sign in with the same account.

Live stylus ink needs a Boox/Onyx e-ink device (arm64) and the Onyx pen controller.
On a review phone, a finger scrolls and does not draw. Settings, notebooks, and
sign-in can still be reviewed. Pen latency cannot be reviewed without a Boox.
```

## Graphics checklist

The manifest icon is still the platform drawable `@android:drawable/ic_menu_edit`. The 512 icon you upload in Play Console does not change the icon on the device. Replace the launcher icon before you ship if you want a branded one on the Boox.

| Asset | Spec | Status |
| --- | --- | --- |
| Hi-res icon | 512×512 PNG, 32-bit, under 1024 KB | Not in the repo. Upload in Play Console. |
| Feature graphic | 1024×500 PNG or JPEG, no alpha | Not in the repo. Upload in Play Console. |
| Phone screenshots | At least 2. JPEG or 24-bit PNG. Each side 320–3840 px. Aspect ratio from 1:2 to 2:1. | Capture on a Boox. Go 7 Color II is 1264×1680 portrait, which fits. |
| 7-inch and 10-inch tablet screenshots | Provide them if the console asks. The app does not declare a screen-size limit. | A 7-inch Boox shot can fill the 7-inch slot. Add a 10-inch or 13-inch Boox shot if you have one. |
| TV, Wear, XR, promo video | Not supported | Skip |

Suggested shots: scratchpad with ink and the notebook tabs; lasso selection; Settings signed in; a page with links. Do not use a phone screenshot that implies finger drawing works.

## Device catalog and policy notes

- `minSdk` 26, `targetSdk` 35, `compileSdk` 35. ABI filter `arm64-v8a` only.
- The manifest does not require an Onyx feature flag (there isn't one). In Play Console → Device catalog, exclude devices that are not Boox/Onyx if the catalog lets you, and keep the listing text anyway.
- No ads, no billing, no news or health declaration.
- Target 35 opts out of forced edge-to-edge (`android:windowOptOutEdgeToEdgeEnforcement`) so page height stays the pre-35 fullscreen window. The activity also pads by system-bar insets on API 35 if that opt-out is ignored. The opt-out is ignored once the app targets API 36; insets have to be handled for real then.
- The app does not declare a foreground service. WorkManager sync is a normal background worker, not an expedited foreground job.
- Native code is the Onyx Pen SDK (`libonyx_pen_touch_reader.so` and its shared C++ runtime), arm64 only. Play requires 16 KB ELF alignment for native libraries on Android 15 targets. Confirm the prebuilt `.so` files in the AAB before you upload; this repo cannot rebuild the Onyx binaries. If Play rejects them, the fix is a newer Onyx SDK, not a Gradle flag.
