# PROJECT_OVERVIEW.md — Generals-Android-Port

Engineering analysis of the repository `redaf12-Decode/Generals-Android-Port`, branch `New-UI`.

> Analysis phase only. No source, build or configuration change was made. Every statement below is
> either marked **[FACT]** (read directly from a file or the GitHub API) or **[INFERENCE]**
> (a conclusion drawn from several facts, with the reasoning given).

---

## 1. What the repository is

**[FACT]** A fork of the Android port of *Command & Conquer: Generals — Zero Hour* (2003). It is
not the origin of the Android port: the source tree carries a mirror note in the single commit of
`New-UI`:

```
9ba1ded Mirror snapshot of claude/network-diagnostics
        Source commit: 6cb177c23fff5860086b324699a3e90a95b67e87
```

**[FACT]** Repository metadata (GitHub API, 2026-11-04):

| Field | Value |
|---|---|
| `full_name` | `redaf12-Decode/Generals-Android-Port` |
| default branch | `main` |
| created | 2026-10-14 |
| last push | 2026-11-04T05:31Z |
| `size` | 752 537 KB (~735 MB, dominated by committed APKs) |
| `fork` | `false` (GitHub does not mark it as a fork of another repo) |
| `parent` | `null` |
| `license` | `null` — **GitHub detects no licence**, although `LICENSE.md` (GPL v3, from the EA source release) is in the tree and `README.md` shows a GPL-3.0 badge |
| `language` | `null` (GitHub's linguist did not classify the tree — the APKs dominate the bytes) |
| Releases | 0 |
| Open issues | 0 |

**[FACT]** Upstream of the content is a different repository. Two independent pieces of evidence:

* `.github/workflows/mirror.yml` on `main` fetches
  `SOURCE_REPO="https://github.com/MYSOREZ/GeneralsZH-Android-Port.git"`,
  `SOURCE_BRANCH="claude/network-diagnostics"` on a `17 */6 * * *` schedule and rewrites this
  repository's branch as a snapshot of it.
* The launcher's own update code points at that same repository:
  `UpdateManager.RAW_ROOT = "https://raw.githubusercontent.com/MYSOREZ/GeneralsZH-Android-Port/"`,
  and `SetupActivity.LANGUAGE_PACK_INDEX` / `LANGUAGE_PACK_BASE` likewise.

**[FACT]** Lineage, as the project documents it (`README.md`, `docs/port/ANDROID_PORT.md`):
EA's GPL v3 source release → TheSuperHackers/GeneralsGameCode → Fighter19's Unix port →
fbraz3/GeneralsX → the Android fork (MYSOREZ lineage) → this repository.

**[INFERENCE]** `New-UI` is therefore a *mirror branch*, not a development branch. The five
remaining branches (`New/app`, `backup16-10`, `backup18-10`, `claude/ui-redesign-dashboard-drawer`,
`fix-ui-Draw-18-10`) are work branches of the same fork, all dated 2026-10-16 … 2026-10-24, i.e.
*before* the current `New-UI` tip (2026-11-03). See `ISSUES_AND_RISKS.md` §5.

---

## 2. Branch and working-tree state at analysis time

**[FACT]**

```
$ git status
On branch New-UI
Your branch is up to date with 'origin/New-UI'.
nothing to commit, working tree clean

$ git rev-parse --is-shallow-repository
true

$ git log --oneline -1
9ba1ded (grafted, HEAD -> New-UI, origin/New-UI) Mirror snapshot of claude/network-diagnostics …
```

* The clone is **shallow** (`--depth 1`). History, tags, merge bases and blame are unavailable
  locally. All branch comparison in this report was done with `git fetch --depth 1 origin <branch>`
  plus the GitHub REST API, never by rewriting anything.
* No local modification, no staged change, no untracked file (the staged build directories
  `android/app/src/main/jniLibs/` and `java-sdl/` do not exist — they are produced by the packaging
  script, see `BUILD_REQUIREMENTS.md` §4).
* No branch switch, reset, merge or rebase was performed during this analysis.

**[FACT]** Remote branches (GitHub API) and their tips:

| Branch | Tip date | Message |
|---|---|---|
| `New-UI` | 2026-11-03T23:27Z | Mirror snapshot of claude/network-diagnostics |
| `main` | 2026-10-22T07:41Z | Add files via upload |
| `New/app` | 2026-10-22T07:46Z | Add files via upload |
| `backup16-10` | 2026-10-16T03:12Z | feat(android): lead the launcher dashboard with launch and Online |
| `backup18-10` | 2026-10-18T02:13Z | Add toolbox layout management and utilities |
| `claude/ui-redesign-dashboard-drawer` | 2026-10-18T06:33Z | Merge pull request #2 from redaf12-Decode/feature/in-game-floating-toolbox |
| `fix-ui-Draw-18-10` | 2026-10-24T12:07Z | fix Workflow 2 files |

**[FACT]** `main` and `New-UI` share **no** common tree content in the parts that matter: the diff
`main..New-UI` is 6 457 files / 2 928 231 insertions / 5 603 deletions, and it *adds* the whole
`android/` application (94 files), `docs/releases/`, `update/`, `vcpkg.json`, `CMakePresets.json`
while *removing* the fork's own workflows (`mirror.yml`, `commit-transfer.yml`,
`download-compress-7z.yml`, `Cash-Cleaner.yml`, the `*-split`/`*-test` build variants and
`GeneralsZH-Android-five-stage-build.yml`).

