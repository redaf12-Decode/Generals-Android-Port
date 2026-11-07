# Tools: Map Packs Manager (activation, deactivation, 200-map limit)

Date: 2026-11-07 · Branch: `New-app` · Area: Android launcher (Tools tab)

The Tools → Map Manager screen from the previous commit installed a map straight into the game's
`Maps/` folder. This change turns it into a **Map Packs Manager**: imported content lives in its own
folder under `<user data>/maps-manager`, and *activating* a pack copies its maps into the game's
`Maps/` folder, where the engine finds them. Deactivating removes those copies again. A combined
limit of **200 unique active maps** is enforced.

It supersedes the old `MapManagerActivity` (deleted) and adds `MapPackManager` / `MapPackActivity`.
`MapManager` is kept as the shared "where the maps are and what a map is" helper.

## 1. Storage (measured, not assumed)

- Imported packs: **`<user data>/maps-manager/<pack>`**, one folder per pack. `SetupActivity
  .userDataDir(ctx)` resolves `<user data>` (the same folder the engine reads).
- The game's maps: **`<user data>/Maps`** (`MapCache::getUserMapDir()` = `getPath_UserData() +
  "Maps"`; the launcher's `MapManager.mapsDir`). Activation copies map folders here; the engine's
  `MapCache::updateCache` scans this folder on every map-list refresh, so an activated pack is
  visible at the next map list with **no restart**.
- A map is still `<name>/<name>.map` (`MapCache::hasValidMapLeafLayout`); a `.map` is a RefPack
  (`EAR\0`) or a `CkMp` chunk tree (`MapManager.isMapFile`). Unchanged from the previous report.

## 2. Activation model

`MapPackManager` keeps the two places in step with an explicit ownership rule:

- The **desired set** is the union of the maps of every *active* pack, keyed by map name
  (case-insensitive, as the engine keys them). A map that two active packs share is installed once
  and removed only when **both** packs are switched off.
- A **manifest** (`maps-manager/.active.txt`) records the `Maps/` folders this manager itself
  created. Only those are ever deleted, so a map the player dropped into `Maps/` by hand is never
  removed — the manager only touches what it installed.
- `activate` / `deactivate` / `sync` all go through one `apply(...)` that computes the desired set,
  copies what is missing, and deletes only manifest entries no longer wanted. A failure part way
  through undoes the copies it made, so a pack is never left half active.
- `sync(ctx)` reconciles the saved active list with the real `Maps/` folder. It runs when the screen
  opens and once more from `GeneralsZHActivity` right before the engine starts, so an interrupted
  activation heals instead of persisting.

Nothing is installed at import time: an imported pack is only listed, and stays inert until the
player switches it on.

## 3. The 200-map limit

- `MAX_ACTIVE_MAPS = 200`, counted on **unique** maps across the active packs (`uniqueMapNames`),
  not on the sum of pack sizes.
- A pack that would push the union past 200 is refused **whole** — nothing is copied — with
  `R.string.mappack_limit_message` (the exact Arabic text the owner specified).
- Deactivation never checks the limit, so a player at 200 can always free room.
- The pack row shows a warning chip when switching the pack on would exceed the limit, before the
  switch is flipped, using `activeCountWith(ctx, name)`.

## 4. UI (`MapPackActivity`)

One screen, reachable from **Setup → Tools → Map Manager**:

- Import card: import a folder (`ACTION_OPEN_DOCUMENT_TREE`) or a ZIP (`ACTION_OPEN_DOCUMENT`).
- Status card: active-map count against the limit, the number of installed maps, and the two storage
  paths.
- Pack list: each pack shows its map count and active/inactive state, a switch to activate or
  deactivate it, and per-pack actions (details, export, delete).
- The pack detail dialog offers activate/deactivate, export (folder or ZIP) and delete.

Export (carried over from the previous task) writes a pack as a folder or a ZIP through the system
picker, from the pack root so the ZIP imports back as the same pack; the pack's own metadata file is
excluded from both.

## 5. Modified files

| File | Change |
|---|---|
| `MapPackManager.java` | new — pack model, list, activate/deactivate, sync, 200-map limit, import, export |
| `MapPackActivity.java` | new — the Map Packs screen |
| `MapManagerActivity.java` | deleted (superseded) |
| `MapManager.java` | kept as the shared map-location/map-format helper |
| `SetupActivity.java` | Tools entry now opens `MapPackActivity` |
| `AndroidManifest.xml` | `.MapPackActivity` registered |
| `GeneralsZHActivity.java` | `MapPackManager.sync(this)` before the engine starts |
| `res/values*/strings.xml` | `mappack_*` strings (50) in all 13 locales |
| `MapAndControlBarToolsTest.java` | map-pack tests (import, activate, limit, sync, delete, export) |

No native file, `Core/**`, `Generals/**`, `GeneralsMD/**` or CMake file was touched.

## 6. Verification

- `:app:testDebugUnitTest` — **266 tests, 0 failures** (the Tools suite drives a real `maps-manager`
  tree and real ZIPs: import creates a pack, activation copies maps and the engine sees them, a map
  shared by two packs is installed once and removed only when both are off, a hand-placed file in
  `Maps/` survives `sync`, the 200-map limit refuses with the exact message and copies nothing, a
  pack exports as a ZIP that imports back to the same maps).
- `:app:assembleLauncherOnly` — `BUILD SUCCESSFUL`.
- `:app:lintDebug` — `0 errors`.
- `scripts/qa/android/check-translations.py` — green across 13 locales (670 strings each).
