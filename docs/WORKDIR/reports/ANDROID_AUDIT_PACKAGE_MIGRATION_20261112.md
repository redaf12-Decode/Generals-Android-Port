# Android audit, package migration & scripts-backup repair — report (12/11/2026)

Branch `New-app`. Commits `4cbf289`, `0bd9154`, `0934983` (on top of `2e90024`).

Execution policy for this task: **every Android build and every build-dependent test runs on GitHub
Actions; nothing Android is built locally.** Only Python/Java-source checks that need no Android
toolchain were run in the sandbox (`tests/run_tests.py`, the `scripts/qa/android/*` guards).

## 1. What this task actually was

Three independent strands, in this order:

1. A complete signing-key rotation (the headline of the task text).
2. A Java package migration `com.generalsx.zerohour` → `com.ports.generalsx`.
3. A "Scripts backup repair".

The **key rotation was already carried out on this branch before this task** (commits `d5cecd9`,
`ecc3df0`, `c317ff7`, `29272f2`, `396e405`) and is documented in full in
`docs/WORKDIR/reports/ANDROID_SIGNING_KEY_ROTATION_20261112.md` and
`docs/HOWTO/SIGNING_KEY_ROTATION.md`. This report does **not** re-claim that work as new: §4 records
its verified state and the one thing that still blocks it (owner-configured secrets). The package
migration and the scripts-backup repair are the work done here, and the rotation's own guard
(`check-trust-anchors.py`) was kept green throughout.

## 2. Package migration `com.generalsx.zerohour` → `com.ports.generalsx` (commit `4cbf289`)

The applicationId has been `com.ports.generalsx` since 08/11/2026, but the Java namespace and every
class were still under `com.generalsx.zerohour`. Moved:

- 50 application-owned classes in `android/app/src/main/java/…` and 27 in `…/src/test/java/…`,
  with every package declaration and import updated; the empty old directories removed.
- Gradle `namespace` in `android/app/build.gradle` (the `applicationId` was already correct and is
  unchanged; the FileProvider authority stays `${applicationId}.fileprovider` ==
  `com.ports.generalsx.fileprovider`).
- The two native JNI exports in
  `Core/GameEngineDevice/Source/SDL3Device/GameClient/AndroidTextEditor.cpp`
  (`Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorChanged` /
  `…Done` → `Java_com_ports_generalsx_…`). This is the one place a Java package name crosses into
  C++: get it wrong and the text-editor bridge throws `UnsatisfiedLinkError` at runtime, not at
  compile time, so it was changed and is checked by the new guard below.
- The Java paths hardcoded in the signing scripts (`rotate-signing-keys.py`,
  `check-trust-anchors.py`, `check-update-metadata.py`, `print-pinned-key.py`) and in
  `tests/security/test_signing.py`.

