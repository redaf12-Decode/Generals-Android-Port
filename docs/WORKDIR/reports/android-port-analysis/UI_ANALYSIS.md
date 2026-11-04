# UI_ANALYSIS.md — the launcher (New-UI branch)

The launcher's user interface: structure, navigation, components, colour system, the navy theme,
adaptation, state, errors, reuse, and what needs reorganising. **[FACT]** unless marked otherwise.

---

## 1. Scope: what "UI" means in this application

**[FACT]** There are two completely separate UIs, and the boundary is deliberate:

| | Launcher | Game |
|---|---|---|
| Activities | `SetupActivity`, `FolderPickerActivity`, `LogViewerActivity`, `ModManagerActivity`, `ReplayCheckActivity`, `NetworkDiagnosticsActivity`, `GeneralsOnlineActivity` | `GeneralsZHActivity` |
| Theme | `Theme.GeneralsZHSettings` (+ navy / light variants) | `@android:style/Theme.NoTitleBar.Fullscreen` |
| Widgets | Material 3 (MaterialCardView, MaterialButton, MaterialButtonToggleGroup, MaterialSwitch, Slider, BottomNavigationView, TextInputLayout) | none — the game draws everything itself |
| Colours | `gzh_*` tokens, switchable palette | none of these |
| Strings | 488 strings × 13 locales | the game's own `.csf` tables |

**[FACT]** `colors.xml` states the rule: *"GeneralsZHActivity (the game surface itself) never
references any of these — it stays on its own plain fullscreen theme."* `build.gradle` repeats it
for the Material dependency.

**[FACT]** There are **no layout XML files**. Every screen is built in code. `UiKit`'s own header
explains why: the screens are conditional (Vulkan-only cards, a base-Generals button, nine
diagnostic toggles generated from arrays) and "a layout file per permutation would be more
machinery, not less".

---

## 2. Screen structure

### 2.1 `SetupActivity` — five sections behind a bottom navigation bar

**[FACT]** The shell (`buildUi`):

```
LinearLayout shell (background gzh_background, InsetUtil.applySafeInsets)
├── app bar:  overline (e.g. "Generals")  +  large bold title  +  trailing "View logs" icon button
├── FrameLayout contentHost (weight 1)      ← one page swapped in at a time
└── BottomNavigationView                    ← 5 items, fixed order, pinned LTR
```

**[FACT]** `BottomNavigationView` configuration: `LAYOUT_DIRECTION_LTR` +
`TEXT_DIRECTION_LOCALE` (so the five tabs do not reverse in Arabic/Farsi while their labels still
shape right-to-left), `LABEL_VISIBILITY_LABELED`, icon size 22dp, a checked/unchecked
`ColorStateList` (`gzh_on_primary_container` / `gzh_on_surface_faint`), active indicator
`gzh_primary_container`, ripple `gzh_ripple_primary`.

**[FACT]** Tab ids and order: `TAB_HOME=1`, `TAB_GRAPHICS=2`, `TAB_INTERFACE=3`, `TAB_TOOLS=4`,
`TAB_HELP=5`; icons `ic_gzh_home`, `ic_gzh_display`, `ic_gzh_globe`, `ic_gzh_wrench`, `ic_gzh_info`.

**[FACT]** Page composition (`showTab`):

| Tab | Cards, in order |
|---|---|
| **Home** | *Game* (Generals/Zero Hour segmented, skip-intro switch, Mods row) · **Launch Game** (primary, outside a card, above the folder card) · *Game folder* (status line, data-sources chip/row, select folder, select base-Generals folder (ZH only), clear folder, clear base folder) · *GeneralsOnline* · *Updates* |
| **Graphics** | *Simulation rate* · *Render scale / upscaling* (hidden on Vulkan) · *Render backend* · *Vulkan driver* + *DXVK config* (Vulkan only) |
| **Interface** | *Language* · *Appearance* · *Menu text size* · *Interface size* · *Control scheme* (with the mouse/touchpad rows inside it) |
| **Tools** | *Logs / diagnostics entry rows* · *Diagnostics* (master logging switch, Vulkan switch, nine marker switches) |
| **Help** | *About* (version chip) · *Support the project* · *How it works* |

**[FACT]** `showTab` calls `clearPageReferences()` first, swaps the page, then re-runs
`refreshStatus`, `refreshGeneralsOnlineStatus`, `loadDxvkConfigIntoEditor`,
`refreshDiagnosticsSwitches`, `refreshUpdatesStatus`. Every refresher null-checks, so a page that
has no status line simply is not refreshed.

**[FACT]** The open tab survives recreation (`STATE_TAB`), because the language and theme pickers
call `recreate()`.

