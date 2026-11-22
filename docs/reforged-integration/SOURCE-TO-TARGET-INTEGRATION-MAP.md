# Source-to-Target Integration Map — olcay Reforged engine → target Android fork

**Purpose:** concrete map of *exact olcay source files → exact louay adaptations → exact target files →
required modifications → expected output library → CI build/verification steps*. This replaces the
earlier audit-only groundwork with an implementation plan for an actual Android ARM64 build of the
olcay engine.

**Status:** Stage 1 deliverable. Verified patch applicability (below) is real; the ARM64 build itself
is produced by CI (Stage 2/3), not yet run.

---

## 0. Pins

| Role | Repo | Commit |
|------|------|--------|
| Engine (the actual Reforged engine) | `olcayseygan/CnCGeneralsZH-Reforged` | **`61dbc593fb4eecb7af891b80c4c491c3ad2b791d`** (`build(cmake): version 4.0.0`) |
| Android adaptation reference | `louay0khaled/zero-hour-reforged-android-mod` | `d240f2af734cb6c69ddf301c12c473b79a909056` |
| Host application (unchanged identity) | `redaf12-Decode/Generals-Android-Port` | branch `Afork` |

**Why the pin is `61dbc59` and not olcay `main` (`b79aa63`):** the Android adaptation layer
(`reforged-c1-android.patch` → `reforged-frame-selfcheck-entry.patch` →
`reforged-frame-selfcheck-mali-tolerances.patch` → `reforged-c4-game-library.patch`) is verified to
apply to `61dbc59` and to produce the Android shared-library target. On `b79aa63` the C4 patch fails
across ~20 files (ControlBar, InGameUI, udp, PosixDevice9*, SdlGpuFrame*, W3DView, …); re-deriving
~14.8k lines blind is not verifiable. `61dbc59` **is** the actual olcay Reforged engine; bumping the
pin requires re-deriving + re-verifying the adaptation layer.

---

## 1. olcay → louay → target file map

The Android adaptation is expressed as patches against the **olcay engine tree**, vendored from louay
and applied at build time (never committed into the engine tree). Patches are applied in this order
(verified locally with `git apply --check` against `61dbc59`):

