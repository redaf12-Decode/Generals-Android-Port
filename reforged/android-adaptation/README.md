# Reforged Android adaptation layer

These patches adapt the **olcay Reforged engine** (`GeneralsMD/Code`) to build as an Android
ARM64 shared library. They are applied to a fresh checkout of the pinned engine at build time and
are **never committed into the engine tree**.

## Provenance

- Source: `louay0khaled/zero-hour-reforged-android-mod`, commit
  `d240f2af734cb6c69ddf301c12c473b79a909056`, files under `probe/patches/`.
- Engine pin: `olcayseygan/CnCGeneralsZH-Reforged` @
  `61dbc593fb4eecb7af891b80c4c491c3ad2b791d` (see `docs/reforged-integration/engine-source.manifest.json`).
- License: the engine is GPLv3; these patches are derived from and apply to that GPLv3 source and
  carry the same license.

## Applied in this order (by `scripts/build/android/build-reforged-engine.sh`)

| Order | Patch | Tree | Requires |
|-------|-------|------|----------|
| 1 | `reforged-c1-android.patch` | olcay `GeneralsMD/Code` | fresh checkout |
| 2 | `reforged-frame-selfcheck-entry.patch` | olcay `GeneralsMD/Code` | after c1 |
| 3 | `reforged-frame-selfcheck-mali-tolerances.patch` | olcay `GeneralsMD/Code` | after #2 |
| — | *run `GeneralsMD/Code/Tools/vendor.sh`* | populates SDL3 / GameSpy / FreeType / glslang / SPIRV-Cross | — |
| 4 | `reforged-rfx-jni-prefix.patch` | vendored `Libraries/Source/SDL3` | after vendor.sh |
| 5 | `gamespy-android-pthread.patch` | vendored GameSpy (`src/common/linux/gsthreadlinux.c`) | after vendor.sh |
| 6 | `reforged-c4-game-library.patch` | olcay `GeneralsMD/Code` | after #1–3 |

Verified locally against `61dbc59`: #1, #2, #3, #6 all pass `git apply --check`; #4/#5 require the
vendored trees and are applied after `vendor.sh` (as louay's own workflow does).

## Output

`add_library(rfx_game SHARED ...)` (added by c4) → `librfx_game.so` (ELF aarch64, exports `SDL_main`).
Renamed `libmain.so` for packaging as the single play engine.