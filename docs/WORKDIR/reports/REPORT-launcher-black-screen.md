# REPORT: Launcher black screen — nothing drawn, drawer edge-swipe still working

**Date**: 2026-10-15
**Area**: Android launcher shell (`android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java`,
`android/app/src/main/res/drawable/bg_dashboard.xml`, `android/app/src/main/res/values/dimens.xml`)
**Reported as**: "ببدء التطبيق تظهر شاشة سوداء بدون اي ازرار او واجهة لكن شريط السحب لليسار يعمل"
(on launch: a black screen with no buttons and no interface, but the left drawer drag works)

**Verdict**: reproduced and fixed. Not a rendering, GPU, or engine bug — the launcher's view tree was
built, attached and measured, but every child after the first was given zero height and laid out
below the bottom edge, leaving a full-screen near-opaque scrim view as the only thing on the
display. The `DrawerLayout`'s own edge drag does not depend on its content being visible, which is
why the drawer still responded.

## Why guessing was not allowed

The symptom "black screen" is compatible with a dozen causes (SDLXActivity never attaching the
game, a GL surface that never presents, an exception swallowed by `buildUi()`'s fallback, a theme
with no background). The launcher builds every screen in code, so no layout XML can be read to see
what should be on screen. The answer had to be measured, so a Robolectric unit test was added that
builds `SetupActivity`, runs a real `measure()`/`layout()` pass at 1080x2340 and dumps the tree:

`android/app/src/test/java/com/generalsx/zerohour/SetupShellLayoutTest.java`

```
cd android && ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest --tests '*SetupShellLayoutTest*'
```

## The measured tree (before the fix)

```
DrawerLayout 1080x2340
  LinearLayout 1080x2340 bg=LayerDrawable        <- content shell
    View 1080x2340 @0,0  bg=ColorDrawable        <- the top scrim took the WHOLE height
    LinearLayout 1080x0 @0,2340                  <- app bar: zero height, below the screen
    FrameLayout 1080x0 @0,2340                   <- page host: zero height, below the screen
      ScrollView 1080x0 @0,0
        LinearLayout 1080x4919 (page content measured, never visible)
          MaterialCardView 984x484           <- first card, absolute y = 2436 > 2340
  NavigationView 888x2340 @-888,0                <- drawer, items: NONE (empty menu)
    NavigationMenuView > LinearLayout 888x24
```

## Root causes

1. **The scrim ate the shell (`SetupActivity.buildDrawerShell`).** A plain `View` was added to the
   shell `LinearLayout` with `FrameLayout.LayoutParams` (wrong parent class — `LinearLayout`
   silently accepts them, since they extend `MarginLayoutParams`) and a `WRAP_CONTENT` height.
   `View.onMeasure` turns an `AT_MOST` spec into the spec size, and a `LinearLayout` measures a
   `WRAP_CONTENT` child `AT_MOST (parent height - used)`, so the empty view took all 2340px. Every
   later sibling was then measured against 0px of remaining space. The `setLayoutParams()` call
   before it that carried the intended 28dp height was discarded, because passing a params object
   to `addView()` replaces the one already set.
2. **The drawer's menu was never inflated.** `R.menu.drawer` is referenced nowhere in the Java
   sources: the shell created the `NavigationView` and stopped, so the drawer opened as a blank
   panel and `setCheckedItem()` had no item to check. This is the "black screen" from the other
   side — the drawer that still worked showed nothing either.
3. **The background was opaque black over everything (`bg_dashboard.xml`).** The layer list drew the
   opaque gradient *above* the panel-seam pattern (hiding it completely) and then a fully opaque
   `#000000` radial above both, so every pixel behind the cards was pure black — including the
   strip the top scrim was supposed to shade.
4. **Insets were applied to the padding-ignoring parent.** `InsetUtil.applySafeInsets(drawerLayout)`
   had no effect on the content: `DrawerLayout` gives its own padding only to its drawer panes and
   lays the content child out at 0,0. Measured with a throwaway probe (`DrawerLayout` with
   `paddingTop=150`, content child `MATCH_PARENT`): `content top=0 left=0 size=1080x2340`. The app
   bar therefore sat under the status bar / camera cutout on a real device.