**[INFERENCE]** `main` is not a useful base for development of the launcher: it holds a different,
older shape of the project (its own five-stage CI, its own workflow set). The reference for future
work is `New-UI` itself, or the upstream `MYSOREZ/GeneralsZH-Android-Port`.

---

## 3. Size and shape of the tree

**[FACT]** Top-level directories by size (`du -sh`):

| Directory | Size | Role |
|---|---|---|
| `apk/` | 680 MB | Committed APKs (1 current + 7 tester builds) |
| `GeneralsOnlineRelease/` | 341 MB | Release bundle payload |
| `Core/` | 35 MB | Shared engine + platform layer (1 831 `.cpp`/`.h`) |
| `GeneralsMD/` | 24 MB | Zero Hour game code (1 285 `.cpp`/`.h`) |
| `Generals/` | 20 MB | Base game code (1 131 `.cpp`/`.h`) |
| `languages/` | 15 MB | 12 language packs, `generals.str` + `generalszh.str` |
| `tools/` | 13 MB | Translation workbench, replay tools |
| `docs/` | 9.1 MB | Port guides, work notes, lessons, release notes |
| `assets/` | 5.0 MB | Repository icons/splash (not APK assets) |
| `android/` | 2.4 MB | Gradle shell application (25 Java files, 14 853 LOC) |
| `scripts/` | 1.1 MB | Build, packaging, QA, update tooling |

**[FACT]** Languages present: Java 25 files (no Kotlin), C/C++ in `Core`/`Generals`/`GeneralsMD`,
XML (48 resource files), CMake, Bash, Python (tooling). **No test sources exist on `New-UI`**
(`git ls-tree -r HEAD android | grep -c test/` → `0`).

---

## 4. What the application does, end to end

**[FACT]** One Android application, `com.generalsx.zerohour`, containing:

1. A **launcher** (`SetupActivity`, 4 529 LOC) — five bottom-navigation sections: Home, Graphics,
   Interface, Tools, Help.
2. A **game surface** (`GeneralsZHActivity extends org.libsdl.app.SDLActivity`) that loads the
   engine as a native library and hands it the SDL3 activity.
3. **Six more activities** — `FolderPickerActivity`, `LogViewerActivity`, `ModManagerActivity`,
   `ReplayCheckActivity`, `NetworkDiagnosticsActivity`, `GeneralsOnlineActivity`.
4. **20 support classes** — theming, localisation, mods, updates, data packs, network diagnostics,
   session handling, in-game text editing.

**[FACT]** The game itself is *not* bundled. The user supplies their own `.big` archives; the
launcher writes the chosen folder to a marker file that native code reads at start.

**[FACT]** Two games ship in one APK: Zero Hour (`libmain.so` / `libmain60.so`) and the base game
(`libgenerals.so` / `libgenerals60.so`), and two simulation tick rates (30 Hz / 60 Hz) — four engine
libraries in total, selected at load time by `GeneralsZHActivity.getLibraries()`.

