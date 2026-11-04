#!/usr/bin/env python3
"""Generate the accent colour and theme resources for the launcher.

Three outputs, all committed:

  values/colors_accents.xml       the dark halves, shared by the STANDARD and NAVY palettes
                                  (gzh_accent_<name>_<role>)
  values/colors_accents_day.xml   the DAY halves, which also tint the surface ladder
                                  (gzh_day_accent_<name>_<role>)
  values/themes_accents.xml       the overlays and dialog styles that name them

Resources are named after the *role* (the base colour's own name after "gzh_"), because
ThemeHelper.mapColor() composes "<palettePrefix>accent_<name>_<role>" from the colour it is
asked to map and every UiKit widget follows with no call-site change. The reference branch
spells some of those roles differently in its own names; ROLES below keeps the two apart.

Dark accent values are the reference branch's (its values-night ladder IS this app's navy
palette, and accent roles carry no ladder, so they suit any dark ground). Day accent values are
the reference's accent roles, with the surface ladder re-tinted per accent hue so a day-mode
accent recolours the page too.

    python3 scripts/tooling/theme/generate-accent-colors.py [--ref DIR]

--ref points at the two files extracted from the reference branch (fix-ui-Draw-18-10):
DIR/values_accent_colors.xml and DIR/values-night_accent_colors.xml. Only needed to
regenerate, which is only when the palette itself changes; the outputs are committed.
"""

import argparse
import colorsys
import io
import os
import re
import sys

OUT_DARK = ("/workspace/project/Generals-Android-Port/android/app/src/main/res"
            "/values/colors_accents.xml")
OUT_DAY = ("/workspace/project/Generals-Android-Port/android/app/src/main/res"
           "/values/colors_accents_day.xml")
OUT_THEMES = ("/workspace/project/Generals-Android-Port/android/app/src/main/res"
              "/values/themes_accents.xml")

# Overlay attributes: the accent roles every palette defines, then the day-only
# ladder. The dark palettes share one overlay set because their accent values
# are identical; day mode needs its own because it tints the page as well.
ACCENT_ITEMS = [
    ("colorPrimary", "primary"),
    ("colorOnPrimary", "on_primary"),
    ("colorPrimaryContainer", "container"),
    ("colorOnPrimaryContainer", "on_container"),
    ("colorAccent", "primary"),
    ("android:textColorHighlight", "container"),
    ("itemRippleColor", "ripple"),
    ("colorSecondary", "secondary"),
    ("colorOnSecondary", "on_secondary"),
    ("colorSecondaryContainer", "secondary_container"),
    ("colorOnSecondaryContainer", "on_secondary_container"),
]
LADDER_ITEMS = [
    ("android:colorBackground", "background"),
    ("android:windowBackground", "background"),
    ("colorSurface", "surface"),
    ("colorSurfaceContainerLow", "surface_container_low"),
    ("colorSurfaceContainer", "surface_container"),
    ("colorSurfaceContainerHigh", "surface_container_high"),
    ("colorSurfaceContainerHighest", "surface_container_highest"),
    ("colorSurfaceVariant", "surface_container_high"),
    ("colorOutline", "outline"),
    ("colorOutlineVariant", "outline_variant"),
]

# Dialog styles are separate themes, so the activity's accent overlay cannot reach the colour a
# dialog paints with: each palette needs its own accent dialog pair. (label, surface colour
# prefix, accent colour prefix, window background drawable). The accent prefix differs from the
# surface one on purpose -- the two dark palettes share the accent resources, while every palette
# keeps its own surface ladder.
DIALOG_PALETTES = [
    ("", "gzh_", "gzh_accent_", "gzh_dialog_background", "Theme.Material.Dialog.Alert"),
    ("Navy", "gzh_navy_", "gzh_accent_", "gzh_dialog_background_navy", "Theme.Material.Dialog.Alert"),
    ("Day", "gzh_day_", "gzh_day_accent_", "gzh_dialog_background_day", "Theme.Material.Light.Dialog.Alert"),
]

# Same order as ThemeHelper.ACCENT_* (0 = default, which needs no resource:
# each palette's own natural accent is already its base colour set).
NAMES = ["blue", "green", "teal", "orange", "red",
         "black", "gray", "gold", "transparent", "white", "yellow"]

