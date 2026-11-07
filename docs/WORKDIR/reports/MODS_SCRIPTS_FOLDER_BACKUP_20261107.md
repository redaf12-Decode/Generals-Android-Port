# Mods: automatic `data/scripts` backup (07/11/2026)

## What was asked

An option in the existing Mods section that renames the game folder's `data/scripts` directory to
`data/scriptsbackup` while a mod is active, and restores it when the mod is deactivated — without
losing or overwriting any file, with the preference remembered, and with safe handling of the
conflicts and the interrupted operations.

## The existing mod system, as it actually is

| Question | Answer | Where |
|---|---|---|
| How is the game folder found? | `SetupActivity.getSavedGamePath(ctx)` — a SharedPreferences path per game (`GAME_ZERO_HOUR` / `GAME_GENERALS`), self-healed from an external marker file | `SetupActivity.java` |
| Where is the mods root? | `<game folder>/mods` | `ModManager.modsRoot` |
| How is a mod switched on? | The switch on its card in `ModManagerActivity`; the on/off state plus order is saved as lines `1\|<folder>` / `0\|<folder>` in the `mods_order` (ZH) / `mods_order_generals` preference | `ModManager.saveOrder` / `readOrder` |
| How does the engine learn what is on? | `GeneralsZHActivity.getArguments()` calls `ModManager.writeActiveList()`, which writes the enabled folders highest-priority-first into `mods_active.txt`, which the native `GXManagedMods.h` reads | `ModManager.writeActiveList` |
| How many mods at once? | Any number, ordered; "a mod is active" means at least one `1\|` line | `ModManager.anyEnabled` |
| The scripts folder | `<game folder>/data/scripts` (the CO-OP / AI scripts, `SkirmishScripts.scb`); the game folder also has a `Data` spelling on some repacks, so both cases are accepted | this feature, `ModScriptsBackup.dataDir` |

## Design

The whole operation is a single same-directory `File.renameTo`, so nothing is copied and nothing
can be half-written. That reduces the state to one fact worth persisting: *which* folder was moved
aside and *what it was called before*. It is written to a two-line marker,
`files/mods_scripts_backup_state` (`<game folder>\n<original name>`).

`ModScriptsBackup` is the only class that touches the folders:

| Method | When | What |
|---|---|---|
| `sync(ctx)` | Mods screen resume, game launch | Bring the selected game's folder in line with the option and its mods |
| `apply(ctx)` | A mod switch goes on | Move `data/scripts` aside now, before the switch is saved |
| `restore(ctx)` | The last mod switch goes off, or the option is turned off | Move `data/scriptsbackup` back now |
| `recover(ctx)` | `SetupActivity.onResume()` | Repair a folder left aside by the *other* game's last session, then `sync` |

Safety, exactly as asked:

- **Never deletes**: only `renameTo`, no `deleteRecursively`, no `delete` on either folder.
- **Never overwrites**: `rename()` returns false when the target already exists; the caller reports
  it and changes nothing.
- **Both folders present** → `Result.CONFLICT`, filesystem untouched, a message on the screen.
- **Rename fails** → `Result.FAILED`; the caller aborts the switch (`mustStopActivation()`), so
  activation never continues in an inconsistent state. A mod switched on and then failing is put
  back where it was.
- **Already backed up** → recognised (the backup exists, the scripts folder does not) and *not*
  renamed again; the marker is refreshed instead.
- **Interrupted** → the marker survives; the next `recover`/`sync` re-derives the end state.

Ordering, which is the part that is easy to get wrong: `syncScriptsBackup()` in
`ModManagerActivity` runs **between** the switch moving and `changed()` saving the list, and it
decides "is any mod on?" from the **in-memory** list, not the saved one (which would still show the
state being left). The folder therefore moves before the switch is remembered, and a failure can
cancel the switch.

## Modified files

| File | Change |
|---|---|
| `android/app/src/main/java/com/generalsx/zerohour/ModScriptsBackup.java` | **new** — the option, the rename/restore, the marker, the recovery |
| `android/app/src/main/java/com/generalsx/zerohour/ModManagerActivity.java` | the Mods-screen card + switch, the lifecycle hooks (`onResume`, mod toggle, legacy move, import) |
| `android/app/src/main/java/com/generalsx/zerohour/ModManager.java` | `anyEnabled`, `anyEnabledForPath` (read the saved list without walking the folder) |
| `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java` | `ModScriptsBackup.recover(this)` in `onResume()` |
| `android/app/src/main/java/com/generalsx/zerohour/GeneralsZHActivity.java` | `ModScriptsBackup.sync(this)` in `getArguments()`, before the engine reads the folder |
| `android/app/src/main/res/values*/strings.xml` | 10 new `mods_scripts_backup_*` strings in all 13 locales |
| `android/app/src/test/java/com/generalsx/zerohour/ModScriptsBackupTest.java` | **new** — 14 scenarios against a real folder tree |
| `android/app/src/test/java/com/generalsx/zerohour/ModScriptsBackupScreenTest.java` | **new** — 3 scenarios driving the real Mods screen |

