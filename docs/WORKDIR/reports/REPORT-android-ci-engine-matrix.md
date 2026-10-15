# REPORT: Android CI built one engine, the launcher ships four

Date: 15/10/2026
Branch: `claude/ui-redesign-dashboard-drawer`
Scope: `.github/workflows/build-android.yml`, `scripts/build/android/package-android-zh.sh`

## 1. Root cause (file + step)

**`.github/workflows/build-android.yml`, step `Build z_generals (libmain.so) + DXVK d3d8/d3d9`.**

That single step was the entire engine build:

```yaml
cmake --build build/${PRESET} --target z_generals dxvk_d3d8_install \
  main_hook file_redirect_hook gsl_alloc_hook hook_impl -- -k 0
```

`z_generals` is Zero Hour, and with the preset's default (`SAGE_HIGH_FPS_SIM=OFF`,
`cmake/config-build.cmake:26`) it is the **30 Hz** engine. So every CI APK carried
exactly one engine, `libmain.so`, while the launcher offers four
(`SetupActivity.engineLibraries()`):

| Launcher choice | Library requested | Built by CI before the fix |
|---|---|---|
| Zero Hour, 30 Hz | `libmain.so` | yes |
| Zero Hour, 60 Hz | `libmain60.so` | **no** |
| Generals, 30 Hz | `libgenerals.so` | **no** |
| Generals, 60 Hz | `libgenerals60.so` | no (not shipped by design) |

Two things made the gap invisible:

1. The local pipeline had already moved on. `scripts/build/android/build-local-sandboxed.sh`
   builds `z_generals g_generals` and asserts `Generals/Code/Main/libgenerals.so`
   exists; `scripts/build/android/build-dual-hz.sh` drives the two-pass 30/60 Hz
   mechanism. CI's build step was a stale copy of the older, single-target one.
2. `scripts/build/android/package-android-zh.sh` staged the base-game engine with
   `if [[ -n "${BASE_GAME_LIB}" ]]` — a build tree without `libgenerals.so`
   packaged without it and said nothing. Nothing downstream checked either: the
   only engine verification in the workflow (`Verify artifacts`) and the only APK
   check (`Verify APK contains every library libmain.so needs`) both looked at
   `libmain.so` alone.

Device-visible effect of an APK built that way: with Generals selected, the launch
dies in `System.loadLibrary("generals")`; with the Simulation Rate set to 60 Hz,
`GeneralsZHActivity.getLibraries()` finds no `libmain60.so` and silently loads the
30 Hz engine instead — which is a *wrong behaviour*, not a crash.

## 2. What changed and why

### `.github/workflows/build-android.yml`

| Change | Why |
|---|---|
| New step `Build the 60 Hz engine (pass 1 of 2)` — reconfigure the same tree with `-DSAGE_HIGH_FPS_SIM=ON`, build `z_generals`, `cp` the result to `build/libmain60.so` | The tick rate is compile-time (`WWSyncPerSecond`, `GameLogic::m_frameLegacy`); `build-dual-hz.sh` is the project's mechanism and it configures twice. Pass 1 must run first because both passes write the same `libmain.so` — the last pass is what stays on disk, and the 30 Hz engine is the launcher's fallback. |
| New step `Build the 30 Hz engines (pass 2 of 2) + DXVK d3d8/d3d9` — reconfigure with `-DSAGE_HIGH_FPS_SIM=OFF`, build `z_generals g_generals dxvk_d3d8_install main_hook file_redirect_hook gsl_alloc_hook hook_impl` | `g_generals` is the base game (`libgenerals.so`). The DXVK/hook targets are unchanged and still named explicitly. Pass 1 builds only `z_generals`: the base game is not shipped at 60 Hz, so compiling it in that pass would be a full extra base-game build for a library nothing loads. |
| `Strip debug symbols` now also strips `build/libmain60.so` and `Generals/Code/Main/libgenerals.so` | Each is another ~40 MB unstripped engine; DXVK is still deliberately excluded (stripping it crashes its draw path). |
| `Package APK` env gains `GX_SECOND_GAME_LIB` (the pass-1 stash) and `GX_REQUIRE_ALL_ENGINES=1` | Without the first the packaging script has no second engine; the second turns an incomplete stage into a hard failure. |
| New step `Verify the three engines (ELF AArch64 + 30/60 Hz identity)` | See §5; "the build succeeded" is not evidence that three engines exist, nor of *which* engine each one is. |
| `Verify APK contains every library libmain.so needs` → `Verify APK carries all three engines and every library they need` | Now checks presence, size, engine identity and DT_NEEDED for **each** of the three engines, not just `libmain.so`. |
| New step `Verify APK version and signature` | Reads `versionName`/`versionCode` back out of the APK (`aapt2 dump badging`) and compares against the dispatch inputs, or the committed defaults in `android/app/build.gradle`; verifies the signature with the SDK's `apksigner` and states plainly that the build is debug-signed. |
| `Publish GitHub Release` body now appends a signing note | The published APK is signed with the in-repo debug keystore; a release reader must not be left thinking otherwise. |

