# Source Compatibility Audit — Integrating the Actual Reforged Engine

**Status:** Phase 1 deliverable (inspection only — no engine behavior changed)
**Target:** `redaf12-Decode/Generals-Android-Port`, branch `Afork`
**Engine:** `olcayseygan/CnCGeneralsZH-Reforged`, branch `main`
**Integration reference:** `louay0khaled/zero-hour-reforged-android-mod`, branch `main`
**Date:** 2026-11-21

---

## 0. Commits inspected

| Repo | Ref | Commit |
|------|-----|--------|
| Target fork | `Afork` | tip at audit time (advances as phases commit) |
| Reforged engine | `main` | **`b79aa63b7a35e51e59e135d64fc08df00113fcfd`** ("feat(gameengine): put the skirmish AI's rating on an Elo scale") |
| Louay reference | `main` | as cloned for this audit |

Access: olcay is public and reachable via `git` (API `repos` endpoint returned 502 for it during
this session — a size/proxy quirk — but `git clone`/`ls-remote` work). louay and the target are
reachable. Nothing was pushed to any upstream.

---

## 1. Executive summary

The olcay **Reforged** engine is a *desktop* (Windows/Linux/macOS) engine. It is architecturally
**different** from the target's MYSOREZ-based GeneralsX engine, even though both descend from the
same EA source:

| Concern | Target (GeneralsX) | Olcay Reforged |
|---------|--------------------|----------------|
| Windowing/input | SDL3 3.4.2 | SDL3 3.4.16 (vendored) |
| Graphics | **DXVK** d3d8/d3d9 → Vulkan (+ GLES fallback `d3d8gles`) | **native SDL3-GPU** `posixd3d9` → Vulkan/Metal (no DXVK) |
| Audio | **OpenAL Soft** | **miniaudio** (Miles6 `miles_miniaudio.cpp`) |
| FFmpeg | vcpkg 8.1.1 | committed prebuilt (`Libraries/Source/FFmpeg/dist`) |
| GameSpy | FetchContent (TheAssemblyArmada) | vendored (TheSuperHackers `b1b77d8`) + `Tools/vendor.sh` patches |
| FreeType / shaders | vcpkg / glslang | vendored via `vendor.sh` (FreeType, glslang, SPIRV-Cross) |
| Dependencies | vcpkg manifest | `Tools/vendor.sh` (pinned archives), no submodules |

Consequences:

1. The Reforged engine is **not** a drop-in for the target's engine. It brings its **own**
   SDL3, renderer, audio, and dependency set. Integrating it means running a **second, self-contained
   native stack** alongside the launcher — which is exactly the *separation* model louay uses.
2. Louay's **C1 Android compatibility patch applies cleanly to current olcay** (`git apply --check`
   passes, with harmless offsets) — so the low-level bionic/CMake adaptation is still valid.
3. Louay's **C4 game-library patch (14 861 lines / 79 files) does NOT apply to current olcay** —
   the engine has drifted across ~20 files (ControlBar, InGameUI, udp, PosixDevice9*, SdlGpuFrame*,
   W3DView, glyphrasteriser, stage-overlay.sh, …). It is pinned to olcay `61dbc59`. Re-deriving it
   for `b79aa63` is a build-and-test-driven effort, not a rebase.
4. Louay's **current product (v1.16) does not even ship the olcay engine as the play engine** — it
   ships a stock-MYSOREZ 60-steps engine (`libmainz60.so`) plus the olcay engine only as a *test*
   payload (`libmainrfx.so`). So the "reference" for shipping olcay as the play engine is louay's
   **v1.9–v1.15** lineage, not its current tip.
5. There is an **SDL version skew**: the target's launcher stages SDL **Java 3.4.2**, while olcay
   links SDL **native 3.4.16**. Shipping olcay under the target's 3.4.2 Java is the exact
   silent-native-crash hazard louay documents (`ci/stage-rfx-glue.py`). It must be resolved
   (re-stage 3.4.16 Java, or build olcay against 3.4.2) — not ignored.

