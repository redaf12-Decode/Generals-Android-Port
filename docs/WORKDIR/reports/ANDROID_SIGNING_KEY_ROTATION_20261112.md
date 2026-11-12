# Android signing key rotation — report (12/11/2026)

Branch `New-app`. Commits `d5cecd9`, `ecc3df0`, `c317ff7`, `29272f2`, `396e405`.

## 1. Keys discovered (Phase 1 audit)

| # | File | Purpose | Algorithm | Fingerprint | Consumer | Status |
|---|---|---|---|---|---|---|
| 1 | `android/app/debug.keystore` (git history; removed from tree) | signed released APKs | RSA-2048 debug | cert SHA-256 `644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e`, alias `androiddebugkey`, password `android` | Android OS | **compromised, retired** |
| 2 | `UpdateChecker.PUBLIC_KEY_B64` | verifies `update.json` | ECDSA P-256 SPKI | SPKI SHA-256 `89d7abc6…c0088b` | launcher update check | retired |
| 3 | `UpdateManager.PUBLIC_KEY_B64` | verifies `manifest.json` | ECDSA P-256 SPKI | SPKI SHA-256 `631c9268…d1a7baee` | launcher engine update | retired |
| 4 | `GXSignedSettings.cpp` `kPublicKeyB64` | verifies `manifest.json` (engine) | ECDSA P-256 SPKI | same as #3 | native engine | retired |

Only three pinning sites exist (two engine-update copies must match); no other embedded key,
certificate, PEM/DER/JKS/PKCS12, `storePassword`/`keyAlias`, or hardcoded fingerprint was found in
`android/`, `.github/`, `scripts/`, `Core/`, `Generals/`, `GeneralsMD/`, `cmake/`, `update/`,
`tests/`, `docs/` (the only other PEM is `GeneralsOnlineRelease/cacert.pem`, a public CA bundle,
unrelated). No private key is tracked; `android/.gitignore` already excludes `*.keystore`, `*.jks`,
`keystore.properties`, `signing/`.

## 2. New identities generated (Phase 2)

| Domain | Key | Algorithm | Fingerprint |
|---|---|---|---|
| APK release | `generalsx-release.jks` (PKCS12), alias `generalsx` | RSA-4096, SHA256withRSA, validity ~33 y | cert SHA-256 `D1:CE:EC:FE:0F:59:EA:47:26:46:17:83:71:C2:7E:EC:C2:FA:5B:F8:B2:98:2E:60:FA:43:95:0F:0E:E5:92:FF` |
| Update metadata | `update-metadata-key.pem` | ECDSA P-256 | SPKI SHA-256 `ca6db72af7b72417f9a8207bd31ecc8dce5b37df6876419e3cc8c56c469cf137` |
| Engine update | `engine-update-key.pem` | ECDSA P-256 | SPKI SHA-256 `bf65f3b25e111a8a62c9f2330b39ac204fa7ace11fdc3996ea176b30f31ac96d` |

Generated **outside** the repository by `scripts/update/rotate-signing-keys.py` (mode 600). The
private material is not committed anywhere.

## 3. Keys replaced (Phase 3)

- `UpdateChecker.PUBLIC_KEY_B64` → new metadata key.
- `UpdateManager.PUBLIC_KEY_B64` → new engine-update key.
- `GXSignedSettings.cpp` `kPublicKeyB64` → **same** new engine-update key (byte-for-byte).
- `update.json.sig` re-signed with the new metadata key.

Signature serialization unchanged (base64 of a raw ECDSA P-256 / SHA-256 signature); DER
SubjectPublicKeyInfo encoding and Base64 decoding are validated
(`scripts/qa/android/check-trust-anchors.py`). Malformed, invalid, unsigned and old-key metadata are
all rejected (tests below). No fallback verification and no key from remote metadata is trusted.

## 4. APK signing migration (Phase 4)

`android/app/build.gradle`: the four `GX_RELEASE_*` values are required together (env → gitignored
`android/keystore.properties` → `-P`); `assembleRelease`/`bundleRelease`/`packageRelease` fail at
configuration time if any is missing — no debug fallback. Optional `GX_RELEASE_CERT_SHA256` makes
`packageRelease` verify the built APK's certificate. `applicationId com.ports.generalsx`, the
namespace, ABIs and all variants are unchanged; debug signing stays separate and env-supplied.

## 5. Workflows (Phase 5)

- `build-android.yml` — decodes the keystore to a runner-temp file (`umask 077`), exports secrets
  through the environment, deletes the temp file, verifies the APK is signed/non-debuggable/package
  id/certificate fingerprint, applies the signing lineage when the old-keystore secret is present,
  and asserts the proof-of-rotation attribute. No secret reaches an artifact or a log.
- `publish-update.yml` / `sign-update.yml` — after signing, verify the signature against the pinned
  key **read from source** (`scripts/qa/android/print-pinned-key.py`), so a drifted secret fails at
  sign time.
- `android-unit-tests.yml` — runs `tests/run_tests.py`, keeps the committed-secret scan and the
  trust-anchor check.

## 6. Installation migration (Phase 6)

