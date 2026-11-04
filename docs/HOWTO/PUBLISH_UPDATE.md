# Publishing an update without a new APK

The launcher checks the repository's `updates` branch when the **GeneralsOnline account**
screen opens, and applies the **settings** it finds there (shown on that screen).

Since 04/11/2026 the launcher no longer downloads the **engine** over the air: the Home
screen's "Updates" card, with its **Check for updates** button and its automatic check, was
removed at the owner's request, and with it the only caller that asked for an engine download.
An engine update now needs a new APK. The signing, the manifest and the publishing steps below
are unchanged, so a later change of mind only has to put the card back.

- **Settings** (`update/config.json` in the main tree): values the engine reads at startup. Today
  the STUN and TURN server lists (`stun_servers`, `turn_servers`), the PC client checksum for
  cross-play (`pc_exe_crc`, computed with `scripts/update/pc-exe-crc.py`) and the community data
  patch manifest address (`datapack_manifest_url`), and whether the current PC release ends its
  logic checksum with the GeneralsOnline revision tag (`logic_crc_revision`, `1`/`0`; 100126 does
  not -- used only when the launcher has not read it from the data package's PC executable).
  A missing key keeps the value built in.
  The community data patch itself comes from that manifest and is updated on the multiplayer
  screen (GeneralsOnline account → Online game data), which checks it by itself and installs a
  newer one on Wi-Fi. It also computes the PC checksum
  from the PC executable inside that patch, and that number wins over `pc_exe_crc`, so a new PC
  release normally needs nothing published at all; `pc_exe_crc` covers players without the patch.
- **Feature switches** (engines from 3278, launchers after 1.4.0): `name=1` / `name=0` in the
  settings turns a feature on or off for everyone, missing keeps the build's default. Each one is
  listed in `update/features.json` -- what it does, its default, which builds read it and which do
  not (those need an engine update to follow it), and companion keys. Set them with
  `scripts/update/features.py`, then publish settings as usual:
  `features.py list` (value here and in the published manifest), `features.py off pc_crossplay`,
  `features.py on pc_crossplay pc_exe_crc=<PC number>`. `publish-update.py` refuses a switch that
  is not `0` or `1`. A new switch: an entry in `features.json`, and
  `GXRemoteConfig::enabled("name", default)` in the engine or
  `UpdateManager.featureEnabled(ctx, "name", default)` in the launcher where it applies. Today:
  `pc_crossplay` (Android-PC cross-play, off since 18/10/2026 at GeneralsOnline's request).
  A switch that guards something other players depend on is read in the engine with
  `GXSignedSettings::enabled("name")` instead: the engine checks the manifest's signature itself
  (the launcher saves `manifest.json.sig` next to `manifest.json`), so a patched launcher or a
  hand-edited `remote_config.ini` cannot turn it on. Only a rebuilt engine can -- the source is
  open, so the server remains the final word. Note for later: once such a switch has been
  published as `1` and then back to `0`, the older signed manifest could be put back by hand;
  if that matters, give the engine a minimum serial in the same engine update.
- **Data package manifest mirror**: while `datapack_manifest_url` points at the updates branch
  (`.../updates/datapack-manifest.json`), `publish-update.py` copies the GeneralsOnline CDN manifest
  there with its fields trimmed -- the CDN's `sha256` starts with a space, and launchers up to 1.3.0
  compare it untrimmed, failing every install with "checksum mismatch". The script downloads the
  package and refuses to publish unless the trimmed digest and size match it. Newer launchers ignore
  a mirror address and read the CDN themselves. The mirror is as current as the last publish: after
  a new GeneralsOnline release, publish settings again so 1.3.0 players see it. Point the key back at
  `https://cdn.playgenerals.online/manifest.json` once the CDN fixes the digest.
- **Engine** (published, but no longer downloaded by the launcher): a newer `libmain.so` /
  `libmain60.so`. With the Home "Updates" card gone, nothing asks the launcher for it; the
  entry is kept so publishing an engine still works if the card is put back.

Nothing is used unless it is signed with the update key.

## Two channels: releases and test builds

Releases read the `updates` branch. Test builds (packaged with `GX_UPDATE_CHANNEL=test`, which
writes `assets/update_channel.txt`) read `updates-test` instead, so a test engine reaches testers
over the air and is never offered to players. Each channel keeps its own serial on the device, and
an engine downloaded from one channel does not run after an APK of the other channel is installed.
Same key, same signature check.

```bash
GX_UPDATE_CHANNEL=test ./scripts/build/android/build-dual-hz.sh        # the test APK testers install once
python3 scripts/update/publish-update.py --channel test --apk <newer test APK> --out /tmp/updt
scripts/update/push-updates-branch.sh /tmp/updt updates-test
# then Actions -> Sign update -> branch: updates-test
```

A test engine is published from a later test build than the one testers have (higher commit
count) with the same libraries; `requires_libs` must match the test APK they installed.

## How it is protected

- `manifest.json` is signed with an ECDSA P-256 key. The launcher carries only the public key
  (`UpdateManager.PUBLIC_KEY_B64`) and refuses a manifest whose signature does not verify.
- The engine files are checked against the SHA-256 and size written in the signed manifest.
- Every manifest has a `serial`; the launcher never accepts a lower one than it has seen, so an
  old signed manifest cannot be replayed to roll players back.
- A downloaded engine runs only if its build number is higher than the APK's own engine
  (`assets/engine_build.txt`, the commit count it was built at) and only on an install whose
  other native libraries are byte-for-byte the ones it was built with (`requires_libs`). A change
  to SDL, OpenAL, DXVK or anything else in `lib/` therefore still needs a new APK; the launcher
  says so.
- An updated engine that twice fails to reach the main menu is dropped, and the APK's own
  engine runs again.

Nothing in the manifest is secret -- the service addresses are public anyway -- so it is signed,
not encrypted. Encryption would need the key inside the APK, where anyone can take it out.

## The key

`update_signing_key.pem` is the private key. Keep it out of the repository. To sign in GitHub
Actions, store its full text (including the `-----BEGIN/END EC PRIVATE KEY-----` lines) as the
repository secret `UPDATE_SIGNING_KEY` (Settings → Secrets and variables → Actions → New
repository secret). If the key is ever lost, a new one means a new APK with its public key.

## Publishing

Settings only (after editing `update/config.json`). The engine already published is kept, files
and all -- the branch is replaced whole, and without it a player who reinstalls the APK would run
the APK's own engine again; `--drop-engine` withdraws it on purpose:

```bash
python3 scripts/update/publish-update.py --out /tmp/upd --key update_signing_key.pem
scripts/update/push-updates-branch.sh /tmp/upd
```

With a new engine, from an APK built by `scripts/build/android/build-dual-hz.sh` on top of the
same libraries players already have:

```bash
python3 scripts/update/publish-update.py --apk GeneralsXZH-android-local.apk \
    --out /tmp/upd --key update_signing_key.pem --note "what changed"
scripts/update/push-updates-branch.sh /tmp/upd
```

Without the key at hand, leave out `--key`, push, and run **Actions → Sign update → Run
workflow**; it signs the manifest on the branch with the secret in a few seconds.

The branch always holds a single commit, so old engines do not pile up in the history.
