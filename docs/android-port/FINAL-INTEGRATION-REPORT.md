# Final Integration Report — Reforged Android Integration Adaptation

**Status:** Phase 7 deliverable
**Target:** `redaf12-Decode/Generals-Android-Port`, branch `Afork`
**Reference:** `louay0khaled/zero-hour-reforged-android-mod` (`main`)
**Date:** 2026-11-21

---

## 1. Summary

This task set out to adapt the **Reforged Android integration layer** from
`louay0khaled/zero-hour-reforged-android-mod` into the `Afork` branch of
`Generals-Android-Port`, preserving the existing MYSOREZ-based architecture and
without copying the reference repository.

Inspection (Phase 1, `REFERENCE-ADAPTATION-AUDIT.md`) established that the
reference is a thin **overlay + CI pipeline** on top of a *different* pinned
MYSOREZ-derived engine tree, and that the target is **already** a mature
MYSOREZ-based Android port whose native integration (CMake `android-vulkan`
arm64-v8a, SDL3 + DXVK + OpenAL + FFmpeg, `SDLActivity` shell, JNI entry,
bionic/pthread compatibility, AArch64-verified packaging) already covers the
reference's integration preconditions — in several cases more directly than the
reference's own patches.

Consequently, the faithful adaptation was **verification-and-hardening plus
additive diagnostic tooling**, not a code or data port. The components that
*are* specific to the reference (rebranding surgery, Reforged game-data bundling,
Reforged game-logic patches, a second SDL layer, a standalone GPU-probe app) were
deliberately **not** transferred, because each would violate a stated constraint
(replace architecture / ship game data / break retail compatibility / duplicate
SDLActivity or build system). This is documented explicitly rather than silently
dropped.

The concrete deliverables are an automated, CI-runnable **Android integration
validator** and **logcat load-failure diagnostics**, wired into the packaging
pipeline. The validator is exercised on every real `package-android-zh.sh` run
(including the Build Android CI workflow); its positive/negative behavior was also
verified locally because the private-repo Actions budget prevented a full CI build
(see §8–§9).

---

## 2. Adapted reference components

| Reference component | Disposition | Where it lives in the target |
|---------------------|-------------|------------------------------|
| "Trust no successful exit code" AArch64 verification (`build-apk.yml`) | Adapted as a packaged-artifact gate | `scripts/qa/android/validate-android-integration.sh` (AArch64 + required-lib checks) |
| SDL3 Java/JNI single-stack (`org.libsdl.app`, one `SDLActivity`) | Verified & guarded | validator `--java-sdl` checks; Phase 3 |
| Bionic / pthread compatibility (no `pthread_cancel`; detach is *not* a cancel substitute) | Verified & guarded against regression | validator pthread-semantics check; `cmake/gamespy.cmake`, `thread_compat.cpp` |
| Runtime packaging & user-data model (small assets, no `.big` in APK) | Verified & guarded | validator manifest + APK-content checks; Phase 5 |
| Build-script artifact verification | Extended to cover the *staged/packaged* payload | validator wired into `package-android-zh.sh` |
| (reference has none) native-init load-failure diagnostics | Added (new) | `GeneralsZHActivity.loadLibraries()` logcat diagnostics |

### Reference components deliberately NOT transferred (with reason)

| Reference component | Reason |
|---------------------|--------|
| `ci/apply-surgery.py` (rebrand applicationId/version/label, repoint paths, add Reforged page) | Replaces app identity/architecture — forbidden |
| `ci/apply-phase2.py` + `ci/assets/*.big` (Reforged game data) | Would package full game data — forbidden; no-assets rule |
| `ci/patches/*` (60-steps, smooth-motion, perf-graphics, pacing, step-perf, hitch, carryover) | Reforged game logic; different engine revision; breaks retail/replay compatibility |
| `probe/` GPU probe / renderer selfcheck (standalone 2nd app, own CMake) | Duplicate Activities/build system — forbidden |
| `ci/stage-rfx-glue.py` + `probe/patches/reforged-rfx-jni-prefix.patch` (2nd SDL layer `org.libsdl.apprfx`) | Duplicate `SDLActivity` — forbidden |
| `probe/patches/reforged-c4-game-library.patch` (79-file touch/UI rewrite) | Replaces the target's own touch/UI stack |

---

## 3. Modified and added files

