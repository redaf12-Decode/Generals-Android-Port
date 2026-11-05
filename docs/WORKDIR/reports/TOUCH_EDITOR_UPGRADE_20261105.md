# Floating touch controls, toolbox and button editor — upgrade report

Date: 2026-11-05 · Branch: `New-app` · Commit: `696ea37`

Companion to `TOUCH_EDITOR_AUDIT_20261105.md`, which is the pre-change audit this report answers.

## 1. Modified files

Changed (`android/app/src/main/java/com/generalsx/zerohour/`):

| File | Change |
|---|---|
| `HotkeyOverlayView.java` | `TOOL_ADD` toolbar cell; `toolbarHeightPx()`; reserved-strip hand-off to the editor; localised shape/polygon menu labels; `drawFittedText()` |
| `TouchLayoutEditor.java` | `setReservedBottomPx()`, `placeNewButton()`, `overlapsAny()`; `addButton()` places instead of using a fixed point |
| `TouchEditorRenderer.java` | buttons drawn with their own colours only; selection moved to an external frame + handle + badge |
| `ToolboxOverlayView.java` | Add row, Hide row, icon from the bundled VectorDrawable, panel state persisted |
| `ToolboxConfig.java` | `panelOpen`, per game, default `false` |
| `TouchControlsActivity.java` | border-colour swatch row |
| `SetupActivity.java` | icon attribution caption in the About card |

Added:

| File | Role |
|---|---|
| `android/app/src/main/res/drawable/ic_gzh_toolbox.xml` | the traced Flaticon icon |
| `android/app/src/test/java/com/generalsx/zerohour/TouchEditorAppearanceTest.java` | 21 tests for the upgrade's promises |
| `android/app/src/test/java/com/generalsx/zerohour/TouchKeyCatalogTest.java` | 7 tests: the task's own key list, as an acceptance criterion |
| `CREDITS.md` | third-party asset attribution |
| `docs/WORKDIR/reports/TOUCH_EDITOR_UPGRADE_20261105.md` | this report |

Also: `android/app/build.gradle` (`unitTests.includeAndroidResources = true`), `README.md`
(credits), all 13 `res/values*/strings.xml` (`touch_border_color` and the earlier shape/toolbox
strings), `docs/DEV_BLOG/2026-11-DIARY.md`, and `HotkeyOverlayTouchRoutingTest.java` (two
assertions strengthened).

Not changed: `Core/**`, `Generals/**`, `GeneralsMD/**`, `GroupPanel.wnd`, any native touch file,
any CMake file. `git show --stat 696ea37` lists all 27 files.

## 2. Root cause — the missing Add Button

It was **never wired**, not broken. `HotkeyOverlayView` had `TOOL_COUNT = 8`
(`TOOL_SIZE_DOWN` … `TOOL_SAVE`) with no `TOOL_ADD`, and `runTool()` had no case that called
`addButton()`. The *model* already had `TouchLayoutEditor.addButton()` and the launcher's
full-screen editor already called it, so only the in-game editor lacked the operation — which is
why it looked like a broken button rather than a missing one.

The second defect surfaced while fixing the first: `addButton()` placed the new button at the
fixed fraction `(0.5, 0.6)`, which on a phone in landscape is inside the editor's own bottom
toolbar. A button created there was immediately underneath the toolbar.

