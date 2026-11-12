package com.ports.generalsx;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * GeneralsX @feature Android port 18/10/2026 The launcher's appearance (Interface -> Appearance):
 * the "navy" (navy and gold) or "day" (light) palette of the GeneralsX fork by realkot (used with
 * permission), the standard dark look, or "system", which follows the phone: day when it is light,
 * standard when it is dark. GeneralsX @tweak Android port 08/11/2026 SYSTEM is the default for a
 * fresh install (getMode); a stored choice always wins. Launcher only; the game is never affected.
 *
 * A palette is a set of colours named like the standard ones with a gzh_navy_ / gzh_day_ prefix
 * (colors_palettes.xml): UiKit.color() maps a standard colour to the chosen palette, and apply()
 * sets the matching Material theme, so screens keep naming the standard colours.
 *
 * GeneralsX @feature Android port 04/11/2026 An ACCENT rides on top of the palette: the same
 * Interface card offers eleven accents (colors_accents.xml), and mapColor() maps an accent-bearing
 * role through the accent first, falling back to the palette when that accent does not define it.
 * That fallback is what lets the dark palettes tint only the accent roles while day mode also
 * recolours its surface ladder, with no per-palette role list to keep in sync.
 */
final class ThemeHelper {
    static final int STANDARD = 0;
    static final int NAVY = 1;
    static final int DAY = 2;
    static final int SYSTEM = 3;
    private static final String PREF_MODE = "launcher_theme_mode";
    private static final String PREF_ACCENT = "launcher_accent";

    /** The palette's own accent: no overlay, no preference stored. */
    static final int ACCENT_DEFAULT = 0;
    static final int ACCENT_BLUE = 1;
    static final int ACCENT_GREEN = 2;
    static final int ACCENT_TEAL = 3;
    static final int ACCENT_ORANGE = 4;
    static final int ACCENT_RED = 5;
    static final int ACCENT_BLACK = 6;
    static final int ACCENT_GRAY = 7;
    static final int ACCENT_GOLD = 8;
    static final int ACCENT_TRANSPARENT = 9;
    static final int ACCENT_WHITE = 10;
    static final int ACCENT_YELLOW = 11;
    /** Same order as the resource names in colors_accents.xml and the picker. */
    static final int ACCENT_COUNT = 12;

    /**
     * Index-aligned with the ACCENT_* constants; entry 0 is null because the default is the base
     * palette itself. One set per palette, because each names its own accent resources (day mode
     * also tints its surface ladder) and each carries its own dialog theme references.
     */
    private static final int[] STANDARD_ACCENT_THEMES = {
        0,
        R.style.ThemeOverlay_GeneralsZH_AccentBlue,
        R.style.ThemeOverlay_GeneralsZH_AccentGreen,
        R.style.ThemeOverlay_GeneralsZH_AccentTeal,
        R.style.ThemeOverlay_GeneralsZH_AccentOrange,
        R.style.ThemeOverlay_GeneralsZH_AccentRed,
        R.style.ThemeOverlay_GeneralsZH_AccentBlack,
        R.style.ThemeOverlay_GeneralsZH_AccentGray,
        R.style.ThemeOverlay_GeneralsZH_AccentGold,
        R.style.ThemeOverlay_GeneralsZH_AccentTransparent,
        R.style.ThemeOverlay_GeneralsZH_AccentWhite,
        R.style.ThemeOverlay_GeneralsZH_AccentYellow,
    };
    private static final int[] NAVY_ACCENT_THEMES = {
        0,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyBlue,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyGreen,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyTeal,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyOrange,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyRed,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyBlack,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyGray,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyGold,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyTransparent,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyWhite,
        R.style.ThemeOverlay_GeneralsZH_AccentNavyYellow,
    };
    private static final int[] DAY_ACCENT_THEMES = {
        0,
        R.style.ThemeOverlay_GeneralsZH_AccentDayBlue,
        R.style.ThemeOverlay_GeneralsZH_AccentDayGreen,
        R.style.ThemeOverlay_GeneralsZH_AccentDayTeal,
        R.style.ThemeOverlay_GeneralsZH_AccentDayOrange,
        R.style.ThemeOverlay_GeneralsZH_AccentDayRed,
        R.style.ThemeOverlay_GeneralsZH_AccentDayBlack,
        R.style.ThemeOverlay_GeneralsZH_AccentDayGray,
        R.style.ThemeOverlay_GeneralsZH_AccentDayGold,
        R.style.ThemeOverlay_GeneralsZH_AccentDayTransparent,
        R.style.ThemeOverlay_GeneralsZH_AccentDayWhite,
        R.style.ThemeOverlay_GeneralsZH_AccentDayYellow,
    };

    /**
     * One colour cache per palette, per accent: what a colour maps to depends on both, so a cache
     * keyed on the colour alone would serve a stale value after either changes. They are separate
     * per palette and not shared by the two dark ones, because the palettes name different
     * resources for the same input colour (gzh_primary -> gzh_primary vs gzh_navy_primary).
     */
    private static final android.util.SparseIntArray[][] sCaches = {
        newCaches(ACCENT_COUNT), newCaches(ACCENT_COUNT), newCaches(ACCENT_COUNT),
    };
    private ThemeHelper() {}

    private static android.util.SparseIntArray[] newCaches(int count) {
        android.util.SparseIntArray[] caches = new android.util.SparseIntArray[count];
        for (int i = 0; i < count; i++) {
            caches[i] = new android.util.SparseIntArray();
        }
        return caches;
    }

