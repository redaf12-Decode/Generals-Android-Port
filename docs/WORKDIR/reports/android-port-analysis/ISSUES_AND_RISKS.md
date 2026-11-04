# ISSUES_AND_RISKS.md — Generals-Android-Port, branch New-UI

Defects, duplication, dead code, resource handling, concurrency, performance, compatibility, build
warnings and test coverage.

**Confidence levels used below:**

* **[CONFIRMED]** — the code says so and the consequence follows directly. Quoted evidence.
* **[LIKELY]** — strongly indicated by the code; not exercised to be certain.
* **[SUSPECTED]** — a plausible risk that the code does not settle either way.

**Nothing below is asserted as a bug without a quoted line or a measured fact.**

---

## 1. Test coverage

**[CONFIRMED]** There are **no tests at all** on `New-UI`:

```
$ git ls-tree -r --name-only HEAD android | grep -c 'test/'
0
```

* `android/app/src/test/` and `android/app/src/androidTest/` do not exist.
* `android/app/build.gradle` declares no `testImplementation` or `androidTestImplementation`.
* `.github/workflows/build-android.yml` runs no test task; `.github/workflows/ci.yml` runs only
  Linux/macOS builds and replay tests, neither of which touches the launcher.

**Risk.** The launcher is 14 853 lines of Java with no regression net. The most fragile areas —
`ModImportService` (path traversal, zip bombs, bounds), `UpdateManager` (signature verification,
serial monotonicity, library SHA-256 matching), `SetupActivity`'s BIG-header parser — are exactly
the code where a mistake is silent and a test would be cheap.

**[CONFIRMED]** The project's own planning documents accept this implicitly: `PLAN-025` proposes R8
with "keep rules for everything native code calls through JNI (`org.libsdl.app.**`,
`GeneralsZHActivity` callbacks such as `setLanMulticastLock`, `showTextEditor`, the text-editor
natives)" — a list maintained by hand because no test would catch a missing keep rule.

---

## 2. `SetupActivity` — a 4 529-line god class

**[CONFIRMED]**

| Metric | Value |
|---|---|
| Lines | 4 529 (of 14 853 Java lines = 30 %) |
| `catch` blocks | 52 (the next largest file has 14) |
| `getString(R.string.setup_…)` references | 244 |
| `new Thread(...)` | 4 direct + others via helpers |
| Sections built | Home, Graphics, Interface, Tools, Help (each with 3–7 sub-cards) |

**Consequences, each visible in the code.**

* **[CONFIRMED] A bug caused by the size is documented in the file itself** —
  `SetupActivity.java:2769`: *"This still pointed at the old internal `<filesDir>/.local/share/...`
  copy, so the text size was saved where the game never looks and every value looked the same in
  game."* `optionsIniFile()` was fixed; the same class still owns the marker writers, the BIG
  parser, the driver installer and the network checks.
* **[LIKELY] Duplication has accumulated**: three near-identical `Options.ini` read-modify-write
  methods (`writeUiScalePercent`, `writeInterfaceScalePercent`, `writeUpscaleMode`), four
  near-identical slider stylings, and the "seed from `DefaultOptions.ini` if new" comment repeated
  in two of them.
* **[SUSPECTED] Merge friction**: the file is touched by every feature, so any two concurrent
  branches conflict in it.

---

## 3. Dead code, unused parameters, redundancy

| Item | Evidence | Class |
|---|---|---|
| Unused parameter | `SetupActivity.java:3295` — `private static boolean bigContainsEntry(File big, String wantedLowerSlash, int unusedDepth)`, and the name itself says `unusedDepth` | **[CONFIRMED]** |
| Same file, three overlapping BIG helpers | `bigArchiveHasEntry`, `bigContainsEntry(File,String)`, `bigContainsEntry(File,String,int)`, `forEachBigEntry` | **[CONFIRMED]** |
| `isOurLanguagePack` | 2 occurrences (definition + 1 call) — used once | **[CONFIRMED]** minor |
| `sameFileContents` | 2 occurrences | **[CONFIRMED]** minor |
| `removeRetiredPortStringFiles` | 3 occurrences (definition + calls) — legitimately used | — |
| `syncEngineWindowOverrides` | 2 occurrences | **[CONFIRMED]** minor |
| **Cache wipe broader than intended** | `ModManagerActivity.java:104-110` deletes **every** `*.zip` in `getCacheDir()`, with the comment "a28 zipped mods into the cache to share them" — but the predicate is `name.endsWith(".zip")` with no prefix filter | **[CONFIRMED]** |
| Duplicated `pc_crossplay` gate | `SetupActivity.buildGeneralsOnlineSection` and `GeneralsOnlineActivity.buildUi` both contain `if (UpdateManager.featureEnabled(this, "pc_crossplay", false))` around the same string | **[CONFIRMED]** |
| `TODO` | Exactly one in the whole launcher: `GeneralsOnlineActivity.java:894` — "also DELETE /User/{user_id} server-side like the reference" | **[CONFIRMED]** |

