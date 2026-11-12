# Android permissions audit — `com.ports.generalsx` — 12/11/2026

Phase 5 of the security remediation. Every permission declared in
`android/app/src/main/AndroidManifest.xml` is listed below with the feature that uses it, whether
it is essential, whether a safer alternative exists, and whether it raises a **Google Play policy**
concern (a distribution rule) as opposed to an **Android platform** one. A permission being
justified is not the same as it being Play-approvable.

Distribution context, stated once: this fork is **sideloaded** (the repository owner builds and
hands out APKs; `AGENTS.md` describes the release flow, not a Play listing). The policy column
therefore records what *would* apply if the app were ever submitted to Play, and is not a
statement that the app is currently in violation.

## Summary

| Permission | Essential? | Play policy concern | Action taken |
|---|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Yes (native path access) | Yes, if published | Kept; justified below |
| `READ_EXTERNAL_STORAGE` (maxSdk 29) | Yes on API ≤ 29 | No | Kept |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 29) | Yes on API ≤ 29 | No | Kept |
| `INTERNET` | Yes | No | Kept |
| `ACCESS_NETWORK_STATE` | Yes | No | Kept |
| `CHANGE_WIFI_MULTICAST_STATE` | Yes (LAN) | No | Kept |
| `REQUEST_INSTALL_PACKAGES` | Yes (self-update) | Yes, if published | Kept; user-mediated only |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | No | Yes, if published | Prompt removed; read-only + settings link |

## `MANAGE_EXTERNAL_STORAGE` ("All files access")

- **Why it exists**: the game reads its data with the engine's own `fopen()`/`chdir()` calls, and
  the user's game folder can be anywhere in shared storage (Downloads, an SD card). The launcher's
  `FolderPickerActivity` resolves a **real filesystem path** so the native side can `chdir()` into
  it.
- **Which code uses it**: `SetupActivity.hasStorageAccess()` /
  `requestStorageAccess()` (lines ~4195–4222), `FolderPickerActivity`, and every read/write of the
  chosen game folder and the user-data folder
  (`Environment.getExternalStorageDirectory()/Generals/Command and Conquer Generals Zero Hour Data`).
- **Essential?** Yes, for the current architecture. The safer alternative — the Storage Access
  Framework (`ACTION_OPEN_DOCUMENT_TREE`) — hands back a `content://` tree that the native
  `fopen()`/`chdir()` calls cannot use; adopting it would require copying the ~2–3 GB game data
  into app storage on every launch, which is not viable. This is documented in
  `FolderPickerActivity.java`'s header comment. The permission is requested only when the user
  first picks a game folder, not at launch.
- **Safer alternative?** Only a larger architectural change (a virtual filesystem layer in the
  engine). Out of scope for a security remediation; recorded as the residual option.
- **Play policy**: **yes, a concern if published.** Play requires a Permissions Declaration Form
  and permits "All files access" only for narrow cases (primarily file managers). The manifest
  currently carries `tools:ignore="ScopedStorage"`; that silences lint, it does not satisfy the
  policy. If the app is ever submitted, the folder workflow must be migrated to SAF or the
  permission justified to Google.
- **Sideloaded distribution**: required. Without it the folder browser shows nothing.

## `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="29"`)

- **Why**: on Android 9/10 (`minSdk 28`) `MANAGE_EXTERNAL_STORAGE` and
  `Environment.isExternalStorageManager()` do not exist, so the pre-R runtime permissions are the
  equivalent. `maxSdkVersion="29"` keeps them out of the effective set on API 30+, where "All files
  access" already covers everything.
- **Which code uses it**: `SetupActivity.requestStorageAccess()` (the pre-R branch) and
  `hasStorageAccess()`.
- **Essential?** Yes on API ≤ 29. **Safer alternative?** None at that API level. **Play policy?**
  No — these are ordinary, scoped-storage-era permissions with a bounded `maxSdkVersion`.

## `INTERNET`

- **Why**: GeneralsOnline sign-in, the update check, the data-package download, and the game's
  multiplayer networking.