    // GeneralsX @tweak Android port 08/11/2026 A fresh install follows the phone (SYSTEM), so the
    // launcher matches the system's light/dark setting out of the box. Only the DEFAULT: a stored
    // choice always wins, so an existing install keeps whatever the player picked, and choosing a
    // palette in Interface -> Appearance (SetupActivity.buildThemeSection) still overrides it.
    static int getMode(Context context) {
        int stored = context.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_MODE, SYSTEM);
        return stored >= STANDARD && stored <= SYSTEM ? stored : SYSTEM;
    }

    static void setMode(Context context, int mode) {
        if (mode < STANDARD || mode > SYSTEM) {
            throw new IllegalArgumentException("Unsupported launcher theme");
        }
        context.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PREF_MODE, mode).apply();
    }

    /** The chosen accent, or ACCENT_DEFAULT for the palette's own. */
    static int getAccent(Context context) {
        int stored = context.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_ACCENT, ACCENT_DEFAULT);
        return clampAccent(stored);
    }

    static void setAccent(Context context, int accent) {
        android.content.SharedPreferences.Editor editor =
            context.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (clampAccent(accent) == ACCENT_DEFAULT) {
            // Absent = the palette's own accent, matching how an unset theme mode means SYSTEM.
            editor.remove(PREF_ACCENT);
        } else {
            editor.putInt(PREF_ACCENT, clampAccent(accent));
        }
        editor.apply();
    }

    static int clampAccent(int accent) {
        return accent >= 0 && accent < ACCENT_COUNT ? accent : ACCENT_DEFAULT;
    }

    /** The palette in use: STANDARD, NAVY or DAY (SYSTEM resolved against the phone). */
    static int palette(Context context) {
        int mode = getMode(context);
        if (mode != SYSTEM) {
            return mode;
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_NO ? DAY : STANDARD;
    }

    /** The colour resource to use for a standard gzh_* colour under the chosen palette and accent. */
    static int mapColor(Context context, int colorRes) {
        int palette = palette(context);
        int accent = getAccent(context);
        android.util.SparseIntArray cache = sCaches[palette][accent];
        synchronized (cache) {
            int mapped = cache.get(colorRes, 0);
            if (mapped == 0) {
                mapped = resolve(context, colorRes, palette, accent);
                cache.put(colorRes, mapped);
            }
            return mapped;
        }
    }

    /**
     * A colour's value under the chosen palette and accent: the accent first, then the palette,
     * then the standard name. A colour the accent does not define falls through to the palette,
     * which is how the dark palettes tint only the accent roles while day mode also tints its
     * surface ladder. The default accent defines nothing, so it lands on the palette every time.
     */
    private static int resolve(Context context, int colorRes, int palette, int accent) {
        android.content.res.Resources res = context.getResources();
        String name = res.getResourceEntryName(colorRes);
        if (!name.startsWith("gzh_")) {
            return colorRes;
        }
        String role = name.substring(4);
        if (accent != ACCENT_DEFAULT) {
            int id = res.getIdentifier((palette == DAY ? "gzh_day_" : "gzh_")
                + "accent_" + ACCENT_NAMES[accent] + "_" + role, "color", context.getPackageName());
            if (id != 0) {
                return id;
            }
        }
        if (palette == NAVY) {
            int id = res.getIdentifier("gzh_navy_" + role, "color", context.getPackageName());
            if (id != 0) {
                return id;
            }
        } else if (palette == DAY) {
            int id = res.getIdentifier("gzh_day_" + role, "color", context.getPackageName());
            if (id != 0) {
                return id;
            }
        }
        return colorRes;
    }

    private static final String[] ACCENT_NAMES = {
        "", "blue", "green", "teal", "orange", "red",
        "black", "gray", "gold", "transparent", "white", "yellow",
    };

    /**
     * The accent applied to the ACTIVITY's theme, so anything resolving a theme attribute follows
     * the accent too. Each palette has its own overlay, which also names the accent's dialog
     * themes -- a dialog is a separate theme and never inherits the activity's accent otherwise.
     */
    private static void applyAccentTheme(Activity activity) {
        int accent = getAccent(activity);
        if (accent == ACCENT_DEFAULT) {
            return;
        }
        int[] themes = palette(activity) == DAY ? DAY_ACCENT_THEMES
            : palette(activity) == NAVY ? NAVY_ACCENT_THEMES : STANDARD_ACCENT_THEMES;
        if (themes[accent] != 0) {
            activity.getTheme().applyStyle(themes[accent], true);
        }
    }

    /**
     * The picker's swatch dots. Read from the resources rather than copied as hexes, so a change
     * to a palette's accent cannot leave the dots showing a colour nothing else uses. The default
     * accent has no resource of its own: it is the palette's primary.
     */
    static int swatchColor(Context context, int accent) {
        accent = clampAccent(accent);
        int palette = palette(context);
        String prefix = palette == DAY ? "gzh_day_" : palette == NAVY ? "gzh_navy_" : "gzh_";
        if (accent == ACCENT_DEFAULT) {
            return context.getResources().getColor(
                context.getResources().getIdentifier(prefix + "primary", "color",
                    context.getPackageName()), null);
        }
        return context.getResources().getColor(
            context.getResources().getIdentifier(
                (palette == DAY ? "gzh_day_" : "gzh_") + "accent_"
                    + ACCENT_NAMES[accent] + "_primary", "color", context.getPackageName()), null);
    }

    static void apply(Activity activity) {
        int palette = palette(activity);
        activity.setTheme(palette == NAVY ? R.style.Theme_GeneralsZHSettingsNavy
            : palette == DAY ? R.style.Theme_GeneralsZHSettingsLight : R.style.Theme_GeneralsZHSettings);
        applyAccentTheme(activity);
    }

    static void applyBars(Activity activity) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());
        boolean light = palette(activity) == DAY;
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }
}
