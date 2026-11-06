# Touch controls editor — layout rework, background image and Clear Key

Date: 2026-11-06 · Branch: `New-app`

Follows `TOUCH_EDITOR_UPGRADE_20261105.md`. This session did three things to the touch controls
editor: it made the launcher page's layout structural and responsive, put a bundled battlefield
picture behind it, and added a Clear Key action to both the launcher page and the in-game floating
editor.

## 1. Modified files

Changed (`android/app/src/main/java/com/generalsx/zerohour/`):

| File | Change |
|---|---|
| `TouchControlsActivity.java` | the whole page rebuilt in four bands; `SplitBody`; `FlowLayout` groups; scrollable inspector; wrapping footer; Dim control; Clear Key; safe-area insets |
| `HotkeyOverlayView.java` | `TOOL_CLEAR_KEY` cell (toolbar 10 → 11 cells); localised Cancel/Save labels; unassigned caption on a cleared button |
| `TouchControlConfig.java` | `SCHEMA` 4 → 5; `ButtonSpec.isAssigned()`; `readButton` keeps an unassigned key but still refuses a refused key |
| `TouchLayoutEditor.java` | `clearSelectedKey()`; `setSelectedLabel` no longer invents a label for an unassigned button |
| `TouchKeyCatalog.java` | `isUnassigned()`, `unassignedName(Context)`, `combinationName(Context, int, int)` |
| `TouchKeyPresser.java` | `keysFor()` returns nothing for an unassigned button |
| `TouchEditorRenderer.java` | a cleared button draws the localised "Unassigned" caption |

Added:

| File | Role |
|---|---|
| `android/app/src/main/java/com/generalsx/zerohour/FlowLayout.java` | a row that wraps |
| `android/app/src/main/java/com/generalsx/zerohour/TouchEditorBackgroundView.java` | centre-cropped background picture + scrim |
| `android/app/src/main/res/drawable-nodpi/touch_editor_background.jpg` | the bundled picture (1024×768) |
| `android/app/src/test/java/com/generalsx/zerohour/TouchEditorResponsiveLayoutTest.java` | 16 tests: overlap, containment, orientation, RTL, background |
| `docs/WORKDIR/reports/TOUCH_EDITOR_LAYOUT_BACKGROUND_CLEARKEY_20261106.md` | this report |

Also: all 13 `res/values*/strings.xml` (`touch_clear_key`, `touch_key_unassigned`,
`touch_key_cleared`, `touch_editor_background_dim`, `touch_section_appearance`,
`touch_section_layout`), four test files extended, `CREDITS.md`, `docs/DEV_BLOG/2026-11-DIARY.md`.

Not changed: any native file, `Core/**`, `Generals/**`, `GeneralsMD/**`, any CMake file, any
layout XML (the editor is built in code), the theme colours, or the button-mapping system.

## 2. Root causes of the layout problems

The old page was one `LinearLayout`: header, a body, a footer. Each of the reported symptoms has a
concrete cause in that structure.

**Panels and options overlapped.** Every group of cells — the six shapes, the four polygons, the
three activation modes, the eight colour swatches, the six footer actions — was a plain horizontal
`LinearLayout`. A horizontal `LinearLayout` does not wrap: with the inspector at a fixed 300 dp on a
narrow phone, the last cells were measured past the right edge, were clipped, and drew over
whatever was beside them. This is the "elements appear outside their intended containers" symptom,
and it is one cause, not five.

**Arabic labels were clipped.** The help line was a single `TextView` in the header with no
`maxLines`/wrap width of its own, competing for the row with the title. Arabic is longer than
English at the same font size, so it ran out of room and was cut. Clipping a label is a width
problem, so it is fixed by giving the text a real width — not by shrinking the font, which the
brief forbids.

**Controls were crowded, and the bottom buttons could become inaccessible.** The footer put a
`layout_weight="1"` spacer between the destructive and the saving actions. A weighted *empty* view
in a `WRAP_CONTENT` parent has no defined size of its own, so it stretched the footer to the whole
window height and squeezed the body above it — the footer's own buttons were then pushed off the
bottom on a short screen. It also fought the body for vertical space, which is the "crowded"
feeling.

**The preview did not adapt.** The canvas was a plain `View` added with default `LayoutParams`, so
it measured to a few pixels wide and zero high and drew nothing — the editor could load a layout
and show no button at all. That is why the surface is attached `MATCH_PARENT` and is the thing
`invalidate()` is called on, and it is what `TouchEditorLauncherLayoutTest` pins down.

