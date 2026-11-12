# Google Play Protect "Harmful app blocked" — investigation report — 12/11/2026

Branch: `New-app`. Scope: determine why Google Play Protect blocks the GeneralsX Android APK,
test the reported suspect (a `Scripts` → `Scriptsbackup` rename) against the evidence, and state
the real cause and the residual uncertainty. This report changes no game or engine behaviour.

## 0. Answer up front

- The reported suspect is **not** the cause. "Scripts → Scriptsbackup" is **not a repository
  directory rename**. It is a runtime, on-device folder rename performed by the launcher
  (`ModScriptsBackup.java`, commit `08c0244`, 07/11/2026). It is off by default, only runs when a
  mod is switched on, only touches the player's own game folder, and is reversible. It is not a
  code-execution mechanism and it does not match any Play Protect trigger class.
- The **evidence-backed cause** is the identity of the APK that was actually distributed: it was a
  **debuggable** build signed with the **committed, public debug certificate**
  (`CN=GeneralsX Android Debug`, SHA-256 `644a3b0f…`). A debuggable app signed with a public key
  that anyone can re-sign is the textbook fingerprint Play Protect flags on a sideloaded app. The
  signed release asset `GeneralsXZH-android-run48.apk` (v1.2.9) was downloaded and inspected for
  this report; it is `android:debuggable=true` and carries that certificate.
- A smaller set of contributing signals (broad `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`,
  and native libraries whose names contain "hook") are listed in §6 as plausible secondary
  contributors, not proven causes.

## 1. The reported suspect, tested

### 1.1 There is no `Scripts` directory rename in the repository

Every ref on the remote was inspected. No branch has ever contained a top-level `Scripts` directory
that was renamed to `Scriptsbackup`, and no commit in the full history (54 commits, unshallowed)
renames a `Scripts` directory:

```
git log --all --diff-filter=R --summary   -> only .github/workflows/{old => }/android-unit-tests.yml
git log --all --name-only | grep -i scriptsbackup
  -> android/app/src/main/java/com/generalsx/zerohour/ModScriptsBackup.java
     android/app/src/test/java/com/generalsx/zerohour/ModScriptsBackupTest.java
     android/app/src/test/java/com/generalsx/zerohour/ModScriptsBackupScreenTest.java
```

The three matches are Java sources, not a directory.

### 1.2 What "Scripts → Scriptsbackup" actually is

`ModScriptsBackup.java` (added by `08c0244`, "back up the game's data/scripts while a mod is on")
renames the **player's game data folder** on the device's storage:

- `…/<game folder>/Data/scripts` → `…/<game folder>/Data/scriptsbackup` when a mod is switched on,
- and back when the last mod is switched off.

Properties taken from the source and its tests:

| Property | Value |
|---|---|
| Default state | **off** (`PREF_ENABLED` defaults to `false`; it follows the mods, and there are none by default) |
| Trigger | A mod is switched on in the Mods screen (or the option is on at launch and a mod is enabled) |
| Location touched | The game folder the **user chose**, under `Data/` — never the APK, never app-private code |
| Operation | A single same-directory rename (`Files.move`, else `renameTo`); nothing is copied, nothing is deleted, an existing `scriptsbackup` is never overwritten |
| Failure mode | Both folders present, or a failed rename → filesystem untouched, activation stopped; a marker file (`mods_scripts_backup_state`) heals an interrupted rename on next launch |
| Reversible | Yes — the reverse rename restores the folder |
| Code execution | None. It moves a directory; it does not read, parse, load or execute anything in it |

This is ordinary file management on data the user owns. It does not load code, it is not
obfuscated, and it is not silent (it runs when the player switches a mod on, and the Mods screen
reports the result).

### 1.3 The rename did not ship to the APKs in `apk/`

The APKs committed under `apk/` were checked for the feature by their DEX class references:

```
apk/testers/GeneralsXZH-android-{diag35,a8,a43,a45,a46,a47}.apk  ModScriptsBackup=0
apk/GeneralsXZH-android-generals-a48.apk                         ModScriptsBackup=0
```

None of them contain `ModScriptsBackup`. So the commit that "renamed Scripts" is not even present
in those builds, which rules it out for anyone who installed one of them.

### 1.4 The feature did ship in the one signed release, but is still not the trigger

The only GitHub Release asset, `GeneralsXZH-android-run48.apk` (v1.2.9), **does** contain the
feature (`ModScriptsBackup` appears 6 times in its DEX, plus the `scriptsbackup` string). So the
timing coincidence is real: the release and the rename feature arrived in the same window. But
containing the feature is not the same as being flagged for it:

- the feature is inert unless a mod is enabled,
- a folder rename inside the user's own game directory is not a behaviour Play Protect blocks, and
- the same APK is **debuggable and signed with a public debug key** (§2), which is a far more
  direct and well-understood reason for the warning.

Conclusion: **the rename is disproven as the cause.** It is not a repository rename, it is not
code execution, and the APKs that predate it are equally affected by the real cause.

## 2. The real cause: a debuggable APK signed with a public debug key

### 2.1 What was actually distributed

`GeneralsXZH-android-run48.apk` was downloaded from the v1.2.9 release and inspected
(sha256 `83bb10593d3c81cd1de8b49e164eaec8a29bf72b31dcd41bcc785c096c3d5bdb`, 93,586,754 bytes):

| Property | Value | Evidence |
|---|---|---|
| Package | `com.ports.generalsx`, versionCode 10209, versionName 1.2.9 | `aapt2 dump badging` |
| **Debuggable** | **true** | `aapt2 dump xmltree … android:debuggable=true` |
| Signer DN | `CN=GeneralsX Android Debug, OU=GeneralsX, O=GeneralsX, L=Unknown, ST=Unknown, C=US` | `apksigner verify --print-certs` |
| **Signer SHA-256** | **`644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e`** | `apksigner verify --print-certs` |
| Signature scheme | v2 only | `apksigner verify --verbose` |

The signing certificate is the **committed debug keystore** `android/app/debug.keystore` (alias
`androiddebugkey`, store/key password `android`), which was tracked in this public repository. It
was extracted from history for this report and confirms:

```
Owner: CN=GeneralsX Android Debug, …      Signature algorithm: SHA384withRSA
Valid from: 06/07/2026 until 28/06/2056   Alias: androiddebugkey
```

Every APK committed under `apk/` and `apk/testers/` carries the same certificate and is debuggable:

```
apk/testers/GeneralsXZH-android-diag35.apk            debuggable=1 cert=644a3b0f…
apk/testers/GeneralsXZH-android-generals-a8.apk       debuggable=1 cert=644a3b0f…
apk/testers/GeneralsXZH-android-generals-a43…a47.apk  debuggable=1 cert=644a3b0f…
apk/GeneralsXZH-android-generals-a48.apk              debuggable=1 cert=644a3b0f…
```

### 2.2 Why that triggers Play Protect

Play Protect decides on a sideloaded app from its behaviour and its identity. Two facts about this
APK are, together, the standard profile of an app that anyone can tamper with and re-sign:

1. `android:debuggable=true` — a debug build shipped to end users. Debug builds expose a
   `JDWP` debugging channel and are not what a production release looks like.
2. A **public, widely known debug certificate** — the key is in the repository's history, so any
   third party can produce a differently-signed APK that still claims to be this app. A signing
   identity that is not secret cannot establish app authenticity, which is exactly the property
   Play Protect uses to decide whether an app can be trusted.

Neither is a game-behaviour problem; both are release-engineering defects. This is also the finding
of the earlier audit (`ANDROID_SECURITY_AUDIT_20261110.md`, findings F1/F2), which is consistent
with what this report measures directly against the distributed artifact.

## 3. Behaviour audit: what the launcher does and does not do

The distributed APK's DEX and the current source were both checked for the behaviour classes Play
Protect blocks. The results are negative for all of them.

| Behaviour class | Result |
|---|---|
| Packer / dropper / hidden second DEX | none — 4 normal `classes*.dex`, no embedded payload |
| Dynamic code loading | `DexClassLoader` 0, `PathClassLoader` 0, `InMemoryDexClassLoader` 0, `Class.forName` 0 |
| Shell-out / process spawn | `Runtime.exec` 0, `ProcessBuilder` 0 |
| Loading code from shared storage | none — every library is loaded from the APK's own `nativeLibraryDir` |
| Silent / privileged install | none — installs go through Android's own package installer, with the user's consent |
| SMS / call / camera / contacts abuse | `sendTextMessage` 0, no camera/contacts APIs |
| Root / privilege escalation | no `su`, no `chmod`, no `ptrace` in the engine binary |

`System.load`/`System.loadLibrary` appear only in `GeneralsZHActivity.loadLibraries()` and load
libraries that live in the APK's own `lib/arm64-v8a/` (verified against the APK's native library
list). One optional path, `UpdateManager`, can download a newer engine `.so` over the network and
load it with `System.load(path)` — but it verifies an ECDSA-P256 signature over the manifest and a
SHA-256 over each file before loading, and its `withEngine` argument is **no longer passed true by
any caller** (the Home "Updates" card that used it was removed 04/11/2026). It is a disabled path,
not an active dropper.

## 4. Timeline