- **Which code uses it**: `GeneralsOnlineSession`, `NetworkDiagnostics`, `UpdateChecker`,
  `UpdateManager`, `DataPackInstaller`, and the native netcode.
- **Essential?** Yes. **Safer alternative?** None. **Play policy?** No. Every remote URL is https
  and host-checked (Phase 3/4).

## `ACCESS_NETWORK_STATE`

- **Why**: `NetworkDiagnostics` reports the transport (Wi-Fi/cellular/VPN), metered state, DNS and
  proxy so a player can diagnose a failed sign-in; the launcher also avoids starting a check with
  no network.
- **Which code uses it**: `NetworkDiagnostics.deviceAndNetwork()` (`ConnectivityManager`).
- **Essential?** Yes, for diagnostics. **Safer alternative?** None of the same value. **Play
  policy?** No — it is a normal permission and reveals nothing on its own.

## `CHANGE_WIFI_MULTICAST_STATE`

- **Why**: the LAN lobby/match holds a `WifiManager.MulticastLock` so Wi-Fi drivers do not filter
  LAN broadcast packets.
- **Which code uses it**: `GeneralsZHActivity.setLanMulticastLock()` /
  `releaseLanMulticastLock()`, held only while a LAN match is active.
- **Essential?** Yes, for LAN play. **Safer alternative?** None. **Play policy?** No. The lock is
  released on `onDestroy`, so it is not held in the background.

## `REQUEST_INSTALL_PACKAGES`

- **Why**: the in-app updater downloads a newer APK and hands it to Android's package installer.
- **Which code uses it**: `ApkInstaller` — `canRequestPackageInstalls()` (line ~621) and
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` (line ~639), reached only after the user taps
  Install on an already-verified update.
- **Behaviour verified**:
  - Installation starts only after an explicit user action.
  - The package id, `versionCode`, SHA-256 and **signing certificate** of the downloaded APK are
    checked before the installer is launched (`ApkInstaller` verification path).
  - The download source and every redirect host are validated.
  - Only the platform installer UI is used — no silent, root or background installation.
- **Essential?** Yes, for the self-update feature. **Safer alternative?** None for an in-app
  updater; the alternative is to remove the updater and point users at the release page.
- **Play policy**: **yes, a concern if published.** Play restricts self-updating outside its own
  update mechanism; `REQUEST_INSTALL_PACKAGES` is scrutinised and a self-updater is not
  automatically permitted. For a sideloaded app it is normal. If the app is ever submitted, the
  updater should be disabled on Play-installed builds (check the installer package) and the
  permission dropped from the Play variant.

## `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

- **Why it was there**: to keep the GeneralsOnline login poll alive.
- **Current behaviour**: the permission is **still declared** but the app no longer requests the
  exemption. `GeneralsOnlineActivity.isIgnoringBatteryOptimizations()` (line ~708) only *reads*
  the state to label the battery card, and `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
  (line ~728) opens the **system battery-settings screen**, where the player decides — it is not
  the one-tap `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt.
- **Essential?** No. The login poll runs in the foreground and the session refresh retries on the
  next launch. **Safer alternative**: exactly what is now implemented — explain and link to
  settings. **Play policy?** The direct exemption request was the concern and it is gone. The
  permission declaration remains only so the card can read the current state; it can be dropped
  entirely if the card is removed, at the cost of not telling the player whether the app is exempt.

## Permissions deliberately *not* present

No `CAMERA`, `RECORD_AUDIO`, `READ_CONTACTS`, `ACCESS_FINE_LOCATION`, `QUERY_ALL_PACKAGES`,
`SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, or
`WAKE_LOCK`. `QUERY_ALL_PACKAGES` in particular is absent, which avoids another high-scrutiny Play
permission. The only `<queries>`-style need is `canRequestPackageInstalls()`, which does not
require it.

## Verdict

Every declared permission maps to a concrete, user-visible feature. Two (`MANAGE_EXTERNAL_STORAGE`
and `REQUEST_INSTALL_PACKAGES`) are genuine **Play policy** items *if the app is ever published*,
and both are justified for the current sideloaded distribution. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
was reduced from an automatic prompt to a read-only status plus a settings link. No permission is
declared without a caller.
