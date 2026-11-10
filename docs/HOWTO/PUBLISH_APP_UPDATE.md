# Publishing an app update

The launcher checks for a newer release when the Home screen opens, and — only if the release's
metadata is correctly signed — offers to download and install its APK. This document is for
maintainers publishing that release. It replaces the old `PUBLISH_UPDATE.md`, which described the
removed engine-OTA system.

## How the check works

`UpdateChecker` reads two files that the Publish Android Release workflow attaches to the newest
GitHub Release:

- `update.json` — the metadata (version, APK name, URL, size, SHA-256, signing certificate);
- `update.json.sig` — the ECDSA P-256 signature over the exact bytes of `update.json`.

The app carries only the public key (`UpdateChecker.PUBLIC_KEY_B64`). It verifies the signature
before it reads a single field, so a hand-edited or unsigned `update.json` is ignored outright. A
checksum alone would prove nothing about who wrote the file; the signature is the gate.

After the signature verifies, the app compares the metadata's `versionCode` with the installed
one. Only a strictly newer version is offered. The download is then checked again — size, SHA-256,
package name, version code and signing certificate — against the metadata, and finally against the
certificate of the app already installed. Only a file that passes every check is handed to
Android's own package installer, with the user's explicit approval.

## Publishing a release

1. Bump `versionCode` and `versionName` in `android/app/build.gradle` (the scheme is
   `major*10000 + minor*100 + patch`).
2. Build locally with both engines and commit the APK as the single file in `apk/`:
   ```bash
   ./scripts/build/android/build-dual-hz.sh
   cp GeneralsXZH-android-local.apk apk/<name>.apk
   ```
3. Write the release notes under `docs/releases/v<version>/notes.md`, in English only.
4. Push, then run **Actions → Publish Android Release** with the version (without the leading `v`).

The workflow:

- checks the APK is built as the requested version, carries all four engines, declares package
  `com.ports.generalsx` and is correctly signed;
- publishes the release with the APK and the symbol tables;
- generates `update.json` from the published APK's own values and signs it;
- uploads `update.json` and `update.json.sig` to the same release;
- re-reads the release and fails if any asset disagrees with the metadata.

If `UPDATE_METADATA_KEY` is not set the workflow stops before uploading the metadata: an unsigned
`update.json` would be rejected by the app anyway, so it is never published.

## The metadata signing key

The private key never lives in the repository. It is an ECDSA P-256 key stored as the repository
secret `UPDATE_METADATA_KEY`. To create a pair:

```bash
python3 scripts/update/generate-metadata-key.py /tmp/update-metadata-key.pem
```

Paste the printed public key into `UpdateChecker.PUBLIC_KEY_B64`, and store the private key's
contents as the `UPDATE_METADATA_KEY` secret. Replacing the key invalidates every previously signed
metadata, so only rotate it when the embedded public key is updated in the same commit.

## Regenerating metadata by hand

`scripts/update/generate-update-metadata.py` reads the APK's package name, version code, size,
SHA-256 and signing certificate straight out of the artifact, so nothing is ever hand-written:

```bash
python3 scripts/update/generate-update-metadata.py \
  --apk apk/<name>.apk \
  --repo redaf12-Decode/Generals-Android-Port \
  --tag v<version> \
  --out /tmp/upd \
  --key /tmp/update-metadata-key.pem
```

It refuses to run if a value is missing or if the GitHub asset it looks up disagrees with the bytes
on disk. `scripts/update/validate-release.py` does the same check against a published release.