# (gzh_* role after "gzh_", suffix in the reference's accent names). The ROLE is what the
# resource is named after -- ThemeHelper composes "<prefix>accent_<name>_<role>" from the base
# colour's own name -- and the suffix is only how the reference happens to spell it.
ACCENT_ROLES = [
    ("primary", "primary"),
    ("on_primary", "on_primary"),
    ("primary_container", "container"),
    ("on_primary_container", "on_container"),
    ("ripple_primary", "ripple"),
    ("secondary", "secondary"),
    ("on_secondary", "on_secondary"),
    ("secondary_container", "secondary_container"),
    ("on_secondary_container", "on_secondary_container"),
]

# The extra roles only DAY tints. On the dark palettes these fall through to
# the base palette (mapColor's fallback), which is exactly what the reference
# does: its night ladder is identical for every accent.
LADDER_ROLES = [
    ("background", "background"),
    ("surface", "surface"),
    ("surface_container_low", "container_low"),
    ("surface_container", "container_surface"),
    ("surface_container_high", "container_high"),
    ("surface_container_highest", "container_highest"),
    ("outline", "outline"),
    ("outline_variant", "outline_variant"),
]

NEUTRAL_SATURATION = 0.10   # below this a hue is meaningless (white/gray/black)


def load(path):
    text = io.open(path, encoding="utf-8").read()
    return dict(re.findall(r'<color name="([^"]+)">([^<]+)</color>', text))