**[CONFIRMED]** No `FIXME`, `XXX` or `HACK` anywhere in the launcher's Java.

---

## 4. Coupling and single-source-of-truth problems

### 4.1 `userDataDir` has two definitions and one wrong assumption

**[CONFIRMED]**

```java
// DataPackInstaller.java:127 — "Where the engine looks: BuildUserDataPathFromRegistry's Android branch."
static File userDataDir() {
    return new File(Environment.getExternalStorageDirectory(),
        "Generals/Command and Conquer Generals Zero Hour Data");
}
```

```java
// SetupActivity.java:124 — Generals branch hardcodes a different leaf
return DataPackInstaller.userDataDir();      // Zero Hour
…                                             // Generals: its own hardcoded leaf
```

Consumers that therefore always read the **Zero Hour** folder:

* `ReplayCheckActivity.java:187` — `new File(DataPackInstaller.userDataDir(), "Replays")`
* `ReplayCheckActivity.java:207, 284` — the result file
* `LogViewerActivity.java:274`
* `DataPackInstaller.communityPatchFile()` — the community data patch

**[CONFIRMED] This is a defect, not an ambiguity.** The engine's Android user-data path is
**per game**. `SDL3Main.cpp:88-95` defines it by build:

```c
#if RTS_ZEROHOUR
#define GX_USERDATA_LEAF "Command and Conquer Generals Zero Hour Data"
#else
#define GX_USERDATA_LEAF "Command and Conquer Generals Data"
#endif
…
snprintf(userDataDir, sizeof(userDataDir), "%s/" GX_USERDATA_LEAF, generalsRoot);   // line 748
setenv("GENERALSX_USERDATA_DIR", userDataDir, 1);
```

and `GlobalData::BuildUserDataPathFromRegistry()`'s `__ANDROID__` branch
(`GeneralsMD/Code/GameEngine/Source/Common/GlobalData.cpp:1673-1700`) reads exactly that variable.
`SetupActivity.userDataDir()` already models this correctly with its two leaves; the helper that
four other classes use does not.

**Impact. [CONFIRMED]** With the base game selected:

* the Replay check lists **Zero Hour's** replays (`<…>Zero Hour Data/Replays`) instead of
  `<…>Generals Data/Replays`;
* its result file is written to and read from Zero Hour's folder;
* the log viewer's user-data shortcut opens Zero Hour's folder;
* the GeneralsOnline community patch, which is a Zero Hour artefact, is still resolved from Zero
  Hour's folder — which is correct by accident, but for a reason the code does not state.

**Fix shape.** `DataPackInstaller.userDataDir()` should take the game (or delegate to
`SetupActivity.userDataDir()`), and the three consumers should pass the selected game. The
community patch's Zero-Zhour-only nature then needs to be explicit.

### 4.2 The marker-file interface has no schema

**[CONFIRMED]** 20+ marker files, each with an implicit format and two independent readers (see
`ARCHITECTURE.md` §3.2). A rename on one side is a silent no-op on the other. No file lists them.

### 4.3 Engine version coupling

**[CONFIRMED]** `UpdateManager.librariesMatch` / `dependenciesStillMatch` / `depsStamp` exist
precisely because a downloaded engine must be built against the same libraries as the installed
APK. The engine update path therefore silently stops working whenever any library in the APK
changes — including a change made for an unrelated reason.

### 4.4 Tick-rate coupling

