# Android app identity, 60 Hz default, launcher icon and system theme

**Date:** 08/11/2026
**Branch:** `New-app`
**Scope:** four launcher-only changes. No engine, networking or graphics source was touched.

---

## 1. Default engine simulation rate: 30 Hz → 60 Hz

### What changed

`android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java`

```java
static int getSimHz(android.content.Context ctx) {
    int hz = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .getInt(PREF_SIM_HZ, SIM_HZ_CROSSPLAY);   // was SIM_HZ_RETAIL
    return hz == SIM_HZ_CROSSPLAY ? SIM_HZ_CROSSPLAY : SIM_HZ_RETAIL;
}
```

Only the SharedPreferences **default** changed. The existing normalisation is untouched: any
stored value that is not exactly `SIM_HZ_CROSSPLAY` (60) resolves to `SIM_HZ_RETAIL` (30).

### Why nothing else was needed

- The Simulation Rate card (`buildSimRateSection`) reads its selected segment straight from
  `getSimHz(this)`, so a fresh install now shows 60 Hz selected with no further change.
- `GeneralsZHActivity.getLibraries()` already asks `SetupActivity.getSimHz(this)` and loads the
  60 Hz library only when it is present on disk, otherwise it keeps the 30 Hz one:

  ```java
  final String[] engines = SetupActivity.engineLibraries(this);   // {main, main60} or {generals, generals60}
  String engine = engines[0];
  if (SetupActivity.getSimHz(this) == SetupActivity.SIM_HZ_CROSSPLAY
          && new File(getApplicationInfo().nativeLibraryDir, "lib" + engines[1] + ".so").isFile()) {
      engine = engines[1];
  }
  ```

  So Zero Hour picks `libmain60.so` and the base game `libgenerals60.so`, and the fallback to the
  30 Hz library when the 60 Hz one is missing is preserved exactly as before.
- `scripts/build/android/build-dual-hz.sh` was **not** modified: it already builds both rates and
  packages all four libraries (`libmain`/`libmain60`, `libgenerals`/`libgenerals60`).
- No sync/multiplayer logic was touched. The default is a preference value, not a device refresh
  rate.

### Test

`SetupActivityHelperTest.simulationRateDefaultsToCrossplayAndOnlyAcceptsTheTwoKnownValues`
(renamed from `...DefaultsToRetail...`) asserts: no stored value → 60; stored 30 → 30; stored 60 →
60; a junk value → 30.

---

## 2. Application ID → `com.ports.generalsx`

### What changed

`android/app/build.gradle`

```groovy
applicationId 'com.ports.generalsx'   // was com.generalsx.zerohour
```

`namespace` and every Java `package`/`import` stay `com.generalsx.zerohour`: the namespace is the
**code** namespace (where `R` and the classes live), the applicationId is the **identity** the OS,
the Play Store and other apps see. They are allowed to differ, and renaming the package would move
every class and every manifest `.Name` reference for no functional gain.

### The one thing the applicationId is *not* free of: native paths

Several native files reach the launcher's private files directory by building
`/data/user/<uid>/<applicationId>/files/...` by hand — they run before `SDL_main` (the crash
handler, `GXLogging.h`) or are `Context`-less inline helpers (`GXSubtitles.h`,
`GXControlScheme.h`, `GXMouseMode.h`, `GXManagedMods.h`, `GXRemoteConfig.h`). That `<applicationId>`
segment **is** the applicationId, so changing the id without changing these paths silently breaks
logging-off, mouse mode, subtitles, control scheme, managed mods, remote config and the crash log.

The id now lives in one place, `Core/GameEngine/Include/Common/GXAndroidApp.h`:

```cpp
#define GX_ANDROID_APP_ID "com.ports.generalsx"
```

and the seven native sites use it. This is the smallest change that keeps the native paths and the
Gradle id from drifting apart again.

