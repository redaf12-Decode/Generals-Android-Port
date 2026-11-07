# Physical mouse capture for the Android port

**Date:** 07/11/2026
**Branch:** `New-app`
**Status:** implemented; Java tests + translation check + a syntax check of the new C++ against the
real SDL3 3.4.2 headers pass. **Not verified on hardware** — see *What still needs a device*.

The request was to add Winlator-style physical mouse capture: a real USB/Bluetooth mouse confined to
the game window, its buttons and wheel forwarded to the engine, its motion relative rather than
absolute, the system cursor hidden while captured, and a clean fallback where Android cannot do it.

---

## 1. What was already there (Phase 1)

The port has **no Android Java mouse code of its own**. Input arrives through SDL3, whose Android
backend already implements the whole Winlator-style pipeline — it is simply never switched on.

| Layer | What exists | Where |
|---|---|---|
| Android Activity | `SDLActivity` + `SDLSurface`, one `SurfaceView` | `android/app/src/main/java-sdl/org/libsdl/app/` (staged by `scripts/build/android/stage-sdl-java.sh`) |
| Android mouse glue | `SDLGenericMotionListener_API26.requestPointerCapture()` / `onCapturedPointerEvent()` | same, SDL3's own Java |
| Native event loop | `SDL3GameEngine::pollSDL3Events()` → `SDL3Mouse::addSDLEvent()` | `Core/GameEngineDevice/Source/SDL3GameEngine.cpp` |
| Mouse device | `SDL3Mouse` (SDL3 mouse events → `MouseIO`) | `Core/GameEngineDevice/Source/SDL3Device/GameClient/SDL3Mouse.cpp` |
| Engine mouse | `Mouse::updateMouseData()` → `processMouseEvent()` → `moveMouse()` → message stream | `Core/GameEngine/Source/GameClient/Input/Mouse.cpp` |
| Touch | `SDL3GameEngine` gesture machine, `TouchInput.cpp` | `Core/GameEngineDevice/Source/` |
| Keyboard | `SDL3Keyboard`, same `addSDLEvent()` pattern | `Core/GameEngineDevice/Source/SDL3Device/` |
| Mouse mode | launcher switch → `mouse_mode` marker → `GXMouseMode.h` | `android/.../SetupActivity.java`, `Core/GameEngine/Include/Common/GXMouseMode.h` |

Key facts that decided the design:

* **A real mouse already works, absolutely.** `SDL3Mouse::init()` sets `m_inputMovesAbsolute = TRUE`
  and `scaleMouseCoordinates()` converts SDL window pixels to the game's internal resolution
  (viewport-aware). The existing Winlator analogue of "absolute pointer positioning" is in place.
* **What is missing is capture.** `SDL3Mouse::capture()` only did `SDL_CaptureMouse()` +
  `SDL_SetWindowMouseGrab()` — both no-ops on Android. It never asked for relative mode, so the
  pointer is free to leave the window and can stall against an edge.
* **SDL already does the rest.** `SDL_SetWindowRelativeMouseMode(window, true)` →
  `Android_SetRelativeMouseMode()` → `Android_JNI_SetRelativeMouseEnabled()` →
  `SDLGenericMotionListener_API26.setRelativeMouseEnabled()` → `View.requestPointerCapture()`, and
  `onCapturedPointerEvent()` → `Android_OnMouse(..., relative=true)` → `SDL_SendMouseMotion(...,
  relative=true)`. No new Java or JNI is needed.
* **`SDL_TOUCH_MOUSEID` vs the touchpad.** The touchpad pointer's synthesized events use
  `SDL3Mouse::TOUCHPAD_MOUSE_ID`; SDL's own touch events use `SDL_TOUCH_MOUSEID`. Both must stay
  out of the button tracker, and the touchpad's events must keep their absolute position.

## 2. Design

Drive SDL's existing mechanism from the engine; add nothing parallel.

