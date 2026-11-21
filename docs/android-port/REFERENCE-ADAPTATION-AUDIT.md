# Reference Adaptation Audit — Reforged Android Integration Layer

**Status:** Phase 1 deliverable (inspection only — no runtime behavior changed)
**Target:** `redaf12-Decode/Generals-Android-Port`, branch `Afork`
**Reference:** `louay0khaled/zero-hour-reforged-android-mod` (`main`)
**Date:** 2026-11-21

---

## 1. Executive summary

The reference repository is **not a standalone engine or app**. It is a thin
**overlay** that is applied by GitHub Actions on top of a *pinned checkout of a
different MYSOREZ-derived engine tree* (`MYSOREZ/GeneralsZH-Android-Port` @
`a520ed1`) plus its DXVK submodule. It contains:

- CI glue that re-runs a full engine build and applies ~10 game-logic patches,
  an identity-surgery script, and a data-package bundler;
- a standalone GPU-probe / renderer-selfcheck harness (a throwaway second app);
- bundled Reforged `.big` data packages (3–5 MB each).

The target repository (`Generals-Android-Port`, the "GeneralsX" fork described
in `AGENTS.md`) is **itself already a mature MYSOREZ-based Android port** with a
complete, working integration layer: CMake `android-vulkan` preset (arm64-v8a,
NDK, vcpkg), SDL3 + DXVK + OpenAL + FFmpeg, a full `SDLActivity` shell with
~46 launcher Activities, JNI entry via `Core/Main/SDL3Main.cpp`, native crash
handling, custom-Vulkan-driver loading, and packaging scripts that already verify
the produced `libmain.so` is AArch64 and carries the SDL3 WSI.

**Conclusion of the audit:** The genuine *Android integration* mechanisms the
reference relies on are **already present in the target**, and the target already
solves the same bionic/pthread/SDL issues the reference documents (and in some
cases solves them more directly). Everything else the reference adds is either:

1. **Already present in the target** (verified by file/line evidence below), or
2. **Reforged game logic / data** that would replace engine behavior, break
   retail/replay compatibility, or rebrand the app — all explicitly forbidden by
   the task's constraints, or
3. **Architecture-duplicating** (a second SDLActivity / second SDL version / a
   second build system) — forbidden by constraint 8.

Therefore this adaptation is **primarily a verification-and-hardening effort**
plus **additive diagnostic tooling**, not a code port. Each later phase adds a
real, reviewable, non-breaking artifact (automated integration validation and
logcat diagnostics) and documents, with evidence, the target↔reference
correspondence for the area that phase covers. This is the faithful reading of
"adapt … while preserving the existing architecture … not a blind copy."

---

## 2. Methodology

1. Checked out `origin/Afork` and confirmed a clean working tree.
2. Cloned the reference at `main` (read-only) into a scratch directory.
3. Enumerated every file in the reference (`ci/`, `probe/`, `tools/`,
   `.github/workflows/`, `docs/`, `packages/`).
4. Read every patch/script header and the README/ci/probe documentation.
5. Cross-checked each reference mechanism against the target tree
   (`grep`/`find`/`sed`) to determine whether the target already implements it.
6. Confirmed CI reachability: `GH_TOKEN` authenticates to the GitHub REST API as
   `redaf12-Decode` (verified `GET /user` → 200) and to `git` over HTTPS, so
   `workflow_dispatch` and `git push` are available via the API even though the
   `gh` CLI reports the token as invalid.

---

## 3. Target architecture (existing, on `Afork`)

