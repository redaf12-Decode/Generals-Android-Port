# Android package migration, legacy updater removal and GitHub Release update — audit and report

Date: 10/11/2026 · Branch: `android-package-migration-update` → PR #8 against `New-app`
Repository: `redaf12-Decode/Generals-Android-Port`

## 1. Audit (Phase 1)

Every `.java`, `.xml`, `.gradle`, `.pro`, workflow and script under `android/` (and the JNI and
native-path code outside it) was searched for `com.generalsx.zerohour`, `MYSOREZ`,
`GeneralsZH-Android-Port`, old raw/API GitHub URLs, obsolete updater classes and stale metadata.

| File | Line | Legacy reference | Kind | Action |
|---|---|---|---|---|
| `android/app/build.gradle` | 32 | `com.generalsx.zerohour` | comment (explains the migration) | kept — comment only |
| `android/app/src/main/java/com/ports/generalsx/UpdateManager.java` | 9 | `MYSOREZ` | comment (what was removed) | kept — comment only |
| `android/app/src/main/java/com/ports/generalsx/UpdateChecker.java` | 9 | `MYSOREZ` | comment (what was removed) | kept — comment only |
| `android/app/src/main/java/com/ports/generalsx/DataPackInstaller.java` | 77 | `MYSOREZ` | comment | kept — comment only |
| `android/app/src/main/java/com/ports/generalsx/SetupActivity.java` | 3392 | `MYSOREZ` | comment | kept — comment only |
| `android/app/src/main/java/com/ports/generalsx/GeneralsOnlineActivity.java` | 298 | `MYSOREZ` | comment | kept — comment only |
| `Core/GameEngineDevice/.../AndroidTextEditor.cpp` | 233,239 | `Java_com_generalsx_zerohour_*` | active JNI symbols | **migrated** to `Java_com_ports_generalsx_*` |
| `android/app/src/main/java/com/generalsx/zerohour/*` | — | whole package | active code | **moved** to `com/ports/generalsx/` |
| `.github/workflows/sign-update.yml` | — | MYSOREZ sign-update | active workflow | **deleted** |
| `scripts/update/publish-update.py`, `push-updates-branch.sh`, `features.py`, `pc-exe-crc.py` | — | MYSOREZ update tooling | active scripts | **deleted** |
| `update/config.json`, `update/features.json`, `update.json` | — | MYSOREZ update state | active data | **deleted** |
| `README.md` | 12,19,22,23,262–265 | `MYSOREZ/GeneralsZH-Android-Port` | user-facing badges | **repointed** to `redaf12-Decode/Generals-Android-Port` |
| `README.md` | 13 | `gitverse.ru/MYSOREZ/...` | GitVerse mirror badge | kept (separate host; not an app endpoint) |
| `docs/HOWTO/PUBLISH_UPDATE.md` | — | MYSOREZ publishing | doc | **deleted**, replaced by `PUBLISH_APP_UPDATE.md` |
| `docs/DEV_BLOG/*`, `docs/WORKDIR/reports/*`, `docs/releases/*` | — | MYSOREZ/zerohour | historical docs | kept — historical record |

No active code, resource, manifest, ProGuard rule or workflow references the old package or the old
update repository after this change.

## 2. Migrated Java classes (Phase 2)

All 47 launcher classes and 20 test classes moved from `com/generalsx/zerohour/` to
`com/ports/generalsx/`; `package`/`import` lines updated. Representative classes: `SetupActivity`,
`GeneralsZHActivity`, `ApkInstaller`, `UpdateChecker`, `UpdateDialog`, `UpdateManager`,
`DataPackInstaller`, `GeneralsOnlineActivity`, `ModManager`, `TouchControlsActivity`, `UiKit`,
`ThemeHelper`, `LocaleHelper`.

## 3. Modified XML files

- `android/app/src/main/AndroidManifest.xml` — unchanged (components are relative, authority is
  `${applicationId}.fileprovider`); verified to resolve to the new package.
- `android/app/src/main/res/xml/file_paths.xml` — unchanged; verified.
- No layout or navigation XML named a fully qualified class.

## 4. Deleted legacy updater files