```
Android InputDevice (USB/BT mouse)
        │  MotionEvent (hover or captured)
        ▼
SDLGenericMotionListener_API26 ── requestPointerCapture() / onCapturedPointerEvent()
        ▼
Android_OnMouse(relative) → SDL_SendMouseMotion/Button/Wheel
        ▼
SDL3GameEngine::pollSDL3Events()  ── filters SDL_TOUCH_MOUSEID
        ▼
SDL3Mouse::addSDLEvent()          ── tracks held buttons (for stuck-button release)
        ▼
translateEvent() → scaleMouseCoordinates() → MouseIO
        ▼
Mouse::processMouseEvent() → moveMouse() → TheMessageStream (MSG_RAW_MOUSE_*)
        ▼
GUI / translators (unchanged)
```

* **Absolute mode** (uncaptured, default): unchanged. Android's cursor is shown; positions are
  scaled as before.
* **Relative mode** (captured): `SDL_SetWindowRelativeMouseMode(window, true)`. Android hides its
  cursor and sends relative motion; SDL accumulates it into `mouse->x/y` (clamped to the window) and
  exposes it as `event.motion.x/y`, with `xrel/yrel` as the per-event delta. `SDL3Mouse` therefore
  needs **no accumulator of its own** — `event.motion.x/y` is already the position and
  `scaleMouseCoordinates()` handles it exactly as in absolute mode. The game draws the cursor
  (`SDL3Mouse::draw()`, the same code the touchpad pointer uses).
* **Fallback**: `SDL_SetWindowRelativeMouseMode()` returns false on Android < 8.0, some Samsung DeX
  builds, and wherever `supportsRelativeMouse()` is false. `capture()` records the *granted* state
  (`SDL_GetWindowRelativeMouseMode()`), and when refused the absolute path keeps working unchanged.

## 3. Files changed

| File | Why |
|---|---|
| `Core/GameEngine/Include/Common/GXMouseMode.h` | Added `GXMouseCaptureEnabled()`: reads the **second line** of the `mouse_mode` marker. Absent ⇒ on (a connected mouse works with no setup); `0` ⇒ off. |
| `Core/GameEngineDevice/Include/SDL3Device/GameClient/SDL3Mouse.h` | Added `m_RelativeMode`, `m_RelativeWanted`, `m_ButtonsHeld`, `HARDWARE_MOUSE_ID`, `isRelativeModeActive()`, `releaseStuckButtons()`. |
| `Core/GameEngineDevice/Source/SDL3Device/GameClient/SDL3Mouse.cpp` | See below. |
| `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java` | Added the "Physical mouse capture" switch; the marker now has a second line; helpers made package-private for the test. |
| `android/app/src/main/res/values*/strings.xml` (13 files) | `setup_switch_mouse_capture` + `_desc` in every locale. |

### `SDL3Mouse.cpp`, change by change

* **Constructor** — initialises `m_RelativeMode`/`m_RelativeWanted`/`m_ButtonsHeld`, and reads the
  launcher's capture choice once: `m_RelativeWanted = GXMouseModeEnabled() && GXMouseCaptureEnabled()`.
* **`capture()`** — when `m_RelativeWanted`, calls `SDL_SetWindowRelativeMouseMode(window, true)`
  and records the granted state in `m_RelativeMode` (with a `GX_TRACE` line either way). This is the
  whole capture request; SDL and Android do the rest.
* **`releaseCapture()`** — releases stuck buttons **first** (a held button would otherwise never get
  its release once Android stops delivering captured events), then leaves relative mode.
* **`addSDLEvent()`** — tracks held buttons (`m_ButtonsHeld`, an SDL button mask) from
  hardware-mouse `BUTTON_DOWN`/`BUTTON_UP` only, ignoring the touchpad's id and `SDL_TOUCH_MOUSEID`.
* **`releaseStuckButtons()`** — injects a `SDL_EVENT_MOUSE_BUTTON_UP` for every held button, at
  SDL's current pointer position. Idempotent.
* **`loseFocus()`** — calls `releaseStuckButtons()` then `releaseCapture()`, so a press interrupted
  by a focus loss cannot stay down (this also covers an uncaptured mouse, where `releaseCapture()`
  returns early).
