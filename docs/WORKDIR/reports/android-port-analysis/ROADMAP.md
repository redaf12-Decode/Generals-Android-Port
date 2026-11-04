# ROADMAP.md — improvement plan for Generals-Android-Port

Derived from `ISSUES_AND_RISKS.md`. Ordered by priority. **Nothing here has been implemented** —
this is the plan for review before any execution.

Every item gives: the problem, the change, the files and components affected, the dependencies, the
risk, the priority and the prerequisites.

---

## 1. Ground rules for any future work (read first)

These are consequences of the facts in `ISSUES_AND_RISKS.md`, not opinions.

1. **[CONFIRMED] Do not commit on `New-UI`.** `.github/workflows/mirror.yml` overwrites it every
   6 hours (`cron: '17 */6 * * *'`) from `MYSOREZ/GeneralsZH-Android-Port`. Create a working branch
   (e.g. `ui/new-ui-work`) and open a pull request against `New-UI` or against whatever branch the
   repository owner nominates as the integration branch.
2. **[CONFIRMED] Do not touch `main`** until the owner decides which `main` is authoritative: it
   currently holds a different project shape and cannot be fast-forwarded from `New-UI`.
3. **[CONFIRMED] Local builds only.** `AGENTS.md`: the owner's Actions budget is limited and the
   `push:` trigger was removed deliberately.
4. **[CONFIRMED] Never leave the tree with one engine rebuilt.** Both engines of both games must come
   from one tree (`build-dual-hz.sh`), or the APK carries engines that differ in more than the tick
   rate.
5. **[CONFIRMED] A new or changed string goes into all 13 locales in the same commit** — the tree is
   currently at exactly 488 names per locale, and that property is worth keeping.
6. **[CONFIRMED] A new colour token needs its `gzh_navy_*` and `gzh_day_*` twins**, or it silently
   ignores the theme.
7. **[CONFIRMED] A new marker file needs both writers** — the Java side and a native reader
   (`GX*.h` or `SDL3Main.cpp`).
8. **[INFERENCE] Prefer the patterns already in the tree**: a `UiKit` card, a marker file plus a
   header-only `GX*.h` reader, an entry in the diagnostic arrays, a value in the signed settings.
   Every one of those has several working precedents.

---

## 2. Priority 0 — protect the work (do before anything else)

### P0.1 — Establish a safe integration branch and a written rule

* **Problem.** `New-UI` is a mirror target; `main` is unusable; the five other branches are stale.
  There is no branch a change can safely land on.
* **Change.** Create `ui/redesign` from `New-UI`; add a short section to `AGENTS.md` naming it and
  restating rule 1 above; open a draft PR so every change is reviewable.
* **Files.** `AGENTS.md`; git branches only.
* **Dependencies.** Owner's agreement on the branch name and on the target of the eventual PR.
* **Risk.** Low.
* **Priority.** Blocking — every other item depends on it.
* **Prerequisites.** Owner's decision.

### P0.2 — Decide what to do with the stranded toolbox work

* **Problem.** `claude/ui-redesign-dashboard-drawer` (2026-10-18) adds `ToolboxConfig`,
  `ToolboxIconPreviewView`, `ToolboxIconRenderer`, `ToolboxOverlayView` and `accent_colors.xml`,
  and carries the missing Gradle wrapper; none of it exists on `New-UI`.
* **Change.** Choose one: (a) re-apply the toolbox by hand onto the new branch, (b) deliberately
  abandon it and record that in `PLAN-029`, or (c) keep it on its branch as a reference.
* **Files.** Whatever the decision implies; `docs/WORKDIR/planning/PLAN-029_LAUNCHER_IDEAS_FROM_REALKOT.md`.
* **Dependencies.** Owner's decision; a device to test the overlay on.
* **Risk.** Medium if re-applied blindly (the branches have diverged by two weeks of launcher work).
* **Priority.** High — the work is at risk of being lost.
* **Prerequisites.** P0.1.

### P0.3 — Restore the Gradle wrapper

* **Problem.** `android/gradlew` and `gradle/wrapper/` do not exist on `New-UI`;
  `package-android-zh.sh` falls back to a system `gradle`, and CI's `setup-gradle` masks the gap.