**[CONFIRMED]** From `GeneralsZHActivity.getLibraries()`: *"WWSyncPerSecond is an enum constant, so
it is baked into every static_assert, array bound and derived timing constant at compile time, and
GameLogic declares m_frameLegacy/m_frameLegacyLast behind the same macro — a build that disagreed
about it would disagree about the class layout."*

**Risk. [CONFIRMED]** A one-line change to shared code that lands between the two passes of
`build-dual-hz.sh` produces an APK whose two engines differ in more than the tick rate. `AGENTS.md`
warns about exactly this. There is no check that the two engines were built from the same tree.

---

## 5. Branch and history risks (specific to this fork)

**[CONFIRMED]** GitHub API, 2026-11-04:

| Branch | Tip | Status |
|---|---|---|
| `New-UI` | 2026-11-03 | mirror snapshot |
| `claude/ui-redesign-dashboard-drawer` | 2026-10-18 | merge of PR #2 `feature/in-game-floating-toolbox` |
| `fix-ui-Draw-18-10` | 2026-10-24 | "fix Workflow 2 files" |
| `backup18-10` | 2026-10-18 | "Add toolbox layout management and utilities" |
| `backup16-10` | 2026-10-16 | "lead the launcher dashboard with launch and Online" |
| `New/app` | 2026-10-22 | "Add files via upload" |
| `main` | 2026-10-22 | "Add files via upload" — a **different project shape** |

**[CONFIRMED]** The `claude/ui-redesign-dashboard-drawer` branch adds files that do **not** exist on
`New-UI`:

```
ToolboxConfig.java, ToolboxIconPreviewView.java, ToolboxIconRenderer.java, ToolboxOverlayView.java,
accent_colors.xml
```

and it contains the Gradle wrapper that `New-UI` lacks.

**[CONFIRMED]** `main` and `New-UI` share no development lineage in the launcher: `main` has no
`android/` application at all in the same shape, and the diff is 6 457 files.

**Risks.**

1. **[CONFIRMED]** The in-app toolbox work is stranded on a branch that predates `New-UI` by
   two weeks. If it is wanted, it must be re-applied by hand — the mirror will never merge it.
2. **[CONFIRMED]** `main` is not fast-forwardable from `New-UI`, so the documented release flow
   ("push to `main`, then `Actions → Publish Android Release`") cannot be used on this fork
   without first deciding which `main` wins.
3. **[CONFIRMED]** `New-UI` is overwritten every 6 hours by `mirror.yml`
   (`cron: '17 */6 * * *'`, `MIRROR_TOKEN`). Any commit made directly on `New-UI` is lost at the
   next mirror run. **Work must be done on a new branch, never on `New-UI`.**
4. **[CONFIRMED]** The repository has **0 releases**, so the download badges in `README.md`
   (`MYSOREZ/GeneralsZH-Android-Port/releases/latest`) point at a different repository.

---

## 6. Network endpoints point at the upstream repository

**[CONFIRMED]** Four hardcoded references, all to `MYSOREZ/GeneralsZH-Android-Port`:

```java
// UpdateManager.java:67
RAW_ROOT = "https://raw.githubusercontent.com/MYSOREZ/GeneralsZH-Android-Port/";
// SetupActivity.java:3356
LANGUAGE_PACK_INDEX = "https://api.github.com/repos/MYSOREZ/GeneralsZH-Android-Port/contents/languages?ref=main";
// SetupActivity.java:3358
LANGUAGE_PACK_BASE  = "https://raw.githubusercontent.com/MYSOREZ/GeneralsZH-Android-Port/main/languages/";
// update/config.json:5
"datapack_manifest_url": "https://raw.githubusercontent.com/MYSOREZ/GeneralsZH-Android-Port/updates/datapack-manifest.json"
```

**[CONFIRMED]** `update/config.json` also carries `pc_exe_crc`, `stun_servers` and `turn_servers`
for the upstream service.

**Impact.** On this fork, "Check for updates", the engine-update path, the language-pack list and
the community data-pack manifest all read another repository's branches. If those branches do not
exist there, the features fail with the launcher's own error paths; if they do, the fork installs
upstream artefacts.

**[CONFIRMED]** The unauthenticated GitHub contents API (`LANGUAGE_PACK_INDEX`) is subject to
60 requests/hour/IP — a user who retries the language list repeatedly will be rate-limited, and the
launcher has no visible message for that case (only `describeLanguagePackFailures`).

