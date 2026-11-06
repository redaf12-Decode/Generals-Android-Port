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

// GeneralsX @feature Android touch editor 05/11/2026 The saved layout: the buttons drawn over the
// battlefield, where they sit, how they look and which key each one presses.
//
// This is the one and only store for the buttons. The launcher's editor page, the in-game editor
// and the runtime overlay all read and write this class, so there is no second source of truth
// and no second storage system. It keeps its own preferences file (`generalszh_touch_controls`)
// because the layout is read on the game's launch path and must survive a launcher settings reset
// untouched -- and vice versa.
//
// Storage is versioned. Version 1 is the layout the reference branch wrote; version 3 is this
// one. Every field added since v1 is additive with a default that reproduces the v1 behaviour, so
// a layout a player already has loads unchanged: that is the "preserve existing user layouts"
// requirement, and it is why `enabled`, `activationMode`, `polygonType`, `cornerDp`, `borderWidthDp`,
// `opacity` and `zOrder` all read with opt*() and a fallback rather than requiring a key.
//
// Nothing here invents a game command. A button presses a keyboard key through SDL, so every
// button is the game's own shortcut, resolved by the engine's normal keyboard path.

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TouchControlConfig {

    static final String PREFS_NAME = "generalszh_touch_controls";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_BUTTON_SCALE = "button_scale";
    private static final String PREF_BUTTON_OPACITY = "button_opacity";
    private static final String PREF_BUTTONS = "buttons";
    private static final String PREF_SCHEMA = "schema";

    /** The document this writes and accepts. The reference wrote the same name at version 1/2. */
    static final String FORMAT = "GeneralsZHTouchControls";
    /** Bumped when the button document gains fields; see migrate(). v5 = an unassigned key. */
    static final int SCHEMA = 5;
    private static final int SCHEMA_MIN = 1;

    /** The number of buttons one layout may carry. A v3 document holds at most 20, so raising
     *  this to 40 is what makes an existing layout load with every button kept, never trimmed. */
    static final int MAX_BUTTONS = 40;

    // Bounds. Every one of them is applied where a value is produced -- parsed, dragged or
    // resized -- so no code path can store a button off the screen or a size that cannot be hit.
    static final float MIN_SIZE_DP = 28f;
    static final float MAX_SIZE_DP = 220f;
    // Placement is the WHOLE viewport, as a fraction of the screen: 0 is the top/left edge and 1
    // the bottom/right. There is no safe-area inset and no reserved strip -- a button may sit in a
    // corner, on the game's own HUD, on another button, or half past the edge. The bounds exist
    // only to reject a non-finite or out-of-range coordinate, which is the one thing that would
    // crash the renderer; they are deliberately not a layout policy.
    static final float MIN_X = 0f;
    static final float MAX_X = 1f;
    static final float MIN_Y = 0f;
    static final float MAX_Y = 1f;
    static final float MIN_SCALE = 0.65f;
    static final float MAX_SCALE = 1.75f;
    static final float MIN_OPACITY = 0.15f;
    static final float MAX_OPACITY = 1f;
    static final float MIN_BORDER_DP = 0f;
    static final float MAX_BORDER_DP = 8f;

    /** How a button turns a press into key events. */
    static final int MODE_TAP = 0;
    static final int MODE_HOLD = 1;
    static final int MODE_TOGGLE = 2;
    static final int MODE_COUNT = 3;

    private static final String[] MODE_NAMES = {"Tap", "Hold", "Toggle"};

    static String modeName(int mode) {
        return MODE_NAMES[isValidMode(mode) ? mode : MODE_TAP];
    }

    static boolean isValidMode(int mode) {
        return mode >= MODE_TAP && mode < MODE_COUNT;
    }

    /** One overlay button. Every property is independent of every other button's. */
    static final class ButtonSpec {
        String label;
        /**
         * GeneralsX @feature Android touch editor 06/11/2026 The key this button presses, or
         * KeyEvent.KEYCODE_UNKNOWN when the player has cleared it. An unassigned button keeps its
         * position, size, shape and colours -- it is still drawn, still selectable and still
         * draggable in both editors -- but sends nothing: TouchKeyPresser.keysFor() skips it, so
         * no key goes down and no default is substituted. It is a distinct state from the button
         * being deleted (deleteSelected) and from the whole profile being reset (restoreDefaults).
         */
        int keyCode;
        int modifiers;
        boolean enabled = true;
        /** Screen fraction of the button's centre, so it survives rotation and a different size. */
        float x;
        float y;
        float widthDp;
        float heightDp;
        int shape;
        int polygonType;
        float cornerDp;
        float borderWidthDp;
        /** Per-button alpha, 0..1. The global buttonOpacity multiplies this. */
        float opacity;
        int fillColor;
        int borderColor;
        int textColor;
        int activationMode;
        /** Higher draws later and is hit-tested first. */
        int zOrder;

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y) {
            this(label, keyCode, modifiers, x, y, 52f, 52f, TouchButtonShape.ROUNDED,
                Color.rgb(22, 33, 44), Color.rgb(220, 181, 86), Color.WHITE);
        }

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y,
                   float widthDp, float heightDp, int shape, int fillColor,
                   int borderColor, int textColor) {
            this.label = label;
            this.keyCode = keyCode;
            this.modifiers = modifiers & TouchKeyCatalog.MOD_MASK;
            this.x = clamp(x, MIN_X, MAX_X);
            this.y = clamp(y, MIN_Y, MAX_Y);
            this.shape = TouchButtonShape.isValidShape(shape) ? shape : TouchButtonShape.ROUNDED;
            float width = clamp(widthDp, MIN_SIZE_DP, MAX_SIZE_DP);
            float height = clamp(heightDp, MIN_SIZE_DP, MAX_SIZE_DP);
            if (TouchButtonShape.isFixedAspect(this.shape)) {
                float side = Math.min(width, height);
                width = side;
                height = side;
            }
            this.widthDp = width;
            this.heightDp = height;
            this.polygonType = TouchButtonShape.POLY_HEXAGON;
            this.cornerDp = TouchButtonShape.DEFAULT_CORNER_DP;
            this.borderWidthDp = 2f;
            this.opacity = 1f;
            this.activationMode = MODE_TAP;
            this.fillColor = fillColor;
            this.borderColor = borderColor;
            this.textColor = textColor;
        }

        ButtonSpec copy() {
            ButtonSpec copy = new ButtonSpec(label, keyCode, modifiers, x, y, widthDp, heightDp,
                shape, fillColor, borderColor, textColor);
            copy.enabled = enabled;
            copy.polygonType = polygonType;
            copy.cornerDp = cornerDp;
            copy.borderWidthDp = borderWidthDp;
            copy.opacity = opacity;
            copy.activationMode = activationMode;
            copy.zOrder = zOrder;
            return copy;
        }

        /**
         * Changes the shape while keeping everything the player configured. A square or circle
         * needs one side, so the smaller of the two dimensions becomes both; that is the only
         * dimension change a shape switch is allowed to make.
         */
        void setShape(int newShape) {
            if (!TouchButtonShape.isValidShape(newShape)) {
                return;
            }
            shape = newShape;
            if (TouchButtonShape.isFixedAspect(newShape)) {
                float side = Math.min(widthDp, heightDp);
                widthDp = side;
                heightDp = side;
            }
        }

        void setSize(float newWidthDp, float newHeightDp) {
            float width = clamp(newWidthDp, MIN_SIZE_DP, MAX_SIZE_DP);
            float height = clamp(newHeightDp, MIN_SIZE_DP, MAX_SIZE_DP);
            if (TouchButtonShape.isFixedAspect(shape)) {
                float side = Math.min(width, height);
                width = side;
                height = side;
            }
            widthDp = width;
            heightDp = height;
        }

        /**
         * GeneralsX @feature Android touch editor 06/11/2026 True when the button presses a key.
         * The single test every caller uses, so "unassigned" is never spelled as a raw keycode
         * comparison in more than one place.
         */
        boolean isAssigned() {
            return keyCode != KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    boolean enabled = true;
    float buttonScale = 1.0f;
    float buttonOpacity = 0.78f;
    final ArrayList<ButtonSpec> buttons = new ArrayList<>();

    /** A copy, for an editor to work on: only save() writes. */
    static TouchControlConfig copyOf(TouchControlConfig source) {
        TouchControlConfig copy = new TouchControlConfig();
        copy.enabled = source.enabled;
        copy.buttonScale = source.buttonScale;
        copy.buttonOpacity = source.buttonOpacity;
        copy.buttons.addAll(copyButtons(source.buttons));
        return copy;
    }

    static List<ButtonSpec> copyButtons(List<ButtonSpec> source) {
        ArrayList<ButtonSpec> copy = new ArrayList<>(source.size());
        for (ButtonSpec button : source) {
            copy.add(button.copy());
        }
        return copy;
    }

    /** Buttons in draw order: zOrder first, then the order they were added. Stable sort. */
    List<ButtonSpec> inDrawOrder() {
        ArrayList<ButtonSpec> ordered = new ArrayList<>(buttons);
        Collections.sort(ordered, (a, b) -> Integer.compare(a.zOrder, b.zOrder));
        return ordered;
    }

    /**
     * The starting layout: the ten shortcuts a Generals player reaches for constantly, in one row
     * in the lower third -- above the game's own command bar. These are the reference's defaults,
     * so a fresh install looks like the build this feature came from.
     */
    static TouchControlConfig defaults() {
        TouchControlConfig config = new TouchControlConfig();
        String[] labels = {"E", "Q", "W", "R", "A", "S", "D", "F", "SPACE", "ESC"};
        int[] keys = {
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE
        };
        for (int i = 0; i < labels.length; ++i) {
            ButtonSpec spec = new ButtonSpec(labels[i], keys[i], 0, 0.065f + i * 0.096f, 0.675f);
            spec.zOrder = i;
            config.buttons.add(spec);
        }
        return config;
    }

    // ------------------------------------------------------------------ persistence

    static TouchControlConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(PREF_BUTTONS)) {
            return defaults();
        }
        TouchControlConfig config = new TouchControlConfig();
        config.enabled = prefs.getBoolean(PREF_ENABLED, true);
        config.buttonScale = clamp(prefs.getFloat(PREF_BUTTON_SCALE, 1.0f), MIN_SCALE, MAX_SCALE);
        config.buttonOpacity = clamp(prefs.getFloat(PREF_BUTTON_OPACITY, 0.78f), MIN_OPACITY,
            MAX_OPACITY);
        try {
            JSONArray array = new JSONArray(prefs.getString(PREF_BUTTONS, "[]"));
            readButtons(config, array);
        } catch (Exception ignored) {
            // A preferences file that cannot be parsed is not worth a crash on the launch path.
            return defaults();
        }
        if (config.buttons.isEmpty()) {
            return defaults();
        }
        return config;
    }

    void save(Context context) {
        SharedPreferences.Editor editor =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt(PREF_SCHEMA, SCHEMA);
        editor.putBoolean(PREF_ENABLED, enabled);
        editor.putFloat(PREF_BUTTON_SCALE, clamp(buttonScale, MIN_SCALE, MAX_SCALE));
        editor.putFloat(PREF_BUTTON_OPACITY, clamp(buttonOpacity, MIN_OPACITY, MAX_OPACITY));
        editor.putString(PREF_BUTTONS, buttonsToJson(buttons).toString());
        editor.apply();
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ------------------------------------------------------------------ JSON

    private static JSONArray buttonsToJson(List<ButtonSpec> source) {
        JSONArray array = new JSONArray();
        for (ButtonSpec button : source) {
            JSONObject item = new JSONObject();
            try {
                item.put("label", button.label);
                item.put("key", button.keyCode);
                item.put("keyName", TouchKeyCatalog.displayName(button.keyCode));
                item.put("modifiers", button.modifiers & TouchKeyCatalog.MOD_MASK);
                item.put("x", button.x);
                item.put("y", button.y);
                item.put("widthDp", button.widthDp);
                item.put("heightDp", button.heightDp);
                item.put("shape", button.shape);
                item.put("polygonType", button.polygonType);
                item.put("cornerDp", button.cornerDp);
                item.put("borderWidthDp", button.borderWidthDp);
                item.put("opacity", button.opacity);
                item.put("activationMode", button.activationMode);
                item.put("zOrder", button.zOrder);
                item.put("enabled", button.enabled);
                item.put("fillColor", colorToString(button.fillColor));
                item.put("borderColor", colorToString(button.borderColor));
                item.put("textColor", colorToString(button.textColor));
            } catch (Exception ignored) {
                // org.json only throws for non-finite numbers, and every value is clamped.
            }
            array.put(item);
        }
        return array;
    }

    /** Reads a buttons array into the config, clamping and migrating every field. */
    private static void readButtons(TouchControlConfig config, JSONArray array) {
        for (int i = 0; i < array.length() && config.buttons.size() < MAX_BUTTONS; ++i) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            ButtonSpec spec = readButton(item);
            if (spec != null) {
                config.buttons.add(spec);
            }
        }
    }

    /**
     * One button, with every field validated. Returns null when the entry carries no usable key:
     * an entry that cannot press anything is not a button, and keeping it would only take a slot.
     *
     * GeneralsX @feature Android touch editor 06/11/2026 A key the catalog refuses (Home, Power,
     * the volume rocker, ...) is still dropped, because such a key is never a legitimate mapping.
     * KEYCODE_UNKNOWN is different: it is the documented representation of "no key assigned", which
     * Clear Key writes, so it is kept -- that is what makes a cleared button survive a save and a
     * reload. Everything else about the entry is read exactly as before.
     */
    static ButtonSpec readButton(JSONObject item) {
        int rawKey = item.optInt("key", KeyEvent.KEYCODE_UNKNOWN);
        if (rawKey != KeyEvent.KEYCODE_UNKNOWN && !TouchKeyCatalog.isSelectable(rawKey)) {
            return null;
        }
        String label = item.optString("label", TouchKeyCatalog.displayName(rawKey));
        if (label.isEmpty()) {
            label = TouchKeyCatalog.displayName(rawKey);
        }
        int modifiers = item.optInt("modifiers", 0) & TouchKeyCatalog.MOD_MASK;
        float x = clamp((float) item.optDouble("x", 0.5), MIN_X, MAX_X);
        float y = clamp((float) item.optDouble("y", 0.5), MIN_Y, MAX_Y);
        float widthDp = clamp((float) item.optDouble("widthDp", 52.0), MIN_SIZE_DP, MAX_SIZE_DP);
        float heightDp = clamp((float) item.optDouble("heightDp", 52.0), MIN_SIZE_DP, MAX_SIZE_DP);
        int shape = item.optInt("shape", TouchButtonShape.ROUNDED);
        if (!TouchButtonShape.isValidShape(shape)) {
            shape = TouchButtonShape.ROUNDED;
        }
        int fillColor = parseColor(item.optString("fillColor", ""), Color.rgb(22, 33, 44));
        int borderColor = parseColor(item.optString("borderColor", ""), Color.rgb(220, 181, 86));
        int textColor = parseColor(item.optString("textColor", ""), Color.WHITE);

        ButtonSpec spec = new ButtonSpec(label, rawKey, modifiers, x, y, widthDp, heightDp, shape,
            fillColor, borderColor, textColor);
        // Absent means the v1 behaviour, one field at a time. This is the migration.
        spec.enabled = item.optBoolean("enabled", true);
        int polygonType = item.optInt("polygonType", TouchButtonShape.POLY_HEXAGON);
        spec.polygonType = TouchButtonShape.isValidPolygon(polygonType) ? polygonType
            : TouchButtonShape.POLY_HEXAGON;
        spec.cornerDp = clamp((float) item.optDouble("cornerDp", TouchButtonShape.DEFAULT_CORNER_DP),
            TouchButtonShape.MIN_CORNER_DP, TouchButtonShape.MAX_CORNER_DP);
        spec.borderWidthDp = clamp((float) item.optDouble("borderWidthDp", 2.0), MIN_BORDER_DP,
            MAX_BORDER_DP);
        spec.opacity = clamp((float) item.optDouble("opacity", 1.0), 0f, 1f);
        int mode = item.optInt("activationMode", MODE_TAP);
        spec.activationMode = isValidMode(mode) ? mode : MODE_TAP;
        spec.zOrder = item.optInt("zOrder", 0);
        return spec;
    }

    /** The portable document. Same format the editor exports, so a snapshot and a file match. */
    String toJsonString() throws Exception {
        return buildJson().toString(2);
    }

    private JSONObject buildJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", SCHEMA);
        root.put("enabled", enabled);
        root.put("buttonScale", buttonScale);
        root.put("buttonOpacity", buttonOpacity);
        root.put("buttons", buttonsToJson(buttons));
        return root;
    }

    void exportTo(Context context, Uri uri) throws Exception {
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new Exception("Unable to open export file");
            }
            output.write(toJsonString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    static TouchControlConfig importFrom(Context context, Uri uri) throws Exception {
        StringBuilder json = new StringBuilder();
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new Exception("Unable to open import file");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                json.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (json.length() > 1024 * 1024) {
                    throw new Exception("Import file is too large");
                }
            }
        }
        return fromJsonString(json.toString());
    }

    /**
     * Parses a layout document, rejecting one that carries no usable button rather than
     * half-applying it. Version 1 and 2 documents (the reference's) are accepted: their missing
     * fields take the v1 defaults in readButton().
     */
    static TouchControlConfig fromJsonString(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String format = root.optString("format", FORMAT);
        if (!FORMAT.equals(format)) {
            throw new Exception("Not a " + FORMAT + " document");
        }
        int version = root.optInt("version", SCHEMA_MIN);
        if (version > SCHEMA) {
            throw new Exception("Layout was written by a newer build (version " + version + ")");
        }
        JSONArray array = root.optJSONArray("buttons");
        if (array == null) {
            throw new Exception("No buttons mapping found");
        }
        if (array.length() > MAX_BUTTONS) {
            throw new Exception("The profile contains more than " + MAX_BUTTONS + " buttons");
        }
        TouchControlConfig config = new TouchControlConfig();
        config.enabled = root.optBoolean("enabled", true);
        config.buttonScale = clamp((float) root.optDouble("buttonScale", 1.0), MIN_SCALE, MAX_SCALE);
        config.buttonOpacity = clamp((float) root.optDouble("buttonOpacity", 0.78), MIN_OPACITY,
            MAX_OPACITY);
        readButtons(config, array);
        if (config.buttons.isEmpty()) {
            throw new Exception("The profile contains no valid buttons");
        }
        return config;
    }

    // ------------------------------------------------------------------ helpers

    static int parseColor(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Color.parseColor(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static String colorToString(int color) {
        return String.format("#%08X", color);
    }

    static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