5. **The drawer's items could never be marked as current.** `R.menu.drawer` declared no item
   checkable, and Material 1.12.0's presenter refuses non-checkable items: `NavigationMenuPresenter`
   returns early when `item.isCheckable()` is false, and `NavigationMenuItemView` only puts a row
   into the `state_checked` drawable state when the item is both checkable and checked. Measured
   here: with the plain menu, `nav.getCheckedItem()` was `null` even after an explicit
   `setCheckedItem(R.id.nav_home)` and even after tapping a real `NavigationMenuItemView` row
   (`item.isChecked()` stayed false). So the shell's `setCheckedItem()` calls, at build time and on
   every drawer close, were silently dropped on a device too: no destination was ever highlighted,
   which is what "the NavigationView items were not loaded" looks like from the UI.

## Fixes

- The scrim view is gone; the readability scrim is now the last layer of `bg_dashboard.xml`, where
  it cannot consume layout space. Its comment claims it is translucent and it now is
  (`#A6000000` -> transparent, radial, centred on the top edge).
- `bg_dashboard.xml` layer order corrected (opaque base, then pattern, then scrim) so the pattern is
  actually visible; `dimens.xml`'s now-dead `gzh_status_bar_height` removed.
- `drawerNav.inflateMenu(R.menu.drawer)` — the item list `onDrawerItemSelected()` already handles —
  guarded with `if (drawerNav.getMenu().size() == 0)` so the menu is inflated once per
  `NavigationView` (buildUi() runs again on a language change and on every recreate, but each run
  builds a fresh shell; the guard makes "once" a property of the method, not of its callers).
- Every destination in `menu/drawer.xml` is now `android:checkable="true"` (cause 5); the
  `nav_tools_header` section header deliberately is not.
- Diagnostics: a `TAG` constant plus `Log.e` at `onCreate` (entry, restored state, tab), `buildUi`
  (begin/end with the page-host child count), `buildDrawerShell` (begin, shell built, drawer menu
  item count and checked id, end with the drawer width and page), `showTab` (building/ignored/attached
  with the title) and the fallback (entry and "up" with the view count); `onCreate`'s catch logs the
  full Throwable with `Log.e(TAG, msg, t)`. The two pre-existing `Log.w("SetupActivity", …)` calls
  now use the same TAG. `buildFallbackUi()` itself was reviewed: it builds plain framework widgets
  only (`Button`, not `MaterialButton`), shows the failure text, sets no content host so `showTab()`
  becomes a no-op, and keeps its four actions wired — asserted below.
- `InsetUtil.applySafeInsets(shell)` instead of the `DrawerLayout`.
- Drawer width bounded to `gzh_drawer_width` (300dp, minus two gutters on a narrow screen) instead
  of `MATCH_PARENT`, which covered the whole display when open.

## Verification

- **Red before green.** The geometry assertions (every visible shell child has height > 0 and is on
  screen; the app-bar title and the first `MaterialCardView` are within the screen; at least five
  on-screen texts; at least five drawer menu items) fail on the unfixed tree with
  `shell child 1 (LinearLayout) got no height, so it is invisible`, and pass after the fix. The
  earlier version of the test asserted only "non-zero somewhere" and passed on the broken tree —
  which is how this bug survived a test in the first place.
- **Fixed tree**: app bar 1080x204 @0, page host 1080x2136 @204, `ScrollView` 1080x2136 @204, first
  card 984x484 at absolute y=252 (on screen), 46 on-screen text views. Drawer: `NavigationView`
  900x2340 with real `NavigationMenuItemView` rows at y=24, 168, 312, 456.
- **Six tests, 0 failures** (`tests="6" skipped="0" failures="0" errors="0"`):
  `theDashboardIsOnScreenAndTheDrawerHasItems`,
  `theDrawerListsEveryDestinationAndEachOneTakesTheUserSomewhere`,
  `theToolDestinationsOpenTheirScreensAndThePageSurvivesComingBack`,
  `rotationKeepsThePageAndARecreateRestoresIt`,
  `switchingGamesRebuildsTheShellAndInflatesTheMenuOnce`,
  `theFallbackUiIsReachableUsableAndSurvivesTheFailure`. They cover: the cold-start home page;
  the drawer's nine destinations plus the section header (listed, labelled, checkable, each row
  taking the user somewhere — page swap or the screen it names); the header being a label only;
  the drawer marking the current destination (`getCheckedItem()` now returns the item that was
  tapped); Generals/Zero Hour switching rebuilding the shell with the drawer menu inflated exactly
  once (item count unchanged); rotation via `onConfigurationChanged` keeping the page, a recreate
  restoring it from `savedInstanceState`, and a cold start landing on Home; returning from
  `LogViewerActivity`/`NetworkDiagnosticsActivity`/`ReplayCheckActivity`/`GeneralsOnlineActivity`
  with the page intact; and the fallback UI surviving its own failure.