**Bottom line:** the architecture (separate olcay checkout → Android adaptation layer → ARM64 shared
library → host launcher loads it) is sound and matches louay's proven model. But it is a large,
build-and-test-driven integration, and — critically — **it cannot be built or verified in this
environment** (see §8). The build phases are therefore blocked here, and this audit + the pinned
source are delivered as the verifiable groundwork.

---

## 2. Olcay engine audit (features to preserve)

Entry / platform:
- `GeneralsMD/Code/Main/PosixMain.cpp` — the SDL3 POSIX entry (`SDL_main`), mirrors `WinMain.cpp`.
  Installs the crash handler, sets the root from the working dir (or `-root`), supports `-overlay`.
- `GeneralsMD/Code/CMakeLists.txt` — platform split `ZH_PLATFORM_WINDOWS` / `ZH_PLATFORM_POSIX`
  (`_MACOS` / `_LINUX`). **No Android branch.** Android (`CMAKE_SYSTEM_NAME=Android`) currently
  falls into the generic POSIX bucket without Linux/macOS refinements.
- Linux links with LLD/mold (archive cycle resolution); Android's LLD (`-fuse-ld=lld`) is compatible.

Renderer (must preserve):
- `GameEngineDevice/Source/PosixDevice/Render/PosixDevice9*` — the D3D9-style device drawn through
  **SDL3's GPU API** (Vulkan on Linux/Android, Metal on macOS). Not DXVK.
- `SdlGpuFrame*` — the SDL-GPU frame graph (batch, shadow, smoke, sun-shadow).
- `Libraries/Source/WWVegas/WW3D2/` — W3D, `dx11runtime_posix.cpp`, `glyphrasteriser`.
- Shader toolchain: `glslang` (HLSL→SPIR-V) + `SPIRV-Cross` (SPIR-V→MSL), `SDL_shadercross`,
  `sdl3shadercompile` — vendored, built from source.
- Reforged graphics features (from `docs/linux.md`, `reforged-look.ini`, CHANGELOG): modern water,
  lighting style, bloom/FXAA/etc. — these live in the render/data path and must be preserved.

Audio:
- **miniaudio** via Miles6 (`MilesAudioManager.cpp`, `miles_miniaudio.cpp`). Not OpenAL.

Video:
- **FFmpeg** (committed prebuilt) for Bink replacement / intro videos.

Networking:
- **GameSpy** vendored (TheSuperHackers `b1b77d8`) + two patches (`gamespy-strlwr-lwr-c-only`,
  `gamespy-gsi-unix`) applied by `vendor.sh`. Online uses `mbedtls`, `libcurl` (online/).

Filesystem / data:
- Read-only root + overlay search (`chooseOverlays`, `-overlay`), `Data/INI/INIZH.big` deletion at
  init, `Win32BIGFileSystem.cpp` (BIG archive reader — the Android port needs this compiled for
  Android, since it is the .big reader). Save/config/cache/log paths are relative to root.

Tooling:
- `Tools/vendor.sh` fetches+verifies all third-party sources at pinned commits.
- `.github/workflows/` in olcay contains only `commit-messages.yml` — **olcay has no build CI**;
  the Android build CI is entirely louay's contribution.

## 3. Louay integration audit (what targets which tree)

| Louay artifact | Targets | Keep for this mission? |
|----------------|---------|------------------------|
| `ci/apply-surgery.py` | MYSOREZ app tree (rebrand, paths, page) | **No** — replaces app identity; forbidden |
| `ci/apply-phase2.py`, `ci/assets/*.big` | MYSOREZ app tree (Reforged data) | **No** — ships game data |
| `ci/apply-phase3.py` (RfxGameActivity) | MYSOREZ app tree | Partly — launcher wiring pattern, not wholesale |
| `ci/stage-rfx-glue.py` + `rfx-jni-prefix.patch` | both (2nd SDL Java layer) | **Only if** the SDL-skew needs isolation; a single matching SDL is preferred |
| `probe/patches/reforged-c1-android.patch` | **olcay engine tree** (bionic/CMake) | **Yes — applies to current olcay** (verified) |
| `probe/patches/reforged-c4-game-library.patch` | **olcay engine tree** (game lib/touch/UI/render) | **Conceptually yes, but does NOT apply to current olcay** — must be re-derived |
| `probe/patches/gamespy-android-pthread.patch` | vendored GameSpy tree | Yes (bionic pthread) |
| `ci/patches/*` (60-steps, smooth-motion, perf, pacing) | MYSOREZ/olcay 30-steps engine | Only if building the 60-steps play engine; not required to ship olcay |
| `.github/workflows/build-apk.yml` | build tooling (separation model) | **Yes — the model to adapt** |

