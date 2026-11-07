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
| `sync(ctx)` | Mods screen open/resume, game launch | Bring the selected game's folder in line with its mods |
| `apply(ctx)` | A mod switch goes on | Move `data/scripts` aside now, before the switch is saved |
| `restore(ctx)` | The last mod switch goes off | Move `data/scriptsbackup` back now |
| `recover(ctx)` | `SetupActivity.onResume()` | Repair a folder left aside by the *other* game's last session, then `sync` |

The option is **not a free choice** — it follows the mods. `sync` sets the saved preference to
`ModManager.anyEnabled(ctx)`, `apply` sets it on and `restore` sets it off, so any mod switched on
turns it on (and moves the folder aside) and only the *last* mod switched off turns it off again.
Several mods on therefore keep it on, and the Mods-screen switch shows the state and is not
togglable while a mod is on.

Safety, exactly as asked:

- **Never deletes**: only a same-directory move, no `deleteRecursively`, no `delete` on either folder.
- **Never overwrites**: `rename()` returns false when the target already exists; the caller reports
  it and changes nothing.
- **Both folders present** → `Result.CONFLICT`, filesystem untouched, a message on the screen.
- **Rename fails** → `Result.FAILED`, the filesystem untouched and a message; the mod still switches
  on, because the folder move is a convenience and never a precondition (see below).
- **Already backed up** → recognised (the backup exists, the scripts folder does not) and *not*
  renamed again; the marker is refreshed instead.
- **Any letter case** → the `data` directory and the `scripts`/`scriptsbackup` folder are found by
  comparing names case-insensitively (`findDataDir`/`findChild`), so `Data\Scripts`, `data\Scripts`
  and `Data\scripts` all work; the folder is moved and restored under the spelling it really has.
  When neither spelling holds a scripts folder yet, the retail `Data` spelling is preferred over the
  launcher's lower-case `data` (the latter holds only language packs), so the message and the
  operation point at the folder the game's own `Scripts` would be in.
- **Interrupted** → the marker survives; the next `recover`/`sync` re-derives the end state.

**The folder never gates the mod.** `syncScriptsBackup()` moves the folder and only ever warns: a
failed rename or a both-present conflict leaves the filesystem as it was and the mod still switches
on (the toggle and "turn on now" no longer revert on it). `Result` therefore has no
`mustStopActivation()`. `syncScriptsBackup()` is called from `refresh()` (before drawing) and from
the mod toggle and `enableOnTop`.

Ordering, which is the part that is easy to get wrong: `syncScriptsBackup()` in
`ModManagerActivity` runs **between** the switch moving and `changed()` saving the list, and it
decides "is any mod on?" from the **in-memory** list, not the saved one (which would still show the
state being left). The folder therefore moves before the switch is remembered, and a failure can
cancel the switch. `refresh()` calls it before drawing, so the switch, the status line and the disk
never disagree.

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

## Follow-up 2 (07/11/2026): the option follows the mods

Owner's request: the switch should **turn itself on** when any mod is switched on, and turn off only
when **all** mods are off — with several mods on it must stay on. It is therefore no longer a
free-standing preference a player sets by hand; it is a function of the mods:

- `sync()` sets the saved preference to `ModManager.anyEnabled(ctx)` and reconciles the folder from
  that.
- `apply()` (a mod switched on) sets it on; `restore()` (the last mod switched off) sets it off.
- The Mods-screen switch shows the state (`anyModEnabled()` from the in-memory list) and is
  disabled, so it cannot be turned off while a mod is on. The description and help strings were
  updated in all 13 locales to say this.

The letter-case requirement was already satisfied by `findDataDir`/`findChild` (both compare names
case-insensitively, so `Data\Scripts`, `data\Scripts`, `Data\scripts` are all found and moved, and
restored under the spelling they really have). A test now pins every one of those spellings.

New/updated tests: `theOptionFollowsTheMods`, `withNoModOnNothingIsRenamed`,
`theScriptsFolderIsFoundInAnyLetterCase` (unit), and `theOptionFollowsTheModsOnTheScreen`,
`theOptionStaysOnUntilTheLastModIsOff`, `theOptionCannotBeSwitchedOffWhileAModIsOn` (screen).

After follow-up 2: `testDebugUnitTest` 297 tests / 0 failures, `lintDebug` 0 errors,
`assembleLauncherOnly` `BUILD SUCCESSFUL`, `check-translations.py` green (702 strings × 13).

## Follow-up 3 (07/11/2026): retail spelling preferred, and the folder never blocks the mod

Two owner requests:

1. **Prefer the capital `Data\Scripts` path when the lower-case `data\scripts` is not found.** When
   neither spelling holds a scripts folder yet (a fresh install, or one whose scripts were removed),
   `findDataDir` now returns the retail `Data` spelling rather than the launcher's lower-case
   `data` — the latter is the launcher's own and holds only language packs. So `dataDir()`,
   `backupPathOf()` and the operation all point at the folder the game's own `Scripts` would live in.
   Test: `theRetailDataSpellingIsPreferredWhenNeitherHoldsScripts`.
2. **Do not make mod activation conditional on the folder having been changed.** A rename that cannot
   be done (or a both-present conflict) now only warns: the filesystem is left exactly as it was and
   the mod switches on regardless. `Result.mustStopActivation()` and the toggle/`enableOnTop` revert
   paths were removed; `syncScriptsBackup()` is best-effort. Tests:
   `aModStillSwitchesOnWhenTheFolderCannotBeMoved` (screen) and the reworded
   `aFailedRenameIsReportedAndNothingIsChanged` (unit).

After follow-up 3: `testDebugUnitTest` 299 tests / 0 failures, `lintDebug` 0 errors,
`assembleLauncherOnly` `BUILD SUCCESSFUL`, `check-translations.py` green.

## Follow-up 4 (07/11/2026): show the retail `Data\Scripts` path, not the lower-case one

The owner asked for the retail spelling to be shown instead of the lower-case one. Every string the
Mods screen shows (`mods_scripts_backup_switch`, `_switch_desc`, `_done`, `_restored`, `_active`,
`_idle`, `_conflict`, `_failed`) now reads **`Data\Scripts`** (and `Data\scriptsbackup`), in all 13
locales. Only the display changed: the matching stays case-insensitive, so `Data\Scripts`,
`data\scripts`, `DATA\SCRIPTS` are still found, moved and restored. `_failed` was also corrected —
it still said the mod "was left switched off", which stopped being true in follow-up 3.

Developer comments in `ModScriptsBackup`/`ModManagerActivity`/`GeneralsZHActivity`/`SetupActivity`
were aligned to `Data\Scripts` too, except `SetupActivity.hasSkirmishScripts`'s
`bigContainsEntry(dir, "data/scripts/skirmishscripts.scb")`, which is the **archive entry name** (a
lookup inside a `.big`), not a folder on disk, and must stay as the engine writes it.

After follow-up 4: `testDebugUnitTest` 299 tests / 0 failures, `lintDebug` 0 errors,
`assembleLauncherOnly` `BUILD SUCCESSFUL`, `check-translations.py` green.
