# FEATURES_ANALYSIS.md — Generals-Android-Port

Every feature that is actually implemented, where it lives, how it works, what it depends on, and
what limits it. Features that exist only as a name are marked as such.

Legend: **[FACT]** verified in the code; **[INFERENCE]** reasoned; **[GAP]** a limitation or a
problem; **[IDEA]** an opportunity.

---

## Index

1. [Game and engine management](#1-game-and-engine-management)
2. [Game folder management and integrity](#2-game-folder-management-and-integrity)
3. [Render backends](#3-render-backends)
4. [Graphics and performance settings](#4-graphics-and-performance-settings)
5. [Interface settings](#5-interface-settings)
6. [Input and control schemes](#6-input-and-control-schemes)
7. [Mods](#7-mods)
8. [Updates without a new APK](#8-updates-without-a-new-apk)
9. [GeneralsOnline account and community data](#9-generalsonline-account-and-community-data)
10. [Language packs and subtitles](#10-language-packs-and-subtitles)
11. [Diagnostics and logs](#11-diagnostics-and-logs)
12. [Replay check](#12-replay-check)
13. [Support card](#13-support-card)
14. [Localisation and theming](#14-localisation-and-theming)
15. [In-game text entry](#15-in-game-text-entry)
16. [LAN multicast](#16-lan-multicast)
17. [Features that are *not* implemented](#17-features-that-are-not-implemented)

---

## 1. Game and engine management

**What it does.** One APK runs both *Command & Conquer: Generals* and *Generals: Zero Hour*, at
either 30 Hz or 60 Hz simulation, and each game has its own folder, user-data folder and engines.

**Where.**
* `SetupActivity`: `PREF_GAME`, `GAME_ZERO_HOUR`/`GAME_GENERALS`, `getSelectedGame`,
  `setSelectedGame`, `gamePathPref`, `engineLibraries`, `userDataDir`, `PREF_SIM_HZ`,
  `SIM_HZ_RETAIL`/`SIM_HZ_CROSSPLAY`, `getSimHz`, `setSimHz`, `buildHomeSection` (game segmented
  control), `buildSimRateSection`.
* `GeneralsZHActivity.getLibraries()` — chooses `libmain`/`libmain60`/`libgenerals`/`libgenerals60`.
* `GeneralsZHActivity.getArguments()` — writes the mods list, the subtitles marker, the Zero Hour
  art path.
* Native: `SDL3Main.cpp` `RTS_ZEROHOUR` / `!RTS_ZEROHOUR` branches.

**How it works.** The tick rate cannot be a runtime flag: `WWSyncPerSecond` is an enum constant that
decides the layout of `GameLogic`. So the engine is built twice
(`build-dual-hz.sh`, `SAGE_HIGH_FPS_SIM=ON` then `OFF`) and the launcher loads the matching library.
The game choice likewise selects a different library pair and a different game folder.

**Dependencies.** `build-dual-hz.sh` + `package-android-zh.sh` (`GX_SECOND_GAME_LIB`,
`GX_SECOND_BASE_GAME_LIB`); four engine `.so` files in the APK; `CMakePresets.json` `RTS_BUILD_GENERALS=ON`.

**Limits / gaps.**
* **[FACT][GAP]** `libmain60.so` availability is checked at load time, `libgenerals60.so` is not:
  `getLibraries()` only tests `engines[1]`'s file for the tick-rate fallback.
* **[FACT][GAP]** Switching game calls `buildUi()` — the whole shell is rebuilt, not just the page.
* **[INFERENCE][GAP]** Zero Hour's user-data leaf comes from `DataPackInstaller.userDataDir()`;
  the Generals leaf is hardcoded in `SetupActivity.userDataDir()`. Two sources of truth for one
  concept.

**Opportunities. [IDEA]** One `GameProfile` value object (name, engine libraries, game-folder
preference key, user-data leaf, required files) would replace five scattered lookups.

---

## 2. Game folder management and integrity

**What it does.** Pick the folder holding the user's `.big` archives, optionally a second folder
holding the *base game's* archives for Zero Hour, and be told whether the folder is actually
complete.

**Where.** `SetupActivity`: `PREF_GAME_PATH`, `PREF_GENERALS_GAME_PATH`, `PREF_BASE_GENERALS_PATH`,
`EXTERNAL_MARKER_NAME`, `GENERALS_EXTERNAL_MARKER_NAME`, `REQUIRED_GAME_FILES`,
`GENERALS_REQUIRED_FILE`, `ZERO_HOUR_ONLY_FILE`, `onSelectGameFolder`,
`onSelectBaseGeneralsFolder`, `onClearBaseGeneralsFolder`, `onClearGameFolder`, `saveGamePath`,
`getSavedGamePath`, `writeNativeGameMarker`, `readExternalMarker`, `isValidGameFolder`,
`findGameFolderBelow`, `invalidFolderMessage`, `findGameFolderIntegrityIssues`,
`bigContainsEntry`, `forEachBigEntry`, `hasSkirmishScripts`, `bigArchiveHasEntry`,
`hasBigFHeader`, `readBigEndianInt`, `hasAnyBaseGeneralsAssets`, `resolveBaseGeneralsFolder`,
`copyBundledRuntimeIfMissing`, `syncEngineWindowOverrides`, `removeRetiredPortStringFiles`,
`hasStorageAccess`, `requestStorageAccess`. UI: `buildHomeSection`, `buildDataSourcesRow`,
`refreshStatus`. `FolderPickerActivity` for the browser.

**How it works.**
1. `MANAGE_EXTERNAL_STORAGE` is requested (API 30+); on API 28/29 the pre-R
   `READ/WRITE_EXTERNAL_STORAGE` pair with `maxSdkVersion=29` applies.
2. `FolderPickerActivity` is a plain `java.io.File` browser — deliberately not the SAF tree picker,
   because the engine uses `fopen`/`chdir` and cannot read a `content://` tree.
3. `isValidGameFolder` checks `INIZH.big` + `INI.big` for Zero Hour, `INI.big` and *not* `INIZH.big`
   for Generals (so a Zero Hour folder cannot be used as the base game).
4. `findGameFolderIntegrityIssues` walks the folder and its immediate subfolders, reads the BIG
   headers, and reports missing critical entries (`data\ini\default\weather.ini` for Zero Hour,
   `data\ini\gamedata.ini` for Generals), missing base-game archives, and missing skirmish scripts.
5. The path is stored in `SharedPreferences` **and** in a dot-file in shared external storage so a
   reinstall can recover it, and mirrored to `gamedata_path.txt` in app-private storage for native
   code.
6. `copyBundledRuntimeIfMissing` seeds `fonts/`, `dxvk.conf`, `DefaultOptions.ini` into the game
   folder; `Window/` is always overwritten.

**Dependencies.** `MANAGE_EXTERNAL_STORAGE`; the marker contract with `SDL3Main.cpp`; the BIG header
format.

**Limits / gaps.**
* **[FACT][GAP]** The external marker lives at the storage root, so two apps/fork builds can
  overwrite each other's marker.
* **[FACT][GAP]** `bigContainsEntry(File big, String wanted, int unusedDepth)` takes an unused
  parameter — dead signature.
* **[INFERENCE][GAP]** Integrity scanning reads BIG headers on the UI thread path
  (`refreshStatus` is called from `onResume` and `showTab`), on folders of potentially thousands of
  files.

**Opportunities. [IDEA]** Move the integrity scan off the UI thread with a cached result keyed by
folder mtime; add a "repair" action that copies only the missing pieces.

---

## 3. Render backends

**What it does.** Choose D3D8→Vulkan (DXVK) or D3D8→OpenGL ES 3.0 (native translator, optionally
through ANGLE). GLES is the default; Vulkan is behind an "experimental" switch.

**Where.** `SetupActivity`: `RENDER_BACKEND_CFG_NAME`, `RENDER_BACKEND_VULKAN`,
`RENDER_BACKEND_GLES`, `RENDER_BACKEND_GLES_ANGLE`, `getRenderBackendChoice`, `writeRenderBackend`,
`renderBackendChoices`, `RENDER_BACKEND_CHOICES`, `RENDER_BACKEND_CHOICES_NO_VULKAN`,
`PREF_VULKAN_UNLOCKED`, `PREF_VULKAN_MOVED`, `isVulkanUnlocked`, `moveOffVulkanOnce`,
`shortRenderBackendLabel`, `renderBackendLabel`, `detectGpuName`, `buildRenderBackendSection`,
`onPickRenderBackend`. Native: `d3d8gles_ShouldUseVulkanBackend()`, `d3d8gles_ShouldUseANGLE()`,
`DX8Wrapper::Init()`, `Core/Libraries/Source/d3d8gles/`.

**How it works.** `render_backend.cfg` in app-private storage holds one word. The native side reads
it once and picks the whole rendering path; the GLES path is statically linked
(`d3d8gles` into `libmain.so`) while DXVK is `dlopen`ed by name.

**Dependencies.** `Core/Libraries/Source/d3d8gles/` + the two prebuilt ANGLE libraries; DXVK for the
Vulkan path; `libadrenotools.so` + 4 hook libraries for custom Vulkan drivers.

**Limits / gaps.**
* **[FACT]** `moveOffVulkanOnce()` migrates users off Vulkan exactly once
  (`PREF_VULKAN_MOVED`), and the Vulkan paragraphs of the help text only show while Vulkan is
  offered.
* **[FACT][GAP]** The Graphics page rebuilds itself on every backend change (`showTab(TAB_GRAPHICS)`)
  because the driver/dxvk.conf cards come and go with it — correct, but the scroll position is lost.

**Opportunities. [IDEA]** A one-line "why this default" note with the GPU name is already there;
a per-device recommendation table could be published in `remote_config.ini` and read by the launcher.

---

## 4. Graphics and performance settings

| Feature | Where | Mechanism | Limits |
|---|---|---|---|
| **Upscaling** (off / ultra / quality / balanced / performance → 100/77/67/59/50 %) | `SetupActivity.UPSCALE_MODES`, `UPSCALE_PERCENT`, `buildRenderScaleSection`, `readUpscaleMode`, `writeUpscaleMode`, `updateUpscaleStatus`, `screenSize` | `GXUpscale` in `Options.ini`; the engine renders smaller and stretches back (GLES only) | **[FACT]** Hidden while Vulkan is selected — it lives in the GLES translator |
| **Custom Vulkan driver** | `CUSTOM_DRIVER_*`, `buildCustomDriverSection`, `onImportCustomDriver`, `onClearCustomDriver`, `importCustomDriver`, `customDriverStatusText`, `applyRecommendedDriverIfNeeded`, `deviceReportsVulkan13`, `copyDriverAssetTree`, `findFileByName` | A `.zip` with `meta.json` + driver `.so` is unpacked to `files/custom_driver/`, `custom_driver.cfg` names the library, libadrenotools loads it | **[FACT]** Only offered with Vulkan; import is validated (path traversal, library name) |
| **Bundled Turnip fallback** | `applyRecommendedDriverIfNeeded`, asset dir `default_driver`, `scripts/build/android/fetch-turnip.sh` | On an Adreno device reporting < Vulkan 1.3, the bundled driver is installed automatically and marked `custom_driver.auto` | **[FACT]** 17 MB in the APK; no-op elsewhere |
| **dxvk.conf editor** | `dxvkConfigEdit`, `buildDxvkConfigSection`, `loadDxvkConfigIntoEditor`, `onSaveDxvkConfig`, `onResetDxvkConfig`, `dxvkConfFile`, `bundledDxvkConfFile` | Edits the file in the game folder; reset restores the bundled copy | **[FACT]** Only with Vulkan |
| **Simulation rate** | see §1 | two engines | |
| **Diagnostic performance markers** | see §11 | `gx_perf.txt`, `gx_gles_gputimer.txt` | |

---

## 5. Interface settings

| Feature | Where | Mechanism |
|---|---|---|
| **Menu text size** (0–150 %) | `buildUiScaleSection`, `readUiScalePercent`, `writeUiScalePercent`, `uiScaleSlider`, `uiScaleLabel` | `ResolutionFontAdjustment` in `Options.ini`; `-1`/negative means "game default" (clamped away) |
| **Interface size** (100–200 %) | `buildInterfaceScaleSection`, `readInterfaceScalePercent`, `writeInterfaceScalePercent` | `GXUiScale` in `Options.ini`, applied by the engine to the retail layouts (`GXUiScale.h`) |
| **Appearance** (Standard / Navy / Day / System) | `buildThemeSection`, `ThemeHelper` | `launcher_theme_mode` in `SharedPreferences`; `recreate()` |
| **Launcher language** | `buildLanguageSection`, `onChangeLanguage`, `LocaleHelper` | `language_tag`; `attachBaseContext` wrapping + `recreate()` |
| **Game text language** | `buildLanguageSection`, `onChangeGameTextLanguage`, `updateGameLanguageStatusView`, `installedGameTextTokens`, `collectArchiveLanguages`, `gameTextName`, `applyGameLanguageOverride`, `migrateGameTextTokenFromMarker` | `game_text_token`; a `generals.str` installed under `<game>/data/<token>/` |
| **Subtitles** | `SUBTITLES_MARKER`, `PREF_SUBTITLES`, `subtitlesOn`, `writeSubtitlesMarker` | empty marker file read by `GXSubtitles.h` |
| **Control scheme** | `buildControlSchemeSection`, `readControlScheme`, `writeControlScheme`, `controlSchemeIndex`, `CONTROL_SCHEME_KEYS` | `control_scheme` file, three keys, read by `GXControlScheme.h` |
| **Mouse / touchpad mode + cursor size** | `buildMouseModeRows`, `readMouseCursorPercent`, `writeMouseMode`, `MOUSE_MODE_MARKER` | `mouse_mode` file whose content is the cursor percent; read by `GXMouseMode.h` |
| **Skip intro movies** | `PREF_SKIP_INTRO`, `isSkipIntroEnabled` | `-nologo` argument |

**Limits. [FACT][GAP]** Text size and interface size are *two* sliders that both change how big the
in-game UI is, and the help text has to explain the difference. **[IDEA]** consider merging them
behind one control with an "advanced" disclosure.

---

## 6. Input and control schemes

**Touch (default).** `Core/GameEngineDevice/Source/SDL3GameEngine.cpp` (3 389 LOC) holds the gesture
state machine: `PENDING`, `PANNING`, `SELECTING`, `PLACING`, `LIST_SCROLL`, UI press. Meaning is
resolved by `SDL3Device/GameClient/TouchInput.cpp` through `pickDrawable`, `screenToTerrain`,
`evaluateContextCommand`. Screen-edge scrolling is off on touch. List scrolling is driven directly
(`GadgetListBoxTouchScroll*`) because a list can never see a drag otherwise.

**Mouse / touchpad mode.** `GXMouseMode.h`; `SDL3Mouse.cpp`; the SDL3 Java glue is patched by
`android/patches/sdl-android-mouse-source-clicks.patch` so a Bluetooth mouse's click is recognised.

**Control scheme.** Classic (retail) / Tactical / Custom with three independent switches
(`attack_move`, `army_buttons`, `camera_high`), written to `control_scheme` and read by
`GXControlScheme.h`.

**Back / gesture navigation.** `android/patches/sdl-android-gesture-nav-back.patch`,
`GeneralsZHActivity.applyGestureNavBackBehavior` (`BEHAVIOR_DEFAULT` keeps the back gesture alive
with the bars hidden), `setOrientationBis` pinned to landscape, `onWindowFocusChanged` re-hiding the
bars.

**Limits. [FACT][GAP]** `setLanMulticastLock` is the only JNI entry point for input-adjacent state
and has no counterpart for anything else; the text editor is the only other Java↔native channel.

---

## 7. Mods

**What it does.** A `mods/` folder under the game folder, one subfolder per mod, each with its own
`.big` files and/or loose files laid out like the game, several mods switchable and orderable, each
set getting its own saves/replays/options profile, with import from a folder / `.big` / `.zip`, a
health check, an analysis of what the mod changes, and a "safe start" that backs off a combination
that failed twice.

**Where.**
* `ModManager.java` — `MODS_DIR`, `ACTIVE_LIST_FILE`, `Mod`, `listMods`, `modsRoot`, `modsRootFor`,
  `moveToGame`, `saveOrder`, `readOrder`, `enabledCount`, `writeActiveList`, `profileName`,
  `BOOT_PENDING_FILE`, `failedStarts`, `activeList`, `noteEnabled`, `lastEnabled`, `wrapLooseArchives`.
* `ModManagerActivity.java` — list, order, enable/disable, import, health, filter, search.
* `ModImportService.java` — `importDocument`, `importTree`, `importBig`, `importZip`, bounds
  (`MAX_TOTAL_BYTES` 8 GiB, `MAX_ENTRIES` 100 000, `MAX_DEPTH` 64), staging under
  `mods/.installing/<uuid>`, `resolveSafeRelative`, atomic rename, `CancellationSignal`.
* `ModAnalysis.java` — file kinds, game detection (ZH / Generals / both), level grey/amber/red.
* `ModHealthCheck.java` — bounded walk, BIG table validation.
* `ModPacker.java` — `.gib` → `.big`, loose files packed into `!!loose_files.big`.
* `ModLibraryInfo.java` — size.
* Native: `Core/GameEngine/Include/Common/GXManagedMods.h`,
  `ArchiveFileSystem::loadMods`/`loadManagedMods`, `GameText.cpp`, `INI.cpp`, `GameState.cpp`
  (profile dir), `Recorder.cpp` (profile dir).
* Safe start: `SetupActivity.onLaunchGame` + `ModManager.failedStarts` +
  `GXRemoteConfig::markEngineBootComplete`.

**How it works.** Before a start, `ModManager.writeActiveList` writes `#profile <name>` and the
enabled folders (highest priority first) to `mods_active.txt`. `GXManagedMods` reads it once per
process; `ArchiveFileSystem::loadMods` mounts the folders above the game's own archives and above
legacy `!` archives. The engine deletes `mods_boot_pending` when the main menu is reached, which is
how "did it start" is known.

**Dependencies.** The game folder being set; write access to app-private storage; `ModPacker` for
loose files; the engine's archive mount order.

**Limits / gaps.**
* **[FACT][GAP]** `ModPacker.ensurePacked` runs inside `writeActiveList`, i.e. **on SDL's thread
  just before the game starts**. A large mod costs a directory walk (and possibly a repack) at every
  start.
* **[FACT][GAP]** `ModManagerActivity.onDestroy` interrupts the import thread and shuts the executor
  down, but a rotation/`recreate()` of the Mods screen therefore cancels an in-flight import.
* **[FACT][GAP]** `ModManagerActivity` deletes **all** `*.zip` files in the cache dir on create
  (`getCacheDir().listFiles(name.endsWith(".zip"))`), which is broader than "our import staging".
* **[INFERENCE]** Conflict reporting (which mod wins on a file) is listed in `PLAN-027` as not done.

**Opportunities. [IDEA]** Move `ensurePacked` to a background step at import time and keep the
pre-start path to a stat; persist the import across recreation.

---

## 8. Updates without a new APK

**What it does.** Fetch a signed manifest from the repository's `updates` (or `updates-test`) branch
and take from it: engine settings, feature switches, the support card text, and a newer engine
binary — all without reinstalling the APK.

**Where.** `UpdateManager.java` (`RAW_ROOT`, `isTestChannel`, `channelName`, `baseUrl`,
`PUBLIC_KEY_B64`, `ENGINE_LIBS`, `check`, `applyEngine`, `applySupport`, `writeRemoteConfig`,
`librariesMatch`, `dependenciesStillMatch`, `depsStamp`, `pruneOtherEngines`, `noteEngineBoot`,
`activeEngineSeq`, `activeEngineLibrary`, `verify`, `downloadEngineFile`, `sha256`, `remoteConfig`,
`featureEnabled`, `settingsPublished`, `acceptedSerial`, `datapackUpdateWanted`,
`isUnmeteredNetwork`). `SetupActivity.buildUpdatesSection`, `refreshUpdatesStatus`, `runUpdateCheck`.
`GeneralsZHActivity.loadLibraries`/`getMainSharedObject` (loads the downloaded engine).
Engine side: `Common/GXRemoteConfig.h`, `GeneralsMD/.../Common/GXSignedSettings.{h,cpp}`.
Publishing: `update/*.json`, `scripts/update/{features.py,publish-update.py,push-updates-branch.sh,pc-exe-crc.py}`,
`.github/workflows/sign-update.yml`.

**How it works.**
* The manifest is signed with ECDSA P-256; the public key is in `PUBLIC_KEY_B64`; the private key is
  kept out of the repository. `serial` may never decrease, so an old manifest cannot be replayed.
* `config` keys become `files/update/remote_config.ini`, read by the engine at start.
* `support` is a SHA-256-checked `support.json`.
* `engine` files are downloaded, gunzipped, size- and SHA-256-checked against the **signed**
  manifest, and loaded instead of the APK's engine — but only if `seq` exceeds
  `assets/engine_build.txt` (the commit count), every `requires_libs` SHA-256 matches the installed
  APK's libraries, and the channel matches.
* An engine that twice fails to reach the main menu is marked bad and the APK's own engine runs.
* Two channels: `updates` (release) and `updates-test` (written when `GX_UPDATE_CHANNEL=test`).

**Limits / gaps.**
* **[FACT][GAP]** `RAW_ROOT` and the language-pack URLs point at `MYSOREZ/GeneralsZH-Android-Port`,
  not at this repository. On this fork the update and language-pack flows therefore read the
  upstream repository's branches (which may or may not exist there).
* **[FACT][GAP]** `noteEngineBoot` counts a failed boot by the *pending marker being still present*;
  a normal user-visible crash is indistinguishable from a failure to reach the menu.
* **[INFERENCE][GAP]** Feature switches are only as safe as the engine that reads them; engines
  before build 3278 ignore `pc_crossplay` and claim the PC checksum whenever the marker exists
  (`update/features.json`, `unaware_builds`).

---

## 9. GeneralsOnline account and community data

**What it does.** Sign in to `playgenerals.online` from the phone, keep the session fresh, and
install/refresh the GeneralsOnline community data package (maps + community patch) straight from the
service's own CDN. Cross-play with PC is present as a switch but off.

**Where.** `GeneralsOnlineActivity.java` (932 LOC), `GeneralsOnlineSession.java` (497 LOC),
`DataPackInstaller.java` (738 LOC), `NetworkTrace.java`, `NetworkDiagnostics.java`.
`SetupActivity.buildGeneralsOnlineSection` / `refreshGeneralsOnlineStatus` /
`isLoggingDisabled`-adjacent settings. Native: the engine's GeneralsOnline client under
`GeneralsMD/Code/GameEngine/Source/GameNetwork/GeneralsOnline/`.

**How it works.**
* Sign-in opens `https://www.playgenerals.online/login/?gamecode=<clientId>` in a browser and polls
  every second (up to 180 attempts) against `api.playgenerals.online` (fallback `api-ru.…`).
* The session (`session_token`, `refresh_token`, `user_id`, `display_name`, `ws_uri`) is stored in
  `SharedPreferences` and mirrored to `generalsonline_session.txt` for native code.
  `GeneralsZHActivity.onCreate` calls `refreshSessionAsync` on every launch because server-side
  tokens expire within hours.
* `DataPackInstaller` reads `datapack_manifest_url` (from the signed settings, default the
  GeneralsOnline CDN), downloads the package, writes only the two whitelisted directories, records
  the installed file list, and supports uninstall; `gx_no_community_patch.txt` disables it and
  `gx_community_patch_with_mods.txt` allows it alongside a data mod.
* `maybeAutoCheckDataPacks` installs a newer patch automatically on an unmetered network, with
  progress; on mobile data it only reports.
* `NetworkDiagnostics.run` probes DNS/TCP/TLS/HTTP against the service hosts, produces a redacted
  report, and can share it.

**Limits / gaps.**
* **[FACT][GAP]** `DataPackInstaller.userDataDir()` hardcodes the **Zero Hour** user-data leaf, so
  the community patch, and the replay/result files that use the same helper, are always read from
  the Zero Hour user-data folder even when the base game is selected.
* **[FACT][GAP]** One open `TODO` in the whole launcher:
  `GeneralsOnlineActivity.java:894` — "also DELETE /User/{user_id} server-side like the reference".
* **[FACT]** Cross-play with PC is **off** (`update/config.json` `pc_crossplay: "0"`,
  `update/features.json` explains: turned off 18/10/2026 at the GeneralsOnline developers' request).

---

## 10. Language packs and subtitles

**What it does.** Translate the launcher (13 locales) and the game's text; offer to install a pack
for the phone's language on first run and to update it later; show subtitles for voice lines.

**Where.** `LocaleHelper` (`SUPPORTED_TAGS`, `languagePackFor`, `gameDataLanguageFor`,
`displayNameFor`, `getGameTextToken`/`setGameTextToken`, `hasGameTextToken`, `gameTextDisplayName`,
`wrap`). `SetupActivity`: `LANGUAGE_PACK_INDEX`, `LANGUAGE_PACK_BASE`, `LANGUAGE_PACK_LIST`,
`onDownloadLanguagePack`, `fetchLanguagePackList`, `fetchLanguagePackIndex`, `downloadLanguagePack`,
`maybeOfferLanguagePack`, `maybeCheckLanguagePackUpdate`, `showLanguagePackUpdate`,
`describeLanguagePackFailures`, `isOurLanguagePack`, `sameFileContents`, `PREF_LANGPACK_OFFERED`,
`maybeCheckLanguagePackUpdate` (12-hour interval), `gameHasTextFor`.
`languages/<lang>/{generals.str,generalszh.str}`, `languages/index.txt`.

**How it works.** The launcher lists `languages/` through the GitHub contents API, downloads
`<lang>/generals.str` or `generalszh.str` and installs it as `<game>/data/<lang>/generals.str`,
which is the name the engine reads. The engine's text language is a separate setting from the
launcher's, so both exist.

**Limits / gaps.**
* **[FACT][GAP]** The URLs are hardcoded to the upstream repository, and the language *list* is
  fetched from `api.github.com` without a token — subject to unauthenticated rate limits.
* **[FACT]** `PLAN-026` (one pack per game) is at step 1 of 2; step 2 waits for the owner.

---

## 11. Diagnostics and logs

**What it does.** Nine opt-in marker switches, a master logging switch, an in-app log viewer with
Share, and a network diagnostics screen.

**Where.** `SetupActivity.DIAGNOSTIC_MARKERS`, `DIAGNOSTIC_TITLES`, `DIAGNOSTIC_DESCRIPTIONS`,
`buildDiagnosticsSection`, `setDiagnosticMarker`, `refreshDiagnosticsSwitches`,
`diagnosticsNoFolderHint`, `LOGGING_OFF_MARKER`, `isLoggingDisabled`, `setLoggingDisabled`,
`buildLogsSection`. `LogViewerActivity` (`MAX_CHARS_PER_FILE` 200 000, `HEAD_CHARS_PER_FILE` 60 000,
`loadLogs`, `clearLogs`, `shareLogAsFile` → `generalszh-logs.zip`).
`NetworkDiagnosticsActivity` + `NetworkDiagnostics` + `NetworkTrace` (`MAX_BYTES` 512 KB, redaction
of bearer tokens, `ENGINE_MARKER` `gx_net_trace.txt`). Native:
`Core/Main/AndroidCrashHandler.cpp` (ELF-constructor signal handler writing `crash.log` with raw
syscalls, chaining to the previous handler), `GXLogging.h`, the engine's `[GX-TRACE]`/`[GX-PERF]`
macros.

**Marker set.** `gx_trace.txt`, `gx_perf.txt`, `gx_gles_gputimer.txt`, `gx_audio_trace.txt`,
`gx_net_trace.txt`, `gx_touch_debug.txt`, `dxvk_hud.txt`, `dxvk_validation.txt`,
`dxvk_verbose_log.txt` — all in the **game folder**, except `logging_off` which is app-private.
DXVK switches are hidden unless Vulkan is offered.

**Limits / gaps.**
* **[FACT][GAP]** Logging markers live in the game folder, so they are lost when the folder changes;
  `logging_off` deliberately does not.
* **[FACT][GAP]** `LogViewerActivity` truncates each file (head 60 000, total 200 000 characters);
  the Share path writes the real files, so a very long log is only fully readable after sharing.

---

## 12. Replay check

**What it does.** Pick a replay and either run it through to the end or fast-forward to a frame,
with a result summary; optionally compute a CRC every frame.

**Where.** `ReplayCheckActivity` (`RESULT_FILE` `gx_replay_check_result.txt`, `NET_TRACE_MARKER`,
`CRC_EVERY_FRAME_MARKER`, `MODE_RUN_THROUGH`, `MODE_WATCH_FROM`, `clearCheckMarkers`,
`traceTurnedOnByCheck`). `GeneralsZHActivity.EXTRA_REPLAY`, `EXTRA_FAST_TO`, `EXTRA_AUTO_QUIT`,
`EXTRA_CRC_EVERY_FRAME` and `getArguments` → `-replay`, `-gxFastTo`, `-gxAutoQuit`,
`-gxCrcEveryFrame`, `-gxCheckTrace`. Engine: `scripts/tooling/replay/rep_crc_every_frame.py`,
`scripts/tooling/replay/rep_set_crc.py`.

**How it works.** The screen deletes a stale result file, writes the trace marker if needed, and
launches `GeneralsZHActivity` with extras. `getArguments` turns them into engine arguments. The
engine writes the result file into the user-data dir, which the screen reads on resume.

**Limits / gaps.**
* **[FACT][GAP]** Replays are read from `DataPackInstaller.userDataDir()/Replays`, i.e. always the
  Zero Hour leaf.
* **[FACT]** `clearCheckMarkers` is called from `SetupActivity.launchGameNow` so a check's trace
  markers do not leak into a normal game.

---

## 13. Support card

**What it does.** Show the project's support text, addresses and links, and an occasional popup,
with content and translations fetched online rather than shipped.

**Where.** `SupportLinks.java` (`FILE_NAME` `support.json`, `Entry`, `Block`, `Popup`, `load`),
`SetupActivity.buildSupportSection`, `renderSupportBlocks`, `maybeShowSupportPopup`,
`PREF_SUPPORT_POPUP_SEEN`, `copySupportAddress`, `openSupportLink`, `versionLabel`. Content:
`update/support.json` (24 KB, all languages); delivered by `UpdateManager.applySupport`.

**Limits / gaps. [FACT]** Entirely dependent on the update check succeeding; with no network the
card is absent (by design — it is withdrawn when the manifest has no entry).

---

## 14. Localisation and theming

* **Localisation**: 488 strings, 13 locales (`values`, `ar`, `b+isv`, `de`, `es`, `fa`, `fr`, `ko`,
  `pl`, `pt-rBR`, `ru`, `uk`, `zh`). **[FACT]** Every locale has exactly the same 488 names — no
  missing and no extra — verified by comparing the name sets.
* **Theming**: `ThemeHelper` (4 modes), `colors.xml` (one palette), `colors_palettes.xml`
  (navy + day), `themes.xml` (3 base themes + dialog overlays + the outlined-field overlay),
  `dimens.xml` (one spacing/shape/text scale), 28 drawables.

See `UI_ANALYSIS.md`.

---

## 15. In-game text entry

**What it does.** Let the engine's text fields be edited with a real Android `EditText` (cursor,
selection, copy, paste, on-screen keyboard) instead of the game's own primitive entry.

**Where.** `GeneralsZHActivity.showTextEditor`/`hideTextEditor` + the two natives
`nativeTextEditorChanged`/`nativeTextEditorDone`; `TextEditorBar.java` (`FLAG_SECRET`,
`FLAG_DIGITS_ONLY`, `Listener`, `show`, `hide`, `finish`, `applyCutoutMargins`);
`GeneralsZHActivity.dispatchKeyEvent` (Back closes the bar, keeping the text);
`Core/GameEngineDevice/Source/SDL3Device/GameClient/AndroidTextEditor.cpp` (251 LOC).

**How it works.** The engine calls into Java from its own thread; the bar reports back through the
natives, which only *queue* the change for the engine's next frame. This avoids re-entering the
engine from a UI callback.

---

## 16. LAN multicast

**What it does.** Keep a `WifiManager.MulticastLock` held while a LAN broadcast socket is open, so
Wi-Fi drivers do not filter LAN broadcasts.

**Where.** `GeneralsZHActivity.setLanMulticastLock(boolean)` (called over JNI from the engine's
`udp.cpp`), `CHANGE_WIFI_MULTICAST_STATE` permission.

**Limits. [FACT]** The lock object is created lazily and reference-counted; failures are logged and
swallowed (a LAN game then simply may not see peers).

---

## 17. Features that are *not* implemented

**[FACT]** Confirmed absent, despite appearing in plans or in the branch history:

| Feature | Status |
|---|---|
| Rooms (LAN over a relay, PLAN-023) | Code **removed** from the tree (commits reverted); relay lives in another repository |
| Online platform selector (PLAN-031) | Proposed only |
| Mod conflict report | `PLAN-027` says "not yet" |
| Per-mod-set profiles in the launcher | Partly: `profileName`/`#profile` exist and the engine uses them |
| Delete account server-side | `TODO` in `GeneralsOnlineActivity.java:894` |
| Audio readiness check | Rejected in `PLAN-029` (fixed names differ per edition) |
| In-game floating toolbox, accent-colour themes, launcher widget tests | **Present on the `claude/ui-redesign-dashboard-drawer` and `fix-ui-Draw-18-10` branches, absent on `New-UI`** — see `ISSUES_AND_RISKS.md` §5 |
| Any unit or instrumentation test | **Absent on `New-UI`** |
