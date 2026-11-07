# Touch controls editor — background confined to the layout area, actions in a popup

Date: 2026-11-07 · Branch: `New-app`

Follows `TOUCH_EDITOR_LAYOUT_BACKGROUND_CLEARKEY_20261106.md`. Two defects in the launcher's Touch
Controls Editor are fixed here, both of them the page doing more than it meant to:

1. The bundled battlefield picture was behind the **whole editor page**, not behind the button
   layout area.
2. A button's actions lived in an always-on inspector panel, not in a menu that opens on the
   button the player is actually editing.

Nothing about the button model, the key catalog, the modifier system, import/export, presets or
saving changed.

## 1. Modified files

| File | Change |
|---|---|
| `TouchControlsActivity.java` | the picture moved into the canvas `FrameLayout`; the action popup (`showActionPopup`, `buildActionMenu`, `runAction`, `dismissActionPopup`); the inspector's key grid and Clear Key button removed; the inspector is rebuilt only when the selection changes |
| `TouchEditorBackgroundView.java` | `cropToBounds` mode: the centre-crop is clipped to the view's own bounds |
| `TouchLayoutEditor.java` | `setSelectedKey` updates the button's default caption with the key |
| `TouchEditorResponsiveLayoutTest.java` | 10 tests: the confined picture, the popup's rows, immediate application, the real tap path; the three Clear Key-on-the-page tests now drive the popup |
| `TouchLayoutEditorTest.java` | 3 tests: the caption follows the key, a custom caption survives, a reassigned button takes the new caption |

Not changed: any native file, `Core/**`, `Generals/**`, `GeneralsMD/**`, any CMake file, any layout
XML (the editor is built in code), `TouchControlConfig` (the store and its schema), the key
catalog, `TouchEditorRenderer`, `HotkeyOverlayView`, the in-game floating editor, or any
`strings.xml` (no new string was needed — every row reuses an existing one).

## 2. Root cause 1 — the picture was behind the wrong thing

`buildLayout()` attached `TouchEditorBackgroundView` as the **root** `FrameLayout`'s first child, so
it sat behind the header, the inspector, the sliders, the help text and the footer — the entire
page. The report that added it records this as the intent ("behind the whole page"), which is the
defect: the picture is the battlefield, and the battlefield preview is the canvas.

Fix: the view is now the **canvas** `FrameLayout`'s first child.

- It is added before `editorSurface`, so it is behind the buttons and behind nothing else. The edit
  surface is added over it and stays `MATCH_PARENT`, so every gesture still reaches the canvas.
- `canvas.setClipChildren(true)` keeps it inside the canvas' rectangle. It is the `FrameLayout`
  default, but it is set explicitly so a later edit cannot silently turn clipping off.
- `TouchEditorBackgroundView` gained `cropToBounds(true)`: the draw code already centre-cropped to
  preserve aspect ratio, but it was **not clipping**, so on a canvas narrower than the picture the
  overflow had somewhere to go. It does not any more. This is the "never extends outside the button
  layout boundaries" requirement, fixed at the source rather than by a mask.
- The picture is still neither clickable nor focusable, and its scaling is unchanged, so it resizes
  with the canvas on any screen size or orientation.

The scrim slider still controls the picture's opacity; it now dims only the canvas, which is the
only place the picture is.

## 3. Root cause 2 — a button's actions were a permanent panel

The inspector built a "Button action" section on every selection change: the whole key grid plus a
Clear Key button, always on screen, unrelated to which button was in front of the player.

Fix: tapping a button opens a `PopupMenu` instead.

- **When.** `EditorCanvas.ACTION_UP` with no drag and no resize opens the menu for the tapped
  button. A drag that ends does not, and a tap on empty space clears the selection and leaves no
  menu. `ACTION_DOWN` dismisses any menu already open, so a stale one can never float over the
  layout while the finger moves on.
- **Where.** The anchor is a one-pixel invisible view inside the canvas, translated to the tapped
  button's centre before `show()`. The framework then positions the popup beside the button and
  clamps it to the window, so it never runs off an edge. A `PopupMenu` is modal: a press that is not
  on it is dismissed by the framework and never reaches the canvas, so it cannot interfere with
  dragging.