* **Change.** Add the wrapper (Gradle 8.9, matching `android/build.gradle`'s AGP 8.5.2).
* **Files.** `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`.
* **Dependencies.** None.
* **Risk.** Low.
* **Priority.** High — makes a reproducible local build possible.
* **Prerequisites.** P0.1.

---

## 3. Priority 1 — correctness and release integrity

### P1.1 — Point the update and language-pack endpoints at the right repository

* **Problem.** `UpdateManager.RAW_ROOT`, `SetupActivity.LANGUAGE_PACK_INDEX`/`LANGUAGE_PACK_BASE`
  and `update/config.json`'s `datapack_manifest_url` all name `MYSOREZ/GeneralsZH-Android-Port`.
  On this fork those flows read another repository.
* **Change.** Either point them at this repository (and create the `updates` / `updates-test`
  branches and the signing key), or make the root a single constant read from one place so the
  decision is one edit. Keep the signature verification exactly as it is.
* **Files.** `UpdateManager.java`, `SetupActivity.java`, `update/config.json`,
  `docs/HOWTO/PUBLISH_UPDATE.md`.
* **Dependencies.** Owner's decision on the canonical repository; the private signing key if the
  `updates` branch is to be published here.
* **Risk.** **High if done carelessly** — changing the root without publishing a signed manifest
  makes the update card fail for every existing install. Do it with a manifest already on the
  target branch.
* **Priority.** High.
* **Prerequisites.** Owner's decision; P0.1.

### P1.2 — One definition of the user-data directory, per game

* **Problem.** `DataPackInstaller.userDataDir()` hardcodes Zero Hour's leaf;
  `SetupActivity.userDataDir()` correctly models the two leaves. `ReplayCheckActivity` (the replay
  list, the result file) and `LogViewerActivity` read through the former, so with the base game
  selected they read Zero Hour's folder — while the engine, per
  `SDL3Main.cpp`'s `GX_USERDATA_LEAF`, uses `<…>Generals Data` for the base game. This is a
  confirmed defect (`ISSUES_AND_RISKS.md` §4.1).
* **Change.** `DataPackInstaller.userDataDir()` takes the selected game (or delegates to
  `SetupActivity.userDataDir(ctx)`); `ReplayCheckActivity` and `LogViewerActivity` pass it. Make the
  community patch's Zero-Hour-only nature explicit rather than incidental.
* **Files.** `DataPackInstaller.java`, `SetupActivity.java`, `ReplayCheckActivity.java`,
  `LogViewerActivity.java`.
* **Dependencies.** None — the native side is already read and settles the layout.
* **Risk.** Low-medium: the replay list will now show a different folder for the base game, which is
  the intent, but existing users' expectations should be mentioned in the release notes.
* **Priority.** High.
* **Prerequisites.** P0.1.

### P1.3 — Make the two engines provably identical apart from the tick rate

* **Problem.** Nothing verifies that the 30 Hz and 60 Hz engines were built from the same tree.
* **Change.** After the two passes, compare a hash of the source tree (or of `git status
  --porcelain`) and abort with a clear message if it changed; record both hashes in
  `assets/engine_build.txt`-adjacent metadata.
* **Files.** `scripts/build/android/build-dual-hz.sh`.
* **Dependencies.** None.
* **Risk.** Low.
* **Priority.** Medium-high.
* **Prerequisites.** P0.1.

### P1.4 — Fix the CI verification race for both 60 Hz engines

* **Problem.** Run 40084873349 failed with `##[error]build/libmain60.so is not AArch64` on an
  otherwise clean build. The same workflow already carries a five-attempt retry for `libmain.so`
  (`build-android.yml:337-345`) with a comment explaining the race, but the 60 Hz verify step uses
  the plain one-shot form.
* **Change.** Apply the same retry loop to `libmain60.so` and `libgenerals60.so`.
* **Files.** `.github/workflows/build-android.yml`.
* **Dependencies.** None.
* **Risk.** Low.
* **Priority.** Medium-high (it blocks any CI verification).
* **Prerequisites.** P0.1.

### P1.5 — Make CI able to produce what a release needs

* **Problem.** CI builds only `z_generals`; a release needs four engines and the APK must contain
  `libmain.so` **and** `libmain60.so` (`publish-release.yml` checks for both). The correct
  four-stage workflow exists on `main` only.
* **Change.** Either bring the four-stage structure onto the working branch, or state in
  `AGENTS.md` that CI is for engine smoke-checking only and releases are always local. The second is
  consistent with the existing policy and costs nothing.
* **Files.** `AGENTS.md` (one paragraph) or `.github/workflows/build-android.yml`.
* **Dependencies.** Owner's preference.
* **Risk.** Low.
* **Priority.** Medium.
* **Prerequisites.** P0.1.

---

## 4. Priority 2 — architecture and maintainability

### P2.1 — Split `SetupActivity` by section

* **Problem.** 4 529 lines, 52 catch blocks, 244 strings, five pages plus the folder logic, the BIG
  parser, the driver installer and the network checks in one class. A real bug in this class is
  already documented in the class itself (`optionsIniFile()` pointing at the wrong directory).
* **Change.** Introduce `HomePage`, `GraphicsPage`, `InterfacePage`, `ToolsPage`, `HelpPage` — each
  a class taking the page `LinearLayout` and a small `LauncherState` — and leave `SetupActivity` as
  the shell (app bar, bottom navigation, `showTab`, `clearPageReferences`, `buildFallbackUi`,
  activity results). Move the folder/BIG logic to a `GameFolder` helper and the `Options.ini` logic
  to an `OptionsIni` helper.
* **Files.** `SetupActivity.java` (split), plus five new files and two helpers; `strings.xml` keys
  unchanged.
* **Dependencies.** None (pure refactor).
* **Risk.** Medium — the class is the app's spine. Mitigate by moving one page per commit, keeping
  the string keys and the refresh contract (`clearPageReferences` + the five refreshers) intact, and
  testing each page on a device.
* **Priority.** High value, medium urgency.
* **Prerequisites.** P0.1; a way to test on a device or emulator.

### P2.2 — Extract the repeated UI and file idioms into helpers

* **Problem.** Seven copies of the screen shell; four copies of the slider styling; three copies of
  the `Options.ini` read-modify-write; six hand-rolled marker-file read/writers.
* **Change.**
  * `UiKit.screen(activity, overline, title, icon, action)` → `(root, page)`.
  * `UiKit.slider(parent, from, to, step, value)` with the four tints applied once.
  * `OptionsIni.read/write(game)` with the `DefaultOptions.ini` seeding in one place.
  * `Marker.read/write/exists/delete(context, name)`.
* **Files.** `UiKit.java`, `SetupActivity.java`, the six other activities.
* **Dependencies.** P2.1 preferably first (otherwise the extraction fights the split).
* **Risk.** Low.
* **Priority.** Medium.
* **Prerequisites.** P0.1.

### P2.3 — Document the marker-file interface in one place

* **Problem.** 20+ marker files, no schema, no list; a rename on one side is a silent no-op.
* **Change.** A table in `docs/port/ANDROID_PORT.md` (or a new `docs/port/MARKERS.md`) listing each
  marker: writer, reader, location, format, meaning of absence. Add the two-line comment header to
  each `GX*.h`.
* **Files.** documentation + comments only.
* **Dependencies.** None.
* **Risk.** None.
* **Priority.** Medium — cheap, and it is the interface every feature crosses.
* **Prerequisites.** P0.1.

### P2.4 — A colour-token completeness check

* **Problem.** `ThemeHelper.mapColor` remaps by resource name; a `gzh_*` token without a
  `gzh_navy_*` / `gzh_day_*` twin silently ignores the theme. `colors.xml` has 74 lines and
  `colors_palettes.xml` 80, so the sets are not obviously in sync.
* **Change.** A JVM unit test (`android/app/src/test`) that parses both files and asserts every
  `gzh_*` name has its twins. This is the cheapest possible first test, and it needs no device.
* **Files.** new `android/app/src/test/java/com/generalsx/zerohour/ColorTokenTest.java`,
  `android/app/build.gradle` (`testImplementation junit`), `gradle.properties` unchanged.
* **Dependencies.** P0.3 (wrapper) makes running it trivial.
* **Risk.** Low.
* **Priority.** Medium — establishes the test source set with a genuinely useful first test.
* **Prerequisites.** P0.1, P0.3.

---

## 5. Priority 3 — tests

### P3.1 — Unit tests for the pure logic (no device needed)

* **Problem.** Zero tests. The pure algorithms are the ones that are silent when wrong.
* **Change.** Add JVM tests for:
  * `ModImportService.resolveSafeRelative` — traversal, absolute paths, `..`, symlinks, depth.
  * `ModManager.profileName` — the character filter and the 60-character truncation.
  * `ModManager.failedStarts` — the marker format and the "same list" comparison.
  * `SetupActivity`'s BIG header parser (`hasBigFHeader`, `readBigEndianInt`,
    `bigContainsEntry` with a synthetic archive).
  * `LocaleHelper.gameDataLanguageFor` / `languagePackFor`.
  * `ThemeHelper.mapColor` with a fake resources object.
  * `NetworkDiagnostics`' redaction.