| Area | Location | Notes |
|------|----------|-------|
| Gradle shell | `android/` | `com.android.application` 8.5.2, namespace `com.generalsx.zerohour`, minSdk 28 / targetSdk 35, `abiFilters 'arm64-v8a'` |
| Launcher Activities | `android/app/src/main/java/com/generalsx/zerohour/` | ~46 Java files: `GeneralsZHActivity`, `SetupActivity`, `FolderPickerActivity`, `ModManagerActivity`, `GeneralsOnlineActivity`, touch editor, etc. |
| Native entry | `Core/Main/SDL3Main.cpp` | `SDL_main` entry, Android/JNI, custom Vulkan driver loading |
| Crash handler | `Core/Main/AndroidCrashHandler.cpp` | Android-specific |
| CMake presets | `CMakePresets.json` → `android-vulkan` | arm64-v8a, `ANDROID_PLATFORM=android-28`, `ANDROID_STL=c++_shared`, vcpkg triplet `arm64-android`, SDL3/OpenAL/DXVK/FFmpeg ON |
| CMake modules | `cmake/` | `sdl3.cmake`, `gamespy.cmake`, `openal.cmake`, `dx8.cmake`, `adrenotools.cmake`, triplets, meson cross file |
| Native deps | FetchContent / vcpkg | SDL3, SDL3_image, OpenAL soft, GameSpy (TheAssemblyArmada fork), DXVK (fbraz3 submodule), FFmpeg |
| Build scripts | `scripts/build/android/` | `build-android-zh.sh`, `build-dual-hz.sh`, `build-local-sandboxed.sh`, `package-android-zh.sh`, `stage-sdl-java.sh`, `fetch-turnip.sh`, `fetch-vulkan-validation-layer.sh` |
| SDL Java glue | staged (gitignored) | `package-android-zh.sh` / `stage-sdl-java.sh` copy `org/libsdl/app/*.java` from the in-tree SDL3 source, then apply `android/patches/*.patch` |
| SDL Java patches | `android/patches/` | `sdl-android-gesture-nav-back.patch`, `sdl-android-mouse-source-clicks.patch` |
| Packaging | `package-android-zh.sh` | Stages `.so` set into `jniLibs/arm64-v8a`, small runtime assets (`fonts/`, `dxvk.conf`, `DefaultOptions.ini`, fallback Turnip driver), then `gradle assembleDebug` |
| Game data | user-provided | `.big` archives are **not** packaged; user selects a folder via `FolderPickerActivity` / `MANAGE_EXTERNAL_STORAGE` |
| CI | `.github/workflows/` | `build-android.yml` (workflow_dispatch + PR-to-main), `android-unit-tests.yml` (workflow_dispatch), `ci.yml`, etc. Push triggers were deliberately removed (owner: limited Actions budget). |

The target therefore **already satisfies** the reference's native integration
preconditions: AArch64 CMake build, SDL3 Java+JNI, DXVK, OpenAL, GameSpy, bionic
compat, packaging with AArch64 verification.

---

## 4. Reference architecture

The reference is an **overlay + CI pipeline**, not an app tree:

- `.github/workflows/build-apk.yml` — checks out MYSOREZ @ `a520ed1` (twice) +
  this overlay + the Reforged engine tree + DXVK, applies surgery/patches,
  builds engines with the same `android-vulkan` preset, packages a release APK.
- `ci/apply-surgery.py` — **rebrands** the app (new `applicationId`
  `com.louay.zerohour.reforged`, new version, new label, repoints native
  hardcoded data paths, repoints OTA channel, adds a "Reforged" launcher page).
- `ci/apply-phase2.py` — bundles `!Reforged-v1.big` as a single enabled mod.
- `ci/apply-phase3.py` — "product pass": adds `RfxGameActivity`, per-product
  external data folder, etc.
- `ci/stage-rfx-glue.py` + `probe/patches/reforged-rfx-jni-prefix.patch` —
  stages a **second SDL Java layer** under package `org.libsdl.apprfx` so a
  test payload carrying SDL 3.4.16 can coexist with the game's SDL 3.4.2.
- `ci/patches/*` — Reforged **game-logic** patches: 60-steps simulation,
  smooth motion, perf-graphics, hitch removal, pacing, step-perf, etc.