- **What.** Rows are one per `TouchKeyCatalog` key (the row id is `MENU_ITEM_KEY_BASE + keycode`, so
  no second id table exists), the three modifier latches, then Clear Key / Toggle / Bring to front /
  Delete. The key grid is grouped and checkable, the modifier latches are independently checkable.
  Every row is a real action; none needs a second confirmation.
- **Apply immediately.** `runAction()` runs the same `TouchLayoutEditor` calls the panel used
  (`setSelectedKey`, `toggleSelectedModifier`, `clearSelectedKey`, `toggleSelectedEnabled`,
  `bringSelectedToFront`, `deleteSelected`) and calls `refresh()`, which redraws the canvas and
  rebuilds the inspector at once. No Save, no Apply.

The methods that built the panel's key grid and Clear Key button (`addKeyPicker`, `keyChip`,
`modifierToggle`) are deleted, so there is one copy of the key menu, not two to drift apart.

## 4. A real bug found while wiring it

Choosing a key left the button's caption stale. `TouchLayoutEditor.setSelectedKey` set `keyCode`
but not `label`, so a button showing "E" kept reading "E" after the popup assigned "K" — the
"reflected immediately in the editor preview" requirement. It now updates a label that was the
key's own name (`TouchKeyCatalog.displayName`) and keeps a label the player customised through
`setSelectedLabel()` — a deliberate choice — and the stale label of a cleared button being
reassigned.

A second, smaller fix: the inspector was rebuilt on **every** `ACTION_DOWN`, removing and re-adding
its views under the finger. It is now rebuilt only when the selection actually changes
(`lastInspectorSelection`), so a tap or the start of a drag no longer thrashes the panel.

## 5. Verification

| Check | Result |
|---|---|
| `gradle :app:testDebugUnitTest` | **253 tests, 0 failures** (was 243 / 3 failing) |
| `gradle :app:lintDebug` | clean, no new warnings |
| `gradle :app:assembleLauncherOnly` | `BUILD SUCCESSFUL` |
| `scripts/qa/android/check-translations.py` | green, 13 locales, 593 strings each, no new string |

New and converted tests (`TouchEditorResponsiveLayoutTest`):

- `theBackgroundIsBundledAndConfinedToTheButtonLayout` — the picture is inside the canvas, not the
  root; it is the canvas' first child; it is clipped; it takes no touch.
- `theBackgroundIsBoundedByTheButtonLayoutArea` — the picture's rectangle equals the canvas'
  rectangle, and does not reach the inspector, in both orientations.
- `clearKeyFromThePopupClearsOnlyTheSelectedMapping`, `clearKeyIsDisabledInThePopupOnceThereIsNothingToClear` — the
  three former Clear Key-on-the-page tests, now driving the popup's rows.
- `theActionPopupKeepsEveryKeyAndEveryAction` — every catalog key plus the seven actions are rows.
- `choosingAKeyInThePopupAppliesItImmediately`, `choosingAModifierInThePopupAppliesItImmediately`,
  `deleteAndFrontRowsActOnTheSelectedButton` — a row applies at once, through the model.
- `aTapOnAButtonOpensTheActionPopup`, `aTapOnEmptySpaceOpensNoPopup` — the real touch path.

`TouchLayoutEditorTest`: `changingTheKeyUpdatesTheDefaultCaption`,
`changingTheKeyKeepsACustomCaption`, `reassigningAClearedButtonUsesTheNewKeyCaption`.

## 6. Notes for the next session

- The popup's rows are read by a test through `buildActionMenuForTest()`, and run through
  `runActionForTest(id, index)`; both exist only so a Robolectric test can inspect and exercise the
  menu without a window. They are not used by the app.
- `PopupMenu(Context, View, int, int)` — the x/y constructor — does **not** exist in the framework;
  only `(Context, View)` and `(Context, View, int gravity)`. Positioning is done with a translated
  anchor view, which is also what makes the framework clamp the popup to the window.
- The picture is `drawable-nodpi/touch_editor_background.jpg` (1024×768) and is centre-cropped, not
  stretched; the crop is now clipped, so it can be swapped for any aspect ratio without changing the
  code.
