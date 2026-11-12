# Security baseline — `New-app` @ `ec34301` — 12/11/2026

Phase 0 of the security remediation. This is the record taken **before** any file was changed,
so every later phase can be measured against it. Findings here are grouped by the class of
problem they can cause, because the three are not the same thing:

- **Play Protect** — a malware/risk *classification* by Google's on-device scanner. Nothing in
  source can guarantee its removal; a signature, a permission or a behavior can *contribute*.
- **Android platform** — the OS refusing to install, warning the user, or a hardening rule being
  violated (e.g. a debuggable release, a cleartext URL, an exported component).
- **Google Play policy** — a distribution rule that only applies if the app is published on Play.

## 0. Verified repository state

| Item | Value |
|---|---|
| Branch | `New-app`, tracking `origin/New-app` |
| HEAD | `ec34301` *docs(android): record the green native build in the audit report* |
| Working tree | dirty (Phase 1–6 edits staged/uncommitted — see the closing section) |
| Remote | `https://github.com/redaf12-Decode/Generals-Android-Port.git` |
| Application ID | `com.ports.generalsx` |
| Gradle namespace | `com.ports.generalsx` (`android/app/build.gradle:50`) |
| Java package | `com.ports.generalsx` (`android/app/src/main/java/com/ports/generalsx/`) |
| versionName / versionCode | `1.4.2` / `10402` (`android/app/build.gradle:133`) |
| minSdk / targetSdk / compileSdk | 28 / 35 / 35 |
| Native libs (in APK) | `libmain.so`, `libmain60.so`, `libgenerals.so`, `libgenerals60.so` + SDL3/OpenAL/DXVK/gamespy |
| Native lib load site | `GeneralsZHActivity.loadLibraries()`; engine override via `UpdateManager.activeEngineLibrary` |
| CI workflows | `.github/workflows/` (17 active; `.github/workflows/old/` = retired) |
| Tests | JVM suite `android/app/src/test/…`, plus stdlib Python `tests/run_tests.py security` |
| Signing | production keystore from Actions secrets; `scripts/qa/android/verify-release-apk.py` gate |

## 1. Findings

Each row gives the file and line, the severity, the risk, the recommended remediation, and the
class of problem it can cause.

### F-1 — The published v1.2.9 release APK is a debuggable, debug-signed build · **CRITICAL**

- **File / evidence**: the released asset
  `GeneralsXZH-android-run48.apk` on the `v1.2.9` GitHub Release (published
  `2026-11-09T23:44:53Z`). Measured directly, not inferred:
  - `AndroidManifest.xml` carries `application android:debuggable=true`
    (binary manifest, attribute `0x0101000f` = `true`).
  - The APK Signature Scheme v2 signer certificate is
    `CN=GeneralsX Android Debug, OU=GeneralsX, O=GeneralsX, C=US`, SHA-256
    `64:4A:3B:0F:82:34:C6:EA:89:60:EC:E9:B5:5A:16:1C:00:96:6B:8D:D5:22:D2:55:8D:84:88:3F:4F:77:C4:8E`.
    It was signed with the **committed, public** debug key, so anyone can re-sign a modified APK
    that Android will accept as an update to it.
- **Severity**: CRITICAL.
- **Risk / exploitability**: A debuggable app signed with a public key is the textbook
  fingerprint of a repackaged/trojanised sideloaded APK. It is the strongest **Play Protect**
  contributor in this repository (the `PLAY_PROTECT_INVESTIGATION_20261112.md` report reached the
  same conclusion from the same APK). It is also an **Android platform** problem: the app is
  debuggable in production, and its update channel cannot distinguish a genuine update from a
  re-signed forgery.
- **Remediation**: already implemented on this branch — a production keystore (secret-only), a
  `release` build type that is `debuggable false` and signed with it, and the
  `verify-release-apk.py` gate in `publish-release.yml`. **The published asset itself has not
  been replaced** (see §2).
- **Class**: Play Protect (contributes) + Android platform.

### F-2 — `MANAGE_EXTERNAL_STORAGE` declared with `tools:ignore="ScopedStorage"` · **HIGH (policy)**

- **File / line**: `android/app/src/main/AndroidManifest.xml:45–46`.
- **Risk**: "All files access" is the most-scrutinised permission on Android. The manifest
  currently silences the Play-policy lint rather than justifying the need. It is a **Google Play
  policy** concern (Play requires a Permissions Declaration Form and permits it only for narrow
  use cases such as file managers). It is not itself a Play Protect malware signal.
