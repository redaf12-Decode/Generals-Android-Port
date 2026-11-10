# Signing key rotation (Android releases)

The APK used to be signed with a debug keystore that was committed to this repository
(`android/app/debug.keystore`, alias `androiddebugkey`, password `android`). That key is public and
must be treated as **compromised**: anyone can build an APK the OS will accept as an update to an
existing install. It is no longer used. Releases are signed with a production key that lives only
in GitHub Actions secrets.

This document is the controlled migration for existing installs.

## 1. The production key

Create the key **outside** the repository (never commit it, never paste it into a log):

```bash
keytool -genkeypair -v \
  -keystore generalsx-release.jks \
  -storetype JKS \
  -keyalg EC -groupname secp256r1 \
  -validity 10000 \
  -alias generalsx \
  -dname "CN=GeneralsX, O=GeneralsX, C=US"
```

Back it up somewhere safe and offline. If this key is lost, you can never update an install again
without a signing-key rotation.

## 2. GitHub Actions secrets

Add these to the repository (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | `base64 -w0 generalsx-release.jks` |
| `ANDROID_RELEASE_STORE_PASSWORD` | the keystore password |
| `ANDROID_RELEASE_KEY_ALIAS` | `generalsx` |
| `ANDROID_RELEASE_KEY_PASSWORD` | the key password |

The build workflow decodes the keystore to a runner-temp file, exports the passwords through
`$GITHUB_ENV` (which GitHub masks in logs), and never prints them. A build without these secrets
falls back to a throwaway key and is **refused** by the publish step — only a build with the real
secrets can be released.

## 3. The transition for existing installs

An app can only be updated in place by an APK signed with the **same** key. There are two ways to
move an existing install from the old public key to the new production key.

### 3a. Signing-key rotation with a lineage (preferred, Android 9 / API 28+)

Android's APK Signature Scheme v3 lets the old key authorise the new one, so a device that already
trusts the old key accepts an APK signed with the new key. Add one more secret:

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64` | `base64 -w0 android/app/debug.keystore` (from Git history) |

The old debug keystore is public, so putting it in a secret only keeps the workflow tidy — it is not
confidential. When this secret is present the workflow's "Apply signing-key rotation lineage" step
runs `apksigner rotate` (old debug key → new production key) and re-signs the release APK with that
lineage attached. Devices on API 28+ then accept the update without an uninstall.

To verify the result:

```bash
apksigner verify --verbose --print-certs app-release.apk   # must show a lineage / v3 signer
```

### 3b. One-time uninstall / reinstall (fallback)

If no lineage is attached, an install signed with the old key cannot be updated in place. The user
must uninstall the old app once and install the new APK. Warn them that an uninstall removes
app-private data; saves and settings under shared storage survive.

## 4. Do not rewrite public history

The old key is already in Git history and has been downloaded. Removing the file from the latest
commit does not revoke it. Do not rewrite public history for this: contributors' clones would
break and the key would still be known. Rotate forward instead, as above.

## 5. Verify a release

```bash
apksigner verify --verbose --print-certs app-release.apk
aapt2 dump badging app-release.apk | grep -E "package:|application-debuggable"
```

A release must be signed (v2+, and v3 when a lineage is present), must **not** be debuggable, and
must carry `package: name='com.ports.generalsx'`. The build workflow asserts all three before the
APK can be published.
