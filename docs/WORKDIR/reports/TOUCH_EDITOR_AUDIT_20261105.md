# Touch editor upgrade — Stage 1 architecture audit

Date: 05/11/2026. Branch: `claude/touch-editor-upgrade` (from `New-app`, f74d1fb).
Scope: read-only inspection before any modification.

## 1. What exists on this branch, and what does not

`New-app` carries **only** the native SDL3 touch layer. The configurable Java overlay and its
in-game editor live on `fix-ui-Draw-18-10` and are absent here. Verified with
`git cat-file -e` per path:

| Path under `android/app/src/main/java/com/generalsx/zerohour/` | `New-app` | `fix-ui-Draw-18-10` |
|---|---|---|
| `HotkeyOverlayView.java` | no | yes |
| `TouchControlConfig.java` | no | yes |
| `TouchLayoutEditor.java` | no | yes |
| `ToolboxOverlayView.java` | no | yes |
| `ToolboxConfig.java` | no | yes |
| `TouchControlsActivity.java` | no | yes |

`values/strings.xml`: 0 touch/toolbox strings here, 45 on the reference. `android/app/src/test/`
does not exist here at all.

## 2. Native touch path (must not be disturbed)

```
SDL3 Java (org.libsdl.app.SDLActivity / SDLSurface)
  -> SDL_EVENT_FINGER_DOWN / MOTION / UP / CANCELED
Core/Main/SDL3Main.cpp:1104        SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0")
Core/GameEngineDevice/Source/SDL3GameEngine.cpp
  poll loop case SDL_EVENT_FINGER_*           ~line 2998
    -> handleTouchEvent(SDL_Window*, const SDL_Event&)   line 1373
       -> TouchState phase machine {IDLE, MOMENTUM, PENDING, PANNING, TARGETING, TWOFINGER}
       -> Core/GameEngineDevice/Source/SDL3Device/GameClient/TouchInput.cpp
          tap / doubleTap / beginAiming / cancelOrDeselect / lookAtRadarPoint
```

Key facts for integration:

- Touch-to-mouse synthesis is off (`SDL3Main.cpp:1104`), and the poll loop additionally drops
  events whose `which == SDL_TOUCH_MOUSEID` (`SDL3GameEngine.cpp` ~2978). Nothing the overlay does
  may reintroduce a synthetic mouse event.
- A finger landing on a GUI window gets an immediate `LEFT_BUTTON_DOWN`; battlefield taps are
  resolved by the engine's own rules. Both are reached only if the overlay returns `false` from
  `onTouchEvent` for that region.
- The overlay lives in the Java window **above** SDL's surface. It cannot see or change
  `handleTouchEvent`; it only decides whether a gesture reaches SDL.

## 3. Keyboard injection path (verified end to end)

The overlay's only way to act is a key press. The chain, checked against the actual
SDL 3.4.2 source (`cmake/sdl3.cmake` pins `3.4.2`) and this tree:

```
SDLActivity.onNativeKeyDown(keycode) / onNativeKeyUp(keycode)     (Java, native methods)
  -> src/core/android/SDL_android.c:1340/1355  onNativeKeyDown/Up   (JNI)
     -> src/video/android/SDL_androidkeyboard.c:369 Android_OnKeyDown
        -> TranslateKeycode: Android_Keycodes[keycode] -> SDL_Scancode
        -> SDL_SendKeyboardKey(0, SDL_DEFAULT_KEYBOARD_ID, keycode, scancode, down)
Core/GameEngineDevice/Source/SDL3GameEngine.cpp  SDL_EVENT_KEY_DOWN/UP
  -> SDL3Keyboard::addSDLEvent
     -> SDL3Keyboard::getKey -> translateScanCodeToKeyVal(scancode) -> KeyDefType
Core/GameEngine/Source/GameClient/Input/Keyboard.cpp  Keyboard::update
  -> m_keyStatus[].state, m_modifiers, ALT+TAB suppression (line 151)
```

