# Android security audit and release hardening — 10/11/2026

Scope: the GeneralsX Android launcher (`android/`), its in-app update system, APK signing and
release pipeline, and the CI workflows. Branch: `security/android-hardening` (from `New-app`).
Base commit inspected: `427391d8d02fcbd861dd9fc442f83543a2e399f7`.

## 1. Confirmed findings

| # | Finding | Severity | Status |
|---|---|---|---|
| F1 | A debug keystore (`android/app/debug.keystore`, alias `androiddebugkey`, password `android`) was committed to a public repository and used to sign the released APK. | Critical | Fixed (key retired) |
| F2 | The release APK was `app-debug.apk` renamed: `android:debuggable=true`, signed with the public debug key. | Critical | Fixed |
| F3 | `update.json` shipped an all-zero `apkSha256` and `apkSizeBytes: 0`, and an `apkUrl` that pointed at a non-existent release asset (`.../v1.5.0/app-release.apk`). | High | Fixed |
| F4 | The update metadata was not digitally signed, and the client trusted it unauthenticated. | High | Fixed |
| F5 | `apkUrl` validation was a string-prefix check (`startsWith`), spoofable by `https://github.com.evil.example/...` and `https://github.com@evil.example/...`. | High | Fixed |
| F6 | The download had no hard size ceiling when the metadata declared size 0 — a server could stream unbounded data. | High | Fixed |
| F7 | The download did not validate the URL after HTTP redirects, so a redirect could move the fetch off the trusted host or downgrade to http. | Medium | Fixed |
| F8 | `verify()` would skip the checksum/size checks when the metadata carried no digest or size. | Medium | Fixed |
| F9 | `GeneralsZHActivity` is exported and appends the caller-supplied `-replay` extra to the native command line without validation. | Medium | Fixed |
| F10 | `android:allowBackup="true"` with no backup rules: the GeneralsOnline session/refresh tokens could be copied off-device by a backup/transfer. | Medium | Fixed |
| F11 | `NetworkTrace.redact()` did not cover the `key=value` token form the session marker file uses. | Low | Fixed |
| F12 | CI built and published a debug APK, ran no unit tests in CI (the step was removed 06/11/2026), and granted workflow-wide write permissions. | High | Fixed |
| F13 | `MANAGE_EXTERNAL_STORAGE` is still requested. | Informational | Retained (see §3) |

## 2. Fixes implemented

### Signing and release configuration (F1, F2, F12)
- `android/app/debug.keystore` removed and `android/.gitignore` extended (`*.keystore`, `*.jks`,
  `keystore.properties`, `signing/`).
- `android/app/build.gradle`: signing material comes from `GX_RELEASE_*` (env / `keystore.properties`);
  the release build type is non-debuggable and signed only with the production key. A task-level
  guard fails `assembleRelease` if the key is absent, without breaking debug/lint/test tasks.
- `scripts/build/android/package-android-zh.sh`: `GX_ANDROID_VARIANT` selects the build type
  (`debug` default for local test builds, `release` for CI releases) and the APK path follows.
- `.github/workflows/build-android.yml`: builds `assembleRelease`, decodes the keystore from secrets
  to a runner-temp file, and verifies the artifact (`apksigner verify`, not debuggable, package
  id) before it can be published. The publish steps refuse an ephemeral-signed (fork-PR) build.
- `docs/HOWTO/SIGNING_KEY_ROTATION.md`: the controlled migration.

### Update system (F3–F8)
- `scripts/update/generate-update-json.py`: one source of truth. Reads the real APK (AXML-parsed
  versionCode/versionName), computes sha256/size, writes `update.json` **and** signs it into
  `update.json.sig` (ECDSA-P256/SHA-256). Rejects a missing APK or inconsistent values.
- `update.json` regenerated from the real v1.2.9 release asset and signed. `update.json.sig` added.
- `UpdateChecker.java`: strict field validation (64-hex non-placeholder sha256, positive bounded
  size, package name), URI-based URL validation (scheme, host, path, no user-info, port), and
  signature verification of `update.json` against a pinned public key before any field is used.
