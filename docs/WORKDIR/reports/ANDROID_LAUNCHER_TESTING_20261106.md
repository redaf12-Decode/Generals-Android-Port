# Launcher-Only Android Testing: Architecture, Coverage and a Lightweight CI Job

GeneralsX @build Android CI 06/11/2026

## What this is

A GitHub Actions job that tests the Android launcher — its Java, its resources and its
translations — and packages a launcher-only APK, **without compiling a single line of C++**. It is
`.github/workflows/android-unit-tests.yml`, and it exists because the only Android workflow this
repository had (`build-android.yml`) has to compile four engines before it can produce anything at
all, which makes it useless as a fast check on a launcher change.

The findings below are what the audit turned up, what was actually built and verified, and what is
still not covered.

## 1. The launcher's real architecture

### Activities

Every class below is declared in `android/app/src/main/AndroidManifest.xml`; a test asserts each
one still loads by name, so a rename that misses the manifest fails the build.

| Activity | Role | Native library? |
|---|---|---|
| `SetupActivity` | The launcher's home screen: game folder, game choice, tick rate, settings, language, logs, updates | No |
| `GeneralsZHActivity` | The game surface; extends `SDLActivity` | **Yes** — `libmain.so` / `libmain60.so` via `SDLActivity.loadLibraries()` |
| `FolderPickerActivity` | Picks the game data directory | No |
| `TouchControlsActivity` | The touch-button editor | No |
| `LogViewerActivity` | Reads and shares the engine log | No |
| `ModManagerActivity` | Enables/disables mods | No |
| `ReplayCheckActivity` | Replay checksum comparison | No |
| `NetworkDiagnosticsActivity` | Network trace viewer | No |
| `GeneralsOnlineActivity` | GeneralsOnline account screen | No |

One activity is the whole native dependency. Everything else is Java over `SharedPreferences`,
files under the app's data directory, and XML resources. That asymmetry is what makes a
launcher-only build meaningful rather than a fiction.

### Support classes

`LocaleHelper` (language selection and application), `UiKit` (the widget factory every settings
screen is built from), `ThemeHelper` (palette and accent resolution), `InsetUtil`,
`TouchControlConfig` + the touch editor model, `UpdateManager` (signed manifest, engine delivery,
community data patch), `NetworkTrace` (log capture and redaction), `ModManager`.

### Settings persistence

`SharedPreferences` throughout: `generalszh_setup` for game folder, selected game and tick rate,
`gx_update` for the update channel and engine state. The per-game folder and the per-game user data
directory are separate, and both are covered by tests.

### Game-folder validation and permissions

`SetupActivity.isValidGameFolder(File, String)` and the nested-folder search
(`findGameFolder`) are pure filesystem logic — no native calls — and are tested directly.
`MANAGE_EXTERNAL_STORAGE` is requested and the launcher degrades to the app-scoped directory when
it is not granted.

### Dependencies on SDL3 Java sources

Exactly one: `GeneralsZHActivity extends SDLActivity`, and `SDLActivity` is
`org.libsdl.app.SDLActivity` from `android/app/src/main/java-sdl/`. That directory is **gitignored**
and staged at build time. Nothing else in the launcher touches SDL — `SetupActivity` and the other
seven screens are ordinary Android code.

This is the load-bearing fact for the whole exercise: the launcher compiles against SDL's *Java*
only, so staging eleven Java files is enough to build and test it, and none of SDL's C++ is
involved.

### Existing test infrastructure

Ten test classes for the touch editor already existed (`TouchLayoutEditorTest`,
`TouchEditorAppearanceTest`, …), run as JVM tests with Robolectric, with
`unitTests.includeAndroidResources = true` already set in `app/build.gradle`. There was no CI job
that ran them, and no tests at all for the launcher screens, the language handling, the update
parsing or the translations.

### Workflows, before this change

| Workflow | Trigger | Native? |
|---|---|---|
| `build-android.yml` | `workflow_dispatch` | Yes — CMake, NDK, vcpkg, DXVK, four engines |
| `build-linux.yml`, `build-macos.yml`, `build-windows.yml`, `build-linux-flatpak.yml` | dispatch / push | Yes |
| `ci.yml` | push, PR | No, but it does not touch `android/` |
| `release.yml`, `publish-release.yml`, `sign-update.yml`, `replay-tests.yml` | dispatch | Various |

