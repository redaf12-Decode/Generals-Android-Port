# GitHub JSON update checker — Phase 2 (secure APK download and user-approved install)

09/11/2026, branch `New-app`. Extends Phase 1 (see
`GITHUB_JSON_UPDATE_CHECKER_20261109.md`) without changing it: the version check, the JSON
manifest, the notification memory and the "a new version is available" dialog are the same; Phase 2
adds a Download button, a verified download, and an Install button that hands the APK to Android's
own package installer.

## 1. Files

New:

| File | What it is |
|---|---|
| `android/app/src/main/java/com/generalsx/zerohour/ApkInstaller.java` | download, resume, cancel, SHA-256/package/version/signature verification, install hand-off |
| `android/app/src/main/java/com/generalsx/zerohour/UpdateDialog.java` | the three update screens (available / downloading / ready) and the error dialog |
| `android/app/src/test/java/com/generalsx/zerohour/ApkInstallerTest.java` | 6 unit tests |

Modified:

| File | Change |
|---|---|
| `update.json` | four new fields: `packageName`, `apkUrl`, `apkSha256`, `apkSizeBytes` |
| `android/app/src/main/java/com/generalsx/zerohour/UpdateChecker.java` | `Update` carries the APK fields; parsing validates the URL host and the 64-hex checksum; `isAcceptableApkUrl` / `isSha256`; the dialog UI moved out to `UpdateDialog` |
| `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java` | `onCreate` resumes a live download or clears stale APKs; `onDestroy` detaches; `onActivityResult` handles the install and the permission return |
| `android/app/src/main/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES` |
| `android/app/src/main/res/values*/strings.xml` (13 files) | 29 new strings each |
| `android/app/src/test/java/com/generalsx/zerohour/UpdateCheckerTest.java` | 7 new parsing/validation tests |

No new dependency. No `file_paths.xml` change — the existing FileProvider already exposes
`files-path`, which is where the APK is written.

## 2. Architecture

```
UpdateChecker.check()  ──►  Update { versionCode, versionName, releaseNotes,
                                     packageName, apkUrl, apkSha256, apkSizeBytes }
        │  (newer)
        ▼
UpdateDialog.show()  ──►  "A New Version Is Available"  [Download Update] [Later]
        │  Download
        ▼
ApkInstaller.start()  ──►  background thread:
        │                     download (resumable, cancellable) ──► SHA-256 ──► size
        │                     ──► package name + version (PackageManager) ──► signature
        ▼
UpdateDialog  ──►  "Update Ready to Install"  [Install Now] [Later]
        │  Install Now
        ▼
ApkInstaller.requestInstall()  ──►  canRequestPackageInstalls()?
        │                              no  → explain, open "install unknown apps", recheck on return
        │                              yes → FileProvider content:// → ACTION_VIEW → Android installer
        ▼
Android package installer (the user confirms there; nothing installs silently)
```

The download and the verification never touch the main thread. Progress is delivered to whichever
Activity is currently bound (`ApkInstaller.attach`), so a screen recreation during a download
re-binds instead of restarting.

## 3. Security verification (section 5 of the task)

All checks are in `ApkInstaller.verify`, run in this order, and a file that fails any one is deleted
and never offered:

- **Size** — when `apkSizeBytes` is given, the file must be exactly that many bytes. Reported as a
  size failure before the (also wrong) checksum is computed.
- **SHA-256** — computed by streaming the file (64 KiB buffer, so a 60 MB APK never enters the
  heap) and compared with `apkSha256`. The expected value is accepted only if it is exactly 64 hex
  characters, and is lower-cased at parse time so either case in the manifest works.
- **Package identity** — the APK's `packageName`, read by `PackageManager.getPackageArchiveInfo`,
  must equal both the installed app's package and the manifest's `packageName`.
- **Version** — the APK's `versionCode` must equal the manifest's `versionCode` and be strictly
  greater than the installed one.
- **Signature** — the APK's signers must all be in the installed app's
  `getSigningCertificateHistory()`. Comparing against the *history* (not only the current signer) is
  what makes a rotated key acceptable; an APK signed by anything outside that history is refused.
  Android's own signature check still runs during installation — nothing here replaces it.