* **Files.** new test sources; `android/app/build.gradle` (JUnit).
* **Dependencies.** P0.3, P2.4 (for the source set).
* **Risk.** Low — but the methods are currently `private`/`static` inside activities; some will need
  package-private visibility, which is a small, safe change.
* **Priority.** High value.
* **Prerequisites.** P0.1.

### P3.2 — An instrumentation smoke test for the launcher

* **Problem.** The launcher's failure modes are UI-shaped: a page that throws, a theme that
  mis-maps, a fallback that hides a real bug.
* **Change.** An `androidTest` that launches `SetupActivity`, visits all five tabs, and asserts no
  fallback screen appears (the fallback contains a distinctive string).
* **Files.** new `androidTest` source; `build.gradle` (`androidTestImplementation`).
* **Dependencies.** A device or emulator in the loop.
* **Risk.** Low.
* **Priority.** Medium.
* **Prerequisites.** P3.1 (the source set), a test device.

### P3.3 — A verification test for the update manifest

* **Problem.** The signed-update logic is the most safety-critical Java in the tree and has no test.
* **Change.** A JVM test with a fixture manifest + signature, asserting: a bad signature is
  rejected, a lower `serial` is rejected, a `requires_libs` mismatch is rejected, and an unknown
  feature name falls back to the default.