## Actual folder paths

- Scripts folder: `<game folder>/data/scripts` — but see the follow-up below: the folder is found
  by listing every `data`-spelled directory in the game folder and taking the one that holds
  `scripts`, because a retail install spells it `Data` and the launcher's own language packs live
  in a lower-case `data` beside it
- Backup folder: `<game folder>/data/scriptsbackup` (same directory as the scripts folder)
- Marker: `<app files dir>/mods_scripts_backup_state`

On Android the game folder is the one chosen in Setup (`GENERALSX_USERDATA_DIR` /
`SetupActivity.userDataDir` for the user-data case), and the option acts on the folder of the game
selected on Home.

## Verification

Local build environment (installed for this task, the sandbox had no toolchain): Temurin-compatible
OpenJDK 21, Android SDK `platforms;android-35` + `build-tools;35.0.0` at `/opt/android-sdk`, Gradle
8.9 at `/opt/gradle`, SDL3 Java glue staged by `scripts/build/android/stage-sdl-java.sh`.

```
gradle :app:testDebugUnitTest      -> 283 tests, 0 failures (17 new)
gradle :app:lintDebug              -> 0 errors
gradle :app:assembleLauncherOnly   -> BUILD SUCCESSFUL
python3 scripts/qa/android/check-translations.py -> 680 strings in each of 13 locales
```

The ten scenarios the task lists are covered by `ModScriptsBackupTest`
(`activatingAModMovesTheScriptsFolderAside`, `deactivatingTheLastModRestoresTheScriptsFolder`,
`aMissingScriptsFolderIsLeftAlone`, `anExistingBackupIsNotRenamedAgain`,
`bothFoldersAtOnceIsAConflict`, `aFailedRenameIsReportedAndNothingIsChanged`,
`aRestartWithTheFolderAsideRecovers`, `theFolderIsRestoredOnlyWhenTheLastModIsOff`,
`withTheOptionOffNothingIsRenamed`, `theRoundTripLosesNoFile`) and by
`ModScriptsBackupScreenTest`, which flips the real switches on the real screen.

**Not done here**: a build of the full APK with all four engines (`build-dual-hz.sh`), which needs
the Android NDK and the CMake/vcpkg/DXVK toolchain. The change is Java-only, so
`assembleLauncherOnly` exercises every line that changed.

## Follow-up (07/11/2026): why the folder was not actually disabled

Reported after the first build: switching a mod on left `data/scripts` where it was. The cause was
a **name collision between two `data` directories**.

A retail install spells the directory `Data` (the game was written on Windows), and the loose
scripts live at `Data/Scripts/SkirmishScripts.scb`. The launcher itself, however, installs its
language packs into a **lower-case** `data/<language>/generals.str` in the same game folder
(`SetupActivity.downloadLanguagePack`), and the engine matches either spelling. So a device can
have both `Data` and `data` side by side, and `findChild(game, "data")` — which prefers the exact
match — returned the launcher's own language folder. That folder holds no `scripts`, so `reconcile`
found nothing to move and returned `Result.NONE`: the real `Data/Scripts` was never touched. The
bug was reproduced with a failing test before it was fixed.

The fix is `findDataDir(game)`: it lists the `data`-spelled directories and picks the one that
actually holds `scripts`, else the one holding `scriptsbackup`, else the first found. Both the
`dataDir(ctx)` accessor and `reconcile()` use it. Two regression tests cover the layout in both
directions (`theScriptsAreFoundWhenTheLauncherAlsoMadeALowerCaseData`,
`theScriptsAreFoundWhenTheyLiveInTheLowerCaseData`).

The rename itself was hardened the same way: `Files.move` is tried first because it reports *why* a
move failed and is the call that behaves on the FUSE-backed shared storage Android exposes, with
`File.renameTo` kept as the fallback. Both are same-directory moves, so the directory and its
contents cross in one step either way.

A second gap was closed at the same time: **safe start** ("start without mods") switches every mod
off through `ModManager.switchOff`, outside the Mods screen, so it now follows that with a
`ModScriptsBackup.sync` — otherwise starting without mods would have left `data/scripts` disabled.
(`switchingAllModsOffRestoresTheScriptsFolder`.)

After the follow-up: `testDebugUnitTest` 286 tests / 0 failures, `lintDebug` 0 errors,
`assembleLauncherOnly` `BUILD SUCCESSFUL`.