- `probe/` — a standalone GPU-probe / renderer-selfcheck harness (its own
  `CMakeLists.txt`, `AndroidManifest.xml`, `build-apk.sh`, activities).
- `probe/patches/` — `gamespy-android-pthread.patch`,
  `reforged-c1-android.patch` (bionic compat), `reforged-c4-game-library.patch`
  (~15k-line Reforged game-library feature patch), `reforged-frame-selfcheck-*`.
- `packages/`, `ci/assets/` — Reforged `.big` data packages.

The reference **depends on a different engine revision** (`a520ed1`) than the
target and targets the **Reforged** engine, not the target's GeneralsX engine.

---

## 5. Component-by-component inventory and disposition

Legend — **PRESENT**: target already implements it; **ADAPT**: genuinely portable
and non-breaking, adapted additively; **OUT**: cannot transfer under the task
constraints.

| # | Reference component | Purpose | Target status | Disposition |
|---|---------------------|---------|---------------|-------------|
| 1 | `build-apk.yml` | Full engine CI build on `android-vulkan` | Target has `build-android.yml` (same preset, same NDK/vcpkg/DXVK flow) | **PRESENT** — no second build system added |
| 2 | `apply-surgery.py` | Rebrand applicationId/version/label, repoint paths, add Reforged page | Would replace identity/architecture | **OUT** — constraint 4/8 (do not replace architecture) |
| 3 | `apply-phase2.py` + `.big` assets | Bundle Reforged game data | Would package full game data | **OUT** — constraint (avoid packaging complete game data) + retail-data legality |
| 4 | `apply-phase3.py` (RfxGameActivity) | Second game Activity / product shell | Would add competing Activity | **OUT** — constraint 8 (no duplicate Activities) |
| 5 | `stage-rfx-glue.py` + `rfx-jni-prefix.patch` | Second SDL Java layer (`org.libsdl.apprfx`) for a coexisting SDL 3.4.16 | Target has one SDL3 layer | **OUT** — constraint 8 (no second SDLActivity) |
| 6 | `ci/patches/*` (60-steps, smooth-motion, perf-graphics, pacing, step-perf, hitch, carryover) | Reforged game logic/behavior | Different engine; would break retail/replay compatibility | **OUT** — constraints 5/6/7 + "retail compatibility" golden rule |
| 7 | `gamespy-android-pthread.patch` | Replace `pthread_cancel` (absent in bionic) | Target solves via `cmake/gamespy.cmake` (`-Dpthread_cancel(t)=(0)`) and `thread_compat.cpp` | **PRESENT** — verified equivalent, safer (preprocessor no-op) |
| 8 | `reforged-c1-android.patch` (bionic: `sys/timeb.h`, `backtrace()` < API 33, CMake Android branch) | Make Reforged engine compile on bionic | Target guards `sys/timeb.h` with `_WIN32` (`PreRTS.h`), has its own Android CMake handling, own crash handler | **PRESENT** — verified equivalent |
| 9 | `reforged-c4-game-library.patch` (79 files) | Reforged touch/UI/game library | Target has its own touch stack (`TouchInput.cpp`, touch editor, `TouchInput` gesture rules) | **OUT** — would replace engine behavior; target already has touch |
| 10 | `probe/` GPU probe / renderer selfcheck | Diagnostic harness (second app, own CMake/Gradle-free pipeline) | Target has no such standalone probe | **OUT as a port** — constraint 8 (second Activities/build). **ADAPT the *idea*** as non-invasive validation (Phase 6) instead |
| 11 | `sdl-android-gesture` glue | Gesture-nav back handling in `SDLActivity.java` | Target has `android/patches/sdl-android-gesture-nav-back.patch` | **PRESENT** |
| 12 | SDL Java staging | Copy `org/libsdl/app/*.java` + apply patches | Target: `stage-sdl-java.sh` + `package-android-zh.sh` | **PRESENT** |
| 13 | AArch64 artifact verification | "trust no successful exit code" ELF check | Target: `build-android-zh.sh` already verifies `libmain.so` is AArch64 + DXVK SDL3 WSI | **PRESENT** — extended additively in Phase 2 |
| 14 | Release signing | local-only, committed debug keystore | Target: same model (committed `debug.keystore`) | **PRESENT** |
| 15 | Runtime validation of the *packaged APK* (libs present, ELF deps, JNI entry consistency, manifest/permissions, asset layout, APK contents) | Not present as a single gate in reference either | **No dedicated gate** on the staged `jniLibs`/APK | **ADAPT** — the real gap; built across Phases 2–6 |