* **Files.** new test + fixtures.
* **Dependencies.** Access to a signed fixture (can be generated with a throwaway key).
* **Risk.** Low.
* **Priority.** Medium-high.
* **Prerequisites.** P3.1.

---

## 6. Priority 4 — build and distribution

### P4.1 — Shrink the APK (owner already scoped this)

* **Problem.** 92.8 MB. `PLAN-025` quantifies: 4 engines 67.8 MB, dex 9.5 MB, Turnip 3.8 MB.
* **Change.** `PLAN-025`'s options in its own order: **A** — build the launcher with R8
  (`minifyEnabled true`) with keep rules for everything native code calls through JNI; **B** —
  FFmpeg limited to Bink and its audio; **C** — shared FFmpeg/OpenSSL/curl/GNS; **D** — the 60 Hz
  engines downloaded on demand through the existing signed engine path.
* **Files.** `android/app/build.gradle`, `scripts/build/android/package-android-zh.sh`,
  `vcpkg.json` (B), `build-dual-hz.sh` + `UpdateManager` (D).
* **Dependencies.** A device test of every codec (B) and of every JNI entry point (A).
* **Risk.** **Medium-high.** R8 can strip a JNI-reached class and produce a crash only on a real
  device. The keep list is documented in `PLAN-025`; test sign-in, the text editor, the LAN lock and
  the SDL activity on a device before shipping.
* **Priority.** Medium — the owner parked it, so do not start without their word.
* **Prerequisites.** Owner's decision; P3.2 for the device smoke test.

### P4.2 — Stop shipping a `debuggable` APK

* **Problem.** `package-android-zh.sh` runs `assembleDebug`, so shipped APKs are debuggable and the
  dex is unshrunk. Measured on the committed APK: `debuggable` is present in the manifest.