Nothing ran the Android unit tests. Nothing built an APK without the engine.

### Build steps that require native libraries

Only one: `package-android-zh.sh` copies the CMake-built `.so` set into `android/app/src/main/jniLibs/`
and copies SDL's Java out of the CMake FetchContent tree. Both of those are *staging* steps, not
compilation, and only the first one needs a native build. That is the step this work removes the
need for, by staging the same Java from the SDL3 release tarball instead.

## 2. What is tested, and how

94 new tests across six classes, all passing; the 113 pre-existing touch-editor tests still pass
(207 total, 0 failures).

| Test class | Tests | What it actually exercises |
|---|---|---|
| `LocaleHelperTest` | 14 | Language tags, the picker's list, applying a locale, RTL for Arabic and Persian, fallback for an unknown tag |
| `SetupActivityHelperTest` | 21 | Folder validation for both games, nested-folder search, ambiguous and missing folders, per-game folder memory, tick-rate parsing, engine library names, `engineInstalled()` |
| `UpdateManagerTest` | 16 | Signed-manifest parsing, signature verification (tamper, wrong key, malformed), feature flags, channel selection, bundled-engine sequence |
| `NetworkTraceRedactTest` | 19 | Token redaction (JWTs, base64 with `+`/`/`/`=`, repeats, quoting) and the BIG directory-table bounds checks that guard the mod importer |
| `LauncherSmokeTest` | 14 | The manifest as declared and as resolved; `UiKit` widgets built against the real theme and real strings |
| `TranslationResourceTest` | 9 | Every locale against the base locale: same names, no empty value, printf arguments, escaped apostrophes, RTL locales present |
| **New total** | **94** | |

Deliberately real rather than simulated: the tests call the production methods
(`SetupActivity.isValidGameFolder`, `LocaleHelper.apply`, `UpdateManager.verify`,
`UiKit.card`, `NetworkTrace.redact`), read the production `strings.xml` files and the production
manifest, and resolve the production theme. There are no placeholder classes and no stand-ins for
production code.

Two things are worth calling out because they were found by writing the tests rather than by
reading the code:

- **Positional printf arguments are legitimately reordered.** Korean and Chinese move `%2$s` and
  `%3$s` to follow their own word order. A naive "same arguments in the same order" rule flags four
  correct translations as broken; the check compares the *set* of numbered arguments and keeps the
  order only for unnumbered ones, which is where a dropped argument is actually detectable.
- **`UiKit` requires the app theme.** Building a `MaterialCardView` or a
  `MaterialButtonToggleGroup` outside `Theme.GeneralsZHSettings` throws, which is why the widget
  tests use a themed context rather than the bare application one. That is a real constraint on
  anyone adding a widget later, not a test artifact.

### Static validation

`gradle :app:lintDebug` runs in the workflow. It had never been run against this project: it
reported **31 errors**, all of them in SDL3's vendored Java under `src/main/java-sdl/`
(`MissingPermission` × 29, `NewApi` × 1, `UnspecifiedRegisterReceiverFlag` × 1). They are scoped
out by path in the new `android/app/lint.xml`, so the same checks still fail the build for this
project's own sources. That scoping was verified rather than assumed: injecting a
`getLastKnownLocation` call (a `MissingPermission` error) into `InsetUtil.java` makes
`lintDebug` fail with "1 errors", and removing it returns the build to 0. With the exemptions in
place lint passes with 0 errors and 138 warnings; the warnings are left visible rather than
baselined, because a report is more useful than an ignore list.

### Translation validation

`scripts/qa/android/check-translations.py` enforces the AGENTS.md rules over all 13 locales (587
strings each): the same string names in every locale, no empty translation where English has text,
matching printf arguments, and escaped apostrophes (an aapt2 error). It is also expressed as
`TranslationResourceTest`, so it runs both as a script and as part of the JVM suite.

## 3. The SDL3 Java dependency