**[FACT]** Two render paths ship: D3D8 → **DXVK** → Vulkan (default off since 1.2.1) and
D3D8 → **OpenGL ES 3.0** (default, `Core/Libraries/Source/d3d8gles/`), the latter optionally routed
through a bundled prebuilt ANGLE.

---

## 5. Answers to the eight questions posed

### 5.1 How does the project work from app start to the game running?

**[FACT]** Verified chain, with the file that implements each step:

| Step | Where |
|---|---|
| Home-screen icon → `SetupActivity` (only `LAUNCHER` activity) | `AndroidManifest.xml` |
| Launcher applies theme/locale/insets | `SetupActivity.onCreate` → `ThemeHelper.apply`, `LocaleHelper.wrap` (in `attachBaseContext`), `InsetUtil.applySafeInsets` |
| Launcher builds five pages in code | `SetupActivity.buildUi` / `showTab` → `UiKit` |
| User picks the game folder | `onSelectGameFolder` → `FolderPickerActivity` → `saveGamePath` |
| User taps **Launch Game** | `onLaunchGame` → `launchGameNow` |
| Orientation forced landscape and *confirmed* before start | `launchGameNow` + `onConfigurationChanged` |
| `GeneralsZHActivity.onCreate`: extract bundled runtime, verify folder, refresh online session, write native markers | `extractBundledRuntime`, `GeneralsOnlineSession.refreshSessionAsync`, `SetupActivity.writeNativeGameMarker` |
| SDL3 loads `libSDL3.so` then the engine | `GeneralsZHActivity.loadLibraries` / `getLibraries` |
| Engine argument list assembled | `GeneralsZHActivity.getArguments` (`-gxSafeInsets`, `-nologo`, `-replay`, …) |
| Native start: env bootstrap (HOME, cwd, DXVK cache, stderr mirror), game-folder chdir, base-game path, art path, diagnostic markers | `Core/Main/SDL3Main.cpp` (`__ANDROID__` branch, ~lines 658–940) |
| Render backend chosen | `d3d8gles_ShouldUseVulkanBackend()` / `d3d8gles_ShouldUseANGLE()` reading `render_backend.cfg` |
| SDL_main → engine main loop | `Core/Main/SDL3Main.cpp` |

### 5.2 What components and systems does it depend on?

**[FACT]** Android side: Android SDK 35, NDK 27.2.12479018, Gradle 8.9 / AGP 8.5.2, JDK 17,
Material Components 1.12.0, AndroidX core 1.13.1. Native side: vcpkg (pinned commit), SDL3 3.4.2,
SDL3_image 3.4.0, openal-soft 1.24.2, FreeType, curl+OpenSSL, GameNetworkingSockets 1.6.0, FFmpeg
8.1.1#2, zlib, glm, gli; plus DXVK (local submodule fork), libadrenotools, a bundled ANGLE pair and
a bundled Mesa Turnip driver. Full detail in `BUILD_REQUIREMENTS.md`.

### 5.3 How does the build actually work, source → APK?

**[FACT]** Two independent halves:

* **Native**: CMake preset `android-vulkan` (Ninja, RelWithDebInfo, `arm64-v8a`, `android-28`,
  `c++_shared`) via `scripts/build/android/build-local-sandboxed.sh`; DXVK built with meson through
  `cmake/dx8.cmake`; the engine built twice for the two tick rates by
  `scripts/build/android/build-dual-hz.sh`.
* **Java/packaging**: `scripts/build/android/package-android-zh.sh` stages the `.so` set into
  `android/app/src/main/jniLibs/arm64-v8a/`, copies SDL3's Java glue into `java-sdl/`, patches it,
  stages fonts/dxvk.conf/DefaultOptions.ini/Turnip, writes `engine_build.txt` and
  `update_channel.txt`, then runs `gradle assembleDebug`.

Details, including what must be fetched externally, in `BUILD_REQUIREMENTS.md`.

### 5.4 Which files must be edited to develop each feature?

Mapped per feature in `FEATURES_ANALYSIS.md` and `ROADMAP.md`. The short version:

| Feature area | Files |
|---|---|
| Launcher look / new screen | `UiKit.java`, `themes.xml`, `colors.xml`, `colors_palettes.xml`, `dimens.xml`, `strings.xml` (+13 locales) |
| Launcher setting | `SetupActivity.java` (`build*Section` + a writer), a marker file or `Options.ini` key, the native reader |
| Native behaviour of that setting | a `Core/GameEngine/Include/Common/GX*.h` header |
| Game launch arguments | `GeneralsZHActivity.getArguments` + the engine's `SDL3Main.cpp` |
| Engine/APK packaging | `package-android-zh.sh`, `android/app/build.gradle` |
| Mods | `ModManager.java`, `ModManagerActivity.java`, `ModImportService.java`, `GXManagedMods.h` |
| Updates | `UpdateManager.java`, `update/*.json`, `scripts/update/*` |
| Multiplayer | `GeneralsOnlineActivity.java`, `GeneralsOnlineSession.java`, `DataPackInstaller.java` |

### 5.5 Which coupled systems can be affected by a change?

**[INFERENCE]** from the read code, the coupling points that have already produced bugs in this
tree (all documented in comments in the tree itself):

1. **Marker-file contract**: Java writer ↔ native reader, with no shared schema. Changing a marker
   name or format silently disables the feature. Existing pairs: `gamedata_path.txt`,
   `generals_base_path.txt`, `zerohour_art_path.txt`, `mods_active.txt`, `mods_boot_pending`,
   `logging_off`, `mouse_mode`, `control_scheme`, `subtitles`, `render_backend.cfg`,
   `gx_*` diagnostic markers, `engine_active.txt`, `boot_pending`, `engine_bad.txt`,
   `remote_config.ini`.
2. **Theme ↔ colours**: every screen reads colours through `UiKit.color()`, which routes through
   `ThemeHelper.mapColor()`. A new colour used with `ContextCompat.getColor()` directly bypasses
   the palette switch.
3. **Localisation ↔ layout**: 488 strings × 13 locales; every locale currently has exactly the same
   488 names (verified). A new string must be added to all of them.
4. **Engine/APK version coupling**: a downloaded engine runs only if `requires_libs` SHA-256 values
   match the installed APK's libraries (`UpdateManager.librariesMatch`).
5. **Tick-rate coupling**: 30 Hz and 60 Hz are separate binaries; `GameLogic`'s field layout depends
   on the constant.
6. **Storage model**: game data on shared external storage, user data under
   `/storage/emulated/<uid/100000>/Generals/…`, app-private data under `/data/user/<uid>/…`, with
   paths derived from `getuid()` in three separate places (Java, `SDL3Main.cpp`, `GXLogging.h`,
   `GXManagedMods.h`, `AndroidCrashHandler.cpp`).

### 5.6 What are the current technical problems?

`ISSUES_AND_RISKS.md` lists them with evidence. The headline items: no test sources at all on this
branch; `SetupActivity` is a 4 529-line god-class; no Gradle wrapper; CI builds only Zero Hour
although releases ship both games; CI on this fork has never succeeded; the launcher's update and
language-pack endpoints still point at the upstream repository; 680 MB of APKs in git.

### 5.7 What improvements are possible and what do they need?

`ROADMAP.md` — ordered, each with problem, change, affected files, dependencies, risk, priority and
prerequisites.

### 5.8 How can future work proceed without breaking what works?

`ROADMAP.md` §1 ("Ground rules") and `ISSUES_AND_RISKS.md` §6.

---

## 6. Summary of the analysis deliverables

| File | Contents |
|---|---|
| `PROJECT_OVERVIEW.md` | this file |
| `ARCHITECTURE.md` | component map, data flow, entry points, lifecycles, coupling |
| `BUILD_REQUIREMENTS.md` | toolchain, dependencies, in-repo vs external vs generated, step by step |
| `FEATURES_ANALYSIS.md` | every feature: implementation, files, mechanism, dependencies, limits |
| `UI_ANALYSIS.md` | the New-UI launcher: screens, navigation, tokens, palettes, gaps |
| `ENGINE_ANALYSIS.md` | engine integration, arguments, storage, backends, safe extension points |
| `ISSUES_AND_RISKS.md` | defects, duplication, dead code, risks, with evidence and confidence |
| `ROADMAP.md` | prioritised improvement plan |