Categorisation (as the task requires):
- **MYSOREZ app tree only:** surgery, phase2 data, phase3 activity shell.
- **Reforged engine tree only:** C1, C4, gamespy-pthread, frame-selfcheck patches.
- **Both:** rfx glue (SDL Java isolation).
- **Build tooling only:** build-apk.yml, package knobs.

## 4. Target fork audit (integration surface)

- Gradle shell `android/` (AGP 8.5.2, `arm64-v8a`, minSdk 28). Launcher ~46 Activities;
  `GeneralsZHActivity extends org.libsdl.app.SDLActivity`; `getLibraries()` returns
  `{"SDL3", <engine>}` where engine ∈ `main|main60|generals|generals60`.
- SDL Java staged from **SDL3 3.4.2** (`cmake/sdl3.cmake`), patched by `android/patches/*`.
- Native: `Core/Main/SDL3Main.cpp` (`SDL_main`), `AndroidCrashHandler.cpp`.
- Graphics: DXVK d3d8/d3d9 (fbraz3 submodule) + GLES fallback (`d3d8gles` + committed ANGLE).
- Audio: OpenAL; Video: FFmpeg (vcpkg); Net: GameSpy (FetchContent).
- CMake preset `android-vulkan` (arm64-v8a, `ANDROID_PLATFORM=android-28`, `ANDROID_STL=c++_shared`).
- Packaging `package-android-zh.sh` (stages `.so`, small assets, `gradle assembleDebug`).
- CI: `build-android.yml` (dispatch) — but **private-repo Actions budget is exhausted** (see §8).

## 5. Required source changes (to build olcay on Android)

1. **CMake:** add an explicit `ZH_PLATFORM_ANDROID` branch (C1 does this); ensure Android never
   enters the Linux-desktop branch; gate X11/Wayland/desktop-EGL; keep LLD link; PIC on; final
   target a shared library.
2. **Entry:** build `PosixMain.cpp` as `SDL_main` inside a shared library; Android app/data paths
   (no desktop CWD, no desktop launcher); JNI/exported `SDL_main`.
3. **Bionic compat (C1):** `sys/timeb.h`, `backtrace()` < API 33, crash-handler guards — C1 covers.
4. **Game library (C4-class):** touch input, Android UI scaling, BIG filesystem on Android,
   `PosixDevice9`/`SdlGpuFrame` Android surface lifecycle, `-overlay`/data paths — must be
   re-derived for `b79aa63`.
5. **GameSpy/Bionic:** `pthread_cancel` → safe no-op (not detach).
6. **SDL skew:** make the launcher's SDL **Java** match olcay's SDL **native** (3.4.16), or build
   olcay against 3.4.2 — decide and implement once, do not mix.

## 6. Dependency compatibility table

| Dependency | Olcay | Target | Android action |
|------------|-------|--------|----------------|
| SDL3 | 3.4.16 vendored | 3.4.2 FetchContent | Build olcay's 3.4.16; **match Java to 3.4.16** |
| Renderer | SDL3-GPU `posixd3d9` (Vulkan) | DXVK d3d8/d3d9 | Build olcay's; do **not** ship target DXVK for the Reforged engine |
| Audio | miniaudio | OpenAL | Build miniaudio (Android backend: AAudio/OpenSL) |
| FFmpeg | committed prebuilt | vcpkg 8.1.1 | Build olcay's FFmpeg for arm64 (or vcpkg) |
| GameSpy | vendored + patches | FetchContent | Build olcay's vendored GameSpy + pthread patch |
| FreeType | vendored (vendor.sh) | vcpkg | Build olcay's vendored FreeType |
| glslang/SPIRV-Cross | vendored | (DXVK path) | Build olcay's (needed for its shader pipeline) |
| Vulkan loader/headers | system/NDK | NDK | NDK provides; no desktop packages |
| STL | libc++ | libc++_shared | `ANDROID_STL=c++_shared` |

