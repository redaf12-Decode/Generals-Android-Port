# ENGINE_ANALYSIS.md — the game engine and its Android integration

How Generals / Zero Hour is built for Android, how the launcher starts it, what crosses the
Java↔native boundary, and which integration points are safe to develop.

**[FACT]** unless marked otherwise.

---

## 1. Source and version of the engine

| Question | Answer | Evidence |
|---|---|---|
| Which engine? | EA's *Command & Conquer: Generals — Zero Hour* source release (GPL v3), carried through TheSuperHackers → Fighter19 → fbraz3 → this fork | `README.md` "Lineage & credits"; GPL headers in every file |
| Version in the tree | `versionName 1.4.2`, `versionCode 10402`, engine build `3500` | `android/app/build.gradle`; `assets/engine_build.txt` in the committed APK |
| Engine LOC | `Core` 1 831 + `Generals` 1 131 + `GeneralsMD` 1 285 `.cpp`/`.h` files | file count |
| Two games | `GeneralsMD/` → `z_generals` → `libmain.so`; `Generals/` → `g_generals` → `libgenerals.so` | `*/Code/Main/CMakeLists.txt` |
| Two tick rates | same targets built with `SAGE_HIGH_FPS_SIM=ON` and `OFF` → `libmain60.so`, `libgenerals60.so` | `build-dual-hz.sh`, `GeneralsZHActivity.getLibraries` |

**[FACT]** The Android target is a **shared library, not an executable**, and its `OUTPUT_NAME` is
`main`:

```cmake
if(ANDROID)
    add_library(z_generals SHARED)
    set_target_properties(z_generals PROPERTIES OUTPUT_NAME main)
```

The comment: *"SDLActivity.getLibraries() loads `main` → `libmain.so` (SDL convention)."*

---

## 2. Native libraries in the APK and what loads what

**[FACT]** Load order, from `GeneralsZHActivity.getLibraries()` + `loadLibraries()`:

```
1. libSDL3.so            (System.loadLibrary "SDL3")
2. libmain.so / libmain60.so / libgenerals.so / libgenerals60.so   (chosen, or a downloaded
                                                                    replacement by absolute path)
   └─ the engine's DT_NEEDED: libSDL3_image.so, libopenal.so, libc++_shared.so, libgamespy.so
      all resolve from the same APK
   └─ dlopen()ed by name at D3D init:  libdxvk_d3d8.so, libdxvk_d3d9.so   (Vulkan path)
      statically linked:               d3d8gles (+ libGLESv2_angle.so via its own dispatch table)
   └─ dlopen()ed by name:              libadrenotools.so (custom Vulkan driver), and the four
                                       hook libraries
```

**[FACT]** `loadLibraries()` is overridden so a signed **engine update** can replace the engine:
if `UpdateManager.activeEngineSeq(this) > 0` and `noteEngineBoot` succeeds, the absolute path of the
downloaded `libmain*.so` is `System.load`ed instead, and `getMainSharedObject()` returns that path so
SDL calls `SDL_main` in it. Only the Zero Hour engines can be replaced this way
(`UpdateManager.ENGINE_LIBS`).

**[FACT]** `packagingOptions.jniLibs.useLegacyPackaging = true` — required so the native libraries
exist as real files in `nativeLibraryDir`, because libadrenotools `dlopen`s by absolute path.

---

## 3. How arguments reach the engine

**[FACT]** `GeneralsZHActivity.getArguments()` is SDL's hook; SDL passes the array to `SDL_main` as
`argc`/`argv`. Two of them are always added (when applicable) and five only for a replay check:

| Argument | When | Native effect |
|---|---|---|
| `-gxSafeInsets <left,top,right,bottom>` | when the window has a cutout or rounded corners | `Common/GXSafeArea.h` — keeps the corner HUD out of the cutout |
| `-nologo` | when "skip intro movies" is on | the engine's own switch; skips EA logo, intro, sizzle |
| `-replay <name>` | replay check | the engine's own replay argument |
| `-gxCheckTrace` | the check turned the network trace on | the engine scopes the trace to this replay only |
| `-gxFastTo <frame>` | "watch from frame" | fast-forward |
| `-gxAutoQuit` | "run through" | quit at the end |
| `-gxCrcEveryFrame` | the CRC switch | per-frame CRC comparison |

**[FACT]** `getArguments()` is **also a side-effect point**: it calls
`ModManager.writeActiveList(this)`, `SetupActivity.writeSubtitlesMarker(this)` and
`writeZeroHourArtPath()` before returning. That is deliberate — both engines, a replay and every
launch path read the same list — but it means the argument hook is the last chance to write markers
before the engine reads them.

**[FACT]** Everything else is passed as an **environment variable** set in `SDL3Main.cpp`'s Android
bootstrap, not as an argument:

| Variable | Source |
|---|---|
| `HOME` | `SDL_GetAndroidInternalStoragePath()` |
| `GENERALSX_USERDATA_DIR` | `/storage/emulated/<uid/100000>/Generals/<game leaf>` |
| `GENERALSX_BUNDLED_FONTS_DIR` | `<external>/fonts` |
| `DXVK_STATE_CACHE_PATH` | the app cache dir |
| `CNC_GENERALS_PATH` | `generals_base_path.txt` (Zero Hour only) |
| `GENERALSX_ZH_ART_PATH` | `zerohour_art_path.txt` (Generals only) |
| `DXVK_CONFIG`, `DXVK_LOG_LEVEL` | derived from the game folder / the diagnostic markers |

---

## 4. Startup sequence inside the engine

**[FACT]** `Core/Main/SDL3Main.cpp`, `__ANDROID__` blocks at lines ~57, 173, 296, 658, 1108, 1126,
1182, 1244, 1282:

1. **Crash handler first** — `AndroidCrashHandler.cpp` installs its handlers from an ELF
   constructor, before `SDL_main`, writing `crash.log` with raw syscalls.
2. **Working directory** — `chdir` to, in order: the folder in `gamedata_path.txt`, then
   `<external>/GameData`, then `<external>`. This is what makes the engine's relative asset paths
   work.
3. **User-data path** — derived from the uid, matching `BuildUserDataPathFromRegistry`'s Android
   branch in the engine, so saves/replays/`Options.ini` land in shared storage.
4. **stderr mirror** — `<external>/generals-stderr.log`, unless `GXLoggingDisabled()`. This is what
   `LogViewerActivity` shows.
5. **Diagnostic markers** — `gx_trace.txt`, `gx_perf.txt`, `gx_gles_gputimer.txt`,
   `gx_audio_trace.txt`, `gx_net_trace.txt`, `gx_touch_debug.txt`, `dxvk_hud.txt`,
   `dxvk_validation.txt`, `dxvk_verbose_log.txt` are all read from the game folder.
6. **Bundled runtime seeding** — fonts, `dxvk.conf`, `DefaultOptions.ini` are copied into the game
   folder if missing (mirrors `SetupActivity.copyBundledRuntimeIfMissing`).
7. **Render backend** — `d3d8gles_ShouldUseVulkanBackend()` / `d3d8gles_ShouldUseANGLE()` read
   `render_backend.cfg`; the GLES dispatch table is resolved
   (`d3d8gles_LoadGLESDispatch`), the custom Vulkan driver is attempted
   (`TryLoadCustomVulkanDriver`).
8. **`SDL_main`** → the engine's own boot.

**[FACT]** Two settings are read by the engine from `Options.ini` at start: `GXUiScale` (percent,
applied to the retail layouts by `GXUiScale.cpp`) and `GXUpscale` (one of five mode words, applied
by `DX8Wrapper::Pillarbox_Setup` on GLES).

---