- `ApkInstaller.java`: hard download ceiling, final-URL validation after redirects, and a strict
  verify order (size → checksum → package → version → signing-certificate lineage) that refuses an
  APK with no promised digest/size.
- `.github/workflows/publish-update.yml`: generates and signs the metadata from the released APK and
  refuses to publish placeholders.
- `scripts/qa/android/check-update-metadata.py`: CI check that the committed `update.json`/`.sig`
  are self-consistent and verify against the key pinned in `UpdateChecker.java`.

### Permissions and manifest (F9, F10)
- `GeneralsZHActivity.java`: validates the `-replay` extra (bare file name, no separators, bounded)
  and the fast-forward frame number before they reach the native command line.
- `res/xml/backup_rules.xml` (Android 11-) and `res/xml/data_extraction_rules.xml` (Android 12+)
  exclude `files/generalsonline_session.txt` and the network-trace logs from backup/transfer.
- `REQUEST_INSTALL_PACKAGES` is requested at runtime only when the player taps Install on an
  already-verified update.

### Sensitive data (F11)
- `NetworkTrace.java`: a third redaction pattern for `session_token=`/`refresh_token=`/`access_token=`
  in `key=value` form, plus tests.

### CI and repository protection (F12)
- `.github/workflows/android-unit-tests.yml`: `testDebugUnitTest` re-enabled, plus the
  update-metadata check and the committed-secrets scan; PRs to `New-app` already trigger it.
- `.github/workflows/build-android.yml`: `New-app` added to the PR trigger; write permissions moved
  from the workflow to the single `build-android` job.
- `scripts/qa/android/check-no-committed-secrets.py`: fails CI on a committed keystore, a PEM
  private-key block, or a hardcoded credential in the launcher's Java.

## 3. Findings that remain unresolved, and why

- **F13 `MANAGE_EXTERNAL_STORAGE`.** The native engine opens game files by absolute path and the
  launcher's folder picker browses the user's storage; the Storage Access Framework hands back a
  content URI, not the filesystem path the engine needs. Replacing it requires an import/copy step
  into app-private storage and a large engine-side change. It is kept deliberately, with the
  permission requested contextually (when the player picks a game folder), and documented here.
  Removing it is a follow-up, not a release blocker.
- **Signing-key rotation not executed.** The lineage path (`apksigner rotate` in the workflow) is
  implemented, but it needs the repository owner to generate the production key and add the secrets
  (`ANDROID_RELEASE_*`, `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64`) — this audit does not have, and
  must not create, the production key. Until then existing installs update only after a one-time
  uninstall/reinstall. See `docs/HOWTO/SIGNING_KEY_ROTATION.md`.
- **The old key in Git history is not revoked.** Rewriting public history would break contributors'
  clones and would not revoke a key already downloaded. The key is retired forward; the lineage
  mechanism is the supported migration.
- **Play Protect cannot be attributed to a specific file.** The exact blocked APK was not available,
  and the launcher contains no packer, dropper, hidden DEX, dynamic code from shared storage, or
  silent-install path — it is a normal game that loads only its own bundled libraries. See §6.

## 4. Modified files

| File | Change |
|---|---|
| `android/app/build.gradle` | Production signing from env/properties; release non-debuggable; release-key guard task |
| `android/app/debug.keystore` | Deleted |
| `android/.gitignore` | Ignore keystores, `keystore.properties`, `signing/` |
| `android/app/src/main/AndroidManifest.xml` | Backup/data-extraction rules; permission comment |
| `android/app/src/main/java/.../GeneralsZHActivity.java` | Replay/extra validation |
| `android/app/src/main/java/.../UpdateChecker.java` | Strict validation, URI checks, signature verification |
| `android/app/src/main/java/.../ApkInstaller.java` | Download cap, redirect-URL check, strict verify |
| `android/app/src/main/java/.../NetworkTrace.java` | `key=value` token redaction |
| `android/app/src/main/res/xml/backup_rules.xml` | New |
| `android/app/src/main/res/xml/data_extraction_rules.xml` | New |
| `scripts/build/android/package-android-zh.sh` | Build-type selection |
| `scripts/update/generate-update-json.py` | New — generate + sign metadata |
| `scripts/qa/android/check-update-metadata.py` | New — CI metadata check |
| `scripts/qa/android/check-no-committed-secrets.py` | New — CI secret scan |
| `update.json`, `update.json.sig` | Regenerated and signed |
| `.github/workflows/build-android.yml` | Release build + signing + verification; New-app; scoped permissions |
| `.github/workflows/android-unit-tests.yml` | Tests + metadata + secret scan |
| `.github/workflows/publish-update.yml` | New |
| `docs/HOWTO/SIGNING_KEY_ROTATION.md` | New |
| tests under `android/app/src/test/...` | New/updated security tests |