**Nothing accounted for the system bars.** No insets were applied, so the header could sit under
the status bar and the footer under the gesture bar.

## 3. How the responsive layout was implemented

**Structure.** The page is four bands: header, body, footer, with the picture behind all of them.

**The split.** The body is a `SplitBody extends LinearLayout` that decides its own orientation from
the size it is actually given — side by side while it is wider than it is tall, stacked in portrait
— in `onMeasure` and again in `onSizeChanged`. It is deliberately not a `OnGlobalLayoutListener` or
a window-size listener: neither fires for a view tree measured and laid out by hand (a test, a
preview render), and both arrive after the first layout, so the first frame would be the wrong
shape. Deciding inside the container means the split is right on the first frame and after a
rotation, with no recreate (the manifest already keeps the Activity across `orientation` and
`screenSize`, so `onConfigurationChanged` re-arranges in place).

**The panel's width.** In landscape the inspector takes 42 % of the body, clamped to 220–400 dp and
further clamped so the preview always keeps at least 200 dp. The *width* is part of the guard that
skips a redundant re-arrangement, because the first pass runs before the real width is known;
without that, the panel would freeze at the fallback width forever. That was a real bug found by
the tests, not a hypothetical.

**Wrapping instead of clipping.** `FlowLayout` measures its children at their own size and starts a
new line when the next cell would not fit. Cells keep their size — nothing is shrunk and no option
is hidden — and it honours the layout direction, so an Arabic inspector reads right to left. It is
used for every group of cells including the footer, so Cancel/Save/Apply can never be pushed off
the bottom edge.

**Scrolling.** The inspector's whole content is one `ScrollView` with weight, so a long inspector
scrolls under its own fixed header instead of pushing the preview away. The preview keeps its own
weight, so it is always usable.

**Insets and touch targets.** `InsetUtil.applySafeInsets(root)` pads the root by the system-bar and
cutout insets, so neither band is drawn under a bar. Interactive cells keep a `dp(48)` minimum
height, so a finger can hit them.

**Theme.** The existing colours are untouched: the same dark background, the same cyan section
labels, the same accent for the selected chip. `TouchEditorRenderer` still draws the button borders
in cyan/yellow exactly as before.

## 4. How the background image is rendered

The picture is bundled at `res/drawable-nodpi/touch_editor_background.jpg` and decoded with
`BitmapFactory` — there is no runtime download anywhere in the code. `drawable-nodpi` is used
rather than a density bucket because the picture is scaled at draw time; letting Android
pre-scale it into a bucket would throw away resolution before the centre-crop even runs.

`TouchEditorBackgroundView` centre-crops: it scales by the *larger* of the view's two aspect ratios
and takes the centred sub-rectangle of the other axis, so the picture keeps its proportions and
fills the view with no empty bands in either orientation. `min()` would letterbox (empty bands,
which the brief forbids); stretching would distort it. The picture is 1024×768, larger than a phone
in landscape, so it is not upscaled in the common case.

Readability is a translucent black scrim over the whole view, at a stored opacity (`Dim`, default
62 %, range 0–92 %) that the player can change from the inspector; the setting persists in the
launcher's own preferences. The view is `clickable=false`, `focusable=false` and
`IMPORTANT_FOR_ACCESSIBILITY_NO`, and it is the root's first child, so it is behind every control
and consumes no touch — a press on empty space still reaches the preview canvas.

## 5. How Clear Key works internally

**The state.** An unassigned button is `keyCode == KeyEvent.KEYCODE_UNKNOWN`. That representation
already existed in the model (it is the "nothing assigned" value the codec used), so no second
mapping format was introduced; the change is that `readButton()` now *keeps* such an entry instead
of dropping it, while still refusing a key the catalog refuses (Home, Power, the volume rocker).
`SCHEMA` went 4 → 5 for the new legitimate state; older documents load unchanged.

**The operation.** `TouchLayoutEditor.clearSelectedKey()` sets `keyCode` to `KEYCODE_UNKNOWN` and
clears `modifiers` — a modifier with no primary key would otherwise still press Ctrl — and touches
nothing else. The button stays in the list, in the same slot, with its position, size, shape,
colours, opacity, activation mode and label. It is still drawn, still hit-tested and still
draggable, and `setSelectedKey` can assign it a new key later.

**Three distinct operations, as required:**

