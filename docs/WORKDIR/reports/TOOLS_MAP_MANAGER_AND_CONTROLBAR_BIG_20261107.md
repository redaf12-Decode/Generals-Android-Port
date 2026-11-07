# Tools: Map Manager and ControlBar BIG Importer

Date: 2026-11-07 · Branch: `New-app` · Area: Android launcher (Tools tab)

Two new Tools screens. Both were built on what the repository already had — the engine's own map
and archive rules, the launcher's mod importer and its packer — rather than on a second map
scanner or a second BIG parser. This report records where maps and archives actually live in this
codebase, what each screen does, and what was verified.

## 1. Where the game keeps its maps (measured, not assumed)

- The engine scans **`<user data>/Maps`**. `MapCache::getUserMapDir()` is
  `TheGlobalData->getPath_UserData() + getMapDir()`, and `getMapDir()` returns the literal string
  `"Maps"` (`Core/GameEngine/Source/GameClient/MapUtil.cpp:346`). `MapCache::updateCache` calls
  `loadMapsFromDisk(userMapDir)` on every map-list refresh, so an installed map appears at the
  next map list with **no restart**.
- On Android `getPath_UserData()` is the directory `SDL3Main.cpp` exports as
  `GENERALSX_USERDATA_DIR`: `/storage/emulated/<uid>/Generals/<leaf>`
  (`Core/Main/SDL3Main.cpp:752`, consumed in `GlobalData.cpp`). The launcher already resolves the
  same folder — `SetupActivity.userDataDir(ctx)`, which returns
  `DataPackInstaller.userDataDir()` (the Zero Hour leaf `Command and Conquer Generals Zero Hour
  Data`) or the base game's leaf, depending on the selected game. So the launcher and the engine
  agree on the path with no new code.
- **A map is a folder named after its `.map` file.** `MapCache::hasValidMapLeafLayout` accepts a
  `.map` only when its path ends with `<name>\<name>.map` (case-insensitive). Every install
  therefore lands as `Maps/<name>/<name>.map` plus the map's other files. A `.map` sitting loose
  in `Maps/` is ignored by the engine; so is a `.map` whose folder has a different name.
- A `.map` file is an `EAR\0` RefPack stream or, uncompressed, a `CkMp` chunk tree at its head
  (`CompressionManager`). `MapManager.isMapFile` checks exactly those two magics.
- Storage: the user-data folder is shared storage (the app already holds `MANAGE_EXTERNAL_STORAGE`
  and the legacy READ/WRITE permissions), so the launcher writes to `Maps/` directly with plain
  `File` I/O — the same place the player drops maps by hand, and the same place the engine reads.

## 2. Map Manager

`MapManager.java` (logic) + `MapManagerActivity.java` (UI). Entry: **Setup → Tools → Map Manager**.

- **Import** — `ACTION_OPEN_DOCUMENT_TREE` (a folder) or `ACTION_OPEN_DOCUMENT` (a ZIP). The
  selection is copied into `Maps/.installing/<uuid>` through `ModImportService`'s path helpers, so
  ZIP traversal and absolute paths are rejected exactly as they are for mods. A folder holding one
  map, several maps, or a single loose `.map` all work: the loose file is given a folder of its own,
  and a `.map` whose folder name does not match is refused rather than installed where the game
  would ignore it. Every `.map` is checked with `isMapFile` before anything is installed.
- **Conflicts** — an existing map of the same name is detected before install; the player chooses
  **Replace** or **Keep both**. Replace is atomic (`swapIn`: move the old aside, move the new in,
  delete the old) so there is never a window with neither copy.
- **Export** — an installed map can be written as a folder (`ACTION_OPEN_DOCUMENT_TREE`, recreated
  as `<name>/…`) or as a ZIP (`ACTION_CREATE_DOCUMENT`, entries under `<name>/…`). The original is
  never touched.
- **Browse / delete** — the installed maps are listed (name, file count, size, preview picture) and
  a map can be deleted, but only when it is a direct child of `Maps/` and not a symlink.