- **Version: SDL3 3.4.2**, read from `cmake/sdl3.cmake` (`SDL3_VERSION`), the same file CMake's
  `FetchContent` uses. The script also reads `SDL3_URL_HASH` from it and verifies the SHA-256 of
  the tarball before extracting anything.
- **`scripts/build/android/stage-sdl-java.sh`** downloads only the release tarball, extracts only
  `android-project/app/src/main/java/org/libsdl/app` (11 files, a few hundred KB), and applies
  `android/patches/sdl-android-gesture-nav-back.patch` (to `SDLActivity.java`) and
  `sdl-android-mouse-source-clicks.patch` (to `SDLSurface.java`) — the same files, patches and
  order `package-android-zh.sh` uses. It configures and compiles no C++.
- If `cmake/sdl3.cmake` is ever restructured so the version cannot be read, the script **fails**
  rather than staging a different SDL silently.
- No fake SDL classes were introduced anywhere. The real upstream files are compiled, and a patch
  that no longer applies fails the run.

## 4. The launcher-only variant

`launcherOnly` is a build type in `android/app/build.gradle`, initialised from `debug` (so it keeps
the debug signing config and the same `applicationId` and `versionCode`/`versionName`) with
`minifyEnabled false`. An `androidComponents.onVariants` block adds `**/*.so` to that variant's
`jniLibs` excludes.

Excluding there rather than by emptying `jniLibs.srcDirs` is deliberate: if a `.so` ever does get
staged, the variant still drops it, so the APK's contents do not depend on what happened to be
lying around.

Verified on a real build:

| Check | Result |
|---|---|
| `gradle :app:assembleLauncherOnly` | builds |
| `.so` files in the APK | **0** (924 entries) |
| `classes.dex` present | yes |
| `aapt2 dump badging` → `launchable-activity` | `com.generalsx.zerohour.SetupActivity` |
| Size | ~12.8 MB |
| Signing certificate | byte-identical to the debug APK's |
| Production `debug` build, same tree, with `.so` files staged | still ships `libmain.so` + `libmain60.so` |

The last row is the regression check: the variant did not change what the production build does.

### The native-loading question, answered

`GeneralsZHActivity extends SDLActivity`, and `SDLActivity.onCreate` calls `loadLibraries()`, which
`System.loadLibrary`s the engine. So the inheritance does permit a launcher-only APK, but only
because two guards were added in front of it:

1. `SetupActivity.engineInstalled(Context)` answers whether an engine is actually available. It
   checks `UpdateManager.activeEngineSeq()` first (an engine delivered by a signed update lives
   **outside** the APK, so a file check alone would wrongly refuse it), then looks for
   `libmain*.so` in `nativeLibraryDir`. It reads the directory — it never `dlopen`s anything.
2. `launchGameNow()` shows a dialog explaining the APK carries no engine instead of starting an
   activity that could only fail; and `GeneralsZHActivity.onCreate` redirects back to Setup if it
   is reached anyway, **before** `super.onCreate()` and therefore before any load attempt.

Both strings exist in all 13 locales. The guard is null-safe on `nativeLibraryDir` (null in a plain
JVM test, which is exactly the launcher-only situation).

This is a behaviour change on the production path, and it is deliberate: without it, a
launcher-only APK's Play button produces SDL's own "An error occurred while trying to start the
application" box, which says nothing about the actual problem. On a normal APK with an engine
present the guard passes and nothing changes. `GeneralsZHActivity` still declares
`android.intent.action.MAIN` (so a shortcut placed before the `LAUNCHER` category was removed still
resolves) but not `CATEGORY_LAUNCHER`, and a test asserts Setup is the only launcher entry.

### What cannot be verified here

**The APK has not been installed on a device or emulator.** This machine has no `/dev/kvm` and no
CPU virtualization flags, and no emulator package in the SDK, so an ARM64 system image cannot be
booted; there is also no attached device. What *is* verified is that the APK builds, is signed with
the same certificate as the debug build, declares `SetupActivity` as its launchable activity,
contains a `classes.dex`, and contains no native libraries. **Installation and the Setup screen
actually opening are unverified** and should be confirmed by installing
`app-launcherOnly.apk` on a device before this is relied on.

## 5. The workflow