| File | Type | Change |
|------|------|--------|
| `docs/android-port/REFERENCE-ADAPTATION-AUDIT.md` | added | Phase 1 inspection, component inventory, disposition, risks, adaptation sequence |
| `scripts/qa/android/validate-android-integration.sh` | added | The Android integration validator (Phases 2–6): AArch64, required libs, JNI entry points, single-SDLActivity, pthread semantics, ELF DT_NEEDED, manifest/permissions, APK contents + size summary. Portable (`llvm-readelf` when present, else `od`/`grep -a`). |
| `scripts/build/android/package-android-zh.sh` | modified | Calls the validator over the staged `jniLibs` + SDL Java after staging, before Gradle. Read-only; fails the build on a bad payload. |
| `android/app/src/main/java/com/generalsx/zerohour/GeneralsZHActivity.java` | modified | `loadLibraries()` now logs each library load and, on `UnsatisfiedLinkError`, a detailed logcat entry (missing library + `nativeLibraryDir` contents) before rethrowing unchanged. |
| `.github/workflows/android-integration-validation.yml` | removed | A dispatch-only self-test workflow was trialled but removed: `workflow_dispatch` cannot be triggered from a non-default branch, and the private-repo Actions budget is exhausted. Its positive/negative self-test was verified locally instead (see §8). |
| `docs/android-port/FINAL-INTEGRATION-REPORT.md` | added | This report |

No runtime behavior of the engine, Gradle project, SDL, JNI, CMake, or packaging
changed. The only code-path change (the Java diagnostics) is log-only and rethrows
the original error.

---

## 4. Native dependencies (verified, unchanged)

The target's native dependency set is unchanged and already Android-correct:

- **SDL3** (windowing/input), **SDL3_image**, **OpenAL Soft** (audio), **GameSpy**
  (TheAssemblyArmada fork, `cmake/gamespy.cmake`), **DXVK** d3d8/d3d9 (fbraz3
  submodule, Vulkan), **FFmpeg** (vcpkg), **adrenotools** (custom Vulkan driver),
  **libc++_shared** (NDK), **ANGLE** prebuilt (optional GLES-over-Vulkan), optional
  Vulkan validation layer.