`.github/workflows/sign-update.yml`, `scripts/update/publish-update.py`,
`scripts/update/push-updates-branch.sh`, `scripts/update/features.py`,
`scripts/update/pc-exe-crc.py`, `update/config.json`, `update/features.json`, `update.json`,
`docs/HOWTO/PUBLISH_UPDATE.md`, `android/app/src/test/.../UpdateManagerTest.java` (covered the
removed engine-OTA logic).

## 5. Retained modern updater files

`UpdateChecker.java` (GitHub Releases + signed metadata), `ApkInstaller.java` (download + verify +
install), `UpdateDialog.java` (3-screen UI), `UpdateManager.java` (community data patch only),
`scripts/update/generate-metadata-key.py`, `scripts/update/generate-update-metadata.py`,
`scripts/update/validate-release.py`, `.github/workflows/publish-release.yml`,
`.github/workflows/build-android.yml`.

## 6. Final identity

- `applicationId`: `com.ports.generalsx`
- Java `namespace`: `com.ports.generalsx`
- FileProvider authority: `com.ports.generalsx.fileprovider`
- JNI: `Java_com_ports_generalsx_GeneralsZHActivity_nativeTextEditor{Changed,Done}`

## 7. MYSOREZ endpoints

None remain in active code. The only occurrences of the string are explanatory comments and
historical documentation.

## 8–13. Actual latest release (v1.2.9) and metadata

| Field | Value |
|---|---|
| Repository | `redaf12-Decode/Generals-Android-Port` |
| Latest release | `v1.2.9` (draft=false, prerelease=false) |
| APK file name | `GeneralsXZH-android-run48.apk` |
| Download URL | `https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.2.9/GeneralsXZH-android-run48.apk` |
| Size | `93586754` bytes |
| SHA-256 | `83bb10593d3c81cd1de8b49e164eaec8a29bf72b31dcd41bcc785c096c3d5bdb` |
| versionCode / versionName | `10209` / `1.2.9` |
| Signing certificate SHA-256 | `644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e` |
| Metadata signature | ECDSA P-256 over `update.json`, verified against `UpdateChecker.PUBLIC_KEY_B64` — **OK** |

The metadata generator (`generate-update-metadata.py`) was run against the downloaded v1.2.9 APK;
it produced these values from the artifact itself and signed the document, and the signature
verified against the embedded public key.

## 14. Test / lint / build results

- `:app:testDebugUnitTest` — 339 tests, 0 failures, 0 errors (new: `PackageIdentityTest`,
  `ReleaseMetadataTest`).
- `:app:lintDebug` — 0 errors (the one reported item is a configured `HardcodedText` policy note).
- `:app:assembleLauncherOnly` — BUILD SUCCESSFUL; badging `com.ports.generalsx`, versionCode 10402,
  versionName 1.4.2, launchable activity `com.ports.generalsx.SetupActivity`.
- `scripts/qa/android/check-translations.py` — green (13 locales × 737 strings).
- `assembleRelease` — not run here (needs the NDK/vcpkg/DXVK toolchain); the Java-only
  `assembleLauncherOnly` is the equivalent check for the launcher. Reported honestly rather than
  claimed.

## 15. Final APK artifact

Not produced in this environment: the full APK needs the NDK + vcpkg + DXVK native build
(`scripts/build/android/build-dual-hz.sh`), which is a local, multi-hour job per AGENTS.md. The
launcher-only APK from `assembleLauncherOnly` was produced and inspected.

## 16. Commit and PR

- Commit: `3d3af4a32d34573ea45928af0199b0f6e7bb7882`
- PR: https://github.com/redaf12-Decode/Generals-Android-Port/pull/8 (base `New-app`)

## Open item for the repository owner

`UPDATE_METADATA_KEY` is not set as a GitHub Actions secret — the token this work ran under lacks
the `secrets` scope (`gh secret set` returns 403). The release workflows are written to stop before
uploading metadata if the secret is absent, so nothing unsigned can be published. Generate the key
with `scripts/update/generate-metadata-key.py`, paste the public half into
`UpdateChecker.PUBLIC_KEY_B64`, and store the private half as the secret.