def rgb(hex_color):
    value = int(hex_color.lstrip("#"), 16)
    if len(hex_color.lstrip("#")) == 8:
        return (value >> 24) & 0xFF, (value >> 16) & 0xFF, (value >> 8) & 0xFF
    return (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF


def hue_saturation(hex_color):
    r, g, b = (c / 255.0 for c in rgb(hex_color))
    h, _, s = colorsys.rgb_to_hls(r, g, b)
    return h, s


def tint(hex_color, hue):
    """The same lightness/saturation, rotated onto `hue`."""
    r, g, b = (c / 255.0 for c in rgb(hex_color))
    _, lightness, saturation = colorsys.rgb_to_hls(r, g, b)
    nr, ng, nb = colorsys.hls_to_rgb(hue, lightness, saturation)
    return "#%02X%02X%02X" % (round(nr * 255), round(ng * 255), round(nb * 255))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--ref", default="/tmp/ref",
                        help="directory holding the reference branch's accent_colors.xml pair")
    args = parser.parse_args(argv)

    ref_day_path = os.path.join(args.ref, "values_accent_colors.xml")
    ref_night_path = os.path.join(args.ref, "values-night_accent_colors.xml")
    for path in (ref_day_path, ref_night_path):
        if not os.path.isfile(path):
            sys.stderr.write("Missing reference file: %s\n"
                             "Extract both accent_colors.xml from the reference branch "
                             "(fix-ui-Draw-18-10) into --ref.\n" % path)
            return 1

    ref_day = load(ref_day_path)
    ref_night = load(ref_night_path)

    # The day ladder the reference uses, keyed by the role the resource is named after; its hue
    # is the violet default's, which is what it is already tinted with.
    base_ladder = {role: ref_day["gzh_accent_blue_" + suffix]
                   for role, suffix in LADDER_ROLES}
    base_hue, _ = hue_saturation(ref_day["gzh_accent_blue_primary"])

    dark_lines = []
    day_lines = []

    for name in NAMES:
        dark_lines.append("    <!-- %s -->" % name.capitalize())
        day_lines.append("    <!-- %s -->" % name.capitalize())

        for role, suffix in ACCENT_ROLES:
            dark_lines.append(
                '    <color name="gzh_accent_%s_%s">%s</color>'
                % (name, role, ref_night["gzh_accent_%s_%s" % (name, suffix)]))
            day_lines.append(
                '    <color name="gzh_day_accent_%s_%s">%s</color>'
                % (name, role, ref_day["gzh_accent_%s_%s" % (name, suffix)]))

        # Day only: the tinted surface ladder. A neutral accent keeps the
        # neutral ladder rather than inventing a hue it does not have.
        hue, saturation = hue_saturation(ref_day["gzh_accent_%s_primary" % name])
        if saturation < NEUTRAL_SATURATION:
            hue = base_hue
        for role, _ in LADDER_ROLES:
            day_lines.append(
                '    <color name="gzh_day_accent_%s_%s">%s</color>'
                % (name, role, tint(base_ladder[role], hue)))

        dark_lines.append("")
        day_lines.append("")

    header = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
              "<resources>\n"
              "    <!-- GENERATED by scripts/tooling/theme/generate-accent-colors.py;"
              " do not edit by hand.\n"
              "         See that script for what each half carries and why. -->\n")
    tail = "</resources>\n"

    io.open(OUT_DARK, "w", encoding="utf-8").write(
        header + "\n".join(dark_lines).rstrip() + "\n" + tail)
    io.open(OUT_DAY, "w", encoding="utf-8").write(
        header + "\n".join(day_lines).rstrip() + "\n" + tail)

    # The activity overlay the accent folds into, per palette per accent. It carries the dialog
    # theme references too: a dialog is a separate theme, so it never inherits the activity's
    # accent -- naming its style here is what makes the dialogs follow the choice.
    theme_lines = []
    for name in NAMES:
        title = name.capitalize()
        for label, surface, accent, drawable, parent in DIALOG_PALETTES:
            suffix = label + title
            theme_lines.append('    <style name="ThemeOverlay.GeneralsZH.Accent%s" parent="">' % suffix)
            for attr, role in ACCENT_ITEMS:
                theme_lines.append('        <item name="%s">@color/%s%s_%s</item>'
                                   % (attr, accent, name, role))
            if label == "Day":
                for attr, role in LADDER_ITEMS:
                    theme_lines.append('        <item name="%s">@color/gzh_day_accent_%s_%s</item>'
                                       % (attr, name, role))
            theme_lines.append('        <item name="materialAlertDialogTheme">'
                               '@style/ThemeOverlay.GeneralsZH.Dialog%s</item>' % suffix)
            theme_lines.append('        <item name="android:alertDialogTheme">'
                               '@style/Theme.GeneralsZH.AlertDialog%s</item>' % suffix)
            theme_lines.append("    </style>")
            theme_lines.append("")

    # The dialogs themselves. Only the accent colour differs from the palette's own dialog style
    # (themes.xml): the surface and text roles are the palette's, and the dialog is not tinted
    # like a day-mode page.
    for name in NAMES:
        title = name.capitalize()
        for label, surface, accent, drawable, parent in DIALOG_PALETTES:
            suffix = label + title
            theme_lines.append(
                '    <style name="ThemeOverlay.GeneralsZH.Dialog%s"'
                ' parent="ThemeOverlay.Material3.MaterialAlertDialog">' % suffix)
            theme_lines.append('        <item name="colorSurface">@color/%ssurface_container_high</item>'
                               % surface)
            theme_lines.append('        <item name="colorPrimary">@color/%s%s_primary</item>'
                               % (accent, name))
            theme_lines.append('        <item name="android:textColorPrimary">@color/%son_surface</item>'
                               % surface)
            theme_lines.append('        <item name="android:textColorSecondary">@color/%son_surface_variant</item>'
                               % surface)
            theme_lines.append("    </style>")
            theme_lines.append(
                '    <style name="Theme.GeneralsZH.AlertDialog%s" parent="android:%s">' % (suffix, parent))
            theme_lines.append('        <item name="android:windowBackground">@drawable/%s</item>' % drawable)
            theme_lines.append('        <item name="android:colorAccent">@color/%s%s_primary</item>'
                               % (accent, name))
            theme_lines.append('        <item name="android:textColorPrimary">@color/%son_surface</item>'
                               % surface)
            theme_lines.append('        <item name="android:textColorSecondary">@color/%son_surface_variant</item>'
                               % surface)
            theme_lines.append('        <item name="android:textColorAlertDialogListItem">@color/%son_surface</item>'
                               % surface)
            theme_lines.append("    </style>")
            theme_lines.append("")

    io.open(OUT_THEMES, "w", encoding="utf-8").write(
        header + "\n".join(theme_lines).rstrip() + "\n" + tail)

    print("dark: %d accents x %d roles" % (len(NAMES), len(ACCENT_ROLES)))
    print("day : %d accents x %d roles"
          % (len(NAMES), len(ACCENT_ROLES) + len(LADDER_ROLES)))
    print("themes: %d accents x %d palettes x 3 styles"
          % (len(NAMES), len(DIALOG_PALETTES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
