/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android floating toolbox 17/10/2026 Persistent model behind the
// Interface page's "Floating toolbox" card and the in-game overlay ToolboxOverlayView.
//
// What this is: whether the in-game toolbox icon is drawn at all, what that icon looks
// like, and which touch-control layout the toolbox edits and restores. It is the same
// split TouchControlConfig already uses -- one file for the layout itself, and this one
// for how the toolbox that edits it is presented -- so nothing here is a second source
// of truth for the buttons: the buttons stay in TouchControlConfig and the toolbox
// reads and writes that same model.
//
// Kept in its own preferences file, not in the launcher's: like TouchControlConfig this
// is read on the game's launch path (GeneralsZHActivity builds the overlay from it
// before SDL starts), and it must survive a launcher settings reset untouched, and vice
// versa. Nothing here changes any renderer or graphics setting.
//
// Per game: the launcher shows one game at a time (SetupActivity.getSelectedGame) and
// each game keeps its own icon placement, style and default layout under its own key
// prefix, so switching games never carries one game's position or layout into the other.
//
// Disabled means disabled: enabled defaults to false, and GeneralsZHActivity does not
// even build the overlay when it is off (ToolboxOverlayView.attach returns immediately),
// so nothing of the toolbox exists over the battlefield.

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

final class ToolboxConfig {

    /** Its own file: the launcher's settings and the touch layout's are separate concerns. */
    static final String PREFS_NAME = "generalszh_toolbox";

    /** Bumped when the key layout below changes; see migrate(). */
    static final int SCHEMA = 1;
    private static final String PREF_SCHEMA = "schema";

    // The icon styles the overlay actually draws (ToolboxOverlayView.drawIcon). A style
    // is not decoration on its own: each is a distinct hit shape, so the list stays
    // exactly as long as the list of shapes the overlay implements.
    static final int STYLE_ROUNDED = 0;
    static final int STYLE_CIRCLE = 1;
    static final int STYLE_DIAMOND = 2;
    static final int STYLE_COUNT = 3;

    // The three sizes the launcher offers, as the icon's side in dp. Kept here (not in
    // the overlay) so the segmented control in the launcher and the drawn icon cannot
    // disagree about what "Large" means.
    static final float SIZE_SMALL_DP = 40f;
    static final float SIZE_MEDIUM_DP = 48f;
    static final float SIZE_LARGE_DP = 58f;
    static final float MIN_SIZE_DP = 36f;
    static final float MAX_SIZE_DP = 76f;

    static final float DEFAULT_OPACITY = 0.85f;
    static final float MIN_OPACITY = 0.35f;
    static final float MAX_OPACITY = 1.0f;

    /**
     * Where the icon starts: lower right, above the game's own command panel but clear
     * of the minimap strip. Fractions of the screen, like TouchControlConfig's buttons,
     * so the same setting means the same place in landscape on any device.
     */
    static final float DEFAULT_ICON_X = 0.94f;
    static final float DEFAULT_ICON_Y = 0.30f;

    /** Off by default: an install that never opens this card gets no overlay at all. */
    boolean enabled = false;
    int iconStyle = STYLE_ROUNDED;
    float iconSizeDp = SIZE_MEDIUM_DP;
    float iconOpacity = DEFAULT_OPACITY;
    /** Icon centre as a fraction of the surface, written back by the overlay after a drag. */
    float iconX = DEFAULT_ICON_X;
    float iconY = DEFAULT_ICON_Y;
    /**
     * The touch-control layout the toolbox restores by default, as the same JSON
     * TouchControlConfig exports, or empty for the built-in starting layout. Empty is the
     * safe default: a fresh install restores TouchControlConfig.defaults().
     */
    String defaultLayoutJson = "";

    /**
     * A fresh instance is the shipped defaults -- the same state a file that does not exist
     * yet loads as. Package-private rather than private so the tests can build one, set it,
     * save it and read it back the way the launcher does; nothing outside this package can.
     */
    ToolboxConfig() {}

    /** The settings for the game currently selected in the launcher. */
    static ToolboxConfig load(Context context) {
        return load(context, SetupActivity.getSelectedGame(context));
    }

    static ToolboxConfig load(Context context, String game) {
        SharedPreferences prefs = prefs(context);
        migrate(prefs, game);

        ToolboxConfig config = new ToolboxConfig();
        String prefix = prefix(game);
        try {
            config.enabled = prefs.getBoolean(prefix + "enabled", false);
            config.iconStyle = clampStyle(prefs.getInt(prefix + "icon_style", STYLE_ROUNDED));
            config.iconSizeDp = clampSize(prefs.getFloat(prefix + "icon_size_dp", SIZE_MEDIUM_DP));
            config.iconOpacity = clampOpacity(
                prefs.getFloat(prefix + "icon_opacity", DEFAULT_OPACITY));
            config.iconX = clampX(prefs.getFloat(prefix + "icon_x", DEFAULT_ICON_X));
            config.iconY = clampY(prefs.getFloat(prefix + "icon_y", DEFAULT_ICON_Y));
            config.defaultLayoutJson = prefs.getString(prefix + "default_layout", "");
        } catch (ClassCastException e) {
            // A value written under a key by something that disagreed about its type (an
            // older build, a hand-edited file) must not take the launch path down with it:
            // the whole point of every default above is that an unreadable file looks like
            // a fresh one.
            android.util.Log.w("GXToolbox", "toolbox settings unreadable; using defaults", e);
            return new ToolboxConfig();
        }
        if (config.defaultLayoutJson == null) {
            config.defaultLayoutJson = "";
        }
        return config;
    }