The old APK key is public but **recoverable from git history**, so a v3 proof-of-rotation lineage is
possible. `scripts/build/android/apply-signing-lineage.sh` builds `apksigner rotate` (old → new),
re-signs v3-only with `--rotation-min-sdk-version 28`, and asserts the lineage attribute. `minSdk`
is 28, so every supported device (Android 9+) accepts the update in place. If the old key were
unavailable, the fallback is a documented one-time uninstall/reinstall; no lineage means no in-place
update. **Changing an in-app public key does not change Android's APK signing identity** — the OS
compares APK signatures — and this is stated in the docs.

## 7. Transition & obsolete keys (Phases 7–8)

Old keys are retired but their offline backup is retained (recovery). The old debug keystore is
**not** deleted from git history (rewriting public history would break clones and would not revoke
the key). No old key literal, fingerprint, or secret name remains in active code; the retired
fingerprints are kept only as fingerprints in the checker and as a note in the docs. Old and new
metadata keys are never both trusted — `UpdateChecker` pins exactly one.

## 8. Tests (Phase 9) — `tests/security/test_signing.py`, 19 pass / 0 fail

Metadata: fresh accepted; old-key, modified, invalid-base64 and missing rejected. Trust anchors:
three must agree, differ per domain, verify the committed metadata under the metadata key only, and
carry no retired key; rotation script rewrites all three and leaves the tree consistent;
`print-pinned-key.py` emits the pinned SPKI; secret scan rejects a committed keystore and a PEM key;
APK verifier refuses a non-APK and a missing toolchain. All fixtures are throwaway keys generated in
the test; no production key is a fixture. JVM `TrustAnchorTest` (5 tests, Robolectric) asserts the
same invariants.

## 9. Build & validation (Phase 10)

- Gradle JVM unit tests: **378 tests, 0 failures** (`testDebugUnitTest`).
- `:app:lintDebug`: BUILD SUCCESSFUL, no actionable issues.
- `:app:assembleRelease` with signing material: **signed, non-debuggable, `com.ports.generalsx`**,
  certificate SHA-256 matches the new production certificate; `apksigner verify` reports v2 true
  (v3 when a lineage is attached).
- `:app:assembleRelease` **without** signing material: **BUILD FAILED** at configuration time with
  "Release signing is not configured" — the secure-failure check.
- `apply-signing-lineage.sh` end-to-end: re-signs the release APK, lineage attribute present,
  `verify-release-apk.py --require-rotation` passes.
- Python signing suite: **19 passed**; `check-trust-anchors.py`, `check-no-committed-secrets.py`,
  `check-update-metadata.py`, `check-translations.py` all pass.

## 10. Files modified

`.github/workflows/{build-android,android-unit-tests,publish-update,sign-update}.yml`,
`android/app/build.gradle`, `android/app/src/main/java/com/generalsx/zerohour/{UpdateChecker,UpdateManager}.java`,
`GeneralsMD/Code/GameEngine/Source/Common/GXSignedSettings.cpp`, `update.json.sig`,
`scripts/build/android/{apply-signing-lineage.sh,package-android-zh.sh}`,
`scripts/qa/android/{check-trust-anchors.py,print-pinned-key.py,verify-release-apk.py,check-no-committed-secrets.py,check-update-metadata.py}`,
`scripts/update/rotate-signing-keys.py`, `tests/{run_tests.py,security/test_signing.py}`,
`android/app/src/test/java/com/generalsx/zerohour/TrustAnchorTest.java`,
`docs/HOWTO/{SIGNING_KEY_ROTATION,PUBLISH_UPDATE,README}.md`, `docs/port/ANDROID_PORT.md`.

## 11. GitHub Secrets requiring manual configuration

| Secret | Source |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | `base64 -w0 generalsx-release.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | generated |
| `ANDROID_RELEASE_KEY_PASSWORD` | same as store password (PKCS12) |
| `ANDROID_RELEASE_KEY_ALIAS` | `generalsx` |
| `ANDROID_RELEASE_CERT_SHA256` | the certificate fingerprint above |
| `UPDATE_METADATA_SIGNING_KEY` | full PEM of `update-metadata-key.pem` |
| `UPDATE_SIGNING_KEY` | full PEM of `engine-update-key.pem` |
| `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64` *(optional)* | `base64 -w0 android/app/debug.keystore` from git history |

`set-secrets.sh` (written next to the generated keys) sets all of them with `gh`. **The owner must
run it** — the agent cannot (it must not upload private keys). Until it runs, no real release
signing happens; local builds use `android/keystore.properties`.

## 12. Remaining risks

1. **Secrets not yet configured.** The rotation is not live until the owner sets them. A release
   without them fails closed (never debug-signed), but produces nothing.
2. **Lineage depends on the old debug keystore secret.** If it is omitted, the release is
   production-signed but has no lineage and existing installs need a one-time reinstall. With it,
   the update is in place on Android 9+.
3. **Old app versions pin the old metadata key** and will reject metadata signed with the new key
   until they install the new APK. There is no dual-key window; a rotation is paired with a release.
4. **`apk/` artifacts in the tree** still carry the old anchors; they are not rebuilt by a source
   change. Rebuilding the shipped APK requires a full `build-dual-hz.sh` run (NDK + vcpkg + DXVK),
   which is outside this security task's scope.
5. **Only v3 carries a lineage**; v1/v2 cannot. `minSdk` is 28 so this is not a device gap, but any
   future `minSdk` < 28 would break the lineage path.