## 5. The Java ↔ native boundary, exhaustively

**[FACT]** Every crossing, and its direction:

| Direction | Mechanism | Name | Purpose |
|---|---|---|---|
| Java → native | `System.loadLibrary` / `System.load` | `SDL3`, `<engine>` | load |
| Java → native | SDL's `SDLActivity` JNI | `SDL_main`, `nativeRunMain` | start |
| Java → native | SDL's `SDLActivity` JNI | `setOrientationBis` | the engine calls `SDLActivity.setOrientation()`; overridden to pin landscape |
| Java → native | `getArguments()` | — | argv |
| native → Java | JNI, `GeneralsZHActivity.setLanMulticastLock(boolean)` | engine `udp.cpp` | hold `WifiManager.MulticastLock` while a LAN socket is open |
| native → Java | JNI, `GeneralsZHActivity.showTextEditor(...)` / `hideTextEditor()` | `AndroidTextEditor.cpp` | open the in-game text bar |
| Java → native | `nativeTextEditorChanged(String)` / `nativeTextEditorDone(String)` | `AndroidTextEditor.cpp` | queue a text change for the engine's next frame |
| native → Java | SDL's own glue | clipboard, permissions, dialogs | SDL3 internals |
| both | files (markers, `Options.ini`, `remote_config.ini`) | see `ARCHITECTURE.md` §3.2 | everything else |

**[FACT]** There is no binder service, no AIDL, no shared memory, no socket between the launcher and
the engine. Files are the interface.

---

## 6. Game folders and resource loading

**[FACT]**

* The **game folder** (chosen by the user) holds the `.big` archives. The engine mounts every
  `.big` in it, sorted by name, plus (from this port) the managed mods above them.
* `GXManagedMods::folders()` (highest priority first) are mounted by
  `ArchiveFileSystem::loadMods()` above the game's archives and above legacy `!` archives.
* For **Zero Hour**, `CNC_GENERALS_PATH` points at the base game's folder so the expansion can read
  the base game's archives below its own (`loadBaseGeneralsAssetsForZH`, `StdBIGFileSystem.cpp`).
* For the **base game**, `GENERALSX_ZH_ART_PATH` points at Zero Hour's folder so the few textures
  the base game lacks (e.g. the force-attack crosshair) can be borrowed from `TexturesZH.big`
  mounted **below** every Generals archive (`PLAN-030`).
* The launcher's integrity check reads the BIG headers itself (little-endian count/offset table) to
  answer "does this folder really contain the game".

---

## 7. Graphics, audio and input configuration