    void save(Context context, String game) {
        SharedPreferences.Editor editor = prefs(context).edit();
        String prefix = prefix(game);
        editor.putBoolean(prefix + "enabled", enabled);
        editor.putInt(prefix + "icon_style", clampStyle(iconStyle));
        editor.putFloat(prefix + "icon_size_dp", clampSize(iconSizeDp));
        editor.putFloat(prefix + "icon_opacity", clampOpacity(iconOpacity));
        editor.putFloat(prefix + "icon_x", clampX(iconX));
        editor.putFloat(prefix + "icon_y", clampY(iconY));
        editor.putString(prefix + "default_layout", defaultLayoutJson == null ? "" : defaultLayoutJson);
        editor.putInt(PREF_SCHEMA, SCHEMA);
        editor.apply();
    }

    void save(Context context) {
        save(context, SetupActivity.getSelectedGame(context));
    }

    /**
     * The overlay's own write, after a drag: position only, so a drag can never carry a
     * half-finished settings edit into the file with it.
     */
    static void savePosition(Context context, String game, float x, float y) {
        prefs(context).edit()
            .putFloat(prefix(game) + "icon_x", clampX(x))
            .putFloat(prefix(game) + "icon_y", clampY(y))
            .putInt(PREF_SCHEMA, SCHEMA)
            .apply();
    }

    /** Back to the shipped defaults for one game. The other game's settings are untouched. */
    static void reset(Context context, String game) {
        SharedPreferences.Editor editor = prefs(context).edit();
        String prefix = prefix(game);
        for (String key : new String[] { "enabled", "icon_style", "icon_size_dp", "icon_opacity",
                "icon_x", "icon_y", "default_layout" }) {
            editor.remove(prefix + key);
        }
        editor.putInt(PREF_SCHEMA, SCHEMA);
        editor.apply();
    }

    String iconStyleName(Context context) {
        switch (clampStyle(iconStyle)) {
            case STYLE_CIRCLE: return context.getString(R.string.toolbox_style_circle);
            case STYLE_DIAMOND: return context.getString(R.string.toolbox_style_diamond);
            default: return context.getString(R.string.toolbox_style_rounded);
        }
    }

    String iconSizeName(Context context) {
        switch (sizeIndex(iconSizeDp)) {
            case 0: return context.getString(R.string.toolbox_size_small);
            case 2: return context.getString(R.string.toolbox_size_large);
            default: return context.getString(R.string.toolbox_size_medium);
        }
    }

    static float sizeForIndex(int index) {
        switch (index) {
            case 0: return SIZE_SMALL_DP;
            case 2: return SIZE_LARGE_DP;
            default: return SIZE_MEDIUM_DP;
        }
    }

    /** 0 = small, 1 = medium, 2 = large; the nearest preset to whatever is stored. */
    static int sizeIndex(float sizeDp) {
        float size = clampSize(sizeDp);
        int best = 1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            float distance = Math.abs(size - sizeForIndex(i));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * GeneralsX @feature Android floating toolbox 17/10/2026 Earlier builds of this card
     * (before it was split per game) wrote these keys unprefixed. Copy them into the
     * selected game's profile once and remove them, so an install that already chose an
     * icon style does not silently go back to the defaults -- and so a second game added
     * later does not inherit the first one's position by accident.
     */
    private static void migrate(SharedPreferences prefs, String game) {
        if (prefs.getInt(PREF_SCHEMA, 0) >= SCHEMA) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.contains("enabled")) {
            String prefix = prefix(game);
            if (!prefs.contains(prefix + "enabled")) {
                editor.putBoolean(prefix + "enabled", prefs.getBoolean("enabled", false));
                if (prefs.contains("icon_style")) {
                    editor.putInt(prefix + "icon_style",
                        clampStyle(prefs.getInt("icon_style", STYLE_ROUNDED)));
                }
                if (prefs.contains("icon_size_dp")) {
                    editor.putFloat(prefix + "icon_size_dp",
                        clampSize(prefs.getFloat("icon_size_dp", SIZE_MEDIUM_DP)));
                }
                if (prefs.contains("icon_opacity")) {
                    editor.putFloat(prefix + "icon_opacity",
                        clampOpacity(prefs.getFloat("icon_opacity", DEFAULT_OPACITY)));
                }
                if (prefs.contains("icon_x")) {
                    editor.putFloat(prefix + "icon_x", clampX(prefs.getFloat("icon_x", DEFAULT_ICON_X)));
                }
                if (prefs.contains("icon_y")) {
                    editor.putFloat(prefix + "icon_y", clampY(prefs.getFloat("icon_y", DEFAULT_ICON_Y)));
                }
            }
            for (String key : new String[] { "enabled", "icon_style", "icon_size_dp",
                    "icon_opacity", "icon_x", "icon_y" }) {
                editor.remove(key);
            }
        }
        editor.putInt(PREF_SCHEMA, SCHEMA);
        editor.apply();
    }

    /** "zh." or "generals." -- SetupActivity's own game ids, so the two cannot drift. */
    private static String prefix(String game) {
        return (SetupActivity.GAME_GENERALS.equals(game)
            ? SetupActivity.GAME_GENERALS : SetupActivity.GAME_ZERO_HOUR) + ".";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static int clampStyle(int style) {
        return (style < 0 || style >= STYLE_COUNT) ? STYLE_ROUNDED : style;
    }

    static float clampSize(float sizeDp) {
        return clamp(sizeDp, MIN_SIZE_DP, MAX_SIZE_DP);
    }

    static float clampOpacity(float opacity) {
        return clamp(opacity, MIN_OPACITY, MAX_OPACITY);
    }

    /** Kept inside the screen with the same margins TouchControlConfig clamps buttons to. */
    static float clampX(float x) {
        return clamp(x, 0.05f, 0.95f);
    }

    static float clampY(float y) {
        return clamp(y, 0.06f, 0.94f);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