`TranslateKeycode` is a **table lookup by Android keycode, and there is no modifier state involved
at all** — it is safe to inject a bare `KEYCODE_CTRL_LEFT` (113) and the engine receives
`KEY_LCTRL`. Verified table rows (index = Android keycode):

| Android keycode | SDL scancode | Engine `KeyDefType` |
|---|---|---|
| 61 `TAB` | `SDL_SCANCODE_TAB` | `KEY_TAB` |
| 113 `CTRL_LEFT` | `SDL_SCANCODE_LCTRL` | `KEY_LCTRL` |
| 114 `CTRL_RIGHT` | `SDL_SCANCODE_RCTRL` | `KEY_RCTRL` |
| 57 `ALT_LEFT` | `SDL_SCANCODE_LALT` | `KEY_LALT` |
| 58 `ALT_RIGHT` | `SDL_SCANCODE_RALT` | `KEY_RALT` |
| 59 `SHIFT_LEFT` | `SDL_SCANCODE_LSHIFT` | `KEY_LSHIFT` |
| 60 `SHIFT_RIGHT` | `SDL_SCANCODE_RSHIFT` | `KEY_RSHIFT` |
| 62 `SPACE`, 66 `ENTER`, 111 `ESCAPE` | `SPACE`, `RETURN`, `ESCAPE` | `KEY_SPACE`, `KEY_ENTER`, `KEY_ESC` |
| 67 `DEL` | `SDL_SCANCODE_BACKSPACE` | `KEY_BACKSPACE` |
| 112 `FORWARD_DEL` | `SDL_SCANCODE_DELETE` | `KEY_DEL` |
| 122/123 `MOVE_HOME/END` | `HOME` / `END` | `KEY_HOME` / `KEY_END` |
| 92/93 `PAGE_UP/DOWN` | `PAGEUP` / `PAGEDOWN` | `KEY_PGUP` / `KEY_PGDN` |
| 124 `INSERT` | `INSERT` | `KEY_INS` |
| 7-16 `0`-`9`, 29-54 `A`-`Z`, 131-142 `F1`-`F12` | matching | matching `KEY_*` |

Every entry the task's key catalog needs is present in the engine's
`SDL3Keyboard::translateScanCodeToKeyVal` (`Core/GameEngineDevice/Source/SDL3Device/GameClient/`).
No native change is required for the catalog.

### Two engine behaviours the implementation must respect

1. **The engine latches modifiers itself.** `Keyboard::update` (~line 899) sets
   `m_modifiers |= KEY_STATE_LCONTROL` from the `KEY_LCTRL` key state, then (~line 184)
   `BitSet(m_keys[index].state, m_modifiers)` folds it into that frame's events.
   `getModifierFlags()` is what `Mouse.cpp` attaches to messages. So the overlay does **not**
   need to synthesise a combined keycode — it presses the modifier, then the key, and the engine
   composes `Ctrl+A` on its own. This is also why the modifier flags must stay stored separately
   from the primary key.
2. **Alt+Tab is deliberately swallowed.** `Keyboard.cpp:151` marks a `KEY_TAB` event
   `STATUS_USED` while `KEY_LALT`/`KEY_RALT` is down ("prevent ALT-TAB from causing a TAB event").
   Consequence: a button configured `Alt + Tab` will reach the engine and be discarded. This is
   engine behaviour, not a bug in the overlay, and the editor must not claim otherwise.

## 4. Launcher integration points

- `GeneralsZHActivity extends SDLActivity`; `mLayout` / `mSurface` are SDL's own fields. The
  existing `TextEditorBar` shows the pattern to follow: build the view in
  `runOnUiThread`, `addContentView` onto `mLayout`, restore focus to `mSurface` on dismiss.
- `onCreate` runs the launch path (`writeNativeGameMarker`, `copyBundledRuntimeIfMissing`) before
  `super.onCreate`. The overlay must attach **after** `super.onCreate`, since `mLayout` only exists
  once SDL has built its view.
