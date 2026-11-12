# Signing keys and rotation (Android)

This repository has **two independent signing domains** for updates plus one **APK signing
identity**. They are rotated separately, on purpose: a key that signs one document never signs
another, so compromising (or rotating) one domain cannot silently change the trust of the other.

| Domain | Signs | Private key (never committed) | Public key pinned in |
|---|---|---|---|
| APK release | the release APK | `generalsx-release.jks` | the OS / the launcher's own `ApkInstaller` check |
| Update metadata | `update.json` | `UPDATE_METADATA_SIGNING_KEY` | `UpdateChecker.PUBLIC_KEY_B64` |
| Engine update | `manifest.json` | `UPDATE_SIGNING_KEY` | `UpdateManager.PUBLIC_KEY_B64` **and** `GXSignedSettings.cpp` `kPublicKeyB64` |

`scripts/qa/android/check-trust-anchors.py` (run in CI) fails the build if the three pinned anchors
disagree with each other, stop parsing as P-256, share a key across domains, or still carry a
retired generation. The two engine-update copies (launcher + native engine) must be byte-for-byte
identical.

## Why this exists: the retired debug keystore

The APK used to be signed with a debug keystore committed to this repository
(`android/app/debug.keystore`, alias `androiddebugkey`, password `android`). That key is public and
**compromised**: anyone can build an APK the OS accepts as an update to an existing install. It is
no longer used, and the file is gone from the tracked tree. The other two domains were rotated at
the same time (12/11/2026) even though they were not compromised, so that every anchor in the
repository is from one known generation.

## Current fingerprints (public — safe to record)

| Identity | Fingerprint |
|---|---|
| APK certificate SHA-256 | `D1:CE:EC:FE:0F:59:EA:47:26:46:17:83:71:C2:7E:EC:C2:FA:5B:F8:B2:98:2E:60:FA:43:95:0F:0E:E5:92:FF` |
| Update-metadata key (SPKI SHA-256) | `ca6db72af7b72417f9a8207bd31ecc8dce5b37df6876419e3cc8c56c469cf137` |
| Engine-update key (SPKI SHA-256) | `bf65f3b25e111a8a62c9f2330b39ac204fa7ace11fdc3996ea176b30f31ac96d` |

The private halves are **not** in the repository and must never be. They live only as GitHub
Actions secrets and in an offline backup.

## 1. Rotating the keys

`scripts/update/rotate-signing-keys.py` generates a fresh set, writes the public anchors into the
three source files, re-signs `update.json`, and prints the secrets to configure. It never prints a
private key.

```bash
python3 scripts/update/rotate-signing-keys.py --out /tmp/gx-keys   # writes private material to /tmp/gx-keys
```

It produces:

- `generalsx-release.jks` — RSA-4096, SHA-256 certificate signature, ~33-year validity, alias
  `generalsx`. (RSA-4096 is the recommended Android production configuration; the update-metadata
  and engine-update domains stay ECDSA P-256.)
- `update-metadata-key.pem`, `engine-update-key.pem` — ECDSA P-256.
- `public-keys.env` — the new public values and fingerprints (not secret).
- `set-secrets.sh` — a `gh secret set` helper for the values below.

Move the generated directory to offline storage before doing anything else, and treat the two
`*.pass` files as secrets.

## 2. GitHub Actions secrets

Add these to the repository (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | `base64 -w0 generalsx-release.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | the keystore password |
| `ANDROID_RELEASE_KEY_ALIAS` | `generalsx` |
| `ANDROID_RELEASE_KEY_PASSWORD` | the key password (same as the store password for a PKCS12 keystore) |
| `ANDROID_RELEASE_CERT_SHA256` | the APK certificate fingerprint above (the workflow verifies the built APK against it) |
| `UPDATE_METADATA_SIGNING_KEY` | the full PEM of `update-metadata-key.pem` |
| `UPDATE_SIGNING_KEY` | the full PEM of `engine-update-key.pem` |
| `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64` | *(optional)* the retired debug keystore, for the migration lineage — see §3 |

The build workflow decodes the keystore to a runner-temp file, exports the passwords through the
environment (GitHub masks them in logs), and deletes the temp file at the end of the job. A build
without the four release values is **refused** at configuration time — it never falls back to debug
signing.

## 3. Migrating existing installs

An app can only be updated in place by an APK signed with the **same** key, or — from Android 9
(API 28) — by one carrying a **v3 signing lineage** in which the old key authorises the new one.
The port's `minSdk` is 28, so every supported device honours a lineage.

### 3a. Signing-key rotation with a lineage (preferred)

Add the old keystore as a secret and the workflow attaches the lineage automatically:

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64` | `base64 -w0 android/app/debug.keystore` (recover from Git history) |

The old debug keystore is public, so putting it in a secret only keeps the workflow tidy — it is not
confidential. With the secret present, the workflow's "Apply signing-key rotation lineage" step runs
`scripts/build/android/apply-signing-lineage.sh`, which builds the lineage (old → new), re-signs the
release APK with it, and asserts the v3 proof-of-rotation attribute is present. Devices then accept
the update without an uninstall. The same script runs for a local release build when
`GX_RELEASE_LINEAGE_KEYSTORE` is set.

### 3b. One-time uninstall / reinstall (fallback)

If the old private key is unavailable, no lineage can be built, and an install signed with the old
key cannot be updated in place. **Changing a public key inside the app does not change Android's APK
signing identity** — the OS compares APK signatures, not in-app constants. The user must uninstall
the old app once and install the new APK. Warn them that an uninstall removes app-private data;
saves and settings under shared storage (`Android/data/com.ports.generalsx/`) survive, and the
migration never deletes game data.

## 4. Do not rewrite public history

The old key is already in Git history and has been downloaded. Removing the file from the latest
commit does not revoke it. Do not rewrite public history for this: contributors' clones would break
and the key would still be known. Rotate forward instead, as above. The old key is kept in its
offline backup until the migration is confirmed, then retired.

## 5. Verifying a release

```bash
python3 scripts/qa/android/verify-release-apk.py \
    --apk app-release.apk \
    --expect-cert-sha256 "$ANDROID_RELEASE_CERT_SHA256" \
    --require-rotation        # only when a lineage is expected
```

A release must be signed (v2, or v3 when a lineage is attached), must **not** be debuggable, must
carry `package: name='com.ports.generalsx'`, and — when a fingerprint is given — must match it. The
build workflow asserts all of this before the APK can be published, and a local release build
verifies its own certificate through `GX_RELEASE_CERT_SHA256` in `android/keystore.properties`.

## 6. Emergency recovery

- **Production keystore lost.** Restore it from the offline backup. If it is truly gone, the only
  path is another rotation: generate a new keystore, attach a lineage from the *previous* key (if
  you still hold it), and update the secrets. Without any ancestor key, existing installs need the
  §3b uninstall.
- **Update-metadata or engine-update key lost.** Regenerate that one domain with
  `rotate-signing-keys.py`, update its secret, and commit the new public anchor. The domains are
  independent, so the other domain and the APK are unaffected. Old app versions trust the old
  anchor and will reject metadata signed with the new key until they are updated — this is why a
  rotation is paired with a release.
- **No indefinite dual-key transition.** The old and new metadata keys are never both accepted:
  `UpdateChecker` verifies against exactly one pinned key. A device on an old app keeps trusting the
  old key until it installs the new APK; there is no overlap window in which both are trusted.