## 7. Proposed integration architecture (louay separation model)

```
Host app (target Afork):  android/ Gradle shell + launcher Activities  (UNCHANGED identity)
        │  loads, at launch:  libmain.so  (the Reforged engine)
        ▼
Reforged engine (olcay @ pinned SHA), checked out in CI into  reforged-engine/ :
   Tools/vendor.sh  → SDL3 3.4.16, GameSpy, FreeType, glslang, SPIRV-Cross, miniaudio, FFmpeg
   + Android adaptation layer (patches: C1 + re-derived C4-class + gamespy-pthread)
   → CMake android-vulkan (arm64-v8a)  →  libmain.so (SDL_main, JNI)
        ▼
Package: jniLibs/arm64-v8a/{libmain.so, libSDL3.so, miniaudio deps, gamespy, libc++_shared, …}
         + matched SDL Java (3.4.16) staged into the shell
         → APK (single play engine = Reforged; no competing MYSOREZ engine, no desktop libs)
```

The original MYSOREZ engine is **not** built or shipped as a play engine. The host's launcher,
Gradle, storage, and packaging stay; only the native engine behind `libmain.so` changes to olcay's.

## 8. Build & runtime risks — and the blocking constraint for this environment

Risks:
- ** adaptation depth:** C4 does not apply to `b79aa63`; re-deriving it needs build/test loops.
- **SDL skew:** 3.4.2 Java vs 3.4.16 native — must be reconciled or it crashes at `SDL_Init`.
- **Renderer bring-up:** olcay's SDL-GPU/Vulkan path on Android surface lifecycle is unproven here.
- **Audio:** miniaudio Android backend must be selected and linked.
- **CI budget / build environment (BLOCKING):** this sandbox has **no NDK, vcpkg, meson, glslang,
  or DXVK**, and a 3.5-minute command cap — a full engine build is impossible locally. The repo is
  **private** and its **GitHub Actions minutes are exhausted**: the `Build Android` workflow was
  dispatched twice and **cancelled by GitHub both times** (runs 44961138574, 44961872094). Therefore
  the acceptance criteria of Phases 3–10 ("CI must successfully configure", "CI must produce and
  inspect the actual ARM64 engine library") **cannot be met in this environment**.

Per the mission's own rule — *"If a phase is blocked, document the exact cause and stop rather than
introducing an unverified workaround"* and *"Do not report a successful build when only configuration
or compilation of a test target succeeded"* — the build phases are reported as **blocked**, not
faked. Delivering Phases 0–2 (access, audit, pinned reproducible source) is the verifiable
foundation; Phases 3–11 require a build environment + CI budget and must be executed there.

## 9. Recommended sequence once a build environment + CI budget exist

1. Pin olcay (done in Phase 2) → `reforged-engine/` at the recorded SHA.
2. `Tools/vendor.sh` to fetch deps; verify SDL 3.4.16.
3. Apply C1 (verified) → re-derive the C4-class Android game-library changes against the pinned SHA
   (build-driven), + gamespy-pthread.
4. Add `ZH_PLATFORM_ANDROID` CMake branch; shared-library target; PIC; LLD.
5. Build `libmain.so` (arm64-v8a); verify ELF/AArch64/`SDL_main`/DT_NEEDED/unresolved.
6. Match SDL Java (3.4.16) in the shell; wire `GeneralsZHActivity` to load it.
7. miniaudio Android backend; FFmpeg arm64; GameSpy; FreeType; glslang/SPIRV-Cross.
8. Package (single engine, no DXVK/MYSOREZ engine, no desktop libs, no game data); CI-verify.
9. Device-test render/audio/touch/lifecycle; only then claim success.