---

## 7. Storage and permission model

**[CONFIRMED]**

* `MANAGE_EXTERNAL_STORAGE` (API 30+) plus `READ/WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=29`.
  `MANAGE_EXTERNAL_STORAGE` is a "all files access" permission that Google Play restricts to file
  managers and similar; a Play listing is impossible with it. The project distributes by sideload,
  so this is a deliberate trade-off, but it should be stated.
* `hasStorageAccess(this)` is checked before downloads and warned about on Home; the game's own
  saves need it too.
* The engine's user-data path is derived from `getuid()/100000` in **three** independent places
  (`SDL3Main.cpp`, `GXLogging.h`, `GXManagedMods.h`), plus `AndroidCrashHandler.cpp` derives it a
  fourth time for `crash.log`, plus Java derives it in `DataPackInstaller.userDataDir()`.
  **[SUSPECTED]** A device where the app is not under `/data/user/<n>/` (work profiles, some
  multi-user setups, or a `--user` install) would break all five consistently or not at all; the
  code has no fallback and no diagnostic for it.
* **[CONFIRMED]** `copyBundledRuntimeIfMissing` seeds fonts and `dxvk.conf` into the **game folder**
  but always overwrites `Window/`. A user's own modified `GroupPanel.wnd` is therefore replaced on
  every launch.

---

## 8. Concurrency and threading

**[CONFIRMED]** The pattern is `new Thread(...)` + `Handler(Looper.getMainLooper())` /
`runOnUiThread`, with generation counters where a stale result would be harmful.

**Specific risks.**

| # | Risk | Evidence | Class |
|---|---|---|---|
| 8.1 | `ModPacker.ensurePacked` runs inside `writeActiveList`, which is called from `GeneralsZHActivity.getArguments()` — **on SDL's thread, immediately before the game starts**. An unchanged mod costs a directory walk; a changed one costs a repack, all while the user waits on a black screen. | `ModManager.java:309-322` | **[CONFIRMED]** |
| 8.2 | `ModManagerActivity.onDestroy` interrupts the import thread and calls `worker.shutdownNow()`, but the activity also has no `configChanges` for orientation. A rotate during import cancels the import. | `ModManagerActivity.java:117-127` | **[LIKELY]** |
| 8.3 | `GeneralsOnlineActivity` holds a `Handler` bound to the main looper and cancels it only when `isFinishing()`. Deliberate (a browser round-trip may recreate the activity) and defended by `signInGeneration`, but the abandoned loop still runs its final `handler.post`. | `GeneralsOnlineActivity.java:104-127, 133-140` | **[CONFIRMED]** behaviour, **[SUSPECTED]** leak |
| 8.4 | `NetworkDiagnosticsActivity` starts a thread with no cancellation on destroy. | `NetworkDiagnosticsActivity.java:145` | **[SUSPECTED]** |
| 8.5 | `SetupActivity` has four bare `new Thread(...)` calls (lines 684, 2976, 3144, 3387) with no executor and no shutdown path; a destroyed activity can still receive their `runOnUiThread` callbacks, which then touch views. | **[LIKELY]** every one of them null-checks its target, so the practical impact is low | **[LIKELY]** |
| 8.6 | `ModManager` state is read from both the UI thread (`listMods`) and SDL's thread (`writeActiveList`) with no synchronization. `SharedPreferences` is itself thread-safe, and the file writes are separate files, so a torn read is unlikely — but the *order* of the two writers is unguarded. | **[SUSPECTED]** |

---

## 9. Performance