| Operation | Call | Effect |
|---|---|---|
| Clear Key | `clearSelectedKey()` | key gone, button kept |
| Delete | `deleteSelected()` | button removed |
| Reset profile | `restoreDefaults()` | every button replaced by the shipped layout |

**No unintended input.** `TouchKeyPresser.keysFor()` returns an empty array for an unassigned
button, so `pressKeys()` sends no `keyDown` — no primary key, no modifiers, no default. This holds
even for a hand-edited file that left modifier bits on an unknown key.

**Presentation.** A cleared button draws the localised "Unassigned" caption (resolved at draw time,
so it follows the app language) in both editors, and the in-game toolbar cell reads "Clear" while
there is something to clear and dims to "–" when there is not.

**Persistence.** `TouchControlConfig` writes the unknown key and reads it back, so a cleared
mapping survives Save and a later reopen, and import/export carries it unchanged.

## 6. Tests performed and their results

All run on the host, with no native build and no emulator: `gradle :app:testDebugUnitTest`.

**243 JVM tests, all green.** 26 are new this session.

New coverage, and what each one is for:

- `TouchLayoutEditorTest` (7 new): clearing keeps the button and every visual property; clearing
  drops the modifiers; a cleared button can be reassigned; a second Clear is a no-op; Clear with no
  selection is refused; other buttons are untouched; a cleared mapping survives save and reload;
  and Clear and Delete are asserted to be different operations.
- `TouchKeyPresserTest` (2 new): a cleared button presses nothing at all (no key, no modifier, no
  substitute), including when stale modifier bits were left on it.
- `TouchControlConfigTest` (2 new): an unassigned key is kept across the JSON round trip; a refused
  key is still refused and a document of only refused keys is still rejected.
- `HotkeyOverlayTouchRoutingTest` (4 new): the in-game toolbar's Clear Key clears only the selected
  mapping, driven through the real touch path; the cleared button stays selectable and draggable;
  pressing a cleared button in the match sends no key; and the cleared state survives Save.
- `TouchEditorResponsiveLayoutTest` (16 new): preview and inspector never overlap at two sizes;
  no control is drawn outside its container; the footer stays on screen at 1000×600, 420×900 and
  360×640; all six footer actions are still offered; the split follows the window shape and
  re-arranges on a resize; the Activity survives a configuration change; a wrapping row follows the
  layout direction (LTR and RTL) and keeps every cell inside the width; the help sentence gets the
  full width; the background is bundled, behind everything and not clickable; it centre-crops with
  no transparent band in four aspect ratios; the scrim darkens the picture and is clamped; the
  background refuses a touch; Clear Key on the page clears only the selected mapping; and Clear is
  disabled once there is nothing to clear. Three of these run under `@Config(qualifiers = "ar")`:
  the page is RTL, nothing escapes its container, the footer stays on screen, and a cleared button
  reads the Arabic caption.

**Builds.** `gradle :app:assembleLauncherOnly` — `BUILD SUCCESSFUL`, and
`res/drawable-nodpi-v4/touch_editor_background.jpg` is present in the APK.
`gradle :app:lintDebug` — `BUILD SUCCESSFUL`, no new errors; the warnings it does raise
(`ViewConstructor`, `ClickableViewAccessibility` on `EditorCanvas`) match the two pre-existing
overlay views and are the same class of finding.

**Resource validation.** `TranslationResourceTest` (pre-existing) checks that every locale declares
the same string names, that no translation is empty, that printf arguments match, and that
apostrophes are escaped — so the six new strings are verified present in all 13 locales by a test,
not by eye.

## 7. Remaining limitations

- The layout is exercised by Robolectric's measure/layout, which is faithful to the real View
  system but is not a device. The numbers (42 %, 220–400 dp, 48 dp targets) are chosen to be safe
  across the common range; a foldable in a half-open posture or an unusual aspect ratio is not
  covered by a test, only by the formula.
- The background is 1024×768. On a tablet larger than that in landscape it is upscaled, so it is
  soft there. A higher-resolution source would fix it; the composition and the crop do not change.
- The Dim setting is stored in the launcher's own preferences, not in the layout document, so it is
  per-device rather than per-profile. That matches the other editor preferences.
- The in-game toolbar now has eleven cells instead of ten, so each cell is ~9 % narrower on a given
  screen; labels are fitted to their cell (`drawFittedText`) so nothing is clipped, but a future
  tool would want a scrolling toolbar rather than a twelfth cell.
- `TouchEditorLauncherLayoutTest` is the pre-existing test that first caught the un-attached surface;
  it passes unchanged.