Files: `GXAndroidApp.h` (new), `GXLogging.h`, `GXSubtitles.h`, `GXManagedMods.h`,
`GXControlScheme.h`, `GXMouseMode.h`, `GXRemoteConfig.h`, `Core/Main/AndroidCrashHandler.cpp`.

### Other references updated

- `scripts/build/android/package-android-zh.sh` (3 paths echoed to the user).
- `docs/WORKDIR/reports/android-port-analysis/BUILD_REQUIREMENTS.md`: it stated
  "namespace / applicationId `com.generalsx.zerohour`"; now records the two as different.
- `NetworkTraceRedactTest`: its assertion hardcoded the old id in the external-files path; it now
  asserts against `app.getPackageName()`, which is what the device layout actually uses and is
  correct regardless of the id.

`com.generalsx.zerohour` still appears in `ANDROID_LAUNCHER_TESTING_20261106.md`,
`android-port-analysis/{ARCHITECTURE,PROJECT_OVERVIEW}.md`, the dev-blog entries and the
`build.gradle` comment; in each of those it names the **Java package / namespace** (still correct)
or is a dated snapshot of a past build, so it was left alone.

### Verified in the built APK

```
package: name='com.ports.generalsx' versionCode='10402' versionName='1.4.2'
```

- Merged debug manifest `package="com.ports.generalsx"`.
- Activities keep their class names: `launchable-activity: name='com.generalsx.zerohour.SetupActivity'`.
- FileProvider authority resolves to `com.ports.generalsx.fileprovider` (the manifest uses
  `${applicationId}`; the Java writes URIs with `getPackageName()`, so both sides agree).
- `versionCode`/`versionName` are unchanged (`10402` / `1.4.2`).

---

## 3. Launcher icon

### Source

`https://www.steamgriddb.com/icon/59649` — **"Icon for Command & Conquer: Generals – Zero Hour"**
by **pix0wl**, marked *Official* on SteamGridDB ("Extracted from game's EXE (Steam version)"),
uploaded 6 Jul 2024, 8 entries 16–256 in a single `.ico`. The original file was fetched from the
page's own download target (`https://cdn2.steamgriddb.com/icon/647a924986a26860d4813818f03672f5.ico`,
HTTP 200, 410 716 bytes, a real multi-size ICO) and its **256×256** frame used — not a thumbnail.

### What was replaced

The adaptive icon is `<background>` (a navy radial vector, unchanged) plus `<foreground>` and
`<monochrome>`. Both foreground and monochrome were regenerated for every density
(`mipmap-mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi`), each on a 108dp canvas:

- the 256×256 source is scaled so its farthest opaque pixel lands at **32.0 dp** from the centre,
  strictly inside the 33dp safe circle of the 66dp safe zone (measured per density: 31.97–32.32dp),
  so no launcher mask clips a wing;
- the monochrome layer is the same silhouette as white, alpha preserved (Android 13+ themed icons);
- `AndroidManifest.xml` (`android:icon="@mipmap/ic_launcher"`) is unchanged and still valid, and
  `SetupActivity` remains the only launcher activity.

### Verified in the built APK

`aapt2 dump badging` reports `application-icon-*` → `res/mipmap-anydpi-v26/ic_launcher.xml` for
every density. Each packaged PNG is pixel-identical to the committed file
(`res/mipmap-<density>-v4/ic_launcher_{foreground,monochrome}.png`), and all five densities carry
both layers.

