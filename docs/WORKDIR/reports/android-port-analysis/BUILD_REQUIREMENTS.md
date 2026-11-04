# BUILD_REQUIREMENTS.md — Generals-Android-Port

Everything needed to go from this source tree to an installable APK, discovered from the files
themselves. **[FACT]** = read from a file or a build script; **[INFERENCE]** = derived.

The project's own canonical documents are `docs/port/ANDROID_PORT.md` §3 and
`docs/BUILD/ANDROID_SANDBOXED_LOCAL.md`; this report is a cross-check of those against the scripts,
not a replacement.

---

## 1. The build is two independent halves

**[FACT]** `android/settings.gradle` states it explicitly:

> "The C++ game builds with CMake (preset android-vulkan) **OUTSIDE Gradle**; this project only
> compiles the thin Java shell (SDLActivity + asset extraction) and packages the staged native
> libraries into an APK."

```
┌──────────────────────────────────────────────────────────────────────┐
│ HALF 1 — native (CMake + Ninja + NDK + vcpkg + meson for DXVK)       │
│   build-android-zh.sh  or  build-local-sandboxed.sh                  │
│   → build/android-vulkan/…/libmain.so, libgenerals.so, libdxvk_*.so, │
│     libSDL3.so, libSDL3_image.so, libopenal.so, libgamespy.so,       │
│     libadrenotools.so + 4 hook .so, libEGL_angle/libGLESv2_angle     │
│   build-dual-hz.sh runs that twice (SAGE_HIGH_FPS_SIM=ON then OFF)   │
│   → four engines: libmain[60].so, libgenerals[60].so                 │
└──────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ HALF 2 — Java + packaging (package-android-zh.sh → Gradle)           │
│   stages jniLibs/arm64-v8a/ + java-sdl/ + assets/                    │
│   applies android/patches/*.patch to the SDL3 Java glue              │
│   writes assets/engine_build.txt, assets/update_channel.txt          │
│   → gradle assembleDebug → app-debug.apk                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. Toolchain versions, exactly as pinned in the tree

| Tool | Version | Pinned in |
|---|---|---|
| JDK | **17** (`temurin`) | `.github/workflows/build-android.yml` "Set up JDK 17" |
| Gradle | **8.9** | workflow `GRADLE_VERSION: "8.9"`; `package-android-zh.sh` accepts `./gradlew` **or** system `gradle` |
| Android Gradle Plugin | **8.5.2** | `android/build.gradle` |
| compileSdk / targetSdk | **35** | `android/app/build.gradle` |
| minSdk | **28** | same, with a long comment explaining why it is not 24 |
| NDK | **27.2.12479018** | workflow `NDK_VERSION`, `build-local-sandboxed.sh` `NDK_VERSION` |
| `ANDROID_PLATFORM` | **android-28** | `CMakePresets.json` `android-vulkan`, `cmake/triplets/arm64-android.cmake` |
| `ANDROID_ABI` | **arm64-v8a** | preset + `abiFilters 'arm64-v8a'` |
| `ANDROID_STL` | **c++_shared** | preset; `libc++_shared.so` copied from the NDK sysroot |
| cmdline-tools | **13114758**, sha1 `5fdcc763…` | workflow + `build-local-sandboxed.sh` |
| CMake generator | **Ninja** | preset `"generator": "Ninja"` |
| Build type | **RelWithDebInfo** | preset |
| vcpkg commit | **`42e4e33e1505c9f47b58c21e0f557c1571b751ee`** | workflow `VCPKG_COMMIT`, `build-local-sandboxed.sh` |
| vcpkg manifest baseline | `533a5fda5c0646d1771345fb572e759283444d5f` | `vcpkg.json` `builtin-baseline` |
| ccache | any; `max_size=3G`, `compiler_check=content` | workflow, `build-local-sandboxed.sh` |
| Host tools | `ninja-build meson pkg-config glslang-tools ccache` | workflow apt step |
| Python | 3 (packaging sanity check) | `build-local-sandboxed.sh` final zip check |

**[FACT]** Gradle wrapper: `android/gradle/wrapper/` and `android/gradlew` **do not exist** on
`New-UI`. `package-android-zh.sh` therefore requires a system `gradle`:

```bash
elif command -v gradle >/dev/null 2>&1; then GRADLE_CMD="gradle"
else echo "ERROR: no gradle wrapper and no 'gradle' on PATH."
```

On the `claude/ui-redesign-dashboard-drawer` branch the wrapper **is** committed
(`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`), and the diff
`ui-redesign..New-UI` shows it as removed. **[INFERENCE]** the mirror/consolidation that produced
`New-UI` dropped it.

---

## 3. Dependencies — three distinct categories

### 3.1 Declared by vcpkg (`vcpkg.json`) — built from source at first configure

| Package | Platform | Features / notes |
|---|---|---|
| `zlib`, `glm`, `gli` | all | |
| `freetype` | `!windows` | text rendering |
| `fontconfig` | `!windows & !ios & !android` | **excluded on Android** |
| `openal-soft` | `!windows` | audio backend |
| `curl` | `!windows` | features `ssl`, `websockets` |
| `gamenetworkingsockets` | `android` | **override 1.6.0** — the multiplayer transport |
| `ffmpeg` | `ios \| android` | features `avcodec avformat swscale swresample`; **override 8.1.1 port-version 2**, plus helper port `ffmpeg-bin2c` 8.1.1 |

**[FACT]** `vcpkg-lock.json` exists (1 617 bytes) and is hashed into the CI cache key.

### 3.2 Fetched by CMake `FetchContent` (not vcpkg)

| Component | Tag | Fetched by |
|---|---|---|
| SDL3 | `release-3.4.2` | `cmake/sdl3.cmake` |
| SDL3_image | `release-3.4.0` | `cmake/sdl3.cmake` |
| openal-soft | `1.24.2` | `cmake/openal.cmake` |
| libadrenotools | via `cmake/adrenotools.cmake` | builds `libadrenotools.so` + 4 hook libraries |
| GamespySDK | via the compat library | produces `libgamespy.so` |

**[FACT]** `build-local-sandboxed.sh` pre-clones SDL3 / SDL3_image / openal-soft into
`/opt/fetchcontent-src/` and passes `-DFETCHCONTENT_SOURCE_DIR_SDL3=…` etc., because that sandbox
blocks raw HTTPS to GitHub. It also has to remove the empty `_deps/sdl3-src` placeholder and
symlink the real checkout, because DXVK's meson build reads a generated `sdl3.pc` whose
`includedir` points there.

### 3.3 Git submodules (`.gitmodules`)

| Path | URL | Branch | Used by the Android build? |
|---|---|---|---|
| `references/fbraz3-dxvk` | `github.com/fbraz3/dxvk` | `generalsx-macos-v2.6` | **Yes** — `SAGE_DXVK_USE_LOCAL_FORK=ON`; the Android build compiles DXVK from it |
| `references/fighter19-dxvk-port` | `github.com/Fighter19/CnC_Generals_Zero_Hour` | — | No (reference) |
| `references/jmarshall-win64-modern` | `github.com/jmarshall2323/CnC_Generals_Zero_Hour` | — | No (reference) |
| `references/thesuperhackers-main` | `github.com/TheSuperHackers/GeneralsGameCode` | — | No (upstream baseline) |
| `references/generals-online-client` | `github.com/GeneralsOnlineDevelopmentTeam/GameClient` | — | No (reference) |
| `GeneralsReplays` | `github.com/fbraz3/GeneralsXReplays` | — | No (replay tests) |

**[FACT]** `git submodule status` on `New-UI` shows all six as uninitialised (`-` prefix). The only
one the Android build needs is `references/fbraz3-dxvk`, and it needs its **own** submodules too
(SPIRV-Headers, Vulkan-Headers, libdisplay-info), which the superproject init does not recurse into:

```bash
git submodule update --init --depth 1 references/fbraz3-dxvk
git -C references/fbraz3-dxvk submodule update --init --depth 1
```

### 3.4 Committed binaries in the tree

| File | Size | Why committed |
|---|---|---|
| `Core/Libraries/Source/d3d8gles/angle-prebuilt/arm64-v8a/libGLESv2_angle.so` | 5.4 MB | Vulkan-only ANGLE built from the AOSP mirror with a hand-bootstrapped gn+ninja toolchain; not reproducible in CI |
| `…/libEGL_angle.so` | 40 KB | same |
| `android/app/debug.keystore` | 2 KB | fixed debug signing key so consecutive builds install over each other |
| `apk/*.apk`, `apk/testers/*.apk` | 680 MB | distribution |

**[FACT]** `.gitignore` has a blanket `*.so*` with an explicit negation for the ANGLE pair.

### 3.5 Fetched at packaging time, not in git (must be supplied or downloaded)

| Item | Fetcher | Destination | If missing |
|---|---|---|---|
| Fonts (`arial.ttf`, `arialbold.ttf`, `couriernew.ttf`, `timesnewroman.ttf`) | `scripts/build/ios/stage-fonts.sh` (Liberation, renamed) | `$GX_ANDROID_STAGING/fonts` → `assets/gamedata/fonts` | script fetches them |
| Default Vulkan driver (Mesa Turnip, `vulkan.ad07xx.so` 17 MB + `meta.json`) | `scripts/build/android/fetch-turnip.sh` | `$GX_ANDROID_STAGING/default_driver` → `assets/default_driver` | script fetches them |
| Vulkan validation layer | `scripts/build/android/fetch-vulkan-validation-layer.sh` | `$GX_ANDROID_STAGING/vulkan_validation` | **warning only** — build continues |
| `dxvk.conf`, `DefaultOptions.ini` | staged if present, otherwise **written inline** by `package-android-zh.sh` | `assets/gamedata/` | generated |

**[FACT]** In the sandboxed environment these three fetchers cannot reach GitHub release assets, so
they must be pre-staged (documented in `docs/BUILD/ANDROID_SANDBOXED_LOCAL.md`).

### 3.6 Generated during the build

| Artifact | Producer |
|---|---|
| `android/app/src/main/jniLibs/arm64-v8a/*.so` (18 libraries) | `package-android-zh.sh` |
| `android/app/src/main/java-sdl/org/libsdl/app/*.java` | copied from `build/android-vulkan/_deps/sdl3-src/android-project/app/src/main/java/org/libsdl/app` then patched |
| `android/app/src/main/assets/gamedata/{fonts,dxvk.conf,DefaultOptions.ini,Window}` | staged |
| `android/app/src/main/assets/default_driver/` | staged |
| `android/app/src/main/assets/engine_build.txt` | `git rev-list --count HEAD` |
| `android/app/src/main/assets/update_channel.txt` | written only when `GX_UPDATE_CHANNEL=test` |
| `build/android-vulkan/**`, `GeneralsXZH-android-local.apk` | CMake / packaging |
| `android/app/build/**` | Gradle (deleted before packaging on purpose) |

**[FACT]** The 18 libraries staged into the APK (verified by unzipping the committed 1.4.2 APK):

```
libmain60.so 40.40 MB   libmain.so 40.39 MB
libgenerals60.so 29.67 MB  libgenerals.so 29.66 MB
libGLESv2_angle.so 5.38 MB  libdxvk_d3d9.so 4.58 MB  libSDL3.so 1.93 MB
libdxvk_d3d8.so 1.85 MB  libc++_shared.so 1.71 MB  libopenal.so 1.10 MB
libgamespy.so 0.66 MB  libSDL3_image.so 0.22 MB  libadrenotools.so 0.04 MB
libEGL_angle.so 0.04 MB  libhook_impl.so 0.04 MB
libgsl_alloc_hook.so, libmain_hook.so, libfile_redirect_hook.so (~0)
```

### 3.7 Resources and assets bundled into the APK

**[FACT]** From the committed APK:

```
assets/gamedata/fonts/{arial,arialbold,couriernew,timesnewroman}.ttf   ~1.5 MB
assets/gamedata/Window/{GroupPanel.wnd, ExtrasMenu.wnd}                176 KB
assets/gamedata/dxvk.conf, assets/gamedata/DefaultOptions.ini
assets/default_driver/{meta.json, vulkan.ad07xx.so}                    17 MB
assets/engine_build.txt = "3500"
assets/update_channel.txt = "test"      (a test build; releases omit it)
```

**[FACT]** No game data (`.big` archives) ships. The user supplies it. `README.md` and
`docs/HOWTO/INSTALLATION.md` say so explicitly.

---

## 4. What the repository does **not** contain (and where it comes from)

| Needed | Present in the repo? | Source |
|---|---|---|
| Game `.big` archives | No | the user's own installation |
| Android SDK / NDK / platform 35 / build-tools 35 | No | `dl.google.com` |
| Gradle 8.9 | No wrapper | system install or Android Studio; CI uses `gradle/actions/setup-gradle` |
| vcpkg + the ports tree | No | `github.com/microsoft/vcpkg` at the pinned commit |
| SDL3 / SDL3_image / openal-soft sources | No | their GitHub release tags (FetchContent) |
| DXVK sources | gitlink only | the `references/fbraz3-dxvk` submodule |
| Fonts, Turnip driver, validation layer | No | `scripts/build/android/fetch-*.sh`, `scripts/build/ios/stage-fonts.sh` |
| `gradle-wrapper.jar` | No (removed on this branch) | Android Studio generates it, or use system Gradle |

---

## 5. In-repo content that the Android build does **not** use

**[INFERENCE]** from an exhaustive search for references in `CMakeLists.txt`, `CMakePresets.json`,
the packaging script and the Android workflow:

* `GeneralsOnlineRelease/` (341 MB), `flatpak/`, `ios/`, `resources/`, `Dependencies/MaxSDK`,
  `triplets/`, `tools/`, `GeneralsReplays/`, four of the five `references/` submodules.
* The Linux/macOS/Windows presets and their scripts (`scripts/build/linux/**`,
  `scripts/build/macos/**`, `scripts/build/ios/**` except `stage-fonts.sh`).
* `.github/workflows/{build-linux,build-linux-flatpak,build-macos,build-windows,ci,release,replay-tests}.yml`
  — these are inherited and, on this branch, target platforms the fork does not maintain.

---

## 6. Build procedure, step by step

### 6.1 Recommended (project instruction, `AGENTS.md`): local build only

```bash
# one-time
git submodule update --init --depth 1 references/fbraz3-dxvk
git -C references/fbraz3-dxvk submodule update --init --depth 1
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018
export VCPKG_ROOT=~/vcpkg          # bootstrap-vcpkg.sh run once

# build both engines (60 Hz first, then 30 Hz + package all four)
./scripts/build/android/build-dual-hz.sh
cp GeneralsXZH-android-local.apk apk/<name>.apk
```

Single-game test build (halves the wait):

```bash
GX_BUILD_GAMES=zh        ./scripts/build/android/build-dual-hz.sh
GX_BUILD_GAMES=generals  ./scripts/build/android/build-dual-hz.sh
```

Test channel (reads the `updates-test` branch):

```bash
GX_UPDATE_CHANNEL=test ./scripts/build/android/build-dual-hz.sh
```

Other useful switches read by the scripts:

| Variable | Effect |
|---|---|
| `GX_EXTRA_CMAKE` | extra `-D` for CMake (used by `build-dual-hz.sh` to set `SAGE_HIGH_FPS_SIM`) |
| `GX_SECOND_GAME_LIB`, `GX_SECOND_BASE_GAME_LIB` | the 60 Hz engines set aside by pass 1 |
| `GX_PREV_APK` | the APK the other game's engine is extracted from when `GX_BUILD_GAMES` is not `all` |
| `GX_ANDROID_STAGING` | fonts / default driver / validation layer staging dir |
| `GX_ANDROID_VERSION_CODE`, `GX_ANDROID_VERSION_NAME` | one-off version overrides |
| `GX_KEEP_SYMBOLS_DIR` | keeps the symbol tables of the built engines |

### 6.2 The lower-level path (`ANDROID_PORT.md` §3, option B)

```bash
./scripts/build/android/build-android-zh.sh
./scripts/build/android/package-android-zh.sh --install
```

### 6.3 Sandboxed environment (`build-local-sandboxed.sh`)

Eight explicit steps: apt ccache → Android SDK/NDK/platform-35/build-tools into `/opt/android-sdk`
→ ccache config → vcpkg bootstrap + sandbox patches → pre-clone the three FetchContent sources →
CMake configure → build `z_generals g_generals dxvk_d3d8_install main_hook file_redirect_hook
gsl_alloc_hook hook_impl` → verify + strip + package.

Post-build verification inside the script:

1. `llvm-readelf -h libmain.so` contains `AArch64`.
2. `libdxvk_d3d8.so` and `libdxvk_d3d9.so` exist.
3. `strings libdxvk_d3d9.so | grep "SDL3 WSI:"` — catches a silent SDL2-WSI fallback in DXVK.
4. `libgenerals.so` exists.
5. Everything except the two DXVK libraries is stripped with `llvm-strip`; optionally symbol tables
   are preserved.
6. `rm -rf android/app/build` before Gradle, to avoid stale entries in the APK.
7. A Python check warns if more than 2 MB of the APK belongs to no zip entry (stale incremental
   packaging).

### 6.4 Gradle step

```bash
cd android
gradle assembleDebug [-PandroidVersionCode=N] [-PandroidVersionName=X.Y.Z]
# → android/app/build/outputs/apk/debug/app-debug.apk
```

`package-android-zh.sh` passes `-PandroidVersionCode` / `-PandroidVersionName` only when
`GX_ANDROID_VERSION_CODE` / `GX_ANDROID_VERSION_NAME` are set; otherwise the committed values
(`versionCode 10402`, `versionName 1.4.2`) are used.

---

## 7. Gradle configuration, in full

`android/settings.gradle`
* `pluginManagement` repositories: `google()`, `mavenCentral()`, `gradlePluginPortal()`.
* `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`.
* `rootProject.name = "GeneralsXZH"`, one module `:app`.

`android/build.gradle`
* `com.android.application` 8.5.2, `apply false`.

`android/gradle.properties`
* `org.gradle.jvmargs=-Xmx2048m`
* `android.useAndroidX=true`, `android.nonTransitiveRClass=true`

`android/app/build.gradle`
* namespace / applicationId `com.generalsx.zerohour`
* `compileSdk 35`, `minSdk 28`, `targetSdk 35`, `abiFilters 'arm64-v8a'`
* fixed `signingConfigs.debug` from the in-repo `debug.keystore` (`android`/`androiddebugkey`/`android`),
  used by **both** `debug` and `release` (sideload distribution; Play is out of scope)
* `release { minifyEnabled false }`
* `sourceSets.main`: `java.srcDirs += 'src/main/java-sdl'`,
  `jniLibs.srcDirs = ['src/main/jniLibs']`, `assets.srcDirs = ['src/main/assets']`
* `packagingOptions.jniLibs`: `useLegacyPackaging true` (libadrenotools requires real files at
  `nativeLibraryDir`), `keepDebugSymbols += ['**/libdxvk_d3d8.so','**/libdxvk_d3d9.so']`
  (stripping them crashes DXVK's draw path)
* dependencies: `com.google.android.material:material:1.12.0`, `androidx.core:core:1.13.1`

**[INFERENCE]** Because `minifyEnabled false` and `assembleDebug` is used, the dex ships unshrunk.
`docs/WORKDIR/planning/PLAN-025_APK_SIZE.md` estimates 7–8 MB could be recovered by moving to an R8
release build — parked by the owner.

---

## 8. Signing

**[FACT]**

* Every build, debug or release, is signed with `android/app/debug.keystore`
  (store `android`, alias `androiddebugkey`, password `android`).
* Consequence: consecutive builds install **as updates over each other** provided `versionCode`
  strictly increases. `versionCode = major*10000 + minor*100 + patch` (1.4.2 → 10402).
* The keystore is a debug key with a public password; it is not usable for any store listing and the
  build file says so.

---

## 9. Verification steps that exist

| Check | Where |
|---|---|
| AArch64 ELF | `build-local-sandboxed.sh`, workflow "Verify artifacts" (with 5 retries — an I/O visibility race on the runner) |
| DXVK has SDL3 WSI | both |
| Every non-system `DT_NEEDED` of `libmain.so` is inside the APK | workflow "Verify APK contains every library libmain.so needs" |
| APK not bloated by stale zip entries | `build-local-sandboxed.sh` Python check |
| Exactly one APK in `apk/` and versionName match | `.github/workflows/publish-release.yml` |
| `libmain.so` + `libmain60.so` present in the APK | `publish-release.yml` |

**[FACT]** There is **no** unit test, instrumentation test or lint gate on `New-UI`:
`android/app/src/test/` and `androidTest/` do not exist, and neither `ci.yml` nor
`build-android.yml` runs a test task.

---

## 10. Release procedure (from `AGENTS.md` and `publish-release.yml`)

1. Bump `versionName` / `versionCode` in `android/app/build.gradle`.
2. `./scripts/build/android/build-dual-hz.sh` (both engines, no `GX_BUILD_GAMES`, no
   `GX_UPDATE_CHANNEL`).
3. Commit the APK as the **single** file in `apk/`, and `notes.md` (English only) plus the four
   symbol tables under `docs/releases/v<version>/`.
4. Push to `main`.
5. `Actions → Publish Android Release` with the version — it builds nothing, it only validates and
   publishes (seconds of budget).
6. Mirror to GitVerse: `./scripts/build/android/publish-apk-gitverse.sh --release <version>`.

**[FACT]** Steps 5–6 require a `main` that carries the release; on this fork `main` currently holds a
different (older) project shape, so the release path is not usable as-is on `New-UI`.

---

## 11. Known build-side gaps (evidence in `ISSUES_AND_RISKS.md`)

| Gap | Evidence |
|---|---|
| CI builds **only** Zero Hour | `build-android.yml` target list is `z_generals dxvk_d3d8_install main_hook file_redirect_hook gsl_alloc_hook hook_impl`; `g_generals` appears nowhere in the file |
| CI therefore cannot produce a release-shaped APK | `package-android-zh.sh` packages `libgenerals.so` only if it exists; `publish-release.yml` requires `libmain60.so` in the APK, which a single-pass CI build never produces |
| No Gradle wrapper | `android/gradlew` absent |
| 680 MB of APKs in git | `apk/` + `apk/testers/` |
| `android-staging/` must be pre-populated offline | `build-local-sandboxed.sh` comments; fonts, Turnip, validation layer come from GitHub release assets |
| Cold first configure is 30–60 min | `ANDROID_PORT.md` §3 |