Everything else — caches, vcpkg pin, NDK/Gradle pins, symbol table, artifact
uploads, cleanup, release publishing, the `workflow_dispatch` inputs
(`preset`, `version_name`, `version_code`, `create_release`, `release_notes`) — is
untouched.

### `scripts/build/android/package-android-zh.sh`

| Change | Why |
|---|---|
| `libgenerals.so` is now required: missing ⇒ `ERROR ... the g_generals target was never built` and exit 1 | It comes out of the same build pass as `libmain.so`; there is no legitimate build tree that has one and not the other. |
| A missing second Zero Hour engine is no longer silent: a `WARNING` names the consequence, and `GX_REQUIRE_ALL_ENGINES=1` (CI) makes it a hard error | A one-engine APK is a legitimate local build, but it must not be an *accidental* one. |
| New "Engines staged: …" summary plus a required-`libmain.so`/`libgenerals.so` re-check right after staging | Names what the APK is about to carry, at the layer that decides it. |

`GX_SECOND_BASE_GAME_LIB` (`libgenerals60.so`) is preserved exactly as it was, for
the local dual-Hz build; CI neither sets it nor ships that library.

## 3. Actual output paths (verified against the files, not assumed)

| Stage | Path | Evidence |
|---|---|---|
| `z_generals` 60 Hz | `build/android-vulkan/GeneralsMD/Code/Main/libmain.so` → stashed `build/libmain60.so` | `GeneralsMD/Code/Main/CMakeLists.txt` sets `OUTPUT_NAME main` for Android; `build-dual-hz.sh` reads the same path and the same stash name |
| `z_generals` 30 Hz | `build/android-vulkan/GeneralsMD/Code/Main/libmain.so` | as above; the existing `Verify artifacts` step already used this exact path |
| `g_generals` 30 Hz | `build/android-vulkan/Generals/Code/Main/libgenerals.so` | `Generals/Code/Main/CMakeLists.txt` sets `OUTPUT_NAME generals`; `build-local-sandboxed.sh` asserts this same path |
| In the APK | `lib/arm64-v8a/{libmain.so,libmain60.so,libgenerals.so}` | `android/app/build.gradle` `jniLibs.srcDirs = ['src/main/jniLibs']`, `useLegacyPackaging true`; confirmed by unpacking a locally packaged APK (§6) |

CMake preset for every pass: `android-vulkan` (`RTS_BUILD_GENERALS=ON`,
`RTS_BUILD_ZEROHOUR=ON` by default, `ANDROID_ABI=arm64-v8a`,
`ANDROID_PLATFORM=android-28`). No preset was added, renamed or removed.

## 4. How the two Zero Hour engines and the base game stay intact

* **30 Hz is still the fallback.** `GeneralsZHActivity.getLibraries()` selects
  `libmain60.so` only when the Simulation Rate is 60 Hz *and* the file exists, so
  the 30 Hz pass staying last keeps the default behavior identical.
* **The 60 Hz engine is provably the 60 Hz engine.** `NextGenMP_defines.h` bakes an
  ASCII client id into the engine: `gen_online_60hz` with `SAGE_HIGH_FPS_SIM=ON`,
  `gen_online_30hz` with it OFF. The workflow now asserts, on the built binaries
  *and* on the packaged copies: `libmain.so` has `gen_online_30hz` and not
  `gen_online_60hz`; `libmain60.so` the reverse. That catches both a failed
  reconfigure (identical binaries, checked by sha256 as well) and a swapped pair.
* **The base game cannot be a mislabelled Zero Hour build.** There is no
  GeneralsOnline source tree under `Generals/` at all, so `libgenerals.so` must
  contain *neither* client id; containing one fails the build. Its sha256 must also
  differ from `libmain.so`'s.
* **One pass, both games.** `g_generals` and `z_generals` are built in the same
  30 Hz configure, so Core, the base game and Zero Hour cannot be built with
  disagreeing definitions — the layout hazard `CMakeLists.txt:99` warns about.