## 5. Test and build results

- `gradle :app:testDebugUnitTest`: **373 tests, 0 failures, 0 skipped**.
- `gradle :app:lintDebug`: **clean**.
- `gradle :app:assembleRelease` (production signing config): **BUILD SUCCESSFUL**.
- `scripts/qa/android/check-update-metadata.py`: passes against the committed, signed `update.json`.
- `scripts/qa/android/check-no-committed-secrets.py`: passes (no committed signing material).

Tests added/updated cover: invalid SHA-256, wrong size, zero-byte APK, oversized download, invalid
package name, invalid/downgraded version, wrong signing certificate, untrusted lineage, malformed
JSON, unsigned metadata, URL-prefix spoofing, http downgrade, redirect to an untrusted host,
malicious ZIP paths, ZIP resource exhaustion, invalid driver metadata, non-AArch64 ELF, token
redaction, and backup exclusion.

## 6. Final APK verification (local, production signing configuration)

> Signed with a throwaway local test key to exercise the configuration; the real release uses the
> key from GitHub secrets.

| Property | Value |
|---|---|
| SHA-256 | `1723e219bbbcc83ae5e015011ee11efbd6a14f899604ac636cb25d7c5026e8fa` |
| Size | 12,005,279 bytes |
| Package name | `com.ports.generalsx` |
| versionName / versionCode | `1.4.2` / `10402` |
| min / target SDK | 28 / 35 |
| Certificate SHA-256 (test key) | `f5e2298f04464deba33953b0d1fb2ffb530ff8bd7bf0e4878419184eae8172ec` |
| apksigner verify | Verifies, v2 scheme, 1 signer |
| Debuggable | no |
| Permissions | `MANAGE_EXTERNAL_STORAGE`, `READ/WRITE_EXTERNAL_STORAGE` (maxSdk 29), `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `REQUEST_INSTALL_PACKAGES` |

For comparison, the last published release (v1.2.9, `GeneralsXZH-android-run48.apk`,
sha256 `83bb10593d3c81cd1de8b49e164eaec8a29bf72b31dcd41bcc785c096c3d5bdb`, 93,586,754 bytes) was
signed with the same public debug certificate `644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e`
and was debuggable.

## 7. Play Protect investigation

The warning ("Harmful App Blocked") could not be attributed to a specific file or behaviour because
the exact blocked APK was not provided. What the audit can state with evidence:

- The released APK was a **debuggable** build signed with a **public, committed key**. That alone is
  a standard reason for Google Play Protect to flag a sideloaded app: it is the fingerprint of an
  app that anyone can re-sign and repackage. This is the most likely trigger, and it is now fixed.
- No evidence of the behaviours Play Protect blocks was found: no packer, no dropper, no secondary
  DEX, no `DexClassLoader`/`PathClassLoader`, no reflection-driven code loading, no executable code
  loaded from shared storage, and no silent/privileged installation. Every `System.loadLibrary` call
  loads a bundled engine library or the optional bundled Vulkan driver.
- The app requests `MANAGE_EXTERNAL_STORAGE`, which Play Protect can weigh as a broad-permission
  signal; the rationale is documented in §3.

Do not claim the app is cleared until Google's own scan or review confirms it. If the final,
properly signed, non-debuggable build is still detected, the evidence for an appeal is: the
apksigner output, the non-debuggable flag, the certificate fingerprint, the permission list, and
this report's finding that no dynamic-code or silent-install behaviour exists.