- **pthread cancellation:** bionic has no `pthread_cancel`. The target compiles
  GameSpy's call to a no-op (`-Dpthread_cancel(t)=(0)`, `cmake/gamespy.cmake`) and
  makes `TerminateThread` a no-op on Android (`thread_compat.cpp`). It deliberately
  does **not** substitute `pthread_detach` for cancel (the reference's approach),
  because detach changes the thread lifecycle. The validator asserts this stays so.
- **Bionic:** `sys/timeb.h` is `_WIN32`-guarded (`PreRTS.h`); the crash handler
  guards `backtrace()` below API 33. All verified present.
- **ELF dependencies:** the validator's DT_NEEDED check (via `llvm-readelf` in CI)
  confirms every `DT_NEEDED` resolves to a staged library or a standard Android
  system library.

---

## 5. Final Android architecture (unchanged + hardened)

```
android/ (Gradle shell, com.android.application 8.5.2, arm64-v8a, minSdk 28)
  app/src/main/java/com/generalsx/zerohour/  ~46 launcher Activities
    GeneralsZHActivity  -> SDLActivity (org.libsdl.app, staged)
        getLibraries() -> {"SDL3", <engine>}      engine ∈ main|main60|generals|generals60
        loadLibraries() -> System.loadLibrary + logcat diagnostics (new)
  app/src/main/jniLibs/arm64-v8a/  (staged; validated)
    libmain.so / libmain60.so / libgenerals.so / libgenerals60.so
    libSDL3.so  libSDL3_image.so  libopenal.so  libgamespy.so
    libdxvk_d3d8.so  libdxvk_d3d9.so  libadrenotools.so  (+hooks)  libc++_shared.so
CMake preset android-vulkan (arm64-v8a, ANDROID_PLATFORM=android-28, ANDROID_STL=c++_shared)
  -> libmain.so (z_generals) etc., DXVK d3d8/d3d9 with SDL3 WSI
Core/Main/SDL3Main.cpp  (SDL_main entry, JNI, custom Vulkan driver)
Core/Main/AndroidCrashHandler.cpp  (native crash diagnostics)
User game data: selected via FolderPickerActivity / MANAGE_EXTERNAL_STORAGE (not packaged)
```

The adaptation adds no library, Activity, build system, or engine tree. It adds a
read-only validation gate and log-only diagnostics around this architecture.

---

## 6. Build workflow

- **Local (owner's documented flow):** `scripts/build/android/build-android-zh.sh`
  then `scripts/build/android/package-android-zh.sh`. The packaging script now also
  runs the validator over the staged payload before invoking Gradle.
- **CI:** `.github/workflows/build-android.yml` (`workflow_dispatch`) builds
  `android-vulkan`, runs `package-android-zh.sh` (which runs the validator), and
  uploads the APK artifact. (In this environment the run was cancelled by the
  private-repo Actions budget; see §8–§9.)

---

## 7. CI run links

| Phase | Workflow | Run | Result |
|-------|----------|-----|--------|
| 1 | Android Unit Tests | [44960045551](https://github.com/redaf12-Decode/Generals-Android-Port/actions/runs/44960045551) | ✅ success |
| 6 | Android Unit Tests (compiles Java change) | [44960841137](https://github.com/redaf12-Decode/Generals-Android-Port/actions/runs/44960841137) | ✅ success |
| 7 | Build Android (attempt 1) | [44961138574](https://github.com/redaf12-Decode/Generals-Android-Port/actions/runs/44961138574) | ⚠️ cancelled during CMake configure (pass 1) |
| 7 | Build Android (attempt 2, warm caches) | [44961872094](https://github.com/redaf12-Decode/Generals-Android-Port/actions/runs/44961872094) | ⚠️ cancelled almost immediately |

Commit range on `Afork`: `a086879` (Phase 1) … final Phase 7 commit.

**Build Android did not complete in CI.** Both attempts were cancelled by
GitHub (during / immediately after start, before packaging) because the
repository is **private** and its metered Actions minutes are exhausted — the
same "limited Actions budget" the repository owner documents in `AGENTS.md`. This
is an environmental limit, not a failure of the changes (the cancellation happens
in CMake configure, long before `package-android-zh.sh` runs the validator). No
APK artifact was produced by CI. See §8–§9.

---

## 8. Verified results

- **Android Unit Tests (Phase 1, run 44960045551):** ✅ success — the launcher
  Java, lint, translation-resource check, and launcher-only APK all build on
  `Afork`.
- **Android Unit Tests (Phase 6, run 44960841137):** ✅ success — the
  `GeneralsZHActivity.loadLibraries()` logcat-diagnostics change compiles and the
  launcher-only APK still packages.
- **Validator, local verification (this sandbox):** ✅
  - Positive fixture (real AArch64 ELF from the committed ANGLE prebuilt +
    synthetic `libSDL3.so` exporting `JNI_OnLoad` + one `SDLActivity.java`):
    `RESULT: PASS`.
  - Negative fixtures correctly fail: empty dir (missing libs), a `.big` archive
    in the APK, and a foreign-ABI (`x86`) `.so` are all caught with non-zero exit.
  - pthread-semantics and manifest checks pass against the real tree.
- **Build Android (Phase 7):** ⚠️ cancelled twice (private-repo Actions budget);
  no APK artifact; the validator was therefore **not** exercised end-to-end in CI.

> No gameplay was run. All results above are build-time/static only.

---

## 9. Known limitations

1. **Reference game components not ported (by design).** Rebranding, Reforged
   data packages, Reforged game-logic patches, the second SDL layer, and the
   standalone GPU probe were not transferred: each would replace the existing
   architecture, ship game data, break retail/replay compatibility, or duplicate an
   `SDLActivity`/build system — all forbidden by the task. See
   `REFERENCE-ADAPTATION-AUDIT.md` §7.
2. **Full build could not complete in CI (private-repo Actions budget).** The
   `Build Android` workflow was dispatched twice and cancelled by GitHub both
   times (metered Actions minutes exhausted; the repo is private). This matches the
   repository owner's standing instruction (`AGENTS.md`: limited Actions budget,
   build locally). Consequently no APK artifact was produced by CI and the
   validator's end-to-end run on a real packaged APK is **unverified in CI**. Per
   `AGENTS.md`, the owner builds locally with
   `scripts/build/android/build-dual-hz.sh` / `package-android-zh.sh` (the latter
   now runs the validator).
3. **`workflow_dispatch` only runs from the default branch.** A new
   `workflow_dispatch`-only workflow cannot be triggered from `Afork` (GitHub reads
   `workflow_dispatch` triggers from the default branch, `main`). The attempted
   `android-integration-validation.yml` self-test was therefore removed rather than
   left as an undispatchable, noisy artifact; its positive/negative logic was
   verified locally (§8). The existing `Android Unit Tests` and `Build Android`
   workflows *are* dispatchable on `Afork`.
4. **No device / gameplay evidence.** The validator and diagnostics are static and
   build-time only. Rendering, touch, audio, and online behavior require on-device
   testing.
5. **Sandbox could not build the engine locally.** The cloud sandbox has a 3.5-minute
   command cap; a full engine build (DXVK + vcpkg + NDK) is not feasible there. The
   validator was exercised locally against the committed AArch64 ANGLE libraries and
   synthetic APK fixtures.
6. **CI usage was kept minimal.** Only the cheap `Android Unit Tests` workflow was
   run to completion (Phase 1, Phase 6). The expensive `Build Android` workflow was
   attempted for Phase 7 evidence but the private-repo budget cancelled it.

## 10. Device-only tests still required

- Launch on a real arm64 device; confirm `libmain.so` loads and the engine reaches
  its first frame (logcat tag `GeneralsZH` / `SDLActivity` / `AndroidCrashHandler`).
- Confirm DXVK (Vulkan) and the GLES fallback both render correctly.
- Confirm touch controls, audio (OpenAL/OpenSL), and GeneralsOnline login/LAN.
- Confirm user-selected game folder (SAF / `MANAGE_EXTERNAL_STORAGE`) persists across
  restarts and the engine finds its `.big` data.
- Force a load failure (e.g. remove a staged `.so`) and confirm the new
  `FAILED to load native library …` logcat line appears with the `nativeLibraryDir`
  listing.
- Confirm the Vulkan validation layer and custom-driver (adrenotools) paths behave as
  before.