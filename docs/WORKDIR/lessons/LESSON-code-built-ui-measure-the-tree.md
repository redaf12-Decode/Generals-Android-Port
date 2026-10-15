# LESSON: A screen built in code cannot be reviewed — measure its view tree

**Date**: 2026-10-15
**Phase**: Android port, launcher shell (`SetupActivity.buildDrawerShell`)
**Symptom**: black screen on launch, no buttons and no interface at all, but the left drawer drag
still worked
**Report**: `docs/WORKDIR/reports/REPORT-launcher-black-screen.md`

## TL;DR for the next agent

1. **"Black screen" is not evidence of a rendering bug.** In this port it meant the launcher's view
   tree was fine and *measured to zero*. The `DrawerLayout` edge drag is handled by the layout
   itself, so a drawer that opens over a completely invisible page is the signature of "the content
   is there but has no space" — not of a dead activity, a missing surface or a swallowed exception.
2. **Do not diagnose a code-built screen by reading it.** Every launcher screen here is assembled
   from `new LinearLayout` / `new MaterialButton` calls, so there is no layout XML whose intent can
   be compared with reality. Build the activity under Robolectric, run
   `decor.measure(...)` + `decor.layout(...)` at a phone size, and dump `class, visibility, size,
   absolute position` per view (`SetupShellLayoutTest.java` does exactly this). The dump answered
   this bug in one run, after reading the same code had produced only hypotheses.
3. **A plain `View` with `WRAP_CONTENT` is greedy.** `View.onMeasure` maps `AT_MOST` to the spec
   *size*, and a `LinearLayout` measures a `WRAP_CONTENT` child `AT_MOST (parent - used)`. An empty
   spacer therefore takes the whole remaining height and starves every sibling that follows it (here
   the app bar and the page both got 0px and were laid out at `y = screen height`). A spacer must
   carry an explicit height, a real dimension, or no measurable content at all.
4. **`addView(child, params)` replaces params already set.** The scrim's intended 28dp height was
   set with `setLayoutParams()` and then thrown away one line later by passing a different params
   object to `addView`. Also note that `FrameLayout.LayoutParams` into a `LinearLayout` (or any
   other params/parent mismatch) does **not** throw: they all extend `MarginLayoutParams`, so
   `LinearLayout.generateLayoutParams()` silently converts them. The compiler cannot catch this
   class of bug; the measurement can.
5. **Assert on-screen geometry, not "non-zero".** The first version of this test asserted that the
   shell and its title had non-zero sizes and passed on the broken tree — the title had height 41
   inside a 0px-tall app bar parked at `y = 2340`. What catches the class is position relative to
   the screen: the app-bar text and the first card must be *within* the screen, and the drawer's
   menu must actually have items. An empty `NavigationView` opens as a full-width blank panel; here
   `R.menu.drawer` was referenced nowhere, so the drawer was blank too.
6. **`DrawerLayout` ignores its own padding.** Insets applied to it never reached the content child
   (measured: `paddingTop=150` -> `content top=0`). Pad the content shell instead, and leave the
   drawer panes to Material's own inset handling.
7. **A layer list draws top to bottom.** An opaque layer hides the ones beneath it, so
   "gradient, pattern, scrim" as *item* order is "pattern hidden, then everything painted over".
   The near-opaque top layer here is what turned the whole dashboard black.