- **Remediation**: keep it only if the game-folder / mod / map workflow genuinely cannot use the
  Storage Access Framework, and document the exact reason (Phase 5, `android-permissions-audit.md`).
- **Class**: Google Play policy.

### F-3 — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requested during sign-in · **MEDIUM (policy)**

- **File / line**: `GeneralsOnlineActivity.java` sign-in flow (pre-change), manifest line ~66.
- **Risk**: firing `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` on the first tap of Sign-in asks
  the user to decide something they cannot judge, and is a Play policy smell. It is not required:
  the login poll runs in the foreground.
- **Remediation**: removed the automatic prompt; replaced with a battery card that explains the
  trade-off and opens the system battery settings (Phase 5, done).
- **Class**: Google Play policy.

### F-4 — `DataPackInstaller` accepted an unsigned manifest with an optional digest · **HIGH**

- **File / line**: `DataPackInstaller.java` manifest handling (`installLocked`), pre-change.
- **Risk**: `download_url` accepted any HTTPS host, `sha256` was optional, `size` could be absent,
  and there was no hard cap. An unsigned manifest served by any HTTPS host could point the
  download anywhere. Extracted into shared game-data directories with no bound on the inflated
  size or entry count (a zip bomb / overwrite risk). **Android platform** + integrity.
- **Remediation**: mandatory trusted-host HTTPS URL, mandatory 64-hex SHA-256, mandatory positive
  size under a hard cap, redirect re-check, entry/duplicate/zip-bomb/zip-slip guards, `.part`
  staging, size enforced while streaming (Phase 4, done).
- **Class**: Android platform + data integrity.

### F-5 — Engine-update download trusted the signed manifest's URL without a transport check · **MEDIUM**

- **File / line**: `UpdateManager.open(url)` / `downloadEngineFile`.
- **Risk**: the manifest is signed, so the *content* was trusted, but the URL was fetched with no
  https/host check and redirects were followed blindly — a compromised or misconfigured host could
  move the fetch. `sha256`/`size` were also optional per entry. **Android platform**.
- **Remediation**: `isTrustedUpdateUrl` before the request and again after redirects, mandatory
  SHA-256 + positive size + hard cap, `.part` staging (Phase 3, done).
- **Class**: Android platform.

### F-6 — Imported Vulkan driver packages were validated structurally, not trusted · **MEDIUM**

- **File / line**: `CustomDriverPackage.extract` / `SetupActivity.importCustomDriver`.
- **Risk**: zip-slip, entry-count and total-size limits were present, and the ELF was checked for
  AArch64, but a package that passed those checks was stored and could be activated with no
  explicit confirmation. There is no authenticity control: `meta.json` inside the ZIP is trusted
  because it exists. The library runs `dlopen()`ed with the app's permissions. **Android platform**
  (untrusted native code) — and, in principle, a Play Protect contributor if abused.
- **Remediation**: reject duplicate entries and unexpected file types, require explicit user
  confirmation before activation stating that a third-party native library runs with the app's
  permissions, keep files app-private, keep rollback (Phase 2, done). An allowlist of verified
  publisher SHA-256 fingerprints remains open (see §3).
- **Class**: Android platform.

### F-7 — Synthetic hardware identifiers could be mistaken for real ones · **LOW**

- **File / line**: `NetworkDiagnostics.installId` / `syntheticMac` / `syntheticVolumeSerial`.
- **Risk**: the fields `machine_guid` / `mac_addr` / `vol_serial` are sent to the GeneralsOnline
  API. They are derived from a per-install random UUID and are marked locally-administered, and
  the code comments already say they are substitutes — but nothing user-facing states this, and
  the diagnostic report shows the install id beside the device model. **Privacy**, not Play
  Protect.
- **Remediation**: document every field and its provenance (Phase 6, `privacy-data-flow.md`).
- **Class**: Privacy.

### F-8 — Exported components and intent validation · **LOW (currently sound)**

- **File / line**: `AndroidManifest.xml` (`GeneralsZHActivity` and `SetupActivity` exported with
  `MAIN`; all other activities, both providers, `exported="false"`).
- **Risk**: `GeneralsZHActivity` is exported and appends intent extras to the engine command line.
  This is already guarded by `isSafeReplayName` (no separators, no `..`, length-bounded) and a
  clamped `fastTo`. No new exposure found.
- **Remediation**: add negative tests to lock the existing behaviour in place (Phase 7).
- **Class**: Android platform (hardening, no defect).

### F-9 — No `Scripts` / `Scriptsbackup` repository duplication · **INFORMATIONAL**