- Progress dialogs are cancellable and the copy loop checks the cancellation signal.

## 3. ControlBar BIG Importer

`ControlBarBig.java` (parse + detection + import) + `ControlBarBigActivity.java` (UI). Entry:
**Setup → Tools → ControlBar BIG Importer**.

### What the engine reads for the control bar

- The layout: `Window\ControlBar.wnd`, loaded by `TheWindowManager->winCreateFromScript(
  "ControlBar.wnd")` (`GeneralsMD/…/InGameUI.cpp:4872`).
- The schemes: `Data\INI\Default\ControlBarScheme\*` then `Data\INI\ControlBarScheme\*`
  (`ControlBarSchemeManager::init`).
- The mapped images the schemes name: defined in `Data\INI\MappedImages\*` (`Image.cpp`), each
  mapping an image name to a `Texture` file.
- The textures themselves, under `Art\…`.

All of these are ordinary archive entries, so a mod that ships them in a `.big` only has to be
mounted — **no extraction into the game folder is needed**.

### What the importer does

1. Copies the picked `.big` into the cache and reads its directory table (BIGF/BIG4 magic, the
   per-file offset/size and NUL-terminated path), with the same bounds the importer already uses
   (`ModHealthCheck.checkBigTable` rules: entry count, name length, offset+size within the file).
2. Classifies the entries **following the engine's chain**, not by a name heuristic: the
   `ControlBar.wnd` layout, the scheme INIs, the MappedImages INIs whose image names a scheme
   actually references, and only the textures those images name. An unrelated INI (an `Object`
   definition, another mod's art) is never selected.
3. Writes just those entries, preserving their internal paths, into a new managed mod folder and
   runs `ModPacker.ensurePacked`, so the files end up in the mod's own `!!loose_files.big`. The mod
   is then switched on and ordered exactly like any other in the Mods screen — the importer reuses
   the existing mod loading infrastructure rather than inventing a second one.
4. If an archive holds no control bar files, it is refused with its own message; an archive that is
   not a readable BIG is refused with another. Neither writes anything.

The screen shows what was found (layout / schemes / image definitions / textures) before importing.

## 4. Modified files

| File | Change |
|---|---|
| `MapManager.java` | new — map discovery, folder/ZIP import, folder/ZIP export, delete |
| `MapManagerActivity.java` | new — the Map Manager screen |
| `ControlBarBig.java` | new — BIG parse, control-bar detection, import-as-mod |
| `ControlBarBigActivity.java` | new — the ControlBar BIG Importer screen |
| `SetupActivity.java` | `buildToolsSection` (Tools card with both entries) |
| `ModImportService.java` | visibility of the path helpers, `INSTALLING_DIR` constant, one literal replaced by it — no behaviour change |
| `AndroidManifest.xml` | the two activities (`exported="false"`, settings theme, configChanges) |
| `res/values*/strings.xml` | the new strings, in all 13 locales |
| `app/build.gradle` | `--add-opens=java.base/java.io=ALL-UNNAMED` for unit tests (Robolectric closes a `ParcelFileDescriptor` on export) |
| `MapAndControlBarToolsTest.java` | new — 11 tests over the real code paths |

No native file, `Core/**`, `Generals/**`, `GeneralsMD/**` or CMake file was touched.

## 5. Verification

- `:app:testDebugUnitTest` — **264 tests, 0 failures** (11 new: map describe/list, `.map` magic,
  ZIP import + install, ZIP traversal refusal, empty-ZIP refusal, ZIP export, delete scoping,
  control-bar selection, unsupported archive, no-controlbar archive, import-as-mod listed by the
  Mods screen).
- `:app:assembleLauncherOnly` — `BUILD SUCCESSFUL`.
- `:app:lintDebug` — `0 errors`, no warning in any new file.
- `scripts/qa/android/check-translations.py` — green across 13 locales (656 strings each).