- **URL** — `apkUrl` is accepted only if it is `https://` under
  `https://github.com/redaf12-Decode/Generals-Android-Port/`. GitHub's own HTTPS redirect to the
  release asset is followed by `HttpURLConnection` automatically; an `http://` URL or a different
  host is refused at parse time, so it never becomes a download.

The APK is written to `filesDir/update/`, never to shared storage, so no storage permission is
involved and no other app can read it.

## 4. Manifest

One added permission:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

No new `<provider>` and no `file_paths.xml` change: the existing FileProvider
(`${applicationId}.fileprovider`, already exposing `files-path`) serves the APK's content URI, with
`FLAG_GRANT_READ_URI_PERMISSION` granting the installer temporary read access only.

## 5. JSON example

```json
{
  "versionCode": 10500,
  "versionName": "1.5.0",
  "releaseNotes": [
    "Fixed known bugs.",
    "Improved application performance.",
    "Added new features."
  ],
  "packageName": "com.ports.generalsx",
  "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/app-release.apk",
  "apkSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "apkSizeBytes": 60000000
}
```

The four new fields are optional together: a manifest with only the first three is still a valid
Phase 1 notification, just with no Download button.

## 6. Publishing a release asset

1. Build the APK (`./scripts/build/android/build-dual-hz.sh`).
2. Create a GitHub Release tagged `v1.5.0` and upload the APK as an asset named `app-release.apk`.
3. The asset URL is
   `https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/app-release.apk`.
4. Generate the checksum and size (section 7) and put them in `update.json` on `New-app`.

The APK is **not** committed to the repository; only the release asset is.

## 7. Generating the SHA-256 and the size

```bash
sha256sum app-release.apk          # -> the apkSha256 value
stat -c '%s' app-release.apk       # -> the apkSizeBytes value (Linux)
# macOS: shasum -a 256 app-release.apk ; stat -f '%z' app-release.apk
```

## 8. Split vs universal APK (section 7 of the task)

The project produces a **universal** APK: `android/app/build.gradle` sets
`abiFilters 'arm64-v8a'` and declares no `splits`, and the release path (`build-dual-hz.sh`) writes
one APK. So one release asset is a complete update and no split-install process is needed. If the
project ever moves to splits or an App Bundle, this design must be revisited — installing one split
as if it were the whole app is exactly the failure the task warns about.

## 9. State management (section 9)

Stored in the existing `gx_app_update` SharedPreferences (the same file Phase 1 uses for the
notification memory):

| Key | Meaning |
|---|---|
| `notified_version_code` | Phase 1: the version the user has already been shown |
| `verified_version_code` | the version whose APK was downloaded and passed every check |
| `verified_sha256` | the digest it was verified against |

The verified APK is kept on disk so a later launch offers Install with no second download. Stale
APKs are deleted at launch (all but the newest), and the verified record is cleared if its file is
gone. The installer's result is only reported to the user — the update is never recorded as
installed from it; the next version check reads the real version from `PackageManager`.

## 10. Build and test results

- `:app:testDebugUnitTest` — **349 tests, 0 failures** (336 from Phase 1 + 13 new: 7 in
  `UpdateCheckerTest`, 6 in `ApkInstallerTest`).
- `:app:lintDebug` — **0 errors**; the only warnings in the new code were two obsolete `SDK_INT`
  checks (minSdk 28 makes them dead) and a static-Activity warning, all addressed.
- `:app:assembleLauncherOnly` — **BUILD SUCCESSFUL**.
- `scripts/qa/android/check-translations.py` — green (13 locales × 737 strings).

## 11. What is not tested here

The download, the certificate comparison and the actual install need a real network, a real signed
APK and a device, none of which exist in this environment. The unit tests cover the pure parts
(streaming SHA-256 against a known vector, hex encoding, the failure-to-message map, the empty/
missing/size guards of `verify`). On-device verification of scenarios 2–14 of the task's test list
is left to the owner; the code paths all funnel through the single `verify()` and the single
`run()`'s `try/catch`.

## 12. Known limitation carried over from Phase 1

The repository is **private**, so `raw.githubusercontent.com/.../update.json` returns 404 to the
app's anonymous fetch (see §7a of the Phase 1 report). Until the repository is made public — or the
manifest is hosted publicly — the check finds nothing and neither phase does anything. The
`apkUrl` is a GitHub *Release* URL, which is a different host path and would need the same fix.