* **`draw()`** — draws the cursor when `m_touchpadPointer && GXMouseModeEnabled()` **or**
  `m_RelativeMode` (Android hides its cursor while captured).

**Not changed, on purpose:** `regainFocus()`. Capture comes back through the engine's own rules —
`SDL3GameEngine.cpp` calls `refreshCursorCapture()` right after `regainFocus()` on both the
focus-gained and the foreground path, which re-runs `Mouse::canCapture()`. Forcing `capture()` here
would bypass those rules.

## 4. Launcher UI

`Setup → Controls → Mouse and touchpad` now has a second switch, shown only while the mode is on:

* **Physical mouse capture** (default on) — writes the marker's second line (`1`/`0`).

The `mouse_mode` marker is now:

```
<cursor percent>
<capture flag: 1 or 0>
```

A marker written by an earlier launcher has only the first line, and `GXMouseCaptureEnabled()` and
`readMouseCaptureEnabled()` both read that as **on** — backward compatible.

## 5. Lifecycle

| Event | Handling |
|---|---|
| Capture acquired | `Mouse::capture()` → `SDL_SetWindowRelativeMouseMode(true)` |
| Capture released | `Mouse::releaseCapture()` → stuck buttons up, then relative mode off |
| Window focus lost | `SDL_EVENT_WINDOW_FOCUS_LOST` → `Mouse::loseFocus()` → buttons up + releaseCapture |
| App backgrounded | `SDL_EVENT_DID_ENTER_BACKGROUND` → `loseFocus()` |
| App resumed / focus gained | `SDL_EVENT_DID_ENTER_FOREGROUND` / `FOCUS_GAINED` → `regainFocus()` + `refreshCursorCapture()` |
| Game paused | `Mouse::onGamePaused()` → `blockCapture(Paused)` → `releaseCapture()` |
| Mouse unplugged | Android stops delivering events; last button state may be stale, so the next focus loss or pause releases it |

## 6. Verification

* `:app:testDebugUnitTest` — **307 tests, 0 failures** (includes the new `MouseCaptureMarkerTest`,
  8 cases: round trip, cursor size surviving a capture toggle, the pre-flag marker reading as on,
  an explicit `0` staying off, deletion on disable, clamping/snapping, missing file defaults).
* `:app:lintDebug` — 0 errors, no finding in any new/changed file.
* `scripts/qa/android/check-translations.py` — green, 13 locales, 704 strings each.
* The new C++ logic (`releaseStuckButtons`, the `capture()` relative-mode call, the button-mask
  tracking) compiles cleanly with `g++ -std=c++17 -Wall -Wextra` against the real SDL3 3.4.2 headers.

## 7. What still needs a device

The full native build (vcpkg + DXVK + NDK) was **not** run here, and no mouse was attached. The
following are reasoned from the code paths above and still need to be seen on hardware:

1. That `requestPointerCapture()` is granted on a given device (Android 8+; DeX needs 8.1) and that
   the engine logs "physical mouse captured (relative mode)" rather than "refused".
2. That the game-drawn cursor tracks the mouse and reaches every screen edge (SDL accumulates and
   clamps `mouse->x/y`, and `scaleMouseCoordinates()` maps it, so it should — but it is a
   transformation and the lesson from this port is to measure it, not assume it).
3. Left/right/middle click, wheel, and drag-with-button-held, with a real mouse.
4. That a press held while focus is lost or the mouse is unplugged does not stick.
5. That touch still works with a mouse connected and while captured, and that the two do not
   interfere.

## 8. Limitations

* Side buttons (X1/X2) are tracked for stuck-button release but not mapped to game commands — the
  engine has no command for them.
* No sensitivity setting was added: the engine already has none for the mouse, and inventing one
  would be a new subsystem rather than an integration. SDL's own relative scale
  (`SDL_HINT_MOUSE_RELATIVE_SPEED_SCALE`) is the natural place for it later.
* Horizontal wheel is forwarded by SDL like the vertical one, but the engine's `MouseIO` carries a
  single `wheelPos`, so horizontal scroll does nothing (as on every other platform here).
