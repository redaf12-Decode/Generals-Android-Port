# Tools: Map Packs Manager and ControlBar Pack Manager

Date: 2026-11-07 · Branch: `New-app` · Area: Android launcher (Tools tab)

Two Tools screens. Both were built on what the repository already had — the engine's own map and
archive rules, the launcher's import helpers and BIG reader — rather than on a second map scanner
or a second BIG parser. This report records where maps and archives actually live in this
codebase, what each screen does, and what was verified.

Both screens share one shape, chosen for the same reason: a game file the player installs is a
file that can collide with the game's own, so **nothing is written into the game folder until the
player switches a package on**, and every file written is recorded so it can be taken back out
exactly. Imports land in a manager folder and are never moved; activating copies/unpacks, and
deactivating removes exactly the copies this manager made (and restores anything it had to move
aside). The game's own files and other mods are never touched.

## 1. Where the game keeps its maps (measured, not assumed)

(Background: the map rules below are the ones both Tools screens rely on. The Map Packs Manager
itself is described in section 2 and in `TOOLS_MAP_PACKS_MANAGER_20261107.md`.)

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

## 2. Map Packs Manager

`MapPackManager.java` (logic) + `MapPackActivity.java` (UI), on the shared `MapManager` helper.
Entry: **Setup → Tools → Map Manager**. Full detail in
`TOOLS_MAP_PACKS_MANAGER_20261107.md`; the shape is the one above — import a folder or ZIP into
`<user data>/maps-manager/<pack>`, then switch a pack on to copy its maps into `Maps/`. A combined
limit of 200 unique active maps is enforced.

## 3. ControlBar Pack Manager

`ControlBarBig.java` (BIG read + control-bar detection + extraction) + `ControlBarPackManager.java`
(package install / activate / deactivate) + `ControlBarPackActivity.java` (UI). Entry:
**Setup → Tools → ControlBar BIG Importer**.

The previous version imported an archive's control-bar files as a **mod** (a packed
`!!loose_files.big` the Mods screen mounts). That is gone: a control bar is not a mod, and mixing it
into the Mods list left the player with two ways to change it. The pack manager does for the control
bar what the Map Packs Manager does for maps.

### What the engine reads for the control bar (measured)

- The layout: `Window\ControlBar.wnd`. `InGameUI::createControlBar` calls
  `TheWindowManager->winCreateFromScript("ControlBar.wnd")` (`InGameUI.cpp:4872`), and
  `winCreateFromScript` prepends `"Window\"` (`GameWindowManagerScript.cpp:2731`). So the file the
  engine opens is `Window\ControlBar.wnd`.
- The schemes: `ControlBarSchemeManager::init` calls
  `ini.loadFileDirectory("Data\INI\Default\ControlBarScheme", …)` then
  `…("Data\INI\ControlBarScheme", …)` (`ControlBarScheme.cpp:1038`).
- The mapped images the schemes name: `Data\INI\MappedImages\*` (`Image.cpp:280`), each mapping an
  image name to a `Texture` file.
- The textures themselves: `ControlBar.cpp:2940` builds `Art\Textures\<file>`, and
  `W3DFileSystem.cpp:187` first tries `Data\<language>\Art\Textures\<file>` for an image, where
  `<language>` is `GetRegistryLanguage()`.

**The load path matters.** These are ordinary game files, opened through `FileSystem::openFile`,
which asks the local file system before the archives. The control bar is therefore read from the
game folder as a loose file — that is what the manager installs, and it is why nothing here needs a
mod or a `.big`.

### What the manager does

**Import** (`ACTION_OPEN_DOCUMENT_TREE` for a folder, `ACTION_OPEN_DOCUMENT` for a ZIP). The
selection is copied into `<user data>/Controlsbar-Manager/.installing/<uuid>` through
`ModImportService`'s path helpers, so ZIP traversal and absolute paths are rejected exactly as they
are for mods. The stage must hold at least one readable `.big` (`ControlBarBig.isBig`, the
`ModHealthCheck.checkBigTable` bounds) and at least one of them must carry control-bar files
(`ControlBarBig.detect`); otherwise the import is refused with its own message and the stage is
deleted. A package is a folder holding one or more `.big` archives — nothing is extracted yet.