### 2.2 The other screens

| Screen | Structure |
|---|---|
| `FolderPickerActivity` | app bar · current path (selectable) · validity hint · `ListView` with a custom adapter · a bottom row of two buttons (Cancel / Use This Folder) |
| `LogViewerActivity` | app bar · log text (selectable, head+tail truncated) · Clear (danger) · Share |
| `ModManagerActivity` | app bar with an import action · filter segmented (all / enabled) · order card with a summary chip and help · one card per mod (cover picture, name, meta line, analysis chips, enable switch, up/down, move-to-other-game, share, delete) · import progress dialog |
| `ReplayCheckActivity` | app bar · result card · mode card (run-through / watch-from, frame field, CRC-every-frame switch) · replay list of `UiKit.listRow` |
| `NetworkDiagnosticsActivity` | app bar · report text · Run · Copy · Share |
| `GeneralsOnlineActivity` | app bar · account card (status, sign-out) · sign-in card · online-data card (chip, status, update, delete, enable switch, "with mods" switch, data-mod status) · cross-play card |

---

## 3. The component library — `UiKit`

**[FACT]** 700 lines, no screen-specific code, API 24-safe, start/end-relative for RTL.

| Method | Purpose | Usage count in the tree |
|---|---|---|
| `dp`, `dim` | dp and dimension-resource conversion | 18 / 25 |
| `color`, `tint` | palette-aware colour (via `ThemeHelper.mapColor`), `ColorStateList` builders | 30 / 16 |
| `scrollingPage` | the page shell: `ScrollView` → gutter-padded vertical column | 4 |
| `appBar` | overline + large title + optional trailing icon button; returns the title view | 7 |
| `iconButton` | round tonal icon button | 4 |
| `card` | `MaterialCardView` (radius 24dp, no elevation, no stroke) + inner column | 33 |
| `sectionHeader` | accent icon + bold section name + optional accent value; returns the value view | 29 |
| `body` / `supporting` / `caption` | the three text tiers | 10 / 37 / 3 |
| `helpText` | hairline + caption — "controls, then explanation" | 14 |
| `divider` | hairline | 9 |
| `button(kind)` | `BTN_PRIMARY` / `BTN_TONAL` / `BTN_OUTLINE` / `BTN_DANGER` | 37 |
| `buttonRow`, `share` | equal-width button row | 3 / 6 |
| `segmented` | `MaterialButtonToggleGroup` with checked/unchecked tints | 8 |
| `switchRow` | title + description + trailing `MaterialSwitch` | 13 |
| `listRow` | icon + title + supporting + chevron, with a ripple clipped to the rounded corners; returns a `Row` holding `title`/`supporting` so callers can rewrite them | 10 |
| `chip` | rounded tinted label for one-word verdicts | 18 |

**[INFERENCE]** The library is genuinely shared — every method has multiple callers — so it is a
real abstraction, not a wrapper with one user. This is the strongest part of the UI codebase.

---

## 4. Design tokens

### 4.1 Spacing, shape, type (`dimens.xml`)

| Token | Value |
|---|---|
| `gzh_gutter` | 16dp |
| `gzh_card_gap` | 12dp |
| `gzh_card_padding` | 20dp |
| `gzh_item_gap` / `gzh_item_gap_tight` | 12dp / 8dp |
| `gzh_radius_card` / `gzh_radius_row` / `gzh_radius_field` | 24dp / 16dp / 16dp |
| `gzh_button_height` | 52dp |
| `gzh_icon` / `gzh_icon_button` | 22dp / 44dp |
| `gzh_text_display` / `title` / `body` / `caption` / `overline` | 28sp / 17sp / 14sp / 12sp / 12sp |

### 4.2 The standard palette (`colors.xml`) — a Material 3 dark role set

**[FACT]** Near-black ground with a surface ladder, a soft lavender primary, a muted pastel for
destructive actions, and semantic status colours:

| Role | Value |
|---|---|
| `gzh_background` | `#0E0E11` |
| `gzh_surface` | `#131318` |
| `gzh_surface_container_low` → `_highest` | `#16161C` → `#1A1A21` → `#22222B` → `#2B2B36` |
| `gzh_on_surface` | `#ECEBF2` |
| `gzh_on_surface_variant` | `#A5A2B4` |
| `gzh_on_surface_faint` | `#8F8CA0` (raised from `#7C798C` to clear WCAG AA 4.5:1 at 12sp) |
| `gzh_outline` / `_variant` | `#3A3846` / `#26252F` |
| `gzh_primary` (accent) | `#B9AEEA` (soft lavender) |
| `gzh_on_primary` | `#231B3F` |
| `gzh_primary_container` / `on_` | `#38305C` / `#E4DDFF` |
| `gzh_secondary` | `#C9C4DC` |
| `gzh_tertiary` (destructive) | `#F3C9C6` |
| `gzh_error` | `#FFB4AB` |
| `gzh_status_ok` / `warn` / `error` | `#7FDCA4` / `#F2C97D` / `#FFB4AB` |
| `gzh_on_surface_disabled` / `gzh_container_disabled` | 38 % / 12 % of on-surface |