| Area | Mechanism |
|---|---|
| Graphics backend | `render_backend.cfg`: `vulkan` / `gles` / `gles_angle`; DXVK for Vulkan, the in-tree translator for GLES, optionally through the bundled ANGLE |
| Upscaling | `GXUpscale` in `Options.ini`, GLES only |
| Interface scale | `GXUiScale` in `Options.ini` |
| Menu text size | `ResolutionFontAdjustment` in `Options.ini` |
| dxvk.conf | edited in the game folder by the launcher; `DXVK_CONFIG` points at it |
| Audio | OpenAL (vcpkg `openal-soft` 1.24.2) replacing Miles; `gx_audio_trace.txt` for tracing |
| Input | SDL3 events → the gesture state machine → `TouchInput.cpp`; `mouse_mode` marker switches to the pointer path; `control_scheme` marker changes what an order does |
| Video | FFmpeg 8.1.1 (Bink replacement); `-nologo` skips the movies |
| Frame pacing | the engine asks Android for the rate it needs (release note #69) |

---

## 8. CPU architectures and devices

**[FACT]** `arm64-v8a` only. `abiFilters 'arm64-v8a'`; the NDK triplet pins `arm64`; the only
prebuilt binaries (`angle-prebuilt`, `vulkan.ad07xx.so`) are arm64. `minSdk 28` = Android 9,
deliberately not 24, with a documented reason (the Redmi Note 8 Pro's stock ROM is Android 11;
LineageOS headroom).

**[FACT]** `armeabi-v7a`, `x86`, `x86_64` are **not** supported. A 64-bit-only APK on a 32-bit-only
device simply will not install.

**[FACT]** Device-specific handling: Adreno 7xx gets a bundled Mesa Turnip driver when it reports
less than Vulkan 1.3; ANGLE exists for GPUs whose Vulkan cannot carry DXVK; GLES is the default
because it has worked on every device tested.

---

## 9. Current limitations in running the game

| Limitation | Evidence |
|---|---|
| Cross-play with the PC client is **off** | `update/config.json` `pc_crossplay: "0"`; `features.json`; turned off 18/10/2026 at GeneralsOnline's request |
| No Android↔PC lockstep | Same. `LESSON-cross-play-desync-method.md` records what was measured: aarch64 and x86_64 agree bit-for-bit; the difference is libm behind identical source (VC6 CRT promotes `sinf` to double and uses x87) |
| Vulkan is experimental and hidden | `RENDER_BACKEND_CHOICES_NO_VULKAN`, the "Vulkan (experimental)" switch on Tools |
| LAN multicast needs a `WifiManager` lock, and some routers still filter | `setLanMulticastLock` |
| Rooms (LAN over a relay) removed | `PLAN-023` "paused, code reverted" |
| Two engines per tick rate per game | 4 × ~30–40 MB inside the APK |
| APK ~93 MB | `apk/GeneralsXZH-android-generals-a48.apk` = 92 828 479 bytes |
| Mods in a mod set share one profile; a conflict report is missing | `PLAN-027` |

---

## 10. Integration points that are safe to develop

**[INFERENCE]** from the shape of the existing code — these follow patterns the tree already uses,
so they add behaviour without touching engine internals:

| Safe | Why |
|---|---|
| **A new launcher setting written as a marker file** read by a new `GX*.h` header | exactly what `GXMouseMode.h`, `GXControlScheme.h`, `GXSubtitles.h` do; header-only, self-guarded, `false` off Android |
| **A new launch argument** added in `getArguments()` and parsed in `SDL3Main.cpp` | `-gxFastTo`, `-gxAutoCrc`, `-gxSafeInsets` all did this |
| **A new diagnostic marker** added to `DIAGNOSTIC_MARKERS`/`TITLES`/`DESCRIPTIONS` | the arrays exist precisely for this |
| **A new engine value published in the signed settings** | `GXRemoteConfig::enabled(name, default)` / `UpdateManager.featureEnabled` |
| **A new launcher screen** | `UiKit` + the seven-screen shell pattern |
| **A new mod capability** in the launcher | `ModManager`/`ModImportService`/`ModAnalysis` are self-contained |
| **The UI scale / upscale plumbing** | already parameterised through `Options.ini` |

**Not safe without engine-side work. [INFERENCE]**
* Anything touching `GameLogic`'s class layout (the tick-rate constant).
* Anything in the archive mount order without reading `ArchiveFileSystem::loadMods` first.
* Anything in the touch path that synthesises mouse messages — the project has a written rule
  against it (`LESSON-touch-input-is-not-a-mouse.md`) and a documented history of four bugs caused
  by it.
* Anything that changes what the CRC covers while cross-play could be turned on again
  (`GXCrcStream.h`, `logic_crc_revision`).

---

## 11. Unverified

**[FACT]** Not verified from this clone: the internal GeneralsOnline client's protocol details
(`GeneralsMD/Code/GameEngine/Source/GameNetwork/GeneralsOnline/` was read only for its file list and
its logging hook); the exact set of FFmpeg codecs the engine uses; whether every one of the four
engines is bit-identical apart from the tick-rate constant (the release notes' `engine_build.txt`
and the dual-pass build script imply yes, but the binaries were not compared).