- `onDestroy` exists and releases a multicast lock; `onWindowFocusChanged` exists. There is no
  `onPause`/`onResume` override today — the overlay needs one, to release held keys.
- `SetupActivity` provides `getSelectedGame(Context)`, `getSavedGamePath(Context, String)` and
  `copyBundledRuntimeIfMissing(File, String)`. `ThemeHelper.accentColor(Activity)` exists and is
  the accent source the reference's overlay uses.
- `UiKit` provides `card`, `sectionHeader`, `helpText`, `button`, `switchRow` for the launcher page.

## 5. Conflict to resolve, not inherit

`TouchControlConfig.prepareForLaunch` on the reference **deletes** `Window\GroupPanel.wnd` when the
overlay is on. On `New-app` that file is not a redundant panel: it carries the army drawer
(`ArmyScreen`, `ArmyMap`, …) and is anchored to the command bar by
`GXUiScale.cpp:94 { "GroupPanel.wnd", 1.0f, 1.0f, FALSE, {nullptr}, "ControlBar.wnd" }`, with
`GXUiScale::setControlBarLeftEdge` sliding the bar when the drawer opens.

Blindly copying `prepareForLaunch` would therefore **delete a feature this branch has and the
reference does not**. Decision taken: the overlay does not touch `GroupPanel.wnd` at all, and the
editor's layout guides are computed against the full window rather than assuming the bottom strip
is free. This is recorded in the editor UI as a documented difference.

## 6. Files to add and to change

Add (`android/app/src/main/java/com/generalsx/zerohour/`):

| File | Role |
|---|---|
| `TouchKeyCatalog.java` | key catalog, names, groups, modifier helpers, safety check |
| `TouchButtonShape.java` | shape kinds + polygon geometry, `Path` build and point-in-shape test |
| `TouchControlConfig.java` | button model, versioned JSON, migration, validation, persistence |
| `TouchLayoutEditor.java` | edit session: select, move, resize, clamp, save/cancel |
| `HotkeyOverlayView.java` | runtime button layer: render, hit test, edit mode, bottom toolbar |
| `TouchEditorRenderer.java` | shared editor drawing (buttons, selection ring, handle, badge) |
| `TouchKeyPresser.java` | key state machine: Tap / Hold / Toggle, modifiers, release-all |
| `ToolboxConfig.java` | floating icon placement/style, per game |
| `ToolboxOverlayView.java` | floating icon + tool panel, opens the editor |
| `TouchControlsActivity.java` | full-screen editor in the launcher |

`TouchEditorOverlayView` was folded into `HotkeyOverlayView`: the edit mode is a mode of the same
view, so the buttons the player drags are the very views that receive touches in a match. Two
views would have had to keep their geometry in step by hand. The drawing both editors share is in
`TouchEditorRenderer`, so the in-game editor and the launcher editor cannot drift apart.

Change:

| File | Change |
|---|---|
| `GeneralsZHActivity.java` | attach the two overlay layers after `super.onCreate`; add `onPause`/`onResume` key release |
| `SetupActivity.java` | Mobile Controls + Floating toolbox cards on the Interface page |
| `AndroidManifest.xml` | register `TouchControlsActivity` |
| `res/values*/strings.xml` | new strings in all 13 locale files |
| `android/app/build.gradle` | unit-test dependencies (JUnit + Robolectric) |

Not changed: `Core/**`, `Generals/**`, `GeneralsMD/**`, `GroupPanel.wnd`, any native touch file.
The engine's `handleTouchEvent` and `TouchInput` stay exactly as they are.

## 7. Toolchain available in this environment

`/workspace/.buildtools/sdk` (cmdline-tools, `platforms;android-35`, `build-tools;35.0.0`),
JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), Gradle wrapper in `android/`. SDL3 3.4.2 Java glue
staged from `/tmp/SDL3-3.4.2/android-project/...` into the gitignored `src/main/java-sdl`.
Native `.so` libraries are **not** built here (needs the NDK + vcpkg tree); the build evidence is
therefore a Java/APK build plus the unit-test suite, and that limitation is reported as such.