Third-party packages are untouched: SDL3 stays `org.libsdl.app` (its `java-sdl` glue is not the
application's code).

New guard, `scripts/qa/android/check-no-old-package.py`, wired into
`.github/workflows/android-unit-tests.yml`: it fails the build if any source, the manifest, or a
native JNI symbol still names the old package, and asserts the two JNI symbols and the FileProvider
authority are present in their new form. It passes.

## 3. Scripts-backup repair (commits `0bd9154`, `0934983`)

### The defect

`ModScriptsBackup` renames the selected game's `Data\Scripts` to `Data\scriptsbackup` while a mod is
on, and restores it when the last mod is switched off. Two problems:

1. **No recovery state machine.** Recovery replayed the remembered operation instead of inspecting
   the filesystem, so a crash between the rename and the marker write left a state the next launch
   could not classify.
2. **The integrity check could not see a valid backup.** `findGameFolderIntegrityIssues()` reported
   "`Data\Scripts\SkirmishScripts.scb` is missing — computer opponents will do nothing in skirmish
   games" whenever the loose file was not in place — which is exactly the state a mod on *creates*.
   So a working setup was shown as broken for the whole window between a mod being switched on and
   the next resume, and on every launch that did not reconcile first.

### The fix

- `ModScriptsBackup.State` (`ORIGINAL_PRESENT`, `BACKUP_PRESENT`, `MODS_ENABLED`, `RESTORE_PENDING`,
  `CONFLICT`, `ERROR`) and `inspectState()`, which classifies the **filesystem** (which of the two
  folders exist, whether a mod is on, whether a marker still records this game, whether the data
  directory is writable). The recovery decision is made from that state, not from a remembered
  intent, so a crash at any point heals on the next launch.
- `activeBackupWithSkirmish()`: a `scriptsbackup` is accepted as a stand-in for `Data\Scripts` only
  when the game that owns the folder has a mod switched on **and** the backup holds
  `SkirmishScripts.scb`. A backup the other game owns, or one left with the mods off, is rejected —
  a stale or unrelated backup must never mask a genuinely missing scripts folder.
- The recovery marker is now written **atomically** (temp file in the same directory + rename), so a
  crash while writing it cannot leave a half-written marker.
- `SetupActivity.reportsMissingSkirmishScripts()` is the testable decision the integrity check now
  uses; `hasSkirmishScripts()` also finds `SkirmishScripts.scb` directly inside a folder, because a
  renamed `Scripts` folder holds the file one level in, not under a nested `Data\Scripts`.
- `SetupActivity.onResume()` now calls `ModScriptsBackup.recover(this)` **before** `refreshStatus()`
  and the integrity scan, instead of after, so the status line sees the reconciled state.

Nothing is deleted, and an existing `scriptsbackup` is never overwritten or merged (the `CONFLICT`
state reports instead).

### Tests

`ModScriptsBackupTest` gained three tests (Robolectric, real folders, no mocks):

- `inspectStateClassifiesEveryFilesystemShape` — walks a real folder through every state and back.
- `aValidBackupIsRecognisedAsTheSkirmishStandIn` — the backup is accepted with a mod on, rejected
  with the mods off, and rejected without the file, in which case the integrity check still reports
  it.
- `anInterruptedRenameIsRecoveredFromTheFilesystem` — the folder is moved aside by hand with no
  marker (a simulated crash) and recovered on the next sync; the last mod off still restores it.

The first CI run caught a bug in the first of these (a `File.delete()` on a non-empty
`scriptsbackup` returns false); fixed in `0934983` by deleting recursively.

## 4. Signing-key rotation — verified state (from the earlier work)

Full detail in `docs/WORKDIR/reports/ANDROID_SIGNING_KEY_ROTATION_20261112.md`. Summary of what is
in the tree **now** and re-verified by this task's runs:

| Domain | Public key | Consumer |
|---|---|---|
| Update metadata (`update.json`) | ECDSA P-256 SPKI, SHA-256 `ca6db72a…69cf137` | `UpdateChecker.PUBLIC_KEY_B64` |
| Engine update (`manifest.json`) | ECDSA P-256 SPKI, SHA-256 `bf65f3b2…f31ac96d` | `UpdateManager.PUBLIC_KEY_B64` |
| Engine update, native copy | same as the row above, byte-for-byte | `GXSignedSettings.cpp` `kPublicKeyB64` |
| APK release | RSA-4096, cert SHA-256 `D1:CE:EC:…:0E:E5:92:FF` | Android OS / `verify-release-apk.py` |

`check-trust-anchors.py` (run here, green) enforces that the three embedded keys agree pairwise, that
the metadata and engine domains differ, that the committed `update.json` verifies under the metadata
key only, and that no retired key literal remains. `UpdateChecker` pins exactly one key — old and new
are never both trusted.

**The one thing this task did not and cannot do:** the production private keys are not in the
repository and must not be. The owner still has to run the generated `set-secrets.sh` (or set the
secrets by hand) — see §7. Until then a release build fails closed (never debug-signed), which is
correct, but produces nothing.

## 5. Permission & input audit (found already guarded — no change made)

Checked while auditing, all negative (no fix needed, recorded so it is not re-investigated):

- **Zip-slip.** Every ZIP extractor (`DataPackInstaller`, `MapPackManager`, `ControlBarPackManager`,
  `CustomDriverPackage`, `ModImportService`) canonicalises each entry path and rejects anything that
  escapes the target root before writing.
- **Engine override path.** `UpdateManager.activeEngineLibrary()` builds the path from an `int` seq
  under the app's own files directory, only after the signed manifest and per-file SHA-256 pass;
  `GeneralsZHActivity.loadLibraries()` falls back to the APK's own library on
  `UnsatisfiedLinkError`. No path is taken from remote metadata.
- **`MANAGE_EXTERNAL_STORAGE`.** Declared and lint-suppressed deliberately; this APK is sideloaded,
  not Play-distributed, and the in-app folder browser needs a real path anywhere the user keeps the
  game. Pre-API-30 `READ/WRITE_EXTERNAL_STORAGE` are `maxSdkVersion=29`. `REQUEST_INSTALL_PACKAGES`
  is used only for the already-verified update handoff to the platform installer.
- No Lua anywhere; no `DexClassLoader`/`Runtime.exec`/`ProcessBuilder` in the launcher.

## 6. CI results

| Run | Workflow | Result |
|---|---|---|
| 43183697992 | `android-unit-tests.yml` | **all steps success** — JVM unit tests (381), Android Lint, package-migration guard, committed-secret scan, trust-anchor check, signing security tests, translation check, launcher-only APK build |
| 43183275023 | `android-unit-tests.yml` | failed — 1 of 381 tests (`inspectStateClassifiesEveryFilesystemShape`), fixed in `0934983` |
| 43183313038 | `build-android.yml` (`android-vulkan`) | **success, 35m** — both engines at both rates built and packaged; the JNI rename compiles |

The native run's own gates passed: `Package APK`, `Verify APK is a signed, non-debuggable release`,
`Verify APK contains every library libmain.so needs`. `apksigner verify --verbose --print-certs`
reported v2 signature `true`, `Number of signers: 1`, package `com.ports.generalsx`.

One honest note on that certificate: it is **not** the production certificate, because the owner has
not set the release secrets yet. `build-android.yml` (lines ~523–575) deliberately falls back to a
throwaway keystore (`CN=GeneralsX CI validation, O=GeneralsX, C=US`, digest `6267abc4…3c36e8`) so the
release variant still builds and is still proved non-debuggable, and it says so with a warning. That
is the "fail closed" design from the earlier security work — never debug signing — not a production
release. With `ANDROID_RELEASE_KEYSTORE_BASE64` and the rest set, the same job uses the production
keystore and checks its fingerprint against `ANDROID_RELEASE_CERT_SHA256` (the
`D1:CE:EC:…:92:FF` certificate in §4). A real release therefore still requires §7.

Local Python: `tests/run_tests.py` → **23 passed, 0 failed**. `check-no-old-package`,
`check-trust-anchors`, `check-update-metadata`, `check-no-committed-secrets` all pass.

## 7. GitHub Secrets still requiring owner action

Unchanged from the rotation report; repeated here because the rotation is not live without them:
`ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`,
`ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_CERT_SHA256`, `UPDATE_METADATA_SIGNING_KEY`,
`UPDATE_SIGNING_KEY`, and optionally `ANDROID_RELEASE_OLD_DEBUG_KEYSTORE_BASE64` (only if the v3
proof-of-rotation lineage is wanted). The agent must not upload private keys, so this is an owner
step.

## 8. Remaining risks

1. **Secrets not configured.** Rotation is code-complete but not live. A release without them fails
   closed.
2. **Native build of the JNI rename — confirmed.** `build-android.yml` run 43183313038 compiled and
   packaged both engines at both rates with the new `Java_com_ports_generalsx_…` exports. The Java-side
   guard also asserts the two spellings agree across Java and C++.
3. **In-place update for existing debug-signed installs** needs the v3 lineage, which needs the old
   debug keystore secret. Without it, existing installs need a one-time uninstall/reinstall — the
   game data is on shared storage and is not touched by an uninstall.