**Detection** follows the engine's chain, not a name guess: the `ControlBar.wnd` layout
(`Window\` or `GeneralsOnlineGameData\`), the scheme INIs (`Data\INI\ControlBarScheme\` and
`Data\INI\Default\ControlBarScheme\`), the MappedImages INIs, and the textures those images name
(under `Art\` or `Data\<language>\Art\`). A MappedImages INI is kept when a scheme in the same
archive names one of the images it defines — an unrelated image definition shipped alongside a unit
skin's is not a control-bar file. With no scheme in the archive (a layout-only retexture), every
MappedImages INI is kept, because the images it redefines are the game's own scheme's. Only
textures the kept images name are taken; if a scheme names none of the archive's own art, the
archive's `Art\` textures are taken as the fallback. An unrelated INI (an `Object` definition,
another mod's art) is never selected.

**Activate** unpacks the selected entries into the game folder, preserving their internal paths,
and **rewrites the language folder** of a localized path (`Data\German\Art\…`) to the language the
engine actually reads (`ControlBarPackManager.gameLanguage`, which probes the same `*ZH.big` files
`tryAutoDetectLanguage` does, defaulting to `english`). Only one package is active at a time: any
previous one is deactivated first, and a failed switch restores it, so the game is never left with
two control bars.

**Deactivate / delete** removes exactly the files the manifest records and restores any backup.
Every file written is recorded in a manifest; before writing over a file the game shipped itself,
that file is moved into a backup folder and put back on deactivate. The game's own files and other
mods are never touched.

### Storage

- Packages: **`<user data>/Controlsbar-Manager/<pack>`**, one folder per pack, holding the `.big`
  archive(s) and a small metadata file. `<user data>` is `SetupActivity.userDataDir(ctx)` — the
  same folder the engine reads.
- Installed files: **the game folder** (`SetupActivity.getSavedGamePath(ctx)`, the folder
  `SDL3Main` `chdir()`s into), at the archive's own paths. The game folder is shared storage
  (the app already holds `MANAGE_EXTERNAL_STORAGE` and the legacy READ/WRITE permissions), so plain
  `File` I/O is used, the same way the Mods and Map Packs screens do.
- The manifest (`Controlsbar-Manager/.installed.txt`) records installed path → backup name, so a
  restart can re-unpack a missing file and deactivation can remove exactly what was written.

## 4. Modified files

| File | Change |
|---|---|
| `MapManager.java` | kept — the shared "where the maps are and what a map is" helper |
| `MapPackManager.java` / `MapPackActivity.java` | the Map Packs Manager (see its own report) |
| `ControlBarBig.java` | new — BIG read, control-bar detection, extraction (replaces the old import-as-mod reader) |
| `ControlBarPackManager.java` | new — package import, activate/deactivate, manifest, language handling |
| `ControlBarPackActivity.java` | new — the ControlBar Pack screen |
| `ControlBarBigActivity.java` | deleted (replaced by `ControlBarPackActivity`) |
| `SetupActivity.java` | the Tools card; its ControlBar entry opens `ControlBarPackActivity` |
| `GeneralsZHActivity.java` | calls `ControlBarPackManager.sync(this)` next to `MapPackManager.sync` before the engine starts |
| `ModImportService.java` | visibility of the path helpers, `INSTALLING_DIR` constant, one literal replaced by it — no behaviour change |
| `AndroidManifest.xml` | the activities (`exported="false"`, settings theme, configChanges) |
| `res/values*/strings.xml` | the new strings, in all 13 locales |
| `app/build.gradle` | `--add-opens=java.base/java.io=ALL-UNNAMED` for unit tests (Robolectric closes a `ParcelFileDescriptor` on export) |
| `MapAndControlBarToolsTest.java` | the tests over the real code paths |

No native file, `Core/**`, `Generals/**`, `GeneralsMD/**` or CMake file was touched.

## 5. Verification

- `:app:testDebugUnitTest` — **274 tests, 0 failures** (the Tools suite covers map packs and the
  ControlBar pack manager: control-bar selection, unsupported / no-controlbar archive, nothing
  installed until activated, switching a package, deactivation restoring a shipped file, ZIP import
  and refusal, `sync` re-unpacking a lost file, the language-folder rewrite, and delete).
- `:app:assembleLauncherOnly` — `BUILD SUCCESSFUL`.
- `:app:lintDebug` — `0 errors`, no warning in any new file.
- `scripts/qa/android/check-translations.py` — green across 13 locales (692 strings each).