| # | Item | Evidence | Class |
|---|---|---|---|
| 9.1 | **APK size 92.8 MB** — 4 engines (140 MB uncompressed), a 17 MB bundled Turnip driver, ~9.5 MB uncompressed dex. `PLAN-025` quantifies it and is *parked by the owner*. | `apk/…-a48.apk` = 92 828 479 bytes; `PLAN-025_APK_SIZE.md` | **[CONFIRMED]** |
| 9.2 | **Dex not shrunk**: `minifyEnabled false` and `assembleDebug` (PLAN-025 estimates 7–8 MB recoverable). | `android/app/build.gradle` | **[CONFIRMED]** |
| 9.3 | **Each engine carries its own copy of FFmpeg/OpenSSL/curl/GNS** (PLAN-025 option C: 8–12 MB). | `PLAN-025` | **[CONFIRMED]** |
| 9.4 | **680 MB of APKs in git history** (8 files). Every clone downloads them; GitHub is not an LFS host here (`AGENTS.md` notes LFS is disabled on the fork). | `du -sh apk` | **[CONFIRMED]** |
| 9.5 | **Integrity scan on the UI thread**: `refreshStatus()` → `findGameFolderIntegrityIssues()` walks the game folder and its immediate subfolders and reads BIG headers. It runs from `onResume`, from `showTab` and after a folder pick. | `SetupActivity.java:2880, 2894` | **[CONFIRMED]** |
| 9.6 | **Duplicate 93 MB APK** — `apk/GeneralsXZH-android-generals-a48.apk` and `apk/testers/GeneralsXZH-android-generals-a48.apk` are byte-identical (`md5 a1f65ae3…`), and `apk/testers/README.md` documents `a48` as a tester build. | measured | **[CONFIRMED]** |
| 9.7 | `detectGpuName()` is cached in `cachedGpuName` — good. `ModManagerActivity` caches the mod file tables — good. | — | **[CONFIRMED]** positive |

---

## 10. Android version compatibility

| Item | Assessment |
|---|---|
| `minSdk 28` (Android 9) | **[CONFIRMED]** with a documented reason; `InsetUtil` has an API-28 branch; `computeSafeInsetsArgument` returns `null` on Android 9 |
| `targetSdk 35` | **[CONFIRMED]** edge-to-edge is mandatory from 35 and every screen pads itself |
| `MANAGE_EXTERNAL_STORAGE` | **[CONFIRMED]** API 30+ only; API 28/29 uses the legacy pair |
| `POST_NOTIFICATIONS` | **[CONFIRMED] not declared**, and no notification is ever posted — correct |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **[CONFIRMED]** declared and used: `GeneralsOnlineActivity.java:686-690` asks to be exempted from battery optimisation (`isIgnoringBatteryOptimizations` → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) so a long online match is not killed |
| `preferMinimalPostProcessing` (game activity) | **[CONFIRMED]** API 30+ attribute, harmless below |
| `android:configChanges` on the launcher | **[CONFIRMED]** `orientation\|screenSize\|keyboardHidden` — but **not** `uiMode`, so a system dark/light switch recreates the launcher. `ThemeHelper` resolves SYSTEM mode at `apply()` time, so this is *correct*, just a visible restart |
| `FOREGROUND_SERVICE` / `QUERY_ALL_PACKAGES` / `AD_ID` | **[CONFIRMED]** not present — good hygiene |
| **[SUSPECTED]** | `MULTICAST`-related behaviour on Android 13+ with `NEARBY_WIFI_DEVICES` not declared — the LAN path uses `WifiManager.MulticastLock` only, which needs no such permission, so this is likely fine |

---

## 11. Build warnings and build-system risks

