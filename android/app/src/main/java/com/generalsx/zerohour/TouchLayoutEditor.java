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

// GeneralsX @feature Android floating toolbox 17/10/2026 The in-game touch-button editor's
// model: select, move, resize, add, remove, switch a button off, restore the defaults, save,
// cancel. No View, no drawing, no Android beyond the model it edits -- the toolbox draws this
// and forwards gestures to it, and the tests drive it directly.
//
// Three rules it exists to enforce:
//
//  * An edit never changes what a button DOES. Moving or resizing rewrites x/y/widthDp/heightDp
//    and nothing else: keyCode and modifiers are not touched by any operation here, so the
//    button keeps pressing the same key of the game.
//  * Edits happen on a copy. TouchControlConfig is only written by save(), so Cancel -- and any
//    interrupted edit session -- leaves the stored layout exactly as it was.
//  * Every value is clamped where it is produced. A button can never be moved off the screen,
//    made larger than the model allows, or added past the engine's own maximum, which is what
//    keeps a malformed layout from reaching the game in the first place.

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.RectF;
import android.view.KeyEvent;

final class TouchLayoutEditor {

    /** Kept from TouchControlConfig: the same bounds the launcher's editor clamps to. */
    static final float MIN_SIZE_DP = 28f;
    static final float MAX_SIZE_DP = 220f;
    static final float MIN_X = 0.02f;
    static final float MAX_X = 0.98f;
    static final float MIN_Y = 0.04f;
    static final float MAX_Y = 0.96f;
    /** The corner grab area of the selected button, as a fraction of its shorter side. */
    private static final float HANDLE_FRACTION = 0.34f;

    /**
     * What the working copy is compared against to decide whether there is anything to save:
     * the stored layout when the editor opens, and the just-saved copy after a successful save
     * (so a second Save with nothing changed writes nothing instead of repeating the first).
     */
    private TouchControlConfig baseline;
    /** The copy every operation here writes to. */
    final TouchControlConfig working;

    private int selected = -1;
    private float viewWidth = 1f;
    private float viewHeight = 1f;
    private float density = 1f;

    TouchLayoutEditor(TouchControlConfig source) {
        this.baseline = TouchControlConfig.copyOf(source);
        this.working = TouchControlConfig.copyOf(source);
    }

    /** The editor works in pixels: the caller hands over the surface it is drawing on. */
    void setViewport(float widthPx, float heightPx, float density) {
        this.viewWidth = Math.max(1f, widthPx);
        this.viewHeight = Math.max(1f, heightPx);
        this.density = density > 0f ? density : 1f;
    }

    int buttonCount() {
        return working.buttons.size();
    }

    int selectedIndex() {
        return selected;
    }

    TouchControlConfig.ButtonSpec selectedButton() {
        return (selected >= 0 && selected < working.buttons.size()) ? working.buttons.get(selected) : null;
    }

    /** Two scratch rectangles: hit-testing happens on every touch-move and must not allocate. */
    private final RectF scratch = new RectF();
    private final RectF scratchHandle = new RectF();