- `:app:assembleDebug` (the project's own settings) exits 0 and produces
  `app/build/outputs/apk/debug/app-debug.apk` (13.9 MB), so no Java, resource or AAPT error was
  introduced. The theme (`Theme.GeneralsZHSettings`, Material 3 Dark NoActionBar) and
  `AndroidManifest.xml` are unchanged by this work, and every resource reference in the edited
  XML (menu/drawables/strings/dimens) is resolved by aapt2 in that build.
- Packaging (request item 10): the diff touches no CMake, no script, no `AndroidManifest.xml`, no
  `gradle.properties`, no signing, ABI or `minSdk`; `packagingOptions.jniLibs` and
  `abiFilters 'arm64-v8a'` are untouched, so `libgenerals.so`, `libgenerals60.so`, `libmain.so` and
  `libmain60.so` keep their names, their `lib/arm64-v8a/` path and the 30/60 Hz mechanism. The only
  `build.gradle` change is test-only (`testImplementation` + `testOptions.unitTests`).
- Swept for the same class of bug: every other `addView(child, new *LayoutParams)` in the launcher
  package pairs the params class with the actual parent type. The scrim was a single instance.

## Not verified here (no device or emulator was available)

- **No run on a physical device or emulator.** Everything above is measured against the real
  Android framework and Material classes (Robolectric 4.14.1, SDK 33) with a real
  measure/layout pass. A JVM measurement is stronger than "Gradle succeeded" but it is not a
  device run, and no claim of one is made.
- **DrawerLayout's animated open/close does not run here.** `ViewDragHelper` advances the slide from
  the draw pass (`View.computeScroll`), which Robolectric never performs: measured,
  `openDrawer(Gravity.START)` and `openDrawer(Gravity.LEFT)` both leave the panel at `left=-900`
  with no listener callback and no open flag, while `openDrawer(view, false)` moves it to 0. The
  app-bar button is therefore asserted for its wiring (clickable, attached, listener present, click
  reported) and the panel for the open state, driven non-animated. Whether the *animation* runs on a
  device was not observed here.
- **`nav_folder`'s target screen is not asserted**: on API >= R its handler reads
  `Environment.isExternalStorageManager()`, which Robolectric does not implement (it throws from
  `android.os.Environment`, inside production code the test cannot stub). The item's presence and
  label are asserted instead.
- **Window insets are zero here**, so the padding the shell receives on a device with a status bar
  and a cutout was not observed; the `DrawerLayout` behaviour behind that change was measured
  directly (probe above), the inset values themselves were not. The drawer pane's own insets are
  left to Material's `NavigationView`.
- **The debug APK built here contains no native libraries**: `app/src/main/jniLibs` is empty in this
  workspace, so `mergeDebugNativeLibs` is `NO-SOURCE`. A runnable APK needs the engine build
  (`./scripts/build/android/build-dual-hz.sh`), which was not run here.
- Robolectric is a new test-only dependency (`android/app/build.gradle`); it does not reach the APK.
  The repo has no committed Gradle wrapper yet, so the wrapper present in this workspace is local
  and untracked.

## Open question for the owner (not changed here)

The drawer has no destination that shows the launcher's **Tools page** (`buildLogsSection()` +
`buildDiagnosticsSection()`, which holds the diagnostics master switch that decides whether
anything is logged at all). `showTab(TAB_TOOLS)` has no caller in the drawer shell: `nav_logs` starts
`LogViewerActivity` instead, and `syncDrawerSelection()` maps `TAB_TOOLS` to `nav_logs` without ever
showing that page — so the page is unreachable from the drawer UI (it was reachable from the legacy
bottom navigation, which the drawer shell does not build). Left as found, because closing that gap
is a navigation decision: either a new drawer item (and its string in all 13 launcher locales, per
the repo rule) or pointing an existing item at the page instead of its own screen. Nothing was
removed or hidden by this fix.