### 4.3 The navy theme — yes, it exists

**[FACT]** `colors_palettes.xml` (18/10/2026) adds two alternative palettes "from the GeneralsX fork
by realkot, used with permission". The **navy** palette is navy + gold:

| Role | Value |
|---|---|
| `gzh_navy_background` | `#06111D` (very dark navy) |
| `gzh_navy_surface` / `_low` / `_container` / `_high` / `_highest` | `#0A1726` / `#0C1B2D` / `#0D1E30` / `#15273C` / `#1C3249` |
| **`gzh_navy_primary` (accent)** | **`#FFC84C` (gold)** |
| `gzh_navy_accent` | `#FFC84C` |
| `gzh_navy_on_primary` | `#201607` |
| `gzh_navy_primary_container` / `on_` | `#45321B` / `#FFE6A3` |
| `gzh_navy_secondary` / `container` | `#93BDE8` / `#173A54` |
| `gzh_navy_on_surface` / `variant` / `faint` | `#F5F7FB` / `#B6C9E2` / `#A4B9D4` |
| `gzh_navy_outline` / `_variant` | `#45647F` / `#263E57` |
| `gzh_navy_tertiary` (destructive) | `#F0B9A1` |
| `gzh_navy_status_ok` / `warn` / `error` | `#8BE6B3` / `#FFCE5D` / `#FFB4AB` |
| `gzh_navy_ripple_primary` | `#44FFC84C` (gold ripple) |

Plus a **day** (light) palette (`gzh_day_*`) with an amber accent `#895300`.

### 4.4 How a palette is selected and applied

**[FACT]** `ThemeHelper`:

```java
STANDARD = 0; NAVY = 1; DAY = 2; SYSTEM = 3;      // stored as launcher_theme_mode
palette(ctx)  // SYSTEM resolves via Configuration.UI_MODE_NIGHT_MASK → DAY when light, else STANDARD
mapColor(ctx, colorRes)
    // "gzh_foo" → looks up "gzh_navy_foo" / "gzh_day_foo"; caches in a SparseIntArray
apply(activity)     // setTheme(Theme.GeneralsZHSettingsNavy | …Light | …Settings)
applyBars(activity) // light status/nav bars only for DAY
```

**[FACT]** Every screen names the **standard** colour (`R.color.gzh_*`); `UiKit.color()` routes it
through `mapColor`. Themes (`themes.xml`) carry three full `Theme.Material3.*` definitions plus a
dialog overlay and an `AlertDialog` theme per palette, so framework dialogs match.

**[INFERENCE][GAP]** The remapping is *name-based*: it works only for colours whose resource name
starts with `gzh_` **and** for which a `gzh_navy_*` / `gzh_day_*` twin exists. A new token added
without its twins silently stays in the standard palette in navy/day mode. There is no build-time
check for this. `colors_palettes.xml` has 80 lines vs `colors.xml`'s 74 — the counts are close but
not identical, so at least one token may already be missing a twin.

---

## 5. Adaptation

| Concern | How it is handled |
|---|---|
| **Edge-to-edge (targetSdk 35)** | `InsetUtil.applySafeInsets(root)` on every screen; it captures the caller's padding first and *adds* the system-bar + cutout insets, because `setPadding` replaces rather than accumulates |
| **Display cutout, landscape** | same; the game surface additionally computes a fractional safe-area argument (`-gxSafeInsets`) from cutout insets and, on API 31+, rounded-corner geometry |
| **RTL (Arabic, Farsi)** | `supportsRtl=true`; all layout uses `setMarginStart/End`, `setCompoundDrawablesRelative`, `Gravity.CENTER`; the bottom navigation is pinned LTR on purpose |
| **Long translations** | `UiKit.share()` forces equal-width buttons with `MATCH_PARENT` height and 2 max lines, because a wrapped Ukrainian label once made one button taller than its siblings |
| **Text scaling / wrapping** | `TextView.setLineSpacing(0, 1.25)`, `setMaxLines` + `TruncateAt.END` on chips and overlines |
| **Screen sizes / rotation** | The launcher is portrait-first and scrolls; `SetupActivity` and the other launcher activities declare `configChanges=orientation\|screenSize\|keyboardHidden`; the game is locked landscape |
| **Tablet / wide screens** | **[GAP]** No layout variant (`values-sw600dp`, `layout-land`). The launcher is a single full-width column on any width; on a tablet the cards stretch the full width. |
| **Foldables / multi-window** | **[GAP]** Not addressed; `GeneralsZHActivity` is `singleInstance` landscape, which is hostile to split-screen |