Attribution is recorded in the header comment of
`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.

---

## 4. Default appearance follows the system

### What changed

`android/app/src/main/java/com/generalsx/zerohour/ThemeHelper.java`

```java
static int getMode(Context context) {
    int stored = context.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(PREF_MODE, SYSTEM);                              // was STANDARD
    return stored >= STANDARD && stored <= SYSTEM ? stored : SYSTEM;   // was STANDARD
}
```

Only the **default** and the out-of-range fallback changed to `SYSTEM`. A stored choice always
wins, so an existing install keeps whatever the player picked.

### How system detection works

`ThemeHelper.palette()` already resolved `SYSTEM` against the phone and was not changed:

```java
int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
return night == Configuration.UI_MODE_NIGHT_NO ? DAY : STANDARD;
```

- Light mode → `DAY` palette.
- Dark mode → `STANDARD` palette.
- `NAVY`, `DAY` and `STANDARD` selected by hand are returned as-is (no system lookup).
- Every launcher Activity calls `ThemeHelper.apply(this)` in `onCreate` and does **not** list
  `uiMode` in `android:configChanges`, so a system light/dark switch recreates the Activity and the
  palette is re-resolved automatically.

Manual selection under **Interface → Appearance** (`SetupActivity.buildThemeSection`) is
unchanged and still writes `launcher_theme_mode`; the accent picker on top is unchanged. Nothing
here touches in-game rendering.

### Test

New `ThemeHelperTest` (7 cases): no stored value → SYSTEM; SYSTEM + light → DAY; SYSTEM + dark →
STANDARD; a stored manual choice is preserved; a manual choice ignores the system night flag; an
unknown stored value falls back to SYSTEM; `SYSTEM == UI_MODE_NIGHT_MASK` matches the API constant.

---

## Verification

Environment: JDK 17 (Temurin 17.0.13), Gradle 8.9, Android SDK platform 35 + build-tools 35.0.0,
SDL3 Java glue staged by `scripts/build/android/stage-sdl-java.sh`.

| Check | Command | Result |
|---|---|---|
| Launcher APK builds | `gradle :app:assembleLauncherOnly` | **BUILD SUCCESSFUL** |
| Unit tests | `gradle :app:testDebugUnitTest` | **314 tests, 0 failures** (7 new) |
| Lint | `gradle :app:lintDebug` | **0 errors** (150 pre-existing warnings) |
| Translations | `python3 scripts/qa/android/check-translations.py` | green, 13 locales × 704 strings |
| app id in APK | `aapt2 dump badging` | `com.ports.generalsx` |
| Launcher activity | `aapt2 dump badging` | `com.generalsx.zerohour.SetupActivity` |
| FileProvider authority | `aapt2 dump xmltree` | `com.ports.generalsx.fileprovider` |
| Adaptive icon | `aapt2 dump badging` | `mipmap-anydpi-v26/ic_launcher.xml` at all densities |
| Icon layers in APK | zip compare | foreground + monochrome present and identical, all densities |
| Native headers | `g++ -fsyntax-only` (with and without `__ANDROID__`) | clean |

### Not executed (and why)

- **Full engine APK.** Building `libmain`/`libmain60`/`libgenerals`/`libgenerals60` needs the
  Android NDK and the DXVK fork submodule; neither is present in this environment, and the task
  explicitly scopes the build check to `assembleLauncherOnly`. The engine-selection logic is
  unchanged apart from the preference default, and its fallback path is covered by the unit tests.
- **On-device icon/theme rendering.** No Android device or emulator is available. The icon was
  verified by measuring the safe-zone fit and by decoding the packaged PNGs; the theme by unit
  test. The Activity-recreate-on-`uiMode` behaviour is inferred from the code paths, not observed
  on a device.

## Modified files

| File | Change |
|---|---|
| `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java` | `getSimHz()` default → 60 Hz |
| `android/app/src/main/java/com/generalsx/zerohour/ThemeHelper.java` | `getMode()` default/fallback → SYSTEM |
| `android/app/build.gradle` | `applicationId` → `com.ports.generalsx` |
| `Core/GameEngine/Include/Common/GXAndroidApp.h` | new: `GX_ANDROID_APP_ID` |
| `Core/GameEngine/Include/Common/GX{Logging,Subtitles,ManagedMods,ControlScheme,MouseMode,RemoteConfig}.h` | use `GX_ANDROID_APP_ID` |
| `Core/Main/AndroidCrashHandler.cpp` | use `GX_ANDROID_APP_ID` |
| `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | new icon + attribution |
| `android/app/src/main/res/mipmap-*/ic_launcher_{foreground,monochrome}.png` | regenerated, 5 densities |
| `android/app/src/test/java/com/generalsx/zerohour/SetupActivityHelperTest.java` | 60 Hz default |
| `android/app/src/test/java/com/generalsx/zerohour/NetworkTraceRedactTest.java` | id-agnostic assertion |
| `android/app/src/test/java/com/generalsx/zerohour/ThemeHelperTest.java` | new, 7 tests |
| `scripts/build/android/package-android-zh.sh` | private-files path |
| `docs/WORKDIR/reports/android-port-analysis/BUILD_REQUIREMENTS.md` | namespace/applicationId now recorded as different |
| `docs/DEV_BLOG/2026-11-DIARY.md` | this session's entry |
| `docs/WORKDIR/reports/ANDROID_APP_IDENTITY_60HZ_ICON_THEME_20261108.md` | this report |