`.github/workflows/android-unit-tests.yml` — Ubuntu latest, JDK 17 (temurin), Android SDK platform
35 + build-tools 35.0.0 (cmdline-tools pinned and SHA-1 checked, exactly as `build-android.yml`
does), Gradle 8.9 with `gradle/actions/setup-gradle` caching both the distribution and
`~/.gradle/caches`.

Triggers: `workflow_dispatch` and a `pull_request` on `main` limited to `android/**`,
`scripts/build/android/stage-sdl-java.sh` and the workflow itself. **No `push:` trigger**, matching
`build-android.yml`'s deliberate removal of one and AGENTS.md's local-builds-only instruction.
`permissions: contents: read`, and a `concurrency` group that cancels superseded PR runs.

Twelve steps: checkout → JDK → SDK → Gradle → stage SDL Java → unit tests → lint → translation
check → launcher-only APK → assert no `.so` → upload reports (`if: always()`) → upload APK.

Nothing in it runs `cmake --preset`, `cmake --build`, vcpkg, DXVK, `build-android-zh.sh`,
`build-dual-hz.sh` or `package-android-zh.sh`; no NDK is even installed. The only native-looking
artifact is the *absence* of `.so` files, which the workflow asserts.

Both uploads are `if: always()`, so a failing test or lint run still leaves its report behind —
that report is precisely what is needed when the step above went red.

### Cost

Measured locally, `clean` then the three Gradle tasks end to end: **~58 s** (30 s tests, 27 s lint,
21 s launcher APK, warm Gradle cache). `build-android.yml` documents a cold build at 30–60 minutes
(`docs/port/ANDROID_PORT.md`) and sets a 300-minute timeout, because it compiles four engines plus
DXVK. The launcher job has no native compile at all, so on a runner its cost is the JDK/SDK setup
and a Gradle dependency download that the cache removes after the first run.

## 6. What is not covered

- **No instrumentation tests.** Everything here is a JVM test. Robolectric exercises `UiKit`
  widget construction and manifest resolution but not a real Activity lifecycle, real layout
  measurement, real touch dispatch, or real permission dialogs.
- **`SetupActivity` itself is not instantiated in a test.** Only its static helpers are. Driving
  the Activity needs a device or emulator, which this environment cannot provide.
- **The game engine is untouched by all of this.** Anything that only fails once `libmain.so` is
  loaded is invisible here — by design, but worth stating.
- **RTL is checked at the configuration level** (`LocaleHelper` applies `LayoutDirection.RTL` for
  Arabic and Persian), not by measuring mirrored layouts.
- **`ThemeHelper`'s palette/accent resolution** is exercised indirectly through `UiKit`, not by
  its own test class.
- **The APK is not installed anywhere** (see above).

## 7. Future instrumentation

A `connectedDebugAndroidTest` job would close the biggest gap, and the launcher-only APK is the
right thing to run it against: it needs no engine, so an emulator image does not need the game
data. It would need a runner with KVM — `reactivecircus/android-emulator-runner` on
`ubuntu-latest` — and would cover Activity launch, the Setup screen's actual rendering, the folder
picker, and the language switch end to end. That is a separate workflow, deliberately not added
here: it costs emulator boot time on every run and cannot be verified locally at all.

## 8. Files

New:

- `.github/workflows/android-unit-tests.yml`
- `android/app/lint.xml`
- `scripts/build/android/stage-sdl-java.sh`
- `scripts/qa/android/check-translations.py`
- `android/app/src/test/java/com/generalsx/zerohour/`: `LocaleHelperTest.java`,
  `SetupActivityHelperTest.java`, `UpdateManagerTest.java`, `NetworkTraceRedactTest.java`,
  `LauncherSmokeTest.java`, `TranslationResourceTest.java`

Changed:

- `android/app/build.gradle` — the `launcherOnly` build type and its `jniLibs` exclusion
- `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java` — `engineInstalled()`,
  the `launchGameNow()` guard
- `android/app/src/main/java/com/generalsx/zerohour/GeneralsZHActivity.java` — the engine-absent
  redirect
- `android/app/src/main/res/values*/strings.xml` — `setup_engine_missing_title` and
  `setup_engine_missing_message` in all 13 locales