* **Change.** Move to `assembleRelease` with the existing keystore (so updates still install over
  existing versions) — which is also option A of `PLAN-025`.
* **Files.** `scripts/build/android/package-android-zh.sh`, `android/app/build.gradle`.
* **Dependencies.** The keep rules from P4.1.
* **Risk.** Medium (same as P4.1).
* **Priority.** Medium.
* **Prerequisites.** P4.1.

### P4.3 — Reduce the git weight

* **Problem.** 680 MB of APKs tracked; `apk/GeneralsXZH-android-generals-a48.apk` and
  `apk/testers/GeneralsXZH-android-generals-a48.apk` are byte-identical, and `a48` is described in
  the tester README as a tester build while also being the current build.
* **Change.** Remove the duplicate from `apk/` (or from `apk/testers/`), keeping the one the tester
  README points at. Long term, host release APKs as release assets rather than in the tree — the
  documented flow already publishes a GitHub Release, and the tester links could be release assets
  too.
* **Files.** `apk/`, `apk/testers/README.md`, `AGENTS.md`.
* **Dependencies.** Owner's decision (the tester builds are deliberately never deleted).
* **Risk.** Low, but it is a policy change the owner has strong views on — ask first.
* **Priority.** Low-medium.
* **Prerequisites.** Owner's decision.

### P4.4 — Record how the ANGLE prebuilts were built

* **Problem.** `libGLESv2_angle.so` and `libEGL_angle.so` are committed vendor artefacts with no
  build recipe in the tree; `package-android-zh.sh` treats them as inputs.
* **Change.** Add the recipe (AOSP mirror revision, gn/ninja flags) to
  `docs/port/ANDROID_PORT.md`, or a script that reproduces it.
* **Files.** documentation / a new script.
* **Dependencies.** Knowledge of the original build, or a re-derivation.
* **Risk.** Low.
* **Priority.** Low.
* **Prerequisites.** P0.1.

---

## 7. Priority 5 — UI and UX (preserving the visual identity)

All of these keep the current tokens (`UI_ANALYSIS.md` §10) and change structure only.

### P5.1 — Tablet and wide-screen layout

* **Problem.** One full-width column on every screen size; no `values-sw600dp`, no `layout-land`.
* **Change.** A `values-sw600dp/dimens.xml` with a wider gutter and a maximum content width, or a
  two-column Home on wide screens.
* **Files.** new `values-sw600dp/dimens.xml`; `UiKit.scrollingPage`.
* **Dependencies.** P2.2 (the shell helper makes this a one-place change).
* **Risk.** Low.
* **Priority.** Medium.
* **Prerequisites.** P2.2.

### P5.2 — Preserve scroll position across page rebuilds

* **Problem.** Changing the render backend calls `showTab(TAB_GRAPHICS)`, which rebuilds the page and
  loses the scroll position; changing the game rebuilds the whole shell (`buildUi()`).
* **Change.** Remember each tab's `ScrollView` scrollY and restore it after a rebuild; for the game
  switch, refresh the affected cards instead of the whole shell.
* **Files.** `SetupActivity.showTab`, `buildUi`, `clearPageReferences`.
* **Dependencies.** P2.1 helps.
* **Risk.** Low.
* **Priority.** Low-medium.
* **Prerequisites.** P0.1.

### P5.3 — Loading and empty states for the async cards

* **Problem.** The Updates card, the GeneralsOnline card and the data-pack card are populated by a
  background check; before it returns the card can look empty or stale.
* **Change.** `UiKit.placeholder(parent, text)` used while a check is in flight; the data-pack card
  already does this with a chip, so generalise it.
* **Files.** `UiKit.java`, `SetupActivity.java`, `GeneralsOnlineActivity.java`.
* **Dependencies.** None.
* **Risk.** Low.
* **Priority.** Low-medium.
* **Prerequisites.** P0.1.

### P5.4 — Accessibility pass

* **Problem.** `contentDescription` is set on icon buttons, the segmented controls and some status
  views (there is a good pattern in the language card), but the chips, switches and the folder
  status line rely on their visible text.