### 5.1 What is actually missing in the target (the real work)

The target's build script verifies `libmain.so` **in the build dir** and the DXVK
WSI, but there is **no automated gate over the final packaged artifact**:

- required shared libraries present in `jniLibs/arm64-v8a/` (SDL3, SDL3_image,
  OpenAL, GameSpy, DXVK d3d8/d3d9, adrenotools, libc++_shared, hooks);
- ELF `DT_NEEDED` resolution and absence of unresolved symbols for each `.so`;
- JNI entry-point consistency (`Java_org_libsdl_app_SDLActivity_*` exported by
  `libmain.so` matching the staged SDL Java `native` methods);
- SDL3 integration markers;
- manifest / permission configuration (single SDLActivity, expected permissions,
  `extractNativeLibs`/`useLegacyPackaging` behavior);
- runtime asset presence and directory layout;
- final APK contents and absence of unnecessary desktop binaries / full game data;
- actionable logcat diagnostics on native-init failure.

This is exactly Phases 2–6. It is additive, non-breaking, and CI-validatable.

---

## 6. Exact files that would require changes

Under a *blind copy* the reference would touch (and break) these target files.
**None of them are modified by this adaptation** except additively where a phase
explicitly adds a new file:

| Target file | Why a blind copy would change it | This adaptation |
|-------------|----------------------------------|-----------------|
| `android/app/build.gradle` | rebrand applicationId/version | untouched |
| `Core/**/GX*.h`, `AndroidCrashHandler.cpp` | repoint hardcoded data paths | untouched |
| `android/app/src/main/res/values*/strings.xml` | relabel app | untouched |
| `GeneralsMD/Code/CMakeLists.txt` | Android branch / Reforged targets | untouched |
| `Core/Main/SDL3Main.cpp`, JNI | second SDL prefix | untouched |
| `cmake/gamespy.cmake`, `thread_compat.cpp` | pthread | untouched (already correct) |

Files **added** by this adaptation (new, isolated, no runtime behavior change):

- `docs/android-port/REFERENCE-ADAPTATION-AUDIT.md` (this file)
- `scripts/qa/android/validate-android-integration.sh` (built across Phases 2–6)
- `docs/android-port/FINAL-INTEGRATION-REPORT.md` (Phase 7)
- (possibly) a CI wiring edit to invoke the validator — additive only

---

## 7. Changes that cannot be transferred directly

1. **Rebranding surgery** (`apply-surgery.py`): changes application identity,
   hardcoded native paths, OTA channel, and adds a launcher page. Directly
   violates "do not replace the existing architecture."
2. **Reforged game-data bundling** (`apply-phase2.py`, `*.big`): would package
   full game data into the APK. Violates "avoid packaging complete game data" and
   the no-assets rule.
3. **Reforged game-logic patches** (`ci/patches/*`): 60-steps, smooth motion,
   perf-graphics, pacing, etc. — these require the Reforged engine source and
   change simulation/render behavior; they break retail/replay compatibility and
   "retail compatibility" / "determinism" golden rules. Constraint 6 (do not
   blindly apply patches for a different source revision) forbids this.