---

## Second pass: the dashboard reorganisation (same day, same branch)

The owner asked for the launcher to be reorganised on top of the repaired shell: launch first,
Generals Online second, the settings and tools behind the drawer, and nothing hidden that used to
work. Everything below moved or was re-laid out; nothing was deleted.

**Dashboard (`buildHomeSection`).** Three cards now, in this order, and the test pins the count:

1. **Main game card** — the selected game's name as the heading, the *folder state* line under it
   (`refreshStatus()` colours it: not set / invalid / missing files, so an incomplete folder is
   visible without opening anything), the Play button wired to the existing `onLaunchGame()`, a
   secondary change-folder button, the Generals/Zero Hour segmented switch, and the skip-intro row.
   The game selection moved *into* this card: switching games is part of choosing what to launch.
2. **Generals Online** — kept exactly as it was (the real sign-in line from
   `GeneralsOnlineActivity.getSignedInDisplayName()`, the anti-cheat note, the button into the
   Online screen), just raised to directly after the game card, and its button is now primary. No
   status was invented and no shortcut was added that the Online screen does not have.
3. **Updates** — the brief version (engine status, check button, auto-update row, and the
   conditional "a newer data pack is out" button).

The dashboard's quick-access card (folder/logs/network/replay rows) is gone: those were the drawer's
entries in duplicate. Folder work moved to its own page, the rest to the drawer.

**Drawer.** Ordered as requested — Home; *Settings* header (Graphics, Interface, Game folder); *Tools*
header (Logs, Network Diagnostics, Replay Check); Generals Online; Help — with `nav_settings_header`
reusing the existing `nav_drawer_settings` string. Two defects fixed along the way: the section
header lookup used `Arrays.binarySearch` over an array nothing keeps sorted (aapt assigns R.id
values), which is now a linear scan; and all five drawer labels were missing from the 12 locale
files (the drawer had never been inflated, so nobody had needed them) — added, so the drawer is not
English-only for Arabic, Russian, Persian, Korean and the rest.

**Game folder page (new `TAB_FOLDER`).** The folder card the dashboard used to stack — pick, change,
clear, and Zero Hour's base-game archive folder — now lives on the drawer's "Game folder" page,
behind the same handlers. `nav_folder` opens that page instead of jumping straight into the picker,
so the entry is a destination, not a one-shot action, and the picker is its first button.

**App bar.** `UiKit.appBar` grew an overload with a leading mark (the launcher's brand icon, tinted
in the accent); the drawer shell passes it, so the bar reads as [mark] "COMMAND & CONQUER GENERALS:
ZERO HOUR" over "Home" [menu]. Existing callers keep the old signature.

**Verification.** 12 tests (`tests="12" failures="0" errors="0"`): the previous six, plus the
dashboard order and card count, the launch button being visible and wired on a cold start, the Online
button opening `GeneralsOnlineActivity`, the drawer's exact item order and checkable/unselectable
split, the Game folder page keeping its four actions, Back not leaving the app while the drawer is
open (and leaving it when closed), the Graphics/Interface pages still carrying the simulation rate,
the render backend and the Interface page's four sections, and each game keeping its own folder when
the selection changes (two distinct keys, and switching never carries one game's path into the
other's). `assembleDebug` exits 0; `assembleRelease` exits 0
and produces a signed `app-release.apk` (11.7 MB, committed keystore,
`CN=GeneralsX Android Debug`, matching the CI's known fingerprint). Source files are older than the
test results and both APKs, so the green runs cover exactly what was delivered.

**Limits, unchanged from above.** No device or emulator was available: none of this is a runtime
test on hardware. The harness still cannot run `DrawerLayout`'s animated slide, still throws on
`Environment.isExternalStorageManager()` (so `nav_folder`'s picker screen is not asserted), and still
reports zero window insets. The debug APK built here has no native libraries in `jniLibs`; the
engines, their names, the 30/60 Hz options, CMake, DXVK and the packaging scripts are untouched (the
diff is two Java files, one menu, twelve locale files and the test).