---

## 6. State and data management

**[FACT]**

* **Persistence stores used by the UI**:
  * `SharedPreferences("generalszh_setup")` — game, folders, simulation rate, language, theme,
    skip-intro, subtitles, mod order (per game), langpack offered flags, support-popup-seen.
  * `SharedPreferences("gx_update")` — serial, engine channel, auto-check, last check, deps stamp,
    datapack latest, settings date.
  * `SharedPreferences("generals_online")` / `("generalsonline_session")` — data pack version,
    session tokens.
  * `SharedPreferences("replay_check")`.
  * Marker files in app-private storage for everything native reads.
  * `Options.ini` in the user-data dir for the three values the engine owns.
* **Page-scoped view references** are held in fields and explicitly nulled by
  `clearPageReferences()` before every page swap; every refresher null-checks.
* **Process-scoped flags** (`sAutoUpdateCheckedThisProcess`, `sDataPackCheckedThisProcess`,
  `sTestChannel`, `languagePackUpdateChecked`, `signInGeneration`, `cachedGpuName`) exist to keep
  network work once-per-process.
* **Generation counters**: `ModManagerActivity.analysisGeneration`, `GeneralsOnlineActivity.
  signInGeneration` — a superseded asynchronous result is discarded rather than applied.

**[INFERENCE]** The state model is *ad hoc but disciplined*: the same pattern (field → null-check →
refresh) is applied consistently, and the generation counters show the team has already been bitten
by stale async results. It would still benefit from a small presenter/ViewModel layer — but not at
the cost of the zero-dependency simplicity that currently works.

---

## 7. Error handling and messaging

**[FACT]**

| Mechanism | Where |
|---|---|
| **Fallback UI** | `SetupActivity.onCreate` wraps `buildUi()` in `try/catch (Throwable)` → `buildFallbackUi`, which builds a plain-widget screen (plain `Button`, no Material) offering Select folder / View logs / Launch / Clear folder. The comment explains it must not itself construct a Material widget. |
| **Blocking dialog after a bad folder** | `onActivityResult` → `showFolderProblemDialog(message[, offerBasePicker])` |
| **Toast** | `toast(CharSequence)` and `Toast.makeText` for saves, failures, update results |
| **Status line** | `refreshStatus()` builds a `SpannableStringBuilder` with a bold/underlined/green-amber-red verdict span |
| **Chips** | `UiKit.chip` for GPU name, driver state, data sources, mod summary |
| **Mod import problems** | `ModImportService.ImportProblem` carries a string resource id; `ImportCancelledException` is a distinct type |
| **Native crash visibility** | `AndroidCrashHandler.cpp` writes `crash.log` from an ELF constructor; the Log Viewer surfaces it |
| **Redirect instead of crash** | `GeneralsZHActivity.onCreate` never calls `super.onCreate` (and so never `dlopen`s the engine) when no valid folder is configured; it opens Setup instead |

**[INFERENCE][GAP]** Error text is largely assembled inline in `SetupActivity` with
`getString(...)` and `String.valueOf(throwable)` (e.g. `setup_fallback_warning` includes the raw
exception), which is developer-facing on a user-facing screen.

---

## 8. Component reuse — assessment

**Reused well. [FACT]**
* `UiKit` — used by all seven launcher screens, every method has several callers.
* `ThemeHelper` — applied by all seven; `applyBars` too.
* `LocaleHelper.wrap` — `attachBaseContext` overridden by all seven.
* `InsetUtil` — all seven.
* `UiKit.appBar` + `UiKit.scrollingPage` — all seven (identical shell).
* `UiKit.listRow` returning a `Row` with mutable `title`/`supporting` — used where a line must be
  rewritten on refresh (`SetupActivity` support rows, logs rows, replay list).