| # | Item | Evidence | Class |
|---|---|---|---|
| 11.1 | **CI builds only Zero Hour.** `build-android.yml` builds `z_generals dxvk_d3d8_install main_hook file_redirect_hook gsl_alloc_hook hook_impl`; `g_generals` appears **nowhere** in the file. | grep count = 0 | **[CONFIRMED]** |
| 11.2 | **CI's APK therefore cannot be a release.** `package-android-zh.sh` packages `libgenerals.so` only when it exists; `publish-release.yml` requires `libmain60.so` in the APK, which a single-pass CI build never produces. | both files | **[CONFIRMED]** |
| 11.3 | **`main`'s workflow does the opposite** — `GeneralsZH-Android-five-stage-build.yml` builds Generals 30 Hz, Generals 60 Hz, ZH 30 Hz, ZH 60 Hz in four stages and assembles in a fifth. So the "correct" workflow exists, on the wrong branch. | `git show origin/main:…` | **[CONFIRMED]** |
| 11.4 | **No Gradle wrapper** on `New-UI`; `package-android-zh.sh` falls back to system `gradle`. | file absent | **[CONFIRMED]** |
| 11.5 | **CI on this fork has never fully succeeded.** 79 runs total: `Build Android` 11 success / 10 failure / 3 cancelled; `build-android-split.yml` 3 failure; `Build Android (Four Engines)` 3 failure; `Commit Transfer` 5 failure; `Download and Compress to 7z` 3 failure. The only reliable workflow is the mirror (35 success / 1 failure). | GitHub API | **[CONFIRMED]** |
| 11.6 | **Root cause of the observed `Build Android` failure (run 40084873349, 2026-10-24, branch `fix-ui-Draw-18-10`)**: `##[error]build/libmain60.so is not AArch64`. The log shows the file at 292 519 648 bytes in `build/` and the `llvm-readelf` grep failing. The verify step is the *plain* form (`test -s` + one `readelf`), not the retry form used for `libmain.so` in the same file at line 337-345. | CI log | **[CONFIRMED]** — the failure is a verification-race, and the same workflow already carries the retry fix for the other library but not for this one |
| 11.7 | The runner logs `Node.js 20 is deprecated … actions/setup-java@v4, gradle/actions/setup-gradle@v4`. | CI log | **[CONFIRMED]** |
| 11.8 | **`references/` submodules uninitialised**; the Android build needs `references/fbraz3-dxvk` **and** its own nested submodules (SPIRV-Headers, Vulkan-Headers, libdisplay-info) — meson dies with "Missing SPIRV-Headers" otherwise. Documented in the workflow. | workflow comment + `git submodule status` | **[CONFIRMED]** |
| 11.9 | **`GeneralsOnlineRelease/` (341 MB) is unused by the Android build.** | no reference found | **[LIKELY]** |
| 11.10 | `android/app/build.gradle` uses `packagingOptions`; the modern AGP name is `packaging`. Deprecated, not broken at AGP 8.5.2. | — | **[SUSPECTED]** warning |

---

## 12. Security and privacy

| # | Item | Evidence | Class |
|---|---|---|---|
| 12.1 | **Signed updates are properly designed**: ECDSA P-256, public key in `UpdateManager.PUBLIC_KEY_B64`, monotone `serial`, SHA-256 over each engine file, and `GXSignedSettings` re-verifies in the engine for switches other players depend on. | `UpdateManager.java`, `GXSignedSettings.h`, `PUBLISH_UPDATE.md` | **[CONFIRMED]** positive |
| 12.2 | The manifest's own doc records the residual risk honestly: *"once such a switch has been published as `1` and then back to `0`, the older signed manifest could be put back by hand; if that matters, give the engine a minimum serial."* | `PUBLISH_UPDATE.md` | **[CONFIRMED]** accepted |
| 12.3 | **Debug keystore committed with a public password** and used for release signing. Anyone can sign an APK that installs over this app. Acceptable for sideload-only distribution; fatal for any store. | `android/app/build.gradle` | **[CONFIRMED]** |
| 12.4 | `android:debuggable` — the manifest has no explicit value, and `assembleDebug` sets it **true**. The shipped APKs are `debug` builds, so `run-as`, `gdb` attach and `/data/local` access are enabled on user devices. `PLAN-025` option A would fix this along with the size. | `assembleDebug` in `package-android-zh.sh` | **[CONFIRMED]** |
| 12.5 | `NetworkDiagnostics` redacts bearer tokens and synthesises a MAC/volume serial for the shareable report — deliberate privacy work. | `NetworkDiagnostics.java` | **[CONFIRMED]** positive |
| 12.6 | `ModImportService` validates paths (`resolveSafeRelative`), rejects traversal, bounds the entry count/size/depth and validates BIG headers before renaming — a genuinely careful importer. | `ModImportService.java` | **[CONFIRMED]** positive |
| 12.7 | `DataPackInstaller` writes only two whitelisted directories and records the installed file list for clean uninstall. | `DataPackInstaller.java` | **[CONFIRMED]** positive |
| 12.8 | `usesCleartextTraffic` — not set, so cleartext is off by default; all endpoints are `https://`. | manifest + code | **[CONFIRMED]** positive |
| 12.9 | **[SUSPECTED]** `custom_driver` import accepts a `.zip` and unpacks `.so` files that are then `dlopen`ed by libadrenotools. That is a user-supplied native library being loaded into the process. The UI says where to get drivers; the risk is the user's, and every comparable app does the same. Worth documenting explicitly. | `SetupActivity.onImportCustomDriver` | **[SUSPECTED]** |