* **Change.** Set `contentDescription` on the icon-only chips and the status spans, and check the
  touch target sizes (44dp icon buttons are already right; the mod cards' up/down buttons are not
  obviously 48dp).
* **Files.** `UiKit.java`, the activities that add chips.
* **Dependencies.** A device with TalkBack.
* **Risk.** Low.
* **Priority.** Low.
* **Prerequisites.** P0.1.

### P5.5 — Friendly text on the fallback screen

* **Problem.** `setup_fallback_warning` interpolates `String.valueOf(failure)` — a raw exception on
  a user-facing screen.
* **Change.** Show a friendly line; log the exception; keep a "copy details" action.
* **Files.** `SetupActivity.buildFallbackUi`, `strings.xml` + 13 locales.
* **Dependencies.** None.
* **Risk.** Low.
* **Priority.** Low.
* **Prerequisites.** P0.1.

---

## 8. Priority 6 — mods and engines (feature development)

### P6.1 — Move `ModPacker.ensurePacked` off the start path

* **Problem.** `writeActiveList` runs from `GeneralsZHActivity.getArguments()` — on SDL's thread,
  immediately before the game starts — and walks (and possibly repacks) every enabled mod.
* **Change.** Pack at import time and after any change in the Mods screen, and keep the pre-start
  path to a `stat`-based freshness check.
* **Files.** `ModManager.java`, `ModManagerActivity.java`, `ModImportService.java`.
* **Dependencies.** A cheap freshness marker per mod (mtime of the folder vs. the packed file).
* **Risk.** Medium — if the freshness check is wrong, a mod starts unpacked and its loose files are
  invisible, which is exactly the bug the current code was added to fix (`ModManager.java:311-316`).
* **Priority.** Medium.
* **Prerequisites.** P3.1 (a test for the freshness rule).

### P6.2 — Keep an in-flight import across recreation

* **Problem.** `ModManagerActivity.onDestroy` interrupts the import thread and shuts the executor
  down; a rotation cancels an import.
* **Change.** Either declare `configChanges` for orientation on that activity, or move the import to
  a retained holder (a small bound service or an application-scoped object with a listener).
* **Files.** `ModManagerActivity.java`, `AndroidManifest.xml`.
* **Dependencies.** None.
* **Risk.** Low-medium.
* **Priority.** Medium.
* **Prerequisites.** P0.1.

### P6.3 — Mod conflict report

* **Problem.** Nothing says which of two mods wins on a file. `PLAN-027` lists it as not done.
* **Change.** `ModAnalysis` already reads each mod's file table; compare the tables across enabled
  mods and report collisions with the winner by order.
* **Files.** `ModAnalysis.java`, `ModManagerActivity.java`, `strings.xml` + 13 locales.
* **Dependencies.** P6.1 would make the tables cheap to reuse.
* **Risk.** Low.
* **Priority.** Medium.
* **Prerequisites.** P0.1.

### P6.4 — Tighten the mod cache wipe

* **Problem.** `ModManagerActivity` deletes every `*.zip` in `getCacheDir()`.
* **Change.** Delete only files whose names match the importer's own prefix.
* **Files.** `ModManagerActivity.java`.
* **Dependencies.** None.
* **Risk.** Low.
* **Priority.** Low-medium.
* **Prerequisites.** P0.1.

### P6.5 — One `GameProfile` for the two games

* **Problem.** The game's libraries, folder preference key, user-data leaf and required files are
  looked up in five different places, with `isGeneralsSelected(this)` ternaries scattered through
  the UI.
* **Change.** A single `GameProfile` with static instances for Generals and Zero Hour.
* **Files.** new class; `SetupActivity`, `GeneralsZHActivity`, `ModManager`, `DataPackInstaller`.
* **Dependencies.** P1.2 (the user-data decision) first.
* **Risk.** Medium — it touches the launch path.
* **Priority.** Medium.
* **Prerequisites.** P1.2.

---

## 9. Priority 7 — documentation

### P7.1 — Make the documentation describe the Android port