**Not reused / duplicated. [FACT]**
* The **shell boilerplate** (LinearLayout + background colour + appBar + host + page +
  setContentView + applySafeInsets) is repeated in seven `onCreate`s. `UiKit.scrollingPage` is used
  by four of them (`SetupActivity`, `GeneralsOnlineActivity`, `ModManagerActivity`,
  `ReplayCheckActivity`); `LogViewerActivity` and `NetworkDiagnosticsActivity` hand-roll their own
  `ScrollView`, and `FolderPickerActivity` uses a `ListView`. A
  `UiKit.screen(Activity, overline, title, icon, action)` returning `(root, page)` would remove
  ~10 lines × 7 and bring the two hand-rolled screens back into the shared shell.
* **Slider styling** — the four tint lines are repeated verbatim for the text-size slider, the
  interface-size slider, the cursor-size slider and the upscale slider. A
  `UiKit.slider(parent, from, to, step, value)` is the obvious extraction.
* **`Options.ini` read-modify-write** — `writeUiScalePercent`, `writeInterfaceScalePercent` and
  `writeUpscaleMode` each re-implement "seed from `DefaultOptions.ini` if new, else read, then put,
  then write". One helper would serve all three.
* **Marker-file read/write** — `readMouseCursorPercent`/`writeMouseMode`, `readControlScheme`/
  `writeControlScheme`, `setDiagnosticMarker`, `setLoggingDisabled`, `writeNativeGameMarker`,
  `readExternalMarker` all hand-roll file I/O with their own error handling.
* **The three big `if (UpdateManager.featureEnabled(this, "pc_crossplay", false))` blocks** and the
  repeated `isGeneralsSelected(this) ? R.string.x_generals : R.string.x` idiom.

---

## 9. What needs improvement, keeping the visual identity

Ordered by value, all of them preserving the current look:

| # | Issue | Change | Risk |
|---|---|---|---|
| 1 | `SetupActivity` is 4 529 lines, 244 strings, ~60 methods | Split by section into `HomePage`, `GraphicsPage`, `InterfacePage`, `ToolsPage`, `HelpPage` classes that take the `page` column and a shared `LauncherState`; keep `SetupActivity` as the shell | Medium — mechanical, but the file is the app's spine; needs care with `clearPageReferences` and the fallback UI |
| 2 | Shell boilerplate × 7 | `UiKit.screen(...)` | Low |
| 3 | Slider styling × 4 | `UiKit.slider(...)` | Low |
| 4 | `Options.ini` write × 3 | one helper | Low |
| 5 | Marker-file I/O scattered | a `Marker` helper (`readLine`/`writeLine`/`exists`/`delete`) | Low |
| 6 | Palette twins can silently go missing | a lint/test that asserts, for every `gzh_*` in `colors.xml`, the presence of `gzh_navy_*` and `gzh_day_*` | Low (needs a test source set — see `ROADMAP.md`) |
| 7 | No tablet layout | `values-sw600dp` with a wider gutter, or a two-column `LinearLayout` for Home | Low |
| 8 | Scroll position lost on Graphics page rebuild | remember and restore the `ScrollView` position across `showTab` | Low |
| 9 | Whole shell rebuilt when the game changes | rebuild only the affected pages, or re-run the Home page | Low |
| 10 | Raw exception text in the fallback screen | show a friendly line and log the exception | Low |
| 11 | No empty/loading skeleton for async cards | a `UiKit.placeholder(parent, text)` used while the update/data-pack checks run | Low |
| 12 | No accessibility audit | `contentDescription` is set on icon buttons, segments and some status views; a pass over the chips and switches would help. **[FACT]** there is one deliberate pattern already: `content.setContentDescription(getString(setup_language_status, …))` so a screen reader hears the full sentence the visual design abbreviates | Low |

---

## 10. Visual identity — summary of what must be preserved

**[FACT]** The identity, in the code's own terms:

* A near-black ground (`#0E0E11`) with a four-step surface ladder for cards and rows.
* One accent per palette: soft lavender `#B9AEEA` (standard), **gold `#FFC84C` on navy `#06111D`
  (navy)**, amber `#895300` on light (day).
* Cards with 24dp corners, **zero elevation, no stroke** — depth comes from the surface ladder, not
  from shadows.
* A single spacing scale: 16dp gutter, 12dp gaps, 20dp card padding, 52dp buttons, 44dp icon
  buttons.
* Three text tiers only (17sp title / 14sp body / 12sp caption-overline), plus a 28sp display title.
* Colour used *semantically*: the accent marks headings, active state and the hero action; the muted
  pastel marks destructive actions; green/amber/red mark a verdict.
* Status is never a bare sentence: it is a chip, a coloured value, or a bold/underlined verdict span.

**[INFERENCE]** Any redesign should therefore change *structure* (splitting the god class, adding
layout variants, adding loading states) rather than *tokens*. The token layer is the part that is
already good, and it is what makes the theme switch work at all.