---

## Independent re-verification (second pass)

Re-checked from the committed tree on a fresh machine (no prior session state), not from the
earlier session's notes.

- **Icon fidelity to the source, measured again:** committed foreground vs the 256×256 frame of
  `https://cdn2.steamgriddb.com/icon/647a924986a26860d4813818f03672f5.ico` — silhouette IoU
  **0.985**, mean absolute RGB difference **0.9/255** over the shared opaque area, 0 pixels added,
  ~139 of 15390 lost. Against the repository's own retail icons (`Generals/Code/Main/Generals.ico`,
  `Core/Tools/Launcher/GeneralsZH.ico`) the source scores IoU 0.83–0.89, confirming it is the
  genuine Zero Hour emblem and not a redraw.
- **Contrast on the navy background** (the one open question): mean |Δluminance| between emblem and
  background is **112.7**; only 4.8% of emblem pixels fall below 25 and 2.1% below 12, and those sit
  on the mid-tone edges of the metal, not on the black regions — the black (~22% of the emblem)
  borders bright silver/gold/blue, not the background. Nothing is hidden.
- **Safe zone:** farthest opaque pixel at **32.0–32.4 dp** from centre on all five densities —
  inside the 33 dp safe circle of the 66 dp safe zone.
- **Fresh `:app:assembleLauncherOnly` build** (JDK 21, Gradle 8.9, AGP 8.5.2, SDK 35):
  `BUILD SUCCESSFUL`. `aapt2 dump badging` → `package: name='com.ports.generalsx'`,
  `launchable-activity: name='com.generalsx.zerohour.SetupActivity'`, and every
  `application-icon-*` → `res/mipmap-anydpi-v26/ic_launcher.xml`. The manifest carries
  `com.ports.generalsx.fileprovider` and no bare `com.generalsx.zerohour` package string; the
  variant still contains zero `.so` files. All ten packaged icon PNGs are byte-identical (sha256) to
  the committed ones.
- **Unit tests, re-run:** 314 tests, 0 failures, including the 61 covering these four changes
  (`SetupActivityHelperTest`, `ThemeHelperTest`, `LauncherSmokeTest`, `NetworkTraceRedactTest`).
- **Native app-id paths:** `grep -rn 'com\.generalsx\.zerohour' Core Generals GeneralsMD scripts
  --include='*.cpp' --include='*.h' --include='*.sh'` returns nothing; the id lives only in
  `GXAndroidApp.h`.

### One open item (not a regression)

The committed download artifact `apk/GeneralsXZH-android-generals-a48.apk` was **not** rebuilt by
this change: `git diff 3602822 HEAD -- apk/` is empty, so that APK still reports the old
`com.generalsx.zerohour` package and carries the previous icon. The branch sources and any APK built
from them are correct; only a full `scripts/build/android/build-dual-hz.sh` run (NDK + vcpkg + DXVK,
four engine builds) plus a commit of the result makes the shipped APK match. That build is out of
scope for a launcher-only change and is left to the owner.