* **Problem.** `docs/HOWTO/INSTALLATION.md` is Linux/macOS only and states that online play is "not
  implemented and planned for the future" — the opposite of this fork's reality.
  `docs/WORKDIR/planning/FUTURE_RELEASES.md` is about the Linux Flatpak. `.devin/wiki.json`
  describes a Linux/macOS/Windows port with no mention of Android, the launcher, mods or
  GeneralsOnline. `.github/instructions/docs.instructions.md` names
  `github.com/fbraz3/GeneralsX/issues` as the tracker.
* **Change.** Add an Android section to `INSTALLATION.md` (or a new `docs/BUILD/ANDROID.md` that it
  links), rewrite `.devin/wiki.json` for this fork, archive `FUTURE_RELEASES.md` under
  `docs/ETC/`, and correct the tracker reference.
* **Files.** those four files.
* **Dependencies.** Owner's agreement on the canonical repository and tracker.
* **Risk.** Low.
* **Priority.** Medium — every future contributor and agent reads these first.
* **Prerequisites.** P0.1.

### P7.2 — Start the November diary

* **Problem.** `docs/DEV_BLOG/` stops at `2026-10-DIARY.md`, but work continued into 01–03/11.
* **Change.** Create `docs/DEV_BLOG/2026-11-DIARY.md` (newest-first, per the project's rule) and
  record the 1.4.2 release, #68 and #60.
* **Files.** one new file.
* **Dependencies.** None.
* **Risk.** None.
* **Priority.** Low.
* **Prerequisites.** P0.1.

### P7.3 — Publish this analysis

* **Problem.** This analysis lives under `docs/WORKDIR/reports/android-port-analysis/` and is not
  linked from anywhere.
* **Change.** Link it from `AGENTS.md`'s "Must-Load Context" and from `docs/port/ANDROID_PORT.md`.
* **Files.** `AGENTS.md`, `docs/port/ANDROID_PORT.md`.
* **Dependencies.** Owner's review of the analysis.
* **Risk.** None.
* **Priority.** Low.
* **Prerequisites.** Owner's review.

---

## 10. Suggested order of execution

```
P0.1 branch + rule  ─┬─► P0.2 toolbox decision
                     ├─► P0.3 gradle wrapper ──► P2.4 colour-token test ──► P3.1 unit tests
                     ├─► P1.4 CI verify race
                     └─► P1.1 endpoints ──► P1.5 CI scope
P1.2 user-data (after reading the native branch) ──► P6.5 GameProfile
P1.3 engine-tree check
P2.1 split SetupActivity ──► P2.2 extract helpers ──► P5.1 wide screens
P3.1 ──► P3.3 update-manifest test ──► P3.2 instrumentation smoke ──► P4.1/P4.2 R8 + release build
P2.3 marker documentation
P6.1–P6.4 mods
P7.1–P7.3 documentation
```

---

## 11. What this plan deliberately does **not** do

* It does not touch `GameLogic`, the tick-rate constant, the CRC stream or the archive mount order —
  those are engine-stability boundaries (`ENGINE_ANALYSIS.md` §10).
* It does not turn cross-play with the PC client back on. That needs the owner's word **and**
  GeneralsOnline's agreement, and it needs the libm difference described in
  `LESSON-cross-play-desync-method.md` to be solved first.
* It does not un-park `PLAN-025` (APK size) or `PLAN-023` (Rooms) — both are owner decisions.
* It does not propose a rewrite. Every item is incremental and lands on the patterns the tree
  already uses.

---

## 12. Open questions for the repository owner

1. Which branch is the integration target for new work, given that `New-UI` is overwritten every
   6 hours and `main` holds a different shape?
2. Should the launcher's update / language-pack / data-pack endpoints point at this repository, and
   if so, who holds the signing key for the `updates` branch?
3. Is the toolbox work (`claude/ui-redesign-dashboard-drawer`) wanted on top of the current
   launcher, or should it be recorded as abandoned?
4. Should the base game and Zero Hour share one user-data folder (the engine's Android branch
   decides this; the Java currently assumes they do not)?
5. Are tester APKs to stay in `apk/testers/` indefinitely, or should they become GitHub Release
   assets?
6. Is `PLAN-025` (APK size, and with it R8 + a release build) to be started?