| When | Event | Source |
|---|---|---|
| 03/11/2026 | Repository mirror snapshot (`9ba1ded`); `apk/` APKs committed | git |
| 07/11/2026 10:34 | `08c0244` adds `ModScriptsBackup` (the "Scripts → Scriptsbackup" rename) | git |
| 09/11/2026 | v1.2.9 released (`run48.apk`), signed with the debug key, debuggable | GitHub Releases |
| 10/11/2026 | `f7c526f` removes the committed debug keystore and adds a production signing path | git |
| 12/11/2026 | Trust-anchor rotation for the two update domains | git |
| — | Play Protect "Harmful app blocked" reported | owner |

The rename (07/11) and the release (09/11) are two days apart, which is what makes the rename look
suspicious — but §1.3 and §2 show the release's real problem is its signing identity, which predates
the rename and is present in every APK from 03/11 onward.

## 5. What has already changed, and what it fixes

- `android/app/debug.keystore` was deleted (`f7c526f`); the release build type is now
  `debuggable false` and is signed only with a production key supplied from secrets
  (`GX_RELEASE_*`). A release with no key now **fails** instead of falling back to debug signing.
- `scripts/qa/android/verify-release-apk.py` refuses an unsigned, debug-signed, wrong-fingerprint or
  debuggable APK. Run against the distributed debug APK it correctly fails:
  `::error::APK certificate does not match the expected production certificate
   expected D1CEECFE… actual 644A3B0F…`.
- `build-android.yml` builds the release variant and refuses to publish a debuggable or
  wrong-certificate APK.

None of this removes the warning from an APK already installed from the old debug-signed release —
that is a migration problem, not a signing-config problem (see §7).

## 6. Contributing signals (plausible, not proven)

These are things Play Protect can weigh; none is confirmed as the trigger. They are recorded so the
next properly-signed release can be checked against them if the warning persists.

1. `MANAGE_EXTERNAL_STORAGE` ("All files access") — a broad permission, and combined with a feature
   that renames directories in shared storage it is the one place the reported suspect and a real
   heuristic overlap. The permission is required because the engine opens game files by absolute
   path (documented in the manifest and the audit, §3).
2. `REQUEST_INSTALL_PACKAGES` — the app can hand a downloaded APK to the installer (self-update).
   Normal, but a signal.
3. Native libraries named `lib*_hook*.so` and `libadrenotools.so` — the adrenotools linker-namespace
   bypass ("hook") that lets a custom Vulkan driver load can read as instrumentation to an AV
   heuristic. These are legitimate graphics-layer components, present in every build.
4. Large, unusual native content (DXVK translation libraries, ANGLE) and a low install base.

## 7. Migration and residual risk

- **The debug key is retired, not revoked.** It is in this repository's history and cannot be
  un-published. The production certificate is now different, so an APK signed with the new key does
  **not** install over one signed with the old key through an ordinary update. Existing installs
  need a one-time uninstall/reinstall, or an APK carrying a v3 proof-of-rotation lineage built from
  the old debug keystore (`scripts/build/android/apply-signing-lineage.sh`, gated on the
  `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64` secret). This is documented in
  `docs/HOWTO/SIGNING_KEY_ROTATION.md`.
- **Changing a public key inside the app does not change the APK's signing identity.** The pinned
  update/engine keys are trust anchors for *metadata*; Android's decision to install an update is
  made from the APK signing certificate, which is a separate thing. The two must not be conflated.
- **The verdict itself cannot be confirmed from here.** The exact APK Google scanned and Google's
  own reason were not available to this investigation; the conclusion in §2 is the strongest
  inference from the artifact that was distributed and from documented Play Protect behaviour, not
  a reading of Google's verdict. The confirming test is to install the new production-signed,
  non-debuggable APK and see whether the warning is gone.
- **Data safety on reinstall.** The game reads its data from the user-chosen external game folder
  (and `Android/data/<id>/files/`), not from app-private storage, so an uninstall/reinstall does not
  delete the game files. The player should still be warned to keep the game folder before
  uninstalling.

## 8. Recommendation

1. Ship the next release built with the production key and `debuggable=false` (§5), and confirm the
   Play Protect warning is gone on a device.
2. Keep the release gate (§5, and the strengthened `publish-release.yml` gate added with this
   report) so a debug-signed or debuggable APK can never be published again.
3. If the warning persists on the properly-signed build, appeal with: the `apksigner` output, the
   non-debuggable flag, the certificate fingerprint, the permission list, and this report's §3
   finding that no packer, dropper, dynamic-code or silent-install behaviour exists.
4. Treat §6 as a checklist, not a cause: change one thing at a time and re-test, so the real trigger
   is isolated rather than guessed.