---

## 13. Resource handling

| # | Item | Evidence | Class |
|---|---|---|---|
| 13.1 | **[CONFIRMED] positive** — file and network handles use try-with-resources throughout (`readKeyValueFile`, `writeKeyValueFile`, `readControlScheme`, `readMouseCursorPercent`, `DataPackInstaller`, `UpdateManager`). | code | — |
| 13.2 | `readText(pending, 64 * 1024)` and `LogViewerActivity`'s `MAX_CHARS_PER_FILE` / `HEAD_CHARS_PER_FILE` bound what is read into memory — deliberate. | `ModManager.java`, `LogViewerActivity.java` | **[CONFIRMED]** positive |
| 13.3 | `SetupActivity.readKeyValueFile` ignores IO errors and returns an empty map; the caller then writes a file with only the new key. If the read failed because the file was corrupt, the write **destroys** the other values. | `SetupActivity.java:2855-2870` | **[LIKELY]** — the "seed from DefaultOptions.ini" path mitigates the first-write case but not a later corrupt read |
| 13.4 | `ModManagerActivity` wipes the whole cache of `.zip` files (see §3). | — | **[CONFIRMED]** |
| 13.5 | `UpdateManager.pruneOtherEngines` deletes previously downloaded engines — bounded growth. | — | **[CONFIRMED]** positive |
| 13.6 | The two bundled `.so` sets are committed, not generated, so there is no version-drift risk for ANGLE — but also no record of how to rebuild it (`BUILD_REQUIREMENTS.md` §3.4). | — | **[CONFIRMED]** |

---

## 14. Exception handling

**[CONFIRMED]** 52 catch blocks in `SetupActivity`, 14 in `UpdateManager`, 9 in `ModImportService`,
8 each in `ModManager`, `GeneralsOnlineSession` and `DataPackInstaller`.

| Pattern | Assessment |
|---|---|
| Catch and fall back to a default (`NumberFormatException ignored` → the engine default) | Good, and each one carries a comment explaining why the fallback is right |
| Catch and log with `android.util.Log.w` | Good |
| Catch and `Toast` the message | Acceptable |
| Catch and swallow with a comment (`catch (IOException ignored) { // Treat as empty; caller falls back to defaults. }`) | Acceptable but hides a corrupt file (see §13.3) |
| `catch (Exception e)` in `GeneralsOnlineSession` (4×), `DataPackInstaller` (4×), `GeneralsOnlineActivity` (4×) | **[SUSPECTED]** broad catches around network/JSON code can hide a programming error as "the server said no" |
| `SetupActivity.onCreate` catches `Throwable` around `buildUi()` and builds a fallback screen | **[CONFIRMED]** a deliberate, well-commented safety net — the app cannot be bricked by a UI bug |

**[CONFIRMED]** No empty catch block was found; every `catch` has a body.

---

## 15. Consistency and documentation risks