Fix: a tenth toolbar cell and a toolbox row both call the one model method. `addButton()` now calls
`placeNewButton()`, a bounded ring search from `(0.5, 0.42)` outwards that avoids
`reservedBottomPx` (the toolbar's height, reported by `HotkeyOverlayView.toolbarHeightPx()`), the
window insets, and — when the screen has room — every enabled button. Deterministic, no randomness.

## 3. Root cause — the accent colour on buttons

`TouchEditorRenderer.draw()` drew a **selected** button a second time:

```java
borderPaint.setColor(accentColor);   // the launcher accent
borderPaint.setStrokeWidth(dp(3f));
drawShape(canvas, spec, scale);      // over the button's own outline
```

So the button's saved `borderColor` was on screen only while the button was *unselected*; selecting
it — which happens automatically when the editor opens — replaced its border with the global
accent. That is exactly the reported symptom, "the global accent overwrites my button colour".

Fix: a button is always drawn with its own `fillColor` / `borderColor` / `textColor`, in the editor
exactly as in the match. Selection is an accent **dashed frame drawn outside the button's box**,
plus the resize handle and the position badge. Nothing about the selection path writes to the
model, so entering or leaving the editor cannot change a saved appearance, and changing the accent
cannot change a button.

## 4. Shape selection

`TouchButtonShape` keeps the reference numbering `RECTANGLE=0 … OVAL=4` and appends `POLYGON=5`, so
a layout saved by an older build loads with the same shape. Polygon variants are
`TRIANGLE`/`PENTAGON`/`HEXAGON`/`OCTAGON` (0–3).

- Rendering and hit testing share one pair of vertex tables (`CORNER_COS`/`CORNER_SIN`, computed
  once at class load), so the drawn polygon and the tappable polygon cannot drift apart.
- Hit testing is analytic — an ellipse test, a rounded-rect test, a convex-polygon test — so no
  `Region` or `Path` is built per touch event.
- Square and circle take the shorter side (`shapeRect`), so a one-dimensional resize moves the
  shape instead of stretching it; `isFixedAspect` is what the editor's sliders honour.
- The shape lives in the button's config (`shape`, `polygonType`), defaults to `ROUNDED` for a
  document that has no `shape`, and an out-of-range value is replaced rather than trusted.
- Changing the shape never touches `keyCode` or `modifiers` (`TouchLayoutEditorTest
  .changingShapeKeepsKeyAndPosition`, `.shapeChangeDoesNotLoseTheModifiers`).

## 5. Toolbox icon — source and licensing

- Source: <https://www.flaticon.com/free-icon/control_6841795>, title **"Control"**, author
  **Creatype** (the page's author line, not Freepik, which the URL pattern suggests at a glance).
- Licence: **Flaticon Free License**, free for personal and commercial use **with attribution**.
- Handling: traced to `res/drawable/ic_gzh_toolbox.xml` (`24dp`, `viewportWidth/Height 512`) so it
  stays sharp at every density; rasterised once in the constructor and drawn scaled and centred in
  the icon box. **Nothing is fetched at runtime** — the asset is compiled into the APK.
- Attribution ships in four places, because the licence requires the credit to be visible and the
  repository's own rule is that a credit should not live only in a file: the drawable's header
  comment, `CREDITS.md`, `README.md`, and an in-app caption in the launcher's About card in all 13
  locales.

## 6. Build

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/workspace/.buildtools/sdk ANDROID_SDK_ROOT=/workspace/.buildtools/sdk \
/workspace/.buildtools/gradle-8.9/bin/gradle clean :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

Result, from a clean tree:

```
BUILD SUCCESSFUL in 33s
41 actionable tasks: 40 executed, 1 up-to-date
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 13 403 204 bytes.

Icon packaged, verified on the built APK rather than on the source tree:

```
$ unzip -l app-debug.apk | grep ic_gzh_toolbox
     2204  1981-01-01 01:01   res/drawable/ic_gzh_toolbox.xml
$ aapt2 dump resources app-debug.apk | grep -A1 ic_gzh_toolbox
    resource 0x7f0700a6 drawable/ic_gzh_toolbox
      () (file) res/drawable/ic_gzh_toolbox.xml type=XML
```

The SDL3 3.4.2 Java glue was staged into the gitignored `src/main/java-sdl` the way
`package-android-zh.sh` does it. The native `.so` engines are **not** built in this environment
(needs the NDK and the vcpkg tree), so this is a Java/APK build, not a device-verified APK.

## 7. Tests

`gradle :app:testDebugUnitTest`, from the test-result XML:

| Class | Tests | Failures | Errors |
|---|---|---|---|
| `HotkeyOverlayTouchRoutingTest` | 12 | 0 | 0 |
| `TouchButtonShapeTest` | 9 | 0 | 0 |
| `TouchControlConfigTest` | 12 | 0 | 0 |
| `TouchEditorAppearanceTest` | 21 | 0 | 0 |
| `TouchKeyCatalogTest` | 7 | 0 | 0 |
| `TouchKeyPresserTest` | 11 | 0 | 0 |
| `TouchLayoutEditorTest` | 19 | 0 | 0 |
| **Total** | **91** | **0** | **0** |

These are not UI-existence tests. `TouchEditorAppearanceTest` runs under Robolectric with
`@GraphicsMode(NATIVE)`, drives the real views through real `MotionEvent`s and asserts on real
pixels and on the model. The checks that map to the task's verification list:

| # | Check | Test |
|---|---|---|
| 1 | Add Button visible and functional in the editor | `tappingTheToolbarAddCellAddsAButton` (taps cell 0 of the real toolbar) |
| 2 | Add Button in the in-game toolbox | `theToolboxAddRowAddsThroughTheModel` |
| 3 | Both use the same editor model | `theOverlayAddEntryPointUsesTheEditorModel`, plus the two above asserting the same `editorButtonCountForTest()` |
| 4 | New button appears immediately and is selectable | same three; `addButton` selects the new index |
| 5 | Icon bundled and drawn | `theBundledToolboxIconIsDrawnCentredAndOpaque` + `aapt2` output above |
| 6 | Hide leaves a reopening mechanism | `theToolboxHideRowKeepsTheIconAndButtons`, `iconIsVisibleForTest()` |
| 7 | Square / Circle / Polygon render | `TouchButtonShapeTest.squareAndCircleAreOneSide`, `.polygonsContainCentreAndRejectBoxCorners` |
| 8 | Polygon variants and hit testing | `polygonSidesMatchTheirNames`, `tappingAPolygonCellStoresTheVariant` |
| 9 | Shape change preserves the key | `everyShapeKeepsTheKey`, `everyPolygonTypeIsSelectableAndKeepsTheKey` |
| 9b | The named key list, sided modifiers and combinations | `TouchKeyCatalogTest` (7 tests) |
| 10 | Accent does not overwrite button colours | pixel-sampling tests in `TouchEditorAppearanceTest` |
| 11 | Selection indicator does not alter the spec | `selectionDoesNotChangeTheSavedAppearance` |
| 12 | Save / Cancel | `editorDragThenSaveWritesTheNewPosition`, `editorDragThenCancelLeavesTheStoredLayoutAlone` |
| 13 | Existing layouts stay compatible | `TouchControlConfigTest` migration tests |
| 14 | Gestures still reach the game outside the overlay | `touchOnEmptySpaceFallsThroughToTheGame`, `theEditorConsumesEvenEmptySpace`, `leavingTheEditorRestoresPassThrough` |
| 15 | No stuck keys after interruption | `pauseReleasesAKeyThatWasStillHeld`, `cancelReleasesEverything` |

## 8. Known limitations

- **Not device-verified.** The native engines cannot be built here, so the evidence is the APK
  build plus the JVM suite. Every claim above is a claim about the Java layer and the built APK.
- **`apk/` is untouched.** No APK was copied into `apk/` and nothing was pushed; the branch state
  is a single commit on `New-app`.
- **Hit testing is "as closely as practical", as the task allows.** A polygon's edge is exact to
  the same vertices the `Path` uses; a circle is tested as an ellipse, so a button whose box is not
  square is tested as the ellipse it is drawn as rather than as a circle.
- **Toolbar labels are English literals for `Cancel` / `Save`**, which is how they were before this
  change; the *new* labels (shapes, polygons, the toolbox rows, the toasts) are localised in all 13
  locales. Localising those two is a follow-up, not a regression.