    /** Fills out with the rectangle a button currently covers, from its fractions and dp size. */
    void rectInto(int index, RectF out) {
        TouchControlConfig.ButtonSpec spec = working.buttons.get(index);
        float width = spec.widthDp * density * working.buttonScale;
        float height = spec.heightDp * density * working.buttonScale;
        if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
            float side = Math.min(width, height);
            width = side;
            height = side;
        }
        float cx = spec.x * viewWidth;
        float cy = spec.y * viewHeight;
        out.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
    }

    /** The rectangle a button covers. Allocates: for tests and one-off calls only. */
    RectF rectOf(int index) {
        RectF rect = new RectF();
        rectInto(index, rect);
        return rect;
    }

    /** The topmost button under a point, or -1. Later buttons are drawn on top. */
    int hitTest(float x, float y) {
        for (int i = working.buttons.size() - 1; i >= 0; i--) {
            rectInto(i, scratch);
            if (scratch.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** Fills out with the resize handle's rectangle on the selected button. */
    void resizeHandleInto(RectF out) {
        out.setEmpty();
        if (selected < 0) {
            return;
        }
        rectInto(selected, out);
        float grab = Math.min(out.width(), out.height()) * HANDLE_FRACTION;
        out.set(out.right - grab, out.bottom - grab, out.right, out.bottom);
    }

    /** True when the point is on the selected button's corner handle (a resize, not a move). */
    boolean isOnResizeHandle(float x, float y) {
        resizeHandleInto(scratchHandle);
        return !scratchHandle.isEmpty() && scratchHandle.contains(x, y);
    }

    void select(int index) {
        selected = (index >= 0 && index < working.buttons.size()) ? index : -1;
    }

    /**
     * Moves the selected button by a pixel delta, clamped so its centre stays inside the
     * margins the launcher's own clamp uses. The button's key is untouched.
     */
    void moveSelectedBy(float dxPx, float dyPx) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return;
        }
        spec.x = TouchControlConfig.clamp(spec.x + dxPx / viewWidth, MIN_X, MAX_X);
        spec.y = TouchControlConfig.clamp(spec.y + dyPx / viewHeight, MIN_Y, MAX_Y);
    }

    /** Resizes the selected button by a pixel delta, keeping it within the model's bounds. */
    void resizeSelectedBy(float dxPx, float dyPx) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return;
        }
        float scale = density * working.buttonScale;
        spec.widthDp = TouchControlConfig.clamp(spec.widthDp + dxPx / scale, MIN_SIZE_DP, MAX_SIZE_DP);
        spec.heightDp = TouchControlConfig.clamp(spec.heightDp + dyPx / scale, MIN_SIZE_DP, MAX_SIZE_DP);
        if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
            float side = Math.min(spec.widthDp, spec.heightDp);
            spec.widthDp = side;
            spec.heightDp = side;
        }
    }

    /**
     * Adds a button with the same shape, colours and size as the layout's own defaults, at a
     * free-ish spot in the middle of the screen, and selects it. Returns false at the engine's
     * maximum (TouchControlConfig.MAX_BUTTONS).
     */
    boolean addButton() {
        if (working.buttons.size() >= TouchControlConfig.MAX_BUTTONS) {
            return false;
        }
        TouchControlConfig.ButtonSpec template = working.buttons.isEmpty()
            ? TouchControlConfig.defaults().buttons.get(0)
            : working.buttons.get(0);
        TouchControlConfig.ButtonSpec spec = new TouchControlConfig.ButtonSpec(
            TouchControlConfig.displayNameForKey(KeyEvent.KEYCODE_SPACE),
            KeyEvent.KEYCODE_SPACE, 0, 0.5f, 0.6f,
            template.widthDp, template.heightDp, template.shape,
            template.fillColor, template.borderColor, template.textColor);
        working.buttons.add(spec);
        selected = working.buttons.size() - 1;
        return true;
    }

    /** Removes the selected button. False when nothing is selected. */
    boolean deleteSelected() {
        if (selected < 0 || selected >= working.buttons.size()) {
            return false;
        }
        working.buttons.remove(selected);
        selected = -1;
        return true;
    }

    /** Switches the selected button on or off without touching its action or its geometry. */
    boolean toggleSelectedEnabled() {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.enabled = !spec.enabled;
        return true;
    }

    /** Puts the shipped layout into the working copy; nothing is stored until save(). */
    void restoreDefaults() {
        TouchControlConfig defaults = TouchControlConfig.defaults();
        working.buttons.clear();
        working.buttons.addAll(TouchControlConfig.copyButtons(defaults.buttons));
        working.buttonScale = defaults.buttonScale;
        working.buttonOpacity = defaults.buttonOpacity;
        selected = -1;
    }

    /** True when the working copy differs from what was loaded. */
    boolean isDirty() {
        if (working.enabled != baseline.enabled
                || Math.abs(working.buttonScale - baseline.buttonScale) > 0.0001f
                || Math.abs(working.buttonOpacity - baseline.buttonOpacity) > 0.0001f
                || working.buttons.size() != baseline.buttons.size()) {
            return true;
        }
        for (int i = 0; i < working.buttons.size(); i++) {
            TouchControlConfig.ButtonSpec a = working.buttons.get(i);
            TouchControlConfig.ButtonSpec b = baseline.buttons.get(i);
            if (a.enabled != b.enabled || a.keyCode != b.keyCode || a.modifiers != b.modifiers
                    || Math.abs(a.x - b.x) > 0.0001f || Math.abs(a.y - b.y) > 0.0001f
                    || Math.abs(a.widthDp - b.widthDp) > 0.0001f
                    || Math.abs(a.heightDp - b.heightDp) > 0.0001f
                    || a.shape != b.shape || !a.label.equals(b.label)) {
                return true;
            }
        }
        return false;
    }

    /** Writes the working copy to the layout's own preferences. False when nothing changed. */
    boolean save(Context context) {
        if (!isDirty()) {
            return false;
        }
        working.save(context);
        // What is stored is now the state "nothing has been edited" means, so a second Save
        // without a further change is a no-op rather than a second write.
        baseline = TouchControlConfig.copyOf(working);
        return true;
    }

    /** Throws the working copy away and starts again from what is stored. */
    void cancel(Context context) {
        TouchControlConfig stored = TouchControlConfig.load(context);
        working.enabled = stored.enabled;
        working.buttonScale = stored.buttonScale;
        working.buttonOpacity = stored.buttonOpacity;
        working.buttons.clear();
        working.buttons.addAll(TouchControlConfig.copyButtons(stored.buttons));
        baseline = TouchControlConfig.copyOf(working);
        selected = -1;
    }
}