| # | Item | Evidence | Class |
|---|---|---|---|
| 15.1 | The tree is full of `GeneralsX @keyword author DD/MM/YYYY` annotations, which is the project's rule — but they also make diffs noisy and they are the *only* change history available on a shallow clone. | every file | **[CONFIRMED]** |
| 15.2 | Several comments record a **removed** implementation rather than the current one (e.g. `SetupActivity`'s "the removed Interface Size note above", `GeneralsOnlineActivity`'s "&client= dropped" note, `ModManager`'s "a28 zipped mods into the cache"). Useful now, but they will rot. | code | **[CONFIRMED]** |
| 15.3 | `docs/HOWTO/INSTALLATION.md` describes **Linux/macOS only** and says "Multiplayes features: LAN play - it's broken for now… Online features - not implemented and planned for the future" — the opposite of the Android reality (GeneralsOnline multiplayer is implemented, LAN is present). A reader following the repo's own HOWTO is misled. | file | **[CONFIRMED]** |
| 15.4 | `docs/WORKDIR/planning/FUTURE_RELEASES.md` is entirely about the Linux Flatpak and is stale relative to the Android focus. | file | **[CONFIRMED]** |
| 15.5 | `.devin/wiki.json` describes the project as a Linux/macOS/Windows port with three reference repositories and never mentions Android, the launcher, mods, GeneralsOnline or language packs. Any agent that loads it starts from the wrong picture. | file | **[CONFIRMED]** |
| 15.6 | `docs/DEV_BLOG/` stops at `2026-10-DIARY.md`; there is no `2026-11-DIARY.md`, although the tree's newest work is dated 02–03/11/2026. | directory | **[CONFIRMED]** |
| 15.7 | `docs/instructions` (`.github/instructions/docs.instructions.md`) names `https://github.com/fbraz3/GeneralsX/issues/` as the issue tracker, not this repository. | file | **[CONFIRMED]** |
| 15.8 | `AGENTS.md` describes the branch to work on as `claude/network-diagnostics`, which does not exist on this fork (the mirror branch is `mirror/claude-network-diagnostics`). | `AGENTS.md` vs branch list | **[CONFIRMED]** |

---

## 16. Things that are notably *good* (so they are not broken by a future change)

**[CONFIRMED]**

1. `UiKit` — a real, shared, single-purpose component library (700 lines, every method used).
2. `ThemeHelper`'s name-remapping palette, which makes a fourth theme a data change, not a code
   change.
3. Complete localisation: 488 strings in **all** 13 locales, verified equal.
4. The signed-update design (signature + monotone serial + per-file SHA-256 + library compatibility
   + two-strike rollback).
5. `ModImportService`'s bounds, path checks and atomic rename.
6. `AndroidCrashHandler` writing `crash.log` with raw syscalls from an ELF constructor.
7. `InsetUtil` adding to (not replacing) existing padding.
8. The fallback UI in `SetupActivity`.
9. Generation counters for the sign-in poll and the mod analysis.
10. The gesture state machine's refusal to synthesise mouse messages, and the written lesson that
    explains why.
11. `NetworkDiagnostics`' redaction.
12. The engine's own safety net: two failed boots → back to the APK's engine.

---

## 17. Ranked risk register

| Rank | Risk | Severity | Likelihood | Class |
|---|---|---|---|---|
| 1 | Committing to `New-UI` — the mirror overwrites it every 6 h | Data loss | Certain | **[CONFIRMED]** |
| 2 | No tests anywhere; the riskiest code (import, update verification, BIG parsing) is untested | High | Certain | **[CONFIRMED]** |
| 3 | `main` cannot be fast-forwarded from `New-UI`, so the documented release path is unusable | High | Certain | **[CONFIRMED]** |
| 4 | `SetupActivity` god class blocks safe change | High | Certain | **[CONFIRMED]** |
| 5 | Update / language-pack endpoints point at another repository | High | Certain | **[CONFIRMED]** |
| 6 | CI cannot build what a release needs (one game, one tick rate) and has never succeeded on this fork | High | Certain | **[CONFIRMED]** |
| 7 | `userDataDir` two definitions; with the base game selected the Replay check and the log viewer read Zero Hour's folder | Medium | Certain | **[CONFIRMED]** |
| 8 | 92.8 MB APK, debug-signed, `debuggable`, unshrunk dex | Medium | Certain | **[CONFIRMED]** |
| 9 | `ModPacker.ensurePacked` on SDL's thread at every start | Medium | Certain | **[CONFIRMED]** |
| 10 | In-app toolbox work stranded on a pre-`New-UI` branch | Medium | Certain | **[CONFIRMED]** |
| 11 | Marker-file interface with no schema; silent failure on a one-sided rename | Medium | Possible | **[CONFIRMED]** design risk |
| 12 | 680 MB of APKs in git | Medium | Certain | **[CONFIRMED]** |
| 13 | Corrupt `Options.ini` read → other keys lost on write | Medium | Possible | **[LIKELY]** |
| 14 | Integrity scan on the UI thread | Low | Certain | **[CONFIRMED]** |
| 15 | Stale docs (`INSTALLATION.md`, `.devin/wiki.json`, `FUTURE_RELEASES.md`) mislead readers and agents | Low | Certain | **[CONFIRMED]** |
| 16 | Import cancelled by rotation; cache `.zip` wipe broader than intended | Low | Possible | **[LIKELY]** |
| 17 | `POST_NOTIFICATIONS` declared but unused | Cosmetic | Certain | **[CONFIRMED]** |