* Runtime selection code (`SetupActivity.engineLibraries`, `getSimHz`,
  `GeneralsZHActivity.getLibraries`) was **not** touched; it already resolved the
  names the build now produces.

## 5. Verification — what ran, what passed, what did not run

| Check | Result |
|---|---|
| YAML parse of the fixed workflow and the reference copy (PyYAML) + `bash -n` of every `run:` block | **passed**: 36 steps (fixed) / 33 (reference), 0 syntax failures |
| `bash -n` on `package-android-zh.sh` | **passed** |
| Packaging guard, missing `libgenerals.so` | **passed**: `ERROR ... the base game's engine is required ... the g_generals target was never built`, exit 1 (before the fix: silently skipped) |
| Packaging guard, base game present, no second engine | **passed**: warning naming the 60 Hz fallback, then the pre-existing required-library check fires (proves the flow continues) |
| Packaging guard, `GX_REQUIRE_ALL_ENGINES=1` with no second engine | **passed**: hard error naming `libmain60.so`, exit 1 |
| Full staging + Gradle packaging with a fabricated build tree carrying all three engines, `GX_REQUIRE_ALL_ENGINES=1`, `GX_ANDROID_VERSION_CODE=99999/9.9.9` | **passed**: `Engines staged: libmain.so libmain60.so libgenerals.so`, `BUILD SUCCESSFUL`, APK unpacked shows `lib/arm64-v8a/libmain.so`, `libmain60.so`, `libgenerals.so` (plus SDL3, SDL3_image, OpenAL, gamespy, DXVK d3d8/d3d9, the four adrenotools hook libs, `libc++_shared.so`, ANGLE) |
| Cross-compile of the three engines from source, and the new CI steps end to end | **NOT RUN**: needs the NDK cross-build of a ~1.6M-LOC engine (tens of minutes, vcpkg + DXVK submodule), i.e. the CI job itself. The owners' CI budget is not to be spent from here. |
| On-device launch of a real APK | **NOT RUN** |

The staged-APK run used **fabricated** `.so` files (random bytes at the expected
paths, plus the real NDK `libc++_shared.so` and the real SDL3 Java glue), so it
proves the packaging path and the in-APK locations — it says nothing about the
engines' correctness. That is what the three CI verification steps are for.

## 6. Files

| File | State |
|---|---|
| `.github/workflows/build-android.yml` | modified (minimal diff, annotated `@bugfix Android CI 15/10/2026`) |
| `scripts/build/android/package-android-zh.sh` | modified (mandatory base game, strict mode, engines summary) |
| `docs/build-reference/build-android-reference.yml` | **new** — the unmodified pre-fix workflow, deliberately outside `.github/workflows/` so GitHub never runs it |
| `docs/build-reference/README.md` | **new** — why the reference lives there, and how to diff it |
| `docs/WORKDIR/reports/REPORT-android-ci-engine-matrix.md` | **new** — this report |

Not touched: `build-dual-hz.sh`, `build-local-sandboxed.sh`, `build-android-zh.sh`,
`CMakePresets.json`, any `CMakeLists.txt`, `android/app/build.gradle`,
`AndroidManifest.xml`, and every Java/Kotlin source.

## 7. Signing

CI signs with `android/app/debug.keystore` (the committed debug key that lets
consecutive CI builds install as updates over each other). No production release
keystore and no signing secrets exist in this repository, so no build published
from here is store-grade signed. The new verification step reports the signer
certificate and warns explicitly, and the release body says so; it never prints a
key or password (there are none to print).

## 8. Open items before an on-device confirmation can be claimed

1. **The first real CI run is the proof.** The new `Verify the three engines` step
   has never executed against real engines; if `g_generals` fails to link in CI
   (it has only ever been built on a dev machine), that run will say so.
2. **`libgenerals.so`'s DT_NEEDED set is unknown until then.** If the base game
   needs a library the packaging script has never learned about, the new APK check
   fails the run — a real finding, and exactly the failure that used to reach a
   device instead.
3. **Build time.** Three engine builds instead of one (the 60 Hz pass's reconfigure
   invalidates the engine objects). The job's `timeout-minutes: 120` should hold on
   a warm ccache, but a cold-cache run is the slowest this job has ever been.
4. **The 60 Hz engine on a device** is not covered by any check here beyond its
   identity string and cross-play behavior; that still needs a phone and a PC
   client to confirm.
5. `apksigner`/`aapt2` come from `build-tools;35.0.0`, the version the workflow
   already installs; the step picks the newest installed build-tools, so a future
   bump cannot silently skip it.
