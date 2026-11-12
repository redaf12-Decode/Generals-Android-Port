/*
**      Command & Conquer Generals Zero Hour(tm)
**      Copyright 2025 Electronic Arts Inc.
**
**      This program is free software: you can redistribute it and/or modify
**      it under the terms of the GNU General Public License as published by
**      the Free Software Foundation, either version 3 of the License, or
**      (at your option) any later version.
**
**      This program is distributed in the hope that it will be useful,
**      but WITHOUT ANY WARRANTY; without even the implied warranty of
**      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**      GNU General Public License for more details.
**
**      You should have received a copy of the GNU General Public License
**      along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android touch editor 05/11/2026 The floating icon that opens the editor in
// the match: whether it is drawn, what it looks like, and the saved default layout.
//
// Its own preferences file, deliberately: the button layout (TouchControlConfig) is what the
// player plays with, and this is how the editor that edits it is presented. A reset of one must
// not touch the other.
//
// Per game. The launcher shows one game at a time, so each game keeps its own icon placement and
// its own default layout under its own key prefix -- an icon placed for Zero Hour cannot appear
// over Generals, and saving a default for one does not overwrite the other's.
//
// Off by default, and off means off: the activity does not build the overlay at all when it is
// false, so an install that never opened the card pays nothing for it.

package com.ports.generalsx;

import android.content.Context;
import android.content.SharedPreferences;

final class ToolboxConfig {

    static final String PREFS_NAME = "generalszh_toolbox";

    /** Bumped when the key layout changes; migrate() moves an older file forward. */
    static final int SCHEMA = 3;
    private static final String PREF_SCHEMA = "schema";

    static final int STYLE_ROUNDED = 0;
    static final int STYLE_CIRCLE = 1;
    static final int STYLE_DIAMOND = 2;
    static final int STYLE_COUNT = 3;

    static final int SIZE_SMALL = 0;
    static final int SIZE_MEDIUM = 1;
    static final int SIZE_LARGE = 2;
    static final int SIZE_COUNT = 3;

    private static final float[] SIZE_DP = {40f, 52f, 68f};

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STYLE = "icon_style";
    private static final String KEY_SIZE = "icon_size";
    private static final String KEY_OPACITY = "icon_opacity";
    private static final String KEY_X = "icon_x";
    private static final String KEY_Y = "icon_y";
    private static final String KEY_DEFAULT_LAYOUT = "default_layout";
    private static final String KEY_PANEL_OPEN = "panel_open";

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Whether the floating toolbox exists for
     * this game -- its icon and its panel. This single flag is the launcher's "Floating toolbox"
     * switch AND the state the in-game "Hide toolbox" row writes, so the two controls always agree:
     * hiding the icon in the match turns the launcher switch off, and turning the switch on brings
     * the icon back. It is deliberately separate from the game buttons' own visibility
     * (TouchControlConfig.enabled): hiding the toolbox never hides a button.
     *
     * Off by default: an install that never opened the card pays nothing, and the activity does not
     * build the overlay at all while it is false.
     */
    boolean enabled;
    int style = STYLE_ROUNDED;
    int size = SIZE_MEDIUM;
    float opacity = 0.75f;
    /** Screen fraction of the icon's centre. */
    float x = 0.92f;
    float y = 0.30f;
    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Whether the tool panel is showing. This
     * is the panel's own visibility, stored per game and separate from the buttons' own visibility
     * (TouchControlConfig.enabled) and from the icon's ({@link #enabled}).
     *
     * The default is false, which is what the overlay did before this was stored: the icon is on
     * screen at launch and the panel opens when it is tapped. An older install has no stored value
     * and therefore keeps exactly that behaviour.
     */
    boolean panelOpen = false;
    /** The layout the panel's "Load saved layout" row applies, as TouchControlConfig JSON. */
    String defaultLayoutJson;

    static float sizeDp(int size) {
        return SIZE_DP[size >= 0 && size < SIZE_COUNT ? size : SIZE_MEDIUM];
    }

    static String sizeName(Context context, int size) {
        switch (size) {
            case SIZE_SMALL: return context.getString(R.string.toolbox_size_small);
            case SIZE_LARGE: return context.getString(R.string.toolbox_size_large);
            default: return context.getString(R.string.toolbox_size_medium);
        }
    }

    static String styleName(Context context, int style) {
        switch (style) {
            case STYLE_CIRCLE: return context.getString(R.string.toolbox_style_circle);
            case STYLE_DIAMOND: return context.getString(R.string.toolbox_style_diamond);
            default: return context.getString(R.string.toolbox_style_rounded);
        }
    }

    private static String prefixed(String game, String key) {
        return (game == null || game.isEmpty() ? "zh" : game) + "." + key;
    }

    static ToolboxConfig load(Context context, String game) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ToolboxConfig config = new ToolboxConfig();
        config.enabled = prefs.getBoolean(prefixed(game, KEY_ENABLED), false);
        int style = prefs.getInt(prefixed(game, KEY_STYLE), STYLE_ROUNDED);
        config.style = (style >= 0 && style < STYLE_COUNT) ? style : STYLE_ROUNDED;
        int size = prefs.getInt(prefixed(game, KEY_SIZE), SIZE_MEDIUM);
        config.size = (size >= 0 && size < SIZE_COUNT) ? size : SIZE_MEDIUM;
        config.opacity = TouchControlConfig.clamp(
            prefs.getFloat(prefixed(game, KEY_OPACITY), 0.75f), 0.2f, 1f);
        config.x = TouchControlConfig.clamp(
            prefs.getFloat(prefixed(game, KEY_X), 0.92f), 0.02f, 0.98f);
        config.y = TouchControlConfig.clamp(
            prefs.getFloat(prefixed(game, KEY_Y), 0.30f), 0.04f, 0.96f);
        config.panelOpen = prefs.getBoolean(prefixed(game, KEY_PANEL_OPEN), false);
        config.defaultLayoutJson = prefs.getString(prefixed(game, KEY_DEFAULT_LAYOUT), null);
        return config;
    }

    void save(Context context, String game) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(prefixed(game, PREF_SCHEMA), SCHEMA)
            .putBoolean(prefixed(game, KEY_ENABLED), enabled)
            .putInt(prefixed(game, KEY_STYLE), style)
            .putInt(prefixed(game, KEY_SIZE), size)
            .putFloat(prefixed(game, KEY_OPACITY), TouchControlConfig.clamp(opacity, 0.2f, 1f))
            .putFloat(prefixed(game, KEY_X), TouchControlConfig.clamp(x, 0.02f, 0.98f))
            .putFloat(prefixed(game, KEY_Y), TouchControlConfig.clamp(y, 0.04f, 0.96f))
            .putBoolean(prefixed(game, KEY_PANEL_OPEN), panelOpen)
            .putString(prefixed(game, KEY_DEFAULT_LAYOUT), defaultLayoutJson)
            .apply();
    }

    static void reset(Context context, String game) {
        String prefix = (game == null || game.isEmpty() ? "zh" : game) + ".";
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    boolean hasDefaultLayout() {
        return defaultLayoutJson != null && !defaultLayoutJson.isEmpty();
    }

    /**
     * The stored default layout, or null when there is none or it no longer reads as a layout. A
     * snapshot that does not parse is never half-applied -- the caller reports which of the two
     * it is.
     */
    TouchControlConfig loadDefaultLayout() {
        if (!hasDefaultLayout()) {
            return null;
        }
        try {
            return TouchControlConfig.fromJsonString(defaultLayoutJson);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Moves a v1 file (before this feature
     * split per game) forward: unprefixed keys are copied to the selected game once, then
     * removed, so a choice already made is not silently dropped and the other game does not
     * inherit the first one's placement.
     */
    static void migrate(Context context, String game) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_ENABLED) && !prefs.contains(KEY_X)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        String prefix = (game == null || game.isEmpty() ? "zh" : game) + ".";
        if (prefs.contains(KEY_ENABLED)) {
            editor.putBoolean(prefix + KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, false));
        }
        if (prefs.contains(KEY_X)) {
            editor.putFloat(prefix + KEY_X, prefs.getFloat(KEY_X, 0.92f));
        }
        if (prefs.contains(KEY_Y)) {
            editor.putFloat(prefix + KEY_Y, prefs.getFloat(KEY_Y, 0.30f));
        }
        if (prefs.contains(KEY_STYLE)) {
            editor.putInt(prefix + KEY_STYLE, prefs.getInt(KEY_STYLE, STYLE_ROUNDED));
        }
        editor.remove(KEY_ENABLED).remove(KEY_X).remove(KEY_Y).remove(KEY_STYLE)
            .putInt(prefixed(game, PREF_SCHEMA), SCHEMA);
        editor.apply();
    }
}