- **File / evidence**: the only `scripts/` tree is the authoritative one; the reported
  "Scripts → Scriptsbackup" is a **runtime, on-device** rename performed by `ModScriptsBackup.java`
  (off by default, only with a mod on, reversible, no code execution). Confirmed by
  `PLAY_PROTECT_INVESTIGATION_20261112.md` and by a repository-wide search here.
- **Remediation**: none required. Phase 9 verifies the tree stays clean.
- **Class**: none (report correction).

### F-10 — Retired workflow tree and a `MYSOREZ` mirror host · **INFORMATIONAL**

- **File / line**: `.github/workflows/old/` (retired workflows, documented) and the
  `raw.githubusercontent.com/MYSOREZ/GeneralsZH-Android-Port` mirror in `UpdateManager.RAW_ROOT`,
  `update/config.json`, `scripts/update/*.py`, `scripts/build/android/publish-apk-gitverse.sh`.
- **Risk**: the mirror host is a deliberate, working upstream of the engine-update channel
  (`updates` branch) and the language packs; it is not dead and not a leak. `MYSOREZ` is a
  repository path, not a credential.
- **Remediation**: keep, but ensure the trusted-host list covers it explicitly (done in Phase 3).
- **Class**: none (documented decision).

### F-11 — Two tooling scripts hardcoded an absolute path from one developer's checkout · **LOW**

- **File / line**: `scripts/tooling/theme/generate-accent-colors.py:36-40`
  (`OUT_DARK`/`OUT_DAY`/`OUT_THEMES` = `/workspace/project/Generals-Android-Port/...`) and
  `tools/translation-work/lang_build.py:19`
  (`/home/user/GeneralsZH-Android-Port/scripts/language/translate_kit.py`).
- **Risk**: not a security hole, but a repository-integrity defect: the accent-colour generator
  wrote its three committed outputs to a path that exists on one machine only (so a regeneration
  anywhere else silently did nothing, or wrote outside the repo), and the translation helper
  invoked a `translate_kit.py` that only exists on one machine.
- **Remediation**: both now resolve their targets relative to `__file__`, so they work from any
  clone. Fixed in Phase 9.
- **Class**: none (build/tooling correctness).

## 2. Status of the already-published v1.2.9 APK — explicit

The `v1.2.9` release asset `GeneralsXZH-android-run48.apk` **is still the old debuggable,
debug-signed APK**. Nothing on this branch changes that asset. A new workflow does not
retroactively fix it. To correct it the repository owner must publish a new release built from
this branch (production key, `debuggable false`), and existing installs will need a one-time
reinstall unless the signing-lineage secret is present (`docs/HOWTO/SIGNING_KEY_ROTATION.md`).

## 3. Residual open items (carried into later phases)

All four were closed on this branch:

1. Driver-package authenticity: an allowlist of verified publisher SHA-256 fingerprints — closed
   in Phase 2 (`CustomDriverPackage.VERIFIED_DRIVER_SHA256` + explicit activation confirmation).
2. `MANAGE_EXTERNAL_STORAGE` justification — closed in Phase 5 (documented in
   `android-permissions-audit.md`; the SAF migration is recorded as the residual option).
3. A user-visible statement that the synthetic identifiers are not real hardware identifiers —
   closed in Phase 6 (`NetworkDiagnostics` header, in every language).
4. Negative tests for exported-component intent handling — closed in Phase 7
   (`GeneralsZHActivity.appendReplayExtras` extracted as a pure static helper and covered by
   `CustomDriverPackageTest`, plus `check-manifest-hardening.py`).

## 4. Changes already in the working tree when this baseline was taken

Phases 1–6 (see `docs/WORKDIR/reports/ANDROID_SECURITY_AUDIT_20261110.md` and the phase reports)
had already modified `AndroidManifest.xml`, `CustomDriverPackage.java`, `DataPackInstaller.java`,
`GeneralsOnlineActivity.java`, `GeneralsOnlineSession.java`, `SetupActivity.java`,
`UpdateManager.java`, the 13 `values-*/strings.xml`, `CustomDriverPackageTest.java`,
`UpdateManagerTest.java`, `tests/security/test_signing.py`, and added
`DataPackInstallerTest.java`. All repository checks pass (`tests/run_tests.py` → 38 passed;
`check-no-committed-secrets.py`, `check-no-old-package.py`, `check-translations.py`,
`check-trust-anchors.py`, `check-driver-allowlist.py`, `check-update-metadata.py`,
`check-manifest-hardening.py` → exit 0).

Phases 7–9 then added `check-manifest-hardening.py`, the intent-extras tests, the
`appendReplayExtras` extraction, and the two absolute-path fixes (F-11).