| # | Patch (from louay `probe/patches/`) | olcay files touched | What it does | Applies to `61dbc59`? |
|---|-------------------------------------|---------------------|--------------|------------------------|
| 1 | `reforged-c1-android.patch` | `GeneralsMD/Code/CMakeLists.txt`, `Main`… `GameEngineDevice/…/SdlGpuFrame.cpp`, `Libraries/…/glyphrasteriser.cpp`, `Tools/ffmpeg-build-posix.sh`, `PreRTS.h`, `CrashHandlerPosix.cpp`, `GameMemory.cpp`, `StackDumpPosix.cpp`, `PosixDevice9Timing.cpp`, `debug.h`, `profile.h` (11 files) | Android platform detection (`ZH_PLATFORM_ANDROID`), bionic crash/backtrace guards, SDL3 main | **Yes** |
| 2 | `reforged-frame-selfcheck-entry.patch` | `GeneralsMD/Code/CMakeLists.txt`, `…/SdlGpuFrame.cpp`, `Tests/frame_selfcheck_main.cpp`, `Tests/test_sdl_gpu_frame.cpp` (4) | adds the `sdl_gpu_frame_selfcheck` build gate (build-only, ships nothing) and the `SdlGpuFrame.cpp` edits C4 depends on | **Yes** |
| 3 | `reforged-frame-selfcheck-mali-tolerances.patch` | `Tests/test_sdl_gpu_frame.cpp` (1) | Mali tolerance for the selfcheck | **Yes** |
| — | *(run `GeneralsMD/Code/Tools/vendor.sh` here)* | populates `Libraries/Source/{SDL3,GameSpy,FreeType,glslang,SPIRV-Cross}` | fetch pinned 3rd-party sources | n/a |
| 4 | `reforged-rfx-jni-prefix.patch` | `Libraries/Source/SDL3/src/core/android/SDL_android.c` (1) | prefixes SDL3's `Java_org_libsdl_app_*` JNI exports (`rfx_`) to isolate the Reforged engine's SDL from the host's | needs vendored SDL3 (after vendor.sh) |
| 5 | `gamespy-android-pthread.patch` | GameSpy `src/common/linux/gsthreadlinux.c` (1) | bionic `pthread_cancel` → safe no-op (not detach) | needs vendored GameSpy |
| 6 | `reforged-c4-game-library.patch` | **79 files** incl. `GeneralsMD/Code/CMakeLists.txt`, `Main/PosixMain.cpp`, `GameEngineDevice/…/PosixDevice9*`, `…/SdlGpu*`, `SdlDevice/…/TouchInput.cpp`, `W3DDevice/…/W3DView.cpp`, `Win32Device/…/Win32BIGFileSystem.cpp`, `GameNetwork/udp.cpp`, `ControlBar*`, `InGameUI.cpp`, `ExecutableDirectory.cpp`, … | the actual Android game library: `add_library(rfx_game SHARED PosixMain.cpp)` + `zh_c4_force_pic`, FFmpeg `--target=aarch64-linux-android`, `__ANDROID__` guards for touch, UI scale, BIG filesystem, surface lifecycle, data paths | **Yes** (after #1–3) |

### Expected output library
- CMake target: **`rfx_game`** → **`librfx_game.so`** (ELF aarch64, `SONAME`/dynsym exports `SDL_main`).
- `Main/PosixMain.cpp` `#include <SDL3/SDL_main.h>` → `int main(...)` becomes exported `SDL_main` on Android.
- Links the whole engine (`sdldevice` + `installroot`): W3D renderer, miniaudio, FFmpeg, GameSpy, FreeType, glslang/SPIRV-Cross.
- For packaging it is renamed **`libmain.so`** (the target's 30 Hz Zero Hour engine slot).

---

## 2. Target MYSOREZ files (host application — mostly unchanged)

| File | Role | Change needed |
|------|------|---------------|
| `android/app/src/main/java/com/generalsx/zerohour/GeneralsZHActivity.java` | `getLibraries()` returns `{"SDL3", <engine>}`; `loadLibraries()` loads them | Engine slot already loads `libmain.so`. No identity change. Only if Reforged's SDL3 is static/prefixed does the SDL Java layer need the staged 3.4.16 glue (see §4 risk). |
| `android/app/src/main/java/.../SetupActivity.java` | `engineLibraries()` / sim-Hz selection | Keep launcher; ensure the selected engine slot is `main` → Reforged. |
| `android/app/build.gradle` | `arm64-v8a`, minSdk 28, `jniLibs` | Package Reforged `libmain.so` + its DT_NEEDED into `jniLibs/arm64-v8a/`; **exclude** the MYSOREZ `libmain*.so` so Reforged is the only play engine. |
| `android/app/src/main/jniLibs/arm64-v8a/` | native libs | Receive `libmain.so` (=`librfx_game.so`) + deps from the Reforged build. |
| `cmake/` + `CMakePresets.json` (target) | builds the **MYSOREZ** engine | **Not used** to build Reforged. The Reforged engine is built by its own `GeneralsMD/Code/CMakeLists.txt` (via the C4 patch) with `-DANDROID_ABI=arm64-v8a`. |
| `.github/workflows/build-android.yml` | target CI (MYSOREZ build) | Not the Reforged path; a new `build-reforged-engine.yml` (Stage 3) is the Reforged acceptance. |

---

## 3. CI build & verification steps (the Reforged acceptance)

New workflow `.github/workflows/build-reforged-engine.yml` (Stage 3), mirroring louay's proven steps:

1. Checkout host at `Afork` (`ref:` pinned to the branch).
2. Checkout olcay at `61dbc59` into `reforged-engine/` (via `scripts/build/android/fetch-reforged-engine.sh`; verify `HEAD`).
3. Install NDK `27.2.12479018`, Ninja, meson, pkg-config, glslang, ccache.
4. `git -C reforged-engine apply` the patch chain in order: c1 → fse → fse-mali → (vendor.sh) → rfx-jni → gamespy-pthread → c4.
5. `bash reforged-engine/GeneralsMD/Code/Tools/vendor.sh` (fetches SDL3 3.4.16, GameSpy, FreeType, glslang, SPIRV-Cross).
6. Configure: `cmake -S reforged-engine/GeneralsMD/Code -B rfx-build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release`.
7. Build: `cmake --build rfx-build --target rfx_game -j`.
8. **Verify the actual engine library:**
   - `llvm-readelf -h librfx_game.so` → `Machine: AArch64`, `Type: DYN`.
   - `llvm-nm -D librfx_game.so | grep ' T SDL_main'` → present.
   - `llvm-readelf -d` → DT_NEEDED all satisfiable; no desktop libs (no libX11/libEGL from host).
   - `llvm-nm -D` → no undefined non-`w` symbols beyond the allowed system set.
9. Upload `librfx_game.so` (+ symbol table) as a workflow artifact.

**Acceptance = step 8 passes on an actual ARM64 build.** A launcher-only APK or a static validation
script is explicitly *not* acceptance.

---

## 4. Build & runtime risks

- **SDL version skew:** host SDL Java 3.4.2 vs Reforged SDL native 3.4.16. Louay handles it with
  `stage-rfx-glue.py` (stage 3.4.16 Java) + `rfx-jni-prefix.patch` (prefix `Java_org_libsdl_app_*`).
  If Reforged's SDL3 is built static into `librfx_game.so`, the host's `System.loadLibrary("SDL3")`
  must be reconciled (load only `libmain.so`, or stage matching `libSDL3.so`). Decide during Stage 4.
- **C4 depth:** 79-file game-library adaptation; if CI surfaces a compile error against `61dbc59`
  in this environment, it is fixed as a new patch revision, not by hand-editing the engine tree.
- **FFmpeg/miniaudio/glslang for arm64:** built from the engine's own scripts; these are the long
  poles of the build.
- **Packaging single engine:** the host must not also package the MYSOREZ `libmain.so` — Reforged's
  replaces it (Stage 4), satisfying "prevent launching the original MYSOREZ engine".

---

## 5. Staged execution

1. **Stage 1 (this commit):** correct pin → `61dbc59`; this map. Commit "docs(android): map Reforged engine integration".
2. **Stage 2:** vendor patches + `build-reforged-engine.sh`. Commit "build(android): add Reforged engine build script and adaptation layer".
3. **Stage 3:** CI workflow; push; wait for Actions; the ARM64 `librfx_game.so` build+verify is the gate. Commit "ci(android): build and verify Reforged engine library".
4. **Stage 4 (DONE):** APK packages libmain.so as the sole play engine with an isolated org.libsdl.apprfx SDL 3.4.16 layer + ReforgedGameActivity; CI-verified (green run 44967213316, artifact reforged-apk ~21.5 MB, no MYSOREZ engine). Remaining: on-device runtime validation (needs a device) + release signing.