4. **Second SDL layer** (`stage-rfx-glue.py`, `rfx-jni-prefix.patch`): creates a
   second `SDLActivity` in a renamed package. Violates constraint 8.
5. **C4 game-library patch** (79 files): Reforged-specific game/UI/touch rewrite;
   the target already has its own touch/UI stack. Would replace engine behavior.
6. **GPU probe harness** as a shipped second app: violates constraint 8. Adapted
   only as a *concept* in the non-invasive validator (Phase 6), not shipped.

---

## 8. Dependency and compatibility risks

| Risk | Assessment | Mitigation |
|------|------------|------------|
| Engine revision mismatch | Reference pins MYSOREZ `a520ed1` + Reforged tree; target is GeneralsX. Patches will not apply cleanly. | Do not apply them; target already implements equivalents. |
| Bionic / pthread | `pthread_cancel`, `sys/timeb.h`, `backtrace()` < API 33 | Target already guards all three (`gamespy.cmake`, `PreRTS.h`, `thread_compat.cpp`, crash handler). Verified. |
| SDL version skew | Reference needs two SDL versions coexisting | Target uses one SDL3 version end-to-end; no skew. |
| DXVK stripping | Stripped DXVK crashes mid-render | Target already keeps DXVK symbols (`keepDebugSymbols` in `build.gradle`). |
| CI budget | Owner limits Actions usage; push does not trigger CI | Use cheap `workflow_dispatch` workflows; do heavy engine build only when necessary; report honestly. |
| Sandbox build limits | 3.5-min command cap; full engine build infeasible locally | Rely on CI + static/local checks; state what was not run. |
| Storage model | Reference uses a fixed external folder; target uses `MANAGE_EXTERNAL_STORAGE` + folder picker | Keep target's existing mechanism (Phase 5 documents; no permission regression). |

---

## 9. Proposed adaptation sequence

The phases below map the task's Phase 2–7 onto concrete, additive, non-breaking
work. Each phase adds one coherent slice of the Android-integration validator and
its documentation, then validates via the cheapest applicable CI.

- **Phase 2 — Native engine build integration.** Document the CMake/AArch64
  correspondence (target preset vs reference build). Deliver: validator core +
  **AArch64 and required-shared-library presence checks** over the staged
  `jniLibs`. No CMake change (already correct); verify and guard it.
- **Phase 3 — SDL3 / JNI integration.** Document SDL3 Java staging + single
  `SDLActivity` + JNI entry correspondence. Deliver: **JNI entry-point
  consistency check** and **SDL3 integration marker check**.
- **Phase 4 — Native dependency compatibility.** Document bionic/pthread/FFmpeg/
  GameSpy/FreeType handling. Deliver: **ELF `DT_NEEDED` / unresolved-symbol
  checks** and **pthread-cancellation safety check** (assert no
  detach-replacing-cancel regression; verify the no-op translation).
- **Phase 5 — Runtime packaging & game data.** Document packaging flow and the
  user-data mechanism. Deliver: **manifest/permission, runtime-asset layout, and
  APK-content checks** (no full game data, no desktop binaries).
- **Phase 6 — Runtime validation & diagnostics.** Finalize the validator into a
  single entry point; add **logcat diagnostic guidance/assertions** for native
  init failure; wire into CI.
- **Phase 7 — Final build & report.** Produce/record an ARM64 APK artifact via CI
  if feasible, measure size/contributors, and write `FINAL-INTEGRATION-REPORT.md`
  with CI run links, verified results, and device-only items still required.

---

## 10. Honest limitations of this phase

- No runtime behavior was modified; this is inspection + planning only.
- The reference's game-logic and data components were **not** ported, by design,
  because the constraints forbid replacing architecture / shipping game data /
  breaking retail compatibility. This is recorded explicitly rather than silently
  skipped.
- CI was validated for reachability (API auth as `redaf12-Decode`); the first
  actual workflow run is triggered after the Phase 1 commit.