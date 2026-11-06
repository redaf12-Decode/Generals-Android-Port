# Third-party assets and attribution

This file records the attribution required by third-party assets bundled with this
port. Engine code and its lineage are covered in the
[README](README.md#lineage--credits).

## Icons

### Floating toolbox icon ("Control")

- **Asset**: `android/app/src/main/res/drawable/ic_gzh_toolbox.xml`
- **Title**: Control
- **Author**: Creatype (Flaticon)
- **Source**: <https://www.flaticon.com/free-icon/control_6841795>
- **License**: [Flaticon Free License](https://www.flaticon.com/legal) — free for
  personal and commercial use **with attribution**

Attribution as required by the license:

> Control icon by [Creatype](https://www.flaticon.com/authors/creatype) from
> [Flaticon](https://www.flaticon.com).

The icon is traced to an Android `VectorDrawable` so it renders sharply at every
screen density. It is bundled in the APK — nothing is fetched at runtime — and its
aspect ratio and transparency are preserved.

## Images

### Touch Controls Editor background

- **Asset**: `android/app/src/main/res/drawable-nodpi/touch_editor_background.jpg`
- **Source**: <https://media.moddb.com/cache/images/games/1/1/184/thumb_620x2000/gen-3.jpg>
- **Subject**: Command & Conquer: Generals — Zero Hour key art, from the game's
  [ModDB profile](https://www.moddb.com/games/cc-generals-zero-hour)

The picture is bundled in the APK and decoded locally; nothing is downloaded at
runtime. It is drawn centre-cropped behind the editor's controls (aspect ratio
preserved, never stretched) under a translucent dark scrim, and it takes no touch.
It is a screenshot of the game this port reimplements, so no separate attribution is
required beyond the game's own